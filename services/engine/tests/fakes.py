"""In-memory stand-ins for the database store and Redis, plus the demo records."""

from datetime import date
from decimal import Decimal

from app.db import DataAccessError
from app.tools.records import InvoiceRecord, PurchaseOrderRecord, VendorRecord

# Mirror infra/db/seed/demo_seed.sql.
VENDOR_ABC = VendorRecord(
    vendor_id="VEND-3391", name="ABC Technologies", category="IT Services", risk_level="LOW"
)
VENDOR_NORTHWIND = VendorRecord(
    vendor_id="VEND-4410", name="Northwind Supplies", category="Office Supplies", risk_level="LOW"
)
PO_7821 = PurchaseOrderRecord(
    po_id="PO-7821",
    vendor_id="VEND-3391",
    approved_amount=Decimal("45000.00"),
    currency="INR",
    status="OPEN",
)
PO_7822 = PurchaseOrderRecord(
    po_id="PO-7822",
    vendor_id="VEND-4410",
    approved_amount=Decimal("45000.00"),
    currency="INR",
    status="OPEN",
)
INV_10492 = InvoiceRecord(
    invoice_id="INV-10492",
    vendor_id="VEND-3391",
    po_id="PO-7821",
    invoice_amount=Decimal("52000.00"),
    invoice_date=date(2026, 9, 15),
    status="PENDING",
)
INV_10493 = InvoiceRecord(
    invoice_id="INV-10493",
    vendor_id="VEND-4410",
    po_id="PO-7822",
    invoice_amount=Decimal("45500.00"),
    invoice_date=date(2026, 9, 15),
    status="PENDING",
)


class FakeCache:
    def __init__(self, entries: dict | None = None) -> None:
        self.entries = dict(entries or {})
        self.ttls: dict[str, int] = {}
        self.gets: list[str] = []
        self.sets: list[str] = []

    async def get_json(self, key: str) -> dict | None:
        self.gets.append(key)
        return self.entries.get(key)

    async def set_json(self, key: str, value: dict, ttl_seconds: int) -> None:
        self.sets.append(key)
        self.entries[key] = value
        self.ttls[key] = ttl_seconds


class FakeStore:
    """Dict-backed ReferenceDataStore that records every call it receives."""

    def __init__(
        self,
        invoices=(),
        purchase_orders=(),
        vendors=(),
        failing: set[str] | None = None,
    ) -> None:
        self._invoices = {r.invoice_id: r for r in invoices}
        self._pos = {r.po_id: r for r in purchase_orders}
        self._vendors = {r.vendor_id: r for r in vendors}
        self._failing = failing or set()
        self.calls: list[tuple[str, str]] = []

    def _record_call(self, tool: str, entity_id: str) -> None:
        self.calls.append((tool, entity_id))
        if tool in self._failing:
            raise DataAccessError(f"{tool} failed")

    async def get_invoice(self, invoice_id: str):
        self._record_call("get_invoice", invoice_id)
        return self._invoices.get(invoice_id)

    async def get_purchase_order(self, po_id: str):
        self._record_call("get_purchase_order", po_id)
        return self._pos.get(po_id)

    async def get_vendor(self, vendor_id: str):
        self._record_call("get_vendor", vendor_id)
        return self._vendors.get(vendor_id)


def demo_store(**kwargs) -> FakeStore:
    return FakeStore(
        invoices=[INV_10492, INV_10493],
        purchase_orders=[PO_7821, PO_7822],
        vendors=[VENDOR_ABC, VENDOR_NORTHWIND],
        **kwargs,
    )


# ---- language model / vector store fakes and sample retrieval results ----

from app.agents.synthesis import Synthesis  # noqa: E402
from app.tools.vector_store import IncidentMatch, PolicyClause  # noqa: E402

CLAUSE_42 = PolicyClause(
    document_name="Invoice Validation Policy",
    clause_id="IVP-4.2",
    text="Invoice amount cannot exceed PO amount without an approved change order.",
    similarity=0.83,
)
CLAUSE_43 = PolicyClause(
    document_name="Invoice Validation Policy",
    clause_id="IVP-4.3",
    text="A variance within the configured tolerance may be approved by the analyst.",
    similarity=0.79,
)
INC_8721 = IncidentMatch(
    incident_id="INC-8721",
    similarity=0.71,
    root_cause="PO_INVOICE_AMOUNT_MISMATCH",
    resolution="Vendor issued corrected invoice.",
    decision="Payment blocked until correction.",
    outcome="RESOLVED",
    summary_text="Root cause: PO_INVOICE_AMOUNT_MISMATCH. Resolution: Vendor issued corrected invoice.",
)
SYN_MISMATCH = Synthesis(
    applicable_clause_id="IVP-4.2",
    policy_violated=True,
    reasoning="The invoice exceeds the PO by 15.56% with no change order.",
    action_type="REQUEST_CORRECTED_INVOICE",
    action_reason="Invoice exceeds PO amount, violating IVP-4.2.",
)
SYN_TOLERANCE = Synthesis(
    applicable_clause_id="IVP-4.3",
    policy_violated=False,
    reasoning="The 1.11% variance is inside the 2% tolerance.",
    action_type="APPROVE_WITHIN_TOLERANCE",
    action_reason="Variance is within tolerance under IVP-4.3.",
)


class FakeLlm:
    """LlmClient stand-in: returns a canned Synthesis and fixed vectors, or raises what it's told to."""

    def __init__(self, synthesis=None, generate_error=None, embed_error=None) -> None:
        self.synthesis = synthesis
        self.generate_error = generate_error
        self.embed_error = embed_error
        self.prompts: list[str] = []
        self.embed_calls: list[tuple[list[str], str]] = []

    async def generate_structured(self, prompt, schema, deadline):
        self.prompts.append(prompt)
        if self.generate_error:
            raise self.generate_error
        assert isinstance(self.synthesis, schema), "test forgot to give FakeLlm a synthesis"
        return self.synthesis

    async def embed(self, texts, *, task, deadline):
        self.embed_calls.append((list(texts), task))
        if self.embed_error:
            raise self.embed_error
        return [[1.0, 0.0] for _ in texts]


class FakeVectorStore:
    def __init__(self, clauses=(), incidents=(), error=None) -> None:
        self.clauses = list(clauses)
        self.incidents = list(incidents)
        self.error = error
        self.calls: list[tuple[str, int]] = []

    async def search_policy(self, embedding, top_k):
        self.calls.append(("search_policy", top_k))
        if self.error:
            raise self.error
        return self.clauses[:top_k]

    async def search_incidents(self, embedding, top_k):
        self.calls.append(("search_incidents", top_k))
        if self.error:
            raise self.error
        return self.incidents[:top_k]
