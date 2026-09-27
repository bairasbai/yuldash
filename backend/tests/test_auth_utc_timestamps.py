"""JWT NumericDate uses UTC regardless of the process's local timezone."""
import os
import time
from datetime import datetime, timedelta, timezone

import pytest
from jose import jwt
from sqlmodel import Session

from app import security
from app.config import settings
from app.db import engine
from app.models import User
from app.timeutil import utcnow


@pytest.fixture(params=["UTC", "America/Chicago", "Asia/Yekaterinburg"], ids=["UTC", "CST-CDT", "Ufa"])
def host_timezone(request):
    if not hasattr(time, "tzset"):
        pytest.skip("Host timezone switching requires time.tzset")
    previous = os.environ.get("TZ")
    os.environ["TZ"] = request.param
    time.tzset()
    try:
        yield request.param
    finally:
        if previous is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = previous
        time.tzset()


@pytest.mark.parametrize("after_logout", [False, True])
def test_issued_jwt_numeric_dates_are_utc(host_timezone, monkeypatch, after_logout):
    now = datetime(2026, 9, 27, 12, 0, 0, 123456)
    monkeypatch.setattr(security, "utcnow", lambda: now)
    token = security.make_token(42, issued_after=now if after_logout else None)
    claims = jwt.get_unverified_claims(token)
    assert claims["iat_utc"] is True
    issued = now + timedelta(microseconds=1) if after_logout else now
    assert claims["iat"] == issued.replace(tzinfo=timezone.utc).timestamp()
    assert claims["exp"] == int((issued + timedelta(minutes=settings.access_expire_min))
                                .replace(tzinfo=timezone.utc).timestamp())


@pytest.mark.parametrize("seconds_from_logout", [-1, 0, 1], ids=["before", "at", "after"])
def test_utc_token_from_another_server_obeys_revocation_boundary(
    host_timezone, client, user_factory, seconds_from_logout,
):
    owner = user_factory()
    boundary = utcnow() - timedelta(seconds=10)
    with Session(engine) as session:
        user = session.get(User, owner["id"])
        user.tokens_valid_from = boundary
        session.add(user)
        session.commit()
    # Same signing authority, independent UTC NumericDate construction: this
    # models a token minted on another worker/server, not a forged signature.
    token = jwt.encode({
        "sub": str(owner["id"]),
        "iat_utc": True,
        "iat": (boundary + timedelta(seconds=seconds_from_logout)).replace(tzinfo=timezone.utc).timestamp(),
        "exp": (boundary + timedelta(hours=1)).replace(tzinfo=timezone.utc),
    }, settings.jwt_secret, algorithm="HS256")
    result = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert result.status_code == (200 if seconds_from_logout > 0 else 401)


@pytest.mark.parametrize("shift_hours", [-5, 5], ids=["legacy-east", "legacy-west"])
@pytest.mark.parametrize("logged_out", [False, True], ids=["no-cutoff", "after-logout"])
def test_legacy_shifted_token_cannot_survive_logout(
    host_timezone, client, user_factory, shift_hours, logged_out,
):
    owner = user_factory()
    boundary = utcnow() - timedelta(seconds=10)
    if logged_out:
        with Session(engine) as session:
            user = session.get(User, owner["id"])
            user.tokens_valid_from = boundary
            session.add(user)
            session.commit()
    token = jwt.encode({
        "sub": str(owner["id"]),
        # Old releases converted naive UTC through the worker's timezone. Its
        # shifted iat cannot establish whether issuance preceded this logout.
        "iat": (boundary + timedelta(hours=shift_hours)).replace(tzinfo=timezone.utc).timestamp(),
        "exp": (boundary + timedelta(hours=1)).replace(tzinfo=timezone.utc),
    }, settings.jwt_secret, algorithm="HS256")
    result = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert result.status_code == (401 if logged_out else 200)
