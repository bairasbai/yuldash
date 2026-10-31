from datetime import timedelta

import pytest
from sqlalchemy import delete, func
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.login_methods import available_methods
from app.models import OtpCode, TgAuth
from app.routers import auth
from app.timeutil import utcnow


@pytest.fixture
def scan_rows():
    """Граничные тысячи строк принадлежат только одному тесту, а не общей ленте следующего."""
    from app.models import Ride, RideRequest
    owners = {"drivers": [], "passengers": []}
    yield owners
    with Session(engine) as session:
        if owners["passengers"]:
            session.execute(delete(RideRequest).where(RideRequest.passenger_id.in_(owners["passengers"])))
        if owners["drivers"]:
            session.execute(delete(Ride).where(Ride.driver_id.in_(owners["drivers"])))
        session.commit()


def _configure(monkeypatch, *, env="prod", provider="mock", bot="synthetic-bot", secret="synthetic-secret"):
    for name, value in {
        "env": env, "sms_provider": provider, "sms_ru_api_id": "",
        "smsdar_id": "", "smsdar_password": "", "review_phone": "", "review_code": "",
        "telegram_bot_token": bot, "telegram_webhook_secret": secret,
    }.items():
        monkeypatch.setattr(settings, name, value)


@pytest.mark.parametrize("env,provider,key,expected", [
    ("prod", "mock", "", ["telegram"]),
    ("dev", "mock", "", ["sms", "telegram"]),
    ("prod", "smsru", "synthetic-sms-key", ["sms", "telegram"]),
    ("prod", "smsru", "", ["telegram"]),
])
def test_public_methods_reflect_available_channels(client, monkeypatch, env, provider, key, expected):
    _configure(monkeypatch, env=env, provider=provider)
    monkeypatch.setattr(settings, "sms_ru_api_id", key)
    response = client.get("/config/login-methods")
    assert response.status_code == 200
    assert response.json() == {"methods": expected}
    assert response.headers["Cache-Control"] == "public, max-age=30"
    assert "synthetic" not in response.text


@pytest.mark.parametrize("bot,secret", [("", "synthetic-secret"), ("synthetic-bot", ""), (" ", " ")])
def test_unconfigured_telegram_is_not_advertised(client, monkeypatch, bot, secret):
    _configure(monkeypatch, bot=bot, secret=secret)
    assert available_methods() == []
    assert client.get("/config/login-methods").json() == {"methods": []}


@pytest.mark.parametrize("path,body,method", [
    ("/auth/request-code", {"phone": "+79990019901"}, "sms"),
    ("/auth/verify", {"phone": "+79990019901", "code": "123456"}, "sms"),
    ("/auth/tg/start", {}, "telegram"),
    ("/auth/tg/verify", {"request_id": "disabled", "code": "123456"}, "telegram"),
    ("/auth/vk-callback", {}, "vk"),
    ("/auth/whatsapp-callback", {}, "whatsapp"),
])
def test_disabled_method_refuses_before_code_or_provider(client, monkeypatch, path, body, method):
    _configure(monkeypatch, bot="", secret="")
    sent = []
    monkeypatch.setattr(auth, "send_sms", lambda *args, **kwargs: sent.append(args))
    with Session(engine) as session:
        before = [session.exec(select(func.count()).select_from(model)).one() for model in (OtpCode, TgAuth)]
    response = client.post(path, json=body)
    assert response.status_code == 403, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "login_method_disabled"
    assert detail["method"] == method
    assert detail["ru"] and detail["ba"]
    assert sent == []
    with Session(engine) as session:
        after = [session.exec(select(func.count()).select_from(model)).one() for model in (OtpCode, TgAuth)]
    assert before == after


def test_store_review_login_survives_disabled_sms(client, monkeypatch):
    _configure(monkeypatch, bot="", secret="")
    monkeypatch.setattr(settings, "review_phone", "+79990019902")
    monkeypatch.setattr(settings, "review_code", "424242")
    response = client.post("/auth/request-code", json={"phone": settings.review_phone})
    assert response.status_code == 200, response.text
    assert response.json() == {"sent": True}
    verified = client.post("/auth/verify", json={"phone": settings.review_phone, "code": "424242"})
    assert verified.status_code == 200, verified.text
    assert verified.json()["user"]["is_reviewer"] is True


@pytest.mark.parametrize("method", ["vk", "whatsapp"])
def test_retired_callbacks_stay_closed_when_future_channels_are_enabled(client, monkeypatch, method):
    from app import login_methods
    monkeypatch.setattr(login_methods, "available_methods", lambda: list(login_methods.ORDER))
    response = client.post(f"/auth/{method}-callback", json={"phone": "+79990019904", "vk_id": "synthetic"})
    assert response.status_code == 403, response.text
    assert response.json()["detail"]["code"] == "login_method_disabled"


def test_enabled_provider_failure_is_not_reported_as_disabled(client, monkeypatch):
    from app.errors import herr
    _configure(monkeypatch, provider="smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", "synthetic-key")
    def unavailable(*args):
        raise herr(503, "Не удалось отправить код", "Код ебәреп булманы")
    monkeypatch.setattr(auth, "send_sms", unavailable)
    response = client.post("/auth/request-code", json={"phone": "+79990019903"})
    assert response.status_code == 503
    assert "code" not in response.json()["detail"]
    with Session(engine) as session:
        live = session.exec(select(OtpCode).where(
            OtpCode.phone == "+79990019903", OtpCode.expires_at > utcnow(),
        )).all()
    assert live == []


def test_telegram_missing_phone_has_specific_machine_code(client, monkeypatch, telegram_login_ready):
    with Session(engine) as session:
        session.add(TgAuth(request_id="rs01-needs-phone", telegram_id="901019903",
                           status="sent", code="123456", expires_at=utcnow() + timedelta(minutes=5)))
        session.commit()
    response = client.post("/auth/tg/verify", json={"request_id": "rs01-needs-phone", "code": "123456"})
    assert response.status_code == 403, response.text
    assert response.json()["detail"]["code"] == "phone_required"


@pytest.mark.parametrize("count,partial", [(2000, False), (2001, True)])
def test_feed_scan_boundary_is_explicit(client, user_factory, monkeypatch, scan_rows, count, partial):
    from app.models import RideRequest
    from app.routers import requests
    driver, passenger = user_factory("RS01 viewer"), user_factory("RS01 requests")
    scan_rows["passengers"].append(passenger["id"])
    original = requests.live_request_conds
    monkeypatch.setattr(requests, "live_request_conds", lambda: [
        *original(), RideRequest.passenger_id == passenger["id"],
    ])
    monkeypatch.setattr(requests, "trust_level", lambda *args: 0)
    with Session(engine) as session:
        session.add_all([RideRequest(passenger_id=passenger["id"], from_city="Сибай", to_city="Акъяр",
                                     only_trusted=True) for _ in range(count)])
        session.commit()
    response = client.get("/requests/feed", headers=driver["auth"])
    assert response.status_code == 200, response.text
    assert response.json() == []
    assert (response.headers.get("X-Yuldash-Partial") == "1") is partial


def test_partial_header_is_exposed_to_browser(client):
    response = client.get("/config/login-methods", headers={"Origin": settings.cors_origin_list[0]})
    assert "X-Yuldash-Partial" in response.headers.get("Access-Control-Expose-Headers", "")


@pytest.mark.parametrize("count,partial", [(2000, False), (2001, True)])
def test_match_scan_boundary_is_explicit(client, user_factory, monkeypatch, scan_rows, count, partial):
    from app.models import Ride, RideRequest, UserRole
    from app.routers import requests
    driver = user_factory("RS01 match driver", role=UserRole.driver, gender="male")
    passenger = user_factory("RS01 match passenger", gender="female")
    scan_rows["passengers"].append(passenger["id"])
    scan_rows["drivers"].append(driver["id"])
    original = requests._ride_chunks
    def own_rides(session, query, scan=None):
        return original(session, query.where(Ride.driver_id == driver["id"]), scan)
    monkeypatch.setattr(requests, "_ride_chunks", own_rides)
    with Session(engine) as session:
        request = RideRequest(passenger_id=passenger["id"], from_city="Сибай", to_city="Акъяр",
                              women_only=True)
        session.add(request)
        session.add_all([Ride(driver_id=driver["id"], from_city="Сибай", to_city="Акъяр",
                              depart_at=utcnow() + timedelta(days=3), seats_total=3,
                              seats_left=3, price=300) for _ in range(count)])
        session.commit()
        session.refresh(request)
        rid = request.id
    response = client.get("/match/rides", headers=passenger["auth"], params={"request_id": rid})
    assert response.status_code == 200, response.text
    assert response.json() == []
    assert (response.headers.get("X-Yuldash-Partial") == "1") is partial
