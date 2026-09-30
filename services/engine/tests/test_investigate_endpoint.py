import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_orchestrator
from app.db import DataAccessError
from app.llm.client import LlmUnavailable
from app.main import create_app
from app.tracing import TRACE_ID_HEADER
from tests.fakes import FakeLlm, FakeVectorStore
from tests.test_contract import SPEC, validate_against_contract
from tests.test_orchestrator import build

URL = "/internal/v1/investigate"
BODY = {
    "investigation_id": "INV-2026-004471",
    "exception_id": "EXC-88231",
    "exception_type": "PO_INVOICE_MISMATCH",
    "references": {"invoice_id": "INV-10492", "po_id": "PO-7821", "vendor_id": "VEND-3391"},
    "context": {"tolerance_pct": 2.0, "confidence_threshold": 70},
    "callback_url": None,
}


def make_client(**build_kwargs):
    orchestrator, *_ = build(**build_kwargs)
    app = create_app()
    app.dependency_overrides[get_orchestrator] = lambda: orchestrator
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def headers(make_token):
    return {"Authorization": f"Bearer {make_token()}", TRACE_ID_HEADER: "trace-abc-123"}


def test_happy_path_returns_a_contract_valid_result(headers):
    response = make_client().post(URL, json=BODY, headers=headers)
    assert response.status_code == 200
    body = response.json()
    validate_against_contract("InvestigationResult", body)
    assert body["status"] == "COMPLETED"
    assert body["investigation_id"] == "INV-2026-004471"
    assert body["root_cause"]["label"] == "PO_INVOICE_AMOUNT_MISMATCH"
    assert isinstance(body["findings"]["investigation_agent"]["difference"], float)
    assert response.headers[TRACE_ID_HEADER] == "trace-abc-123"


def test_absent_sections_are_omitted_not_null(headers):
    missing = {**BODY, "references": {**BODY["references"], "po_id": "PO-99999"}}
    response = make_client().post(URL, json=missing, headers=headers)
    body = response.json()
    assert response.status_code == 200 and body["status"] == "FAILED"
    assert "root_cause" not in body and "recommendation" not in body
    validate_against_contract("InvestigationResult", body)


def test_requires_the_internal_jwt():
    response = make_client().post(URL, json=BODY, headers={TRACE_ID_HEADER: "t-1"})
    assert response.status_code == 401
    assert response.json()["error"] == "UNAUTHORIZED" and response.json()["trace_id"] == "t-1"


def test_rejects_an_invalid_body_with_the_error_envelope(headers):
    bad = {k: v for k, v in BODY.items() if k != "investigation_id"}
    response = make_client().post(URL, json=bad, headers=headers)
    body = response.json()
    assert response.status_code == 400 and body["error"] == "VALIDATION_FAILED"
    assert [d["field"] for d in body["details"]] == ["investigation_id"]
    assert body["trace_id"] == "trace-abc-123"


def test_out_of_range_confidence_threshold_is_a_validation_error(headers):
    bad = {**BODY, "context": {"confidence_threshold": 150}}
    response = make_client().post(URL, json=bad, headers=headers)
    assert response.status_code == 400
    assert response.json()["details"][0]["field"] == "context.confidence_threshold"


def test_a_database_failure_is_a_500_envelope_that_leaks_nothing(headers):
    client = make_client(vectors=FakeVectorStore(error=DataAccessError("secret db detail")))
    response = client.post(URL, json=BODY, headers=headers)
    assert response.status_code == 500
    assert response.json()["error"] == "INTERNAL_ERROR"
    assert response.json()["trace_id"] == "trace-abc-123"
    assert "secret db detail" not in response.text


def test_a_degraded_investigation_is_still_a_200(headers):
    client = make_client(llm=FakeLlm(generate_error=LlmUnavailable("overloaded")))
    response = client.post(URL, json=BODY, headers=headers)
    assert response.status_code == 200
    assert response.json()["status"] == "PARTIAL"
    validate_against_contract("InvestigationResult", response.json())


def test_every_implemented_internal_route_is_declared_in_the_contract():
    implemented = {
        r.path.removeprefix("/internal/v1")
        for r in create_app().routes
        if r.path.startswith("/internal/v1")
    }
    assert implemented == {"/investigate"}
    assert implemented <= set(SPEC["paths"])
