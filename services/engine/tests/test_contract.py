"""
Checks the engine's models against contracts/openapi/internal-api.yaml using
the worked examples from TDD §3.2 and §3.4. (Backlog TASK-19: the contract is
the source of truth, verified in CI rather than trusted.)
"""

import json

import pytest
import yaml
from jsonschema import Draft7Validator
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate as validate_openapi

from app.api.schemas import (
    IncidentWriteBack,
    IncidentWriteBackAccepted,
    InvestigationRequest,
    InvestigationResult,
)
from tests.conftest import CONTRACTS_DIR

SPEC_PATH = CONTRACTS_DIR / "openapi" / "internal-api.yaml"
SPEC = yaml.safe_load(SPEC_PATH.read_text(encoding="utf-8"))

INVESTIGATION_REQUEST = {
    "investigation_id": "INV-2026-004471",
    "exception_id": "EXC-88231",
    "exception_type": "PO_INVOICE_MISMATCH",
    "references": {"invoice_id": "INV-10492", "po_id": "PO-7821", "vendor_id": "VEND-3391"},
    "context": {"tolerance_pct": 2.0, "confidence_threshold": 70},
    "callback_url": None,
}

INVESTIGATION_RESULT = {
    "investigation_id": "INV-2026-004471",
    "status": "COMPLETED",
    "findings": {
        "investigation_agent": {
            "invoice_amount": 52000.00,
            "po_amount": 45000.00,
            "difference": 7000.00,
            "tools_called": ["get_invoice", "get_purchase_order", "get_vendor"],
        },
        "policy_agent": {
            "violated": True,
            "policy_document": "Invoice Validation Policy",
            "clause_id": "IVP-4.2",
            "clause_text": "Invoice amount cannot exceed PO amount without an approved change order.",
        },
        "memory_agent": {
            "matches_found": 1,
            "top_match": {
                "incident_id": "INC-8721",
                "similarity": 0.91,
                "resolution": "Vendor issued corrected invoice.",
                "outcome": "RESOLVED",
            },
        },
    },
    "root_cause": {
        "label": "PO_INVOICE_AMOUNT_MISMATCH",
        "confidence": 96,
        "evidence": [
            {"source": "invoice", "field": "amount", "value": 52000.00},
            {"source": "purchase_order", "field": "approved_amount", "value": 45000.00},
        ],
    },
    "recommendation": {
        "action_type": "REQUEST_CORRECTED_INVOICE",
        "reason": "Amount discrepancy violates Invoice Validation Policy IVP-4.2.",
        "requires_approval": True,
    },
    "agent_trace": [
        {"agent": "orchestrator", "step": "PLAN", "timestamp": "2026-09-15T09:41:14.102Z"},
        {
            "agent": "investigation",
            "step": "TOOL_CALL",
            "tool": "get_invoice",
            "timestamp": "2026-09-15T09:41:14.310Z",
        },
        {
            "agent": "investigation",
            "step": "TOOL_CALL",
            "tool": "get_purchase_order",
            "timestamp": "2026-09-15T09:41:14.480Z",
        },
        {
            "agent": "policy",
            "step": "TOOL_CALL",
            "tool": "search_policy",
            "timestamp": "2026-09-15T09:41:15.020Z",
        },
        {
            "agent": "memory",
            "step": "TOOL_CALL",
            "tool": "search_previous_incidents",
            "timestamp": "2026-09-15T09:41:15.640Z",
        },
        {"agent": "orchestrator", "step": "AGGREGATE", "timestamp": "2026-09-15T09:41:16.900Z"},
    ],
}

INCIDENT_WRITE_BACK = {
    "exception_id": "EXC-88231",
    "root_cause": "PO_INVOICE_AMOUNT_MISMATCH",
    "resolution": "Vendor issued corrected invoice.",
    "decision": "Payment blocked until correction.",
    "evidence": ["PO-7821", "INV-10492"],
    "outcome": "RESOLVED",
    "resolved_at": "2026-09-15T09:44:02Z",
}

# A degraded result: the Policy Agent timed out (TDD §5.4), so no root cause.
PARTIAL_RESULT = {
    "investigation_id": "INV-2026-004472",
    "status": "PARTIAL",
    "findings": {
        "investigation_agent": {
            "invoice_amount": 52000.0,
            "po_amount": 45000.0,
            "difference": 7000.0,
        },
        "policy_agent": {"status": "INCOMPLETE", "reason": "Timed out after 2 attempts."},
    },
    "root_cause": None,
    "recommendation": None,
    "agent_trace": [],
}


def validate_against_contract(schema_name: str, instance: dict) -> None:
    """Validates with OpenAPI 3.0 semantics (nullable etc.), $refs resolved within the spec."""
    schema = {"$ref": f"#/components/schemas/{schema_name}", "components": SPEC["components"]}
    OAS30Validator(schema).validate(instance)


def test_contract_is_a_valid_openapi_document():
    validate_openapi(SPEC, base_uri=SPEC_PATH.as_uri())


@pytest.mark.parametrize(
    ("schema_name", "model", "example"),
    [
        ("InvestigationRequest", InvestigationRequest, INVESTIGATION_REQUEST),
        ("InvestigationResult", InvestigationResult, INVESTIGATION_RESULT),
        ("InvestigationResult", InvestigationResult, PARTIAL_RESULT),
        ("IncidentWriteBack", IncidentWriteBack, INCIDENT_WRITE_BACK),
        (
            "IncidentWriteBackAccepted",
            IncidentWriteBackAccepted,
            {"incident_id": "INC-9014", "embedding_status": "QUEUED"},
        ),
    ],
    ids=["request", "result", "partial-result", "write-back", "write-back-accepted"],
)
def test_tdd_example_matches_contract_and_model(schema_name, model, example):
    # The TDD's own example is valid against the contract...
    validate_against_contract(schema_name, example)
    # ...the model accepts it, and what the model would emit is still contract-valid.
    emitted = json.loads(model.model_validate(example).model_dump_json(exclude_none=True))
    validate_against_contract(schema_name, emitted)


def test_money_serializes_as_json_numbers_not_strings():
    result = InvestigationResult.model_validate(INVESTIGATION_RESULT)
    emitted = json.loads(result.model_dump_json(exclude_none=True))
    agent = emitted["findings"]["investigation_agent"]
    assert agent["invoice_amount"] == 52000.0
    assert isinstance(agent["invoice_amount"], float)
    # Evidence.value is number-or-string; both forms must survive.
    assert emitted["root_cause"]["evidence"][0]["value"] == 52000.0
    text_evidence = InvestigationResult.model_validate(
        {
            **INVESTIGATION_RESULT,
            "root_cause": {
                "label": "X",
                "confidence": 1,
                "evidence": [{"source": "invoice", "field": "id", "value": "INV-10492"}],
            },
        }
    )
    assert json.loads(text_evidence.model_dump_json())["root_cause"]["evidence"][0]["value"] == (
        "INV-10492"
    )


@pytest.mark.parametrize(
    "bad_result",
    [
        pytest.param({**INVESTIGATION_RESULT, "status": "DONE"}, id="unknown-status"),
        pytest.param(
            {
                **INVESTIGATION_RESULT,
                "root_cause": {"label": "X", "confidence": 101, "evidence": []},
            },
            id="confidence-over-100",
        ),
        pytest.param(
            {k: v for k, v in INVESTIGATION_RESULT.items() if k != "agent_trace"},
            id="missing-agent-trace",
        ),
    ],
)
def test_contract_rejects_malformed_results(bad_result):
    with pytest.raises(Exception):  # noqa: B017 — jsonschema.ValidationError
        validate_against_contract("InvestigationResult", bad_result)


def test_error_envelope_example_from_tdd_is_valid():
    schema = json.loads(
        (CONTRACTS_DIR / "schemas" / "error-envelope.schema.json").read_text(encoding="utf-8")
    )
    Draft7Validator(schema).validate(
        {
            "timestamp": "2026-09-15T09:41:12Z",
            "status": 400,
            "error": "VALIDATION_FAILED",
            "message": "One or more fields failed validation.",
            "path": "/api/v1/exceptions",
            "trace_id": "8f3e1c2a-9b4d-4e21-9a10-2c7f0e5b6a11",
            "details": [{"field": "references.po_id", "issue": "does not match required pattern"}],
        }
    )
