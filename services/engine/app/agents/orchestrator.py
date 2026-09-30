"""
Orchestrator Agent: runs one investigation end to end (TDD §2 steps 4-10).

    PLAN -> Investigation (deterministic) -> Policy || Memory (retrieval, parallel)
         -> root-cause synthesis (one model call) -> AGGREGATE

It degrades instead of failing: a model or embedding outage marks that section
INCOMPLETE and the result PARTIAL, and root cause and recommendation still come
from the deterministic rules. Only a missing invoice/PO/vendor (nothing to
investigate) yields FAILED, and a database failure propagates as a 500 (§5.4).
"""

import asyncio
import logging

from app.agents.investigation import AmountAnalysis, InvestigationAgent, InvestigationOutcome
from app.agents.memory import MemoryAgent, MemoryRetrieval
from app.agents.policy import PolicyAgent, PolicyRetrieval
from app.agents.rootcause import (
    determine_label,
    fallback_recommendation,
    score_confidence,
)
from app.agents.synthesis import NO_CLAUSE, RootCauseSynthesizer, Synthesis
from app.agents.trace import TraceRecorder
from app.api.schemas import (
    Evidence,
    Findings,
    InvestigationRequest,
    InvestigationResult,
    MemoryAgentFindings,
    PolicyAgentFindings,
    Recommendation,
    RootCause,
    TopMatch,
)
from app.deadline import Deadline
from app.llm.client import LlmError
from app.tools.vector_store import PolicyClause

logger = logging.getLogger(__name__)


def describe_situation(exception_type: str, label: str, analysis: AmountAnalysis) -> str:
    """
    The text both retrievals search with. Deliberately shaped like a stored
    incident summary ("Root cause: ...") so similar past incidents rank close.
    """
    direction = (
        "over" if analysis.difference > 0 else "under" if analysis.difference < 0 else "equal to"
    )
    pct = "" if analysis.difference_pct is None else f"{analysis.difference_pct}% "
    verdict = "within" if analysis.within_tolerance else "outside"
    return (
        f"Root cause: {label}. {exception_type}: invoice amount {analysis.invoice_amount} against "
        f"purchase order amount {analysis.po_amount}, {pct}{direction} the purchase order, "
        f"{verdict} the {analysis.tolerance_pct}% tolerance."
    )


class OrchestratorAgent:
    def __init__(
        self,
        investigation: InvestigationAgent,
        policy: PolicyAgent,
        memory: MemoryAgent,
        synthesizer: RootCauseSynthesizer,
        *,
        default_tolerance_pct: float,
        deadline_seconds: float,
        precedent_min_similarity: float,
    ) -> None:
        self._investigation = investigation
        self._policy = policy
        self._memory = memory
        self._synthesizer = synthesizer
        self._default_tolerance = default_tolerance_pct
        self._deadline_seconds = deadline_seconds
        self._min_similarity = precedent_min_similarity

    async def investigate(self, request: InvestigationRequest) -> InvestigationResult:
        recorder = TraceRecorder()
        deadline = Deadline(self._deadline_seconds)
        recorder.record(
            "orchestrator",
            "PLAN",
            tool_output={
                "exception_type": request.exception_type,
                "steps": ["investigation", "policy+memory (parallel)", "root_cause_synthesis"],
            },
        )

        context = request.context
        tolerance = (
            context.tolerance_pct
            if context is not None and context.tolerance_pct is not None
            else self._default_tolerance
        )
        outcome = await self._investigation.run(request.references, tolerance, recorder)
        if outcome.analysis is None:
            return self._failed(request, outcome, recorder)

        analysis = outcome.analysis
        label = determine_label(analysis)
        query = describe_situation(request.exception_type, label, analysis)

        policy_result, memory_result = await asyncio.gather(
            self._policy.run(query, recorder, deadline),
            self._memory.run(query, recorder, deadline),
            return_exceptions=True,
        )
        policy, policy_reason = _settle(policy_result)
        memory, memory_reason = _settle(memory_result)
        # A precedent must share the root cause the numbers support. Similarity alone
        # can't separate them (live: 0.82 for a different cause vs 0.84 for the same one),
        # and showing an unrelated incident as a "match" would mislead the reviewer.
        precedents = (
            [m for m in memory.relevant(self._min_similarity) if m.root_cause == label]
            if memory
            else []
        )

        synthesis: Synthesis | None = None
        synthesis_reason: str | None = None
        try:
            synthesis = await self._synthesizer.run(
                exception_type=request.exception_type,
                label=label,
                analysis=analysis,
                vendor=outcome.vendor,
                clauses=policy.clauses if policy else [],
                precedents=precedents,
                deadline=deadline,
            )
        except LlmError as exc:
            synthesis_reason = str(exc)
            logger.warning("Root-cause synthesis unavailable, using rules: %s", synthesis_reason)

        policy_findings = _policy_findings(policy, policy_reason, synthesis, synthesis_reason)
        memory_findings = _memory_findings(memory, memory_reason, precedents)
        confidence = score_confidence(
            label=label,
            analysis=analysis,
            policy_violated=policy_findings.violated if policy_findings.status == "OK" else None,
            precedent=precedents[0] if precedents else None,
        )
        recommendation = (
            Recommendation(
                action_type=synthesis.action_type,
                reason=synthesis.action_reason,
                requires_approval=True,  # every action goes through human approval
            )
            if synthesis
            else fallback_recommendation(label, analysis)
        )

        degraded = [
            name
            for name, ok in (
                ("policy", policy_findings.status == "OK"),
                ("memory", memory_findings.status == "OK"),
                ("synthesis", synthesis is not None),
            )
            if not ok
        ]
        recorder.record(
            "orchestrator",
            "AGGREGATE",
            tool_output={
                "label": label,
                "confidence": confidence,
                "synthesis": "llm" if synthesis else "fallback",
                "reasoning": synthesis.reasoning if synthesis else None,
                "degraded": degraded,
            },
        )
        return InvestigationResult(
            investigation_id=request.investigation_id,
            status="PARTIAL" if degraded else "COMPLETED",
            findings=Findings(
                investigation_agent=outcome.findings,
                policy_agent=policy_findings,
                memory_agent=memory_findings,
            ),
            root_cause=RootCause(
                label=label,
                confidence=confidence,
                evidence=[
                    Evidence(source="invoice", field="amount", value=analysis.invoice_amount),
                    Evidence(
                        source="purchase_order", field="approved_amount", value=analysis.po_amount
                    ),
                ],
            ),
            recommendation=recommendation,
            agent_trace=recorder.steps,
        )

    @staticmethod
    def _failed(
        request: InvestigationRequest, outcome: InvestigationOutcome, recorder: TraceRecorder
    ) -> InvestigationResult:
        recorder.record(
            "orchestrator",
            "AGGREGATE",
            tool_output={"outcome": "FAILED", "reason": outcome.findings.reason},
        )
        return InvestigationResult(
            investigation_id=request.investigation_id,
            status="FAILED",
            findings=Findings(investigation_agent=outcome.findings),
            agent_trace=recorder.steps,
        )


def _settle(result):
    """
    Turns a retrieval result into (value, None) or (None, reason). Model and
    embedding failures degrade that section; anything else (notably a database
    failure, which TDD §5.4 makes a 500) is re-raised.
    """
    if isinstance(result, LlmError):
        return None, str(result)
    if isinstance(result, BaseException):
        raise result
    return result, None


def _policy_findings(
    policy: PolicyRetrieval | None,
    policy_reason: str | None,
    synthesis: Synthesis | None,
    synthesis_reason: str | None,
) -> PolicyAgentFindings:
    if policy is None:
        return PolicyAgentFindings(status="INCOMPLETE", reason=policy_reason)
    if not policy.clauses:
        return PolicyAgentFindings(status="INCOMPLETE", reason="No policy clauses were found.")
    if synthesis is None:
        return PolicyAgentFindings(
            status="INCOMPLETE", reason=f"Policy judgement unavailable: {synthesis_reason}"
        )
    chosen: PolicyClause | None = next(
        (c for c in policy.clauses if c.clause_id == synthesis.applicable_clause_id), None
    )
    if chosen is None:  # the model found no governing clause, so nothing was violated
        return PolicyAgentFindings(status="OK", violated=False)
    return PolicyAgentFindings(
        status="OK",
        violated=synthesis.policy_violated,
        policy_document=chosen.document_name,
        clause_id=chosen.clause_id,
        clause_text=chosen.text,
    )


def _memory_findings(
    memory: MemoryRetrieval | None, memory_reason: str | None, precedents: list
) -> MemoryAgentFindings:
    if memory is None:
        return MemoryAgentFindings(status="INCOMPLETE", reason=memory_reason)
    top = precedents[0] if precedents else None
    return MemoryAgentFindings(
        status="OK",
        matches_found=len(precedents),
        top_match=(
            TopMatch(
                incident_id=top.incident_id,
                similarity=round(top.similarity, 4),
                resolution=top.resolution,
                outcome=top.outcome,
            )
            if top
            else None
        ),
    )


__all__ = ["OrchestratorAgent", "describe_situation", "NO_CLAUSE"]
