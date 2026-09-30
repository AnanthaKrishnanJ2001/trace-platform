import logging

from app.tracing import TRACE_ID_HEADER, TraceIdLogFilter

GATEWAY_TRACE_ID = "8f3e1c2a-9b4d-4e21-9a10-2c7f0e5b6a11"


def test_trace_id_from_gateway_is_echoed_on_success(client):
    response = client.get("/health", headers={TRACE_ID_HEADER: GATEWAY_TRACE_ID})
    assert response.headers[TRACE_ID_HEADER] == GATEWAY_TRACE_ID


def test_trace_id_from_gateway_is_used_in_error_envelope(client):
    response = client.get("/does-not-exist", headers={TRACE_ID_HEADER: GATEWAY_TRACE_ID})
    assert response.json()["trace_id"] == GATEWAY_TRACE_ID
    assert response.headers[TRACE_ID_HEADER] == GATEWAY_TRACE_ID


def test_trace_id_survives_the_catch_all_500_handler(client):
    # The 500 handler runs outside TraceIdMiddleware, so this guards the scope-state path.
    response = client.get("/boom", headers={TRACE_ID_HEADER: GATEWAY_TRACE_ID})
    assert response.status_code == 500
    assert response.json()["trace_id"] == GATEWAY_TRACE_ID
    assert response.headers[TRACE_ID_HEADER] == GATEWAY_TRACE_ID


def test_missing_trace_id_gets_a_consistent_fallback(client):
    response = client.get("/does-not-exist")
    fallback = response.json()["trace_id"]
    assert fallback
    assert response.headers[TRACE_ID_HEADER] == fallback


def test_overlong_trace_id_is_replaced(client):
    response = client.get("/does-not-exist", headers={TRACE_ID_HEADER: "x" * 500})
    assert response.json()["trace_id"] != "x" * 500


def test_missing_trace_id_on_internal_path_is_logged(client, caplog):
    with caplog.at_level(logging.WARNING, logger="app.tracing"):
        client.post("/internal/v1/anything")
    assert any("X-Trace-Id" in r.getMessage() for r in caplog.records)


def test_missing_trace_id_on_health_is_not_logged(client, caplog):
    with caplog.at_level(logging.WARNING, logger="app.tracing"):
        client.get("/health")
    assert not caplog.records


def test_log_records_carry_the_request_trace_id(client, caplog):
    caplog.handler.addFilter(TraceIdLogFilter())
    with caplog.at_level(logging.ERROR):
        client.get("/boom", headers={TRACE_ID_HEADER: GATEWAY_TRACE_ID})
    assert [r.trace_id for r in caplog.records] == [GATEWAY_TRACE_ID]
