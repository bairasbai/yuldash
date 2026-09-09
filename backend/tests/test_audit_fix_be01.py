"""Регрессии BE01: повторный вход не оживляет ранее отозванные access-токены."""

from datetime import timedelta

from jose import jwt

from app.config import settings
from app.timeutil import utcnow


def _login(client, phone: str) -> dict:
    code_response = client.post("/auth/request-code", json={"phone": phone})
    assert code_response.status_code == 200, code_response.text
    code = code_response.json()["dev_code"]
    login_response = client.post(
        "/auth/verify",
        json={"phone": phone, "code": code, "name": "BE01 regression"},
    )
    assert login_response.status_code == 200, login_response.text
    return login_response.json()


def _auth(access_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {access_token}"}


def test_new_login_does_not_revive_access_token_revoked_by_logout(client):
    phone = "+79990000101"
    first_login = _login(client, phone)
    old_auth = _auth(first_login["access_token"])
    assert client.get("/me", headers=old_auth).status_code == 200

    logout_response = client.post("/auth/logout", headers=old_auth)
    assert logout_response.status_code == 200, logout_response.text
    assert client.get("/me", headers=old_auth).status_code == 401

    second_login = _login(client, phone)
    new_auth = _auth(second_login["access_token"])
    assert client.get("/me", headers=new_auth).status_code == 200
    assert client.get("/me", headers=old_auth).status_code == 401, (
        "повторный вход снова сделал действительным access-токен, отозванный logout"
    )


def test_legacy_access_token_without_iat_is_rejected_after_logout(client):
    phone = "+79990000102"
    login = _login(client, phone)
    user_id = login["user"]["id"]

    legacy_token = jwt.encode(
        {"sub": str(user_id), "exp": utcnow() + timedelta(minutes=10)},
        settings.jwt_secret,
        algorithm="HS256",
    )
    legacy_auth = _auth(legacy_token)
    assert client.get("/me", headers=legacy_auth).status_code == 200

    logout_response = client.post("/auth/logout", headers=_auth(login["access_token"]))
    assert logout_response.status_code == 200, logout_response.text
    assert client.get("/me", headers=legacy_auth).status_code == 401, (
        "токен старого формата без iat пережил logout"
    )
