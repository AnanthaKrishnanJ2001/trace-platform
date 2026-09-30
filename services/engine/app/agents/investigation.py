"""
Investigation Agent: gathers the invoice, purchase order and vendor and
compares the amounts against the tolerance. Deterministic — no LLM — so it is
fast, reproducible and cheap; Gemini only enters at root-cause synthesis.
"""

import asyncio
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from app.agents.trace import TraceRecorder
from app.api.schemas import InvestigationAgentFindings, References
from app.tools.records import VendorRecord
from app.tools.reference_data import ReferenceDataTools

# TDD §3.2 lists the tools in this order.
TOOLS_CALLED = ["get_invoice", "get_purchase_order", "get_vendor"]
_CENT = Decimal("0.01")


@dataclass(frozen=True)
class AmountAnalysis:
    invoice_amount: Decimal
    po_amount: Decimal
    difference: Decimal  # invoice - PO: positive means the invoice exceeds the PO
    difference_pct: Decimal | None  # |difference| as % of the PO, 2dp; None when the PO is 0
    tolerance_pct: Decimal
    within_tolerance: bool


@dataclass(frozen=True)
class InvestigationOutcome:
    findings: InvestigationAgentFindings  # the contract-facing section
    analysis: AmountAnalysis | None  # extra detail for the root-cause step; None if INCOMPLETE
    vendor: VendorRecord | None


def analyze_amounts(
    invoice_amount: Decimal, po_amount: Decimal, tolerance_pct: Decimal
) -> AmountAnalysis:
    """
    The tolerance is symmetric (an invoice 3% under the PO is as far off as one
    3% over) and inclusive (exactly on the limit is still within it). The
    check multiplies instead of dividing, so it is exact and needs no special
    case for a zero-value PO: any non-zero difference against a zero PO is
    outside tolerance.
    """
    difference = invoice_amount - po_amount
    within = abs(difference) * 100 <= tolerance_pct * po_amount
    pct = (
        (abs(difference) * 100 / po_amount).quantize(_CENT, rounding=ROUND_HALF_UP)
        if po_amount != 0
        else None
    )
    return AmountAnalysis(
        invoice_amount=invoice_amount,
        po_amount=po_amount,
        difference=difference,
        difference_pct=pct,
        tolerance_pct=tolerance_pct,
        within_tolerance=within,
    )


class InvestigationAgent:
    def __init__(self, tools: ReferenceDataTools) -> None:
        self._tools = tools

    async def run(
        self, references: References, tolerance_pct: float, recorder: TraceRecorder
    ) -> InvestigationOutcome:
        missing = [
            name for name in ("invoice_id", "po_id", "vendor_id") if not getattr(references, name)
        ]
        if missing:
            return _incomplete(f"Missing reference(s): {', '.join(missing)}.")

        invoice, po, vendor = await self._gather(
            self._call(recorder, "get_invoice", {"invoice_id": references.invoice_id}),
            self._call(recorder, "get_purchase_order", {"po_id": references.po_id}),
            self._call(recorder, "get_vendor", {"vendor_id": references.vendor_id}),
        )

        not_found = [
            f"{label} {ref}"
            for label, ref, record in (
                ("invoice", references.invoice_id, invoice),
                ("purchase order", references.po_id, po),
                ("vendor", references.vendor_id, vendor),
            )
            if record is None
        ]
        if not_found:
            return _incomplete(f"Not found: {', '.join(not_found)}.")

        analysis = analyze_amounts(
            invoice.invoice_amount, po.approved_amount, Decimal(str(tolerance_pct))
        )
        findings = InvestigationAgentFindings(
            status="OK",
            invoice_amount=analysis.invoice_amount,
            po_amount=analysis.po_amount,
            difference=analysis.difference,
            tools_called=list(TOOLS_CALLED),
        )
        return InvestigationOutcome(findings=findings, analysis=analysis, vendor=vendor)

    async def _call(self, recorder: TraceRecorder, tool: str, tool_input: dict):
        """Runs one tool and records it in the agent trace (found or not)."""
        (arg,) = tool_input.values()
        record = await getattr(self._tools, tool)(arg)
        recorder.record(
            "investigation",
            "TOOL_CALL",
            tool=tool,
            tool_input=tool_input,
            tool_output=record.model_dump(mode="json") if record else {"found": False},
        )
        return record

    @staticmethod
    async def _gather(*calls):
        # The three lookups are independent, so run them concurrently (the
        # 8s budget is tight). return_exceptions lets every call settle
        # before a failure propagates, so none is left running unattended.
        results = await asyncio.gather(*calls, return_exceptions=True)
        for result in results:
            if isinstance(result, BaseException):
                raise result
        return results


def _incomplete(reason: str) -> InvestigationOutcome:
    findings = InvestigationAgentFindings(status="INCOMPLETE", reason=reason)
    return InvestigationOutcome(findings=findings, analysis=None, vendor=None)
