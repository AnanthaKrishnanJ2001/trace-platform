import json

import pytest
from jsonschema import Draft7Validator

from tests.conftest import CONTRACTS_DIR

ENVELOPE_SCHEMA = json.loads(
    (CONTRACTS_DIR / "schemas" / "error-envelope.schema.json").read_text(encoding="utf-8")
)


def assert_valid_envelope(body: dict, status: int, error: str) -> None:
    Draft7Validator(ENVELOPE_SCHEMA).validate(body)
    assert body["status"] == status
    assert body["error"] == error


@pytest.fixture
def authed(make_token):
    return {"Authorization": f"Bearer {make_token()}"}


def test_validation_failure_is_400_with_field_details(client, authed):
    response = client.post("/probe", json={"name": "x", "count": "not-a-number"}, headers=authed)
    assert response.status_code == 400
    body = response.json()
    assert_valid_envelope(body, 400, "VALIDATION_FAILED")
    assert body["path"] == "/probe"
    assert [d["field"] for d in body["details"]] == ["count"]


def test_missing_field_is_reported_by_name(client, authed):
    response = client.post("/probe", json={"name": "x"}, headers=authed)
    body = response.json()
    assert_valid_envelope(body, 400, "VALIDATION_FAILED")
    assert [d["field"] for d in body["details"]] == ["count"]


def test_validation_errors_never_echo_input_values(client, authed):
    response = client.post("/probe", json={"name": "x", "count": "SENSITIVE-VALUE"}, headers=authed)
    assert "SENSITIVE-VALUE" not in response.text


def test_malformed_json_is_400(client, authed):
    response = client.post(
        "/probe", content="{not json", headers={**authed, "Content-Type": "application/json"}
    )
    body = response.json()
    assert_valid_envelope(body, 400, "VALIDATION_FAILED")
    assert "details" not in body


def test_unknown_route_is_404_envelope(client):
    response = client.get("/does-not-exist")
    assert_valid_envelope(response.json(), 404, "NOT_FOUND")


def test_wrong_method_is_405_envelope(client):
    response = client.get("/probe")
    assert_valid_envelope(response.json(), 405, "METHOD_NOT_ALLOWED")
    assert "POST" in response.headers["Allow"]


def test_unhandled_exception_is_500_envelope_without_leaking_details(client):
    response = client.get("/boom")
    assert response.status_code == 500
    assert_valid_envelope(response.json(), 500, "INTERNAL_ERROR")
    assert "secret internal detail" not in response.text
    assert "Traceback" not in response.text
    assert "RuntimeError" not in response.text
