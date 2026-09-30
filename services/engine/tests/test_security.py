import time

import pytest

VALID_BODY = {"name": "x", "count": 1}


def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_valid_token_is_accepted(client, make_token):
    response = client.post("/probe", json=VALID_BODY, headers=auth(make_token()))
    assert response.status_code == 200


def test_missing_token_returns_401_envelope(client):
    response = client.post("/probe", json=VALID_BODY)
    assert response.status_code == 401
    body = response.json()
    assert body["error"] == "UNAUTHORIZED"
    assert body["status"] == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"


def test_non_bearer_scheme_returns_401(client):
    response = client.post("/probe", json=VALID_BODY, headers={"Authorization": "Basic abc"})
    assert response.status_code == 401


@pytest.mark.parametrize(
    "token_kwargs",
    [
        pytest.param({"exp": int(time.time()) - 60}, id="expired"),
        pytest.param({"aud": "some-other-service"}, id="wrong-audience"),
        pytest.param({"aud": None}, id="no-audience"),
        pytest.param({"exp": None}, id="no-expiry"),
        pytest.param({"secret": "not-the-shared-secret-at-all-xxxxxxxx"}, id="wrong-signature"),
        pytest.param({"algorithm": "HS512"}, id="unexpected-algorithm"),
    ],
)
def test_bad_tokens_are_rejected_with_401(client, make_token, token_kwargs):
    response = client.post("/probe", json=VALID_BODY, headers=auth(make_token(**token_kwargs)))
    assert response.status_code == 401
    assert response.json()["error"] == "UNAUTHORIZED"


def test_garbage_token_is_rejected(client):
    response = client.post("/probe", json=VALID_BODY, headers=auth("not.a.jwt"))
    assert response.status_code == 401


def test_rejection_never_echoes_token_or_secret(client, make_token):
    token = make_token(exp=int(time.time()) - 60)
    response = client.post("/probe", json=VALID_BODY, headers=auth(token))
    assert token not in response.text
    assert "test-secret" not in response.text


def test_rejection_does_not_log_token_or_secret(client, make_token, caplog):
    token = make_token(exp=int(time.time()) - 60)
    with caplog.at_level("DEBUG"):
        client.post("/probe", json=VALID_BODY, headers=auth(token))
    assert caplog.records, "expected the rejection to be logged"
    assert token not in caplog.text
    assert "test-secret" not in caplog.text
