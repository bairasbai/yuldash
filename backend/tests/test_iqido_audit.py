"""Аудит по чеклисту «Программирование и отладка» (2026-07-20) — негативные тесты на фиксы.

Покрывает находки код-аудита: валидация длины (#47), auth-негатив (#26/#4: подделка/протухание
JWT), краевые случаи (seats<1), устойчивость Telegram-вебхука (#18/#21), кламп цены отклика (QA#8).
"""
from datetime import timedelta, timezone

from jose import jwt
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Ride, UserRole
from app.timeutil import utcnow

from test_flows import _publish  # noqa: F401


def _future_iso():
    return (utcnow() + timedelta(days=1)).isoformat()


# --------------------------- #47 валидация длины ---------------------------

def test_ride_city_over_max_length_rejected(client, user_factory):
    drv = user_factory("LenDrv", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "x" * 200, "to_city": "Сибай", "depart_at": _future_iso(), "price": 100,
    })
    assert r.status_code == 422, "город > 120 символов должен отклоняться"


def test_request_city_over_max_length_rejected(client, user_factory):
    pax = user_factory("LenPax")
    r = client.post("/requests", headers=pax["auth"], json={"from_city": "Баймак", "to_city": "y" * 200})
    assert r.status_code == 422


def test_sos_category_over_max_length_rejected(client, user_factory):
    u = user_factory("SosLen")
    r = client.post("/sos", headers=u["auth"], json={"category": "z" * 100, "note": "test"})
    assert r.status_code == 422


# --------------------------- #26/#4 auth-негатив ---------------------------

def test_forged_jwt_rejected(client):
    forged = jwt.encode({"sub": "1"}, "totally-wrong-secret-not-ours", algorithm="HS256")
    r = client.get("/me", headers={"Authorization": f"Bearer {forged}"})
    assert r.status_code == 401, "токен, подписанный чужим секретом, должен быть 401"


def test_expired_jwt_rejected(client, user_factory):
    u = user_factory("ExpUser")
    exp = int((utcnow() - timedelta(hours=1)).replace(tzinfo=timezone.utc).timestamp())
    expired = jwt.encode({"sub": str(u["id"]), "exp": exp}, settings.jwt_secret, algorithm="HS256")
    r = client.get("/me", headers={"Authorization": f"Bearer {expired}"})
    assert r.status_code == 401, "просроченный токен должен быть 401"


def test_malformed_bearer_rejected(client):
    r = client.get("/me", headers={"Authorization": "Bearer not.a.jwt"})
    assert r.status_code == 401


# --------------------------- краевой случай брони ---------------------------

def test_book_zero_seats_rejected(client, user_factory):
    drv = user_factory("ZDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Баймак", to="Сибай")
    pax = user_factory("ZPax")
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 0})
    assert r.status_code == 400, "бронь на 0 мест должна отклоняться"


# --------------------------- #18/#21 устойчивость вебхука ---------------------------

def test_telegram_webhook_malformed_returns_200(client):
    # битый JSON НЕ должен давать 500 (иначе Telegram ретраит «ядовитый» апдейт)
    r = client.post("/telegram/webhook", content=b"{not json",
                    headers={"content-type": "application/json"})
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_telegram_webhook_bad_secret_403(client, monkeypatch):
    from app.routers import auth as auth_mod
    monkeypatch.setattr(auth_mod.settings, "telegram_webhook_secret", "right-secret")
    r = client.post("/telegram/webhook", json={"message": {}},
                    headers={"X-Telegram-Bot-Api-Secret-Token": "wrong-secret"})
    assert r.status_code == 403


# --------------------------- QA#8 кламп цены отклика ---------------------------

def test_driver_response_price_is_clamped(client, user_factory):
    """Цена отклика водителя (до 1_000_000 по RespondIn) не должна попадать в Ride/Booking
    выше общего клампа create_ride (100_000)."""
    pax = user_factory("ClampPax")
    drv = user_factory("ClampDrv", role=UserRole.driver)
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": "Баймак", "to_city": "Сибай", "seats": 1}).json()
    resp = client.post(f"/requests/{req['id']}/respond", headers=drv["auth"],
                       json={"price": 999_999, "comment": "дорого"})
    assert resp.status_code == 200
    responses = client.get(f"/requests/{req['id']}/responses", headers=pax["auth"]).json()
    resp_id = responses[0]["id"]
    accepted = client.post(f"/responses/{resp_id}/accept", headers=pax["auth"])
    assert accepted.status_code == 200
    with Session(engine) as s:
        ride = s.exec(select(Ride).where(Ride.driver_id == drv["id"]).order_by(Ride.id.desc())).first()
    assert ride is not None
    assert ride.price == 100_000, f"цена должна быть скламплена до 100000, а не {ride.price}"
