"""
Request/response models for contracts/openapi/internal-api.yaml.

If a shape here and the contract disagree, the contract wins — update
internal-api.yaml first, then this file (tests/test_contract.py checks the
two agree). Money fields use app.types.Money (Decimal in, JSON number out).
"""

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from app.types import Money

SectionStatus = Literal["OK", "INCOMPLETE"]
AgentName = Literal["orchestrator", "investigation", "policy", "memory", "action"]


# ---- POST /investigate: request --------------------------------------------


class References(BaseModel):
    invoice_id: str | None = None
    po_id: str | None = None
    vendor_id: str | None = None


class InvestigationContext(BaseModel):
    tolerance_pct: float | None = Field(default=None, ge=0)
    confidence_threshold: int | None = Field(default=None, ge=0, le=100)


class InvestigationRequest(BaseModel):
    investigation_id: str
    exception_id: str
    exception_type: str
    references: References
    context: InvestigationContext | None = None
    callback_url: str | None = None


# ---- POST /investigate: response -------------------------------------------


class InvestigationAgentFindings(BaseModel):
    status: SectionStatus = "OK"
    reason: str | None = None
    invoice_amount: Money | None = None
    po_amount: Money | None = None
    difference: Money | None = None
    tools_called: list[str] | None = None


class PolicyAgentFindings(BaseModel):
    status: SectionStatus = "OK"
    reason: str | None = None
    violated: bool | None = None
    policy_document: str | None = None
    clause_id: str | None = None
    clause_text: str | None = None


class TopMatch(BaseModel):
    incident_id: str
    similarity: float = Field(ge=0, le=1)
    resolution: str
    outcome: str


class MemoryAgentFindings(BaseModel):
    status: SectionStatus = "OK"
    reason: str | None = None
    matches_found: int | None = Field(default=None, ge=0)
    top_match: TopMatch | None = None


class Findings(BaseModel):
    investigation_agent: InvestigationAgentFindings | None = None
    policy_agent: PolicyAgentFindings | None = None
    memory_agent: MemoryAgentFindings | None = None


class Evidence(BaseModel):
    source: str
    field: str
    value: Money | str


class RootCause(BaseModel):
    label: str
    confidence: int = Field(ge=0, le=100)
    evidence: list[Evidence]


class Recommendation(BaseModel):
    action_type: str
    reason: str
    requires_approval: bool


class AgentTraceStep(BaseModel):
    agent: AgentName
    step: str
    tool: str | None = None
    tool_input: dict | None = None
    tool_output: dict | None = None
    timestamp: datetime


class InvestigationResult(BaseModel):
    investigation_id: str
    status: Literal["COMPLETED", "PARTIAL", "FAILED"]
    findings: Findings
    root_cause: RootCause | None = None
    recommendation: Recommendation | None = None
    agent_trace: list[AgentTraceStep]


# ---- POST /memory/write-back -----------------------------------------------


class IncidentWriteBack(BaseModel):
    exception_id: str
    root_cause: str
    resolution: str
    decision: str
    evidence: list[str]
    outcome: str
    resolved_at: datetime


class IncidentWriteBackAccepted(BaseModel):
    incident_id: str
    embedding_status: Literal["QUEUED", "COMPLETE"]
