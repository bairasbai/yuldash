"""Done push omits route at actual FCM/WebPush serialization boundaries.

Synthetic persisted done orders isolate notification behavior, not money settlement.
This is not a claim about old messages, actual lock screens or all notifications.
"""
import json
from datetime import timedelta
from types import SimpleNamespace

import pytest
from firebase_admin import messaging
from sqlalchemy import delete
from sqlmodel import Session, select

from app import instant_service as isv, services
from app.config import settings
from app.db import engine
from app.models import AuthSession, DeviceToken, InstantOrder, InstantOrderStatus as S, Notification, User, UserRole, WebPushSubscription
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401

FROM_TEXT = "ул. 8 Марта, 12, кв. 9, код 4321"
TO_TEXT = "ул. Гагарина, 5, кв. 17"


@pytest.fixture
def done_delivery(user_factory, monkeypatch):
    owner = user_factory("DonePrivacySynthetic")
    uid = owner["id"]
    sid = f"done-privacy-session-{uid}"
    endpoint = f"https://push.invalid/done-privacy-{uid}"
    token = f"synthetic-done-device-{uid}"
    oid = _order(uid, S.done, from_text=FROM_TEXT, to_text=TO_TEXT)
    with Session(engine) as session:
        session.add(AuthSession(id=sid, user_id=uid, access_expires_at=utcnow() + timedelta(hours=1)))
        session.add(DeviceToken(user_id=uid, token=token))
        session.commit()
        session.add(WebPushSubscription(user_id=uid, endpoint=endpoint, auth_session_id=sid,
                                        binding_id="b" * 32, p256dh="synthetic", auth="synthetic"))
        session.commit()
    monkeypatch.setattr(settings, "firebase_credentials", "never-read-synthetic.json")
    monkeypatch.setattr(settings, "vapid_private_key", "synthetic-not-a-key")
    monkeypatch.setattr(settings, "vapid_public_key", "synthetic-not-a-key")
    monkeypatch.setattr(services, "_fcm_app", object())
    fcm, web, shares = [], [], []
    def capture_fcm(messages):
        fcm.extend(json.loads(messaging._MessagingService.JSON_ENCODER.encode(m)) for m in messages)
        return SimpleNamespace(responses=[SimpleNamespace(success=True, exception=None) for _ in messages])
    def capture_web(**kwargs):
        web.append((kwargs["subscription_info"]["endpoint"], json.loads(kwargs["data"])))
    monkeypatch.setattr(messaging, "send_each", capture_fcm)
    monkeypatch.setattr("pywebpush.webpush", capture_web)
    monkeypatch.setattr(isv, "_notify_order_shares", lambda session, order, event: shares.append((order.id, event)))
    with Session(engine) as session:
        notifications_before = list(session.exec(select(Notification.id).where(Notification.user_id == uid)).all())
    yield owner, oid, sid, endpoint, token, fcm, web, shares
    with Session(engine) as session:
        assert list(session.exec(select(Notification.id).where(Notification.user_id == uid)).all()) == notifications_before
    with Session(engine) as session:
        session.execute(delete(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint))
        session.execute(delete(DeviceToken).where(DeviceToken.token == token))
        session.execute(delete(AuthSession).where(AuthSession.id == sid))
        session.commit()


@pytest.mark.parametrize("language,title", [("ru", "Поездка завершена"), ("ba", "Сәфәр тамамланды")])
@pytest.mark.parametrize("channel", ["fcm", "web"])
def test_done_push_has_no_route_at_real_transport_boundary(client, done_delivery, language, title, channel):
    owner, oid, sid, endpoint, token, fcm, web, shares = done_delivery
    with Session(engine) as session:
        user = session.get(User, owner["id"])
        user.language = language
        session.add(user)
        session.commit()
        isv._notify_transition(session, session.get(InstantOrder, oid), S.done)
    assert shares == [(oid, "done")], "share notification stays a separate unchanged channel"
    # Authorized API keeps full route; this package only changes the done push.
    response = client.get(f"/instant/orders/{oid}", headers=owner["auth"])
    assert response.status_code == 200, response.text
    assert response.json()["from_text"] == FROM_TEXT
    assert response.json()["to_text"] == TO_TEXT
    receipt = client.get(f"/instant/orders/{oid}/receipt", headers=owner["auth"])
    assert receipt.status_code == 200, receipt.text
    assert receipt.json()["from_text"] == FROM_TEXT and receipt.json()["to_text"] == TO_TEXT
    assert receipt.json()["amount_kop"] == 30000
    assert len(fcm) == len(web) == 1
    assert fcm[0]["token"] == token and "notification" not in fcm[0]
    assert fcm[0]["data"]["recipient_user_id"] == str(owner["id"])
    assert web[0][0] == endpoint
    if channel == "fcm":
        payload = fcm[0]["data"]
        assert set(payload) == {"type", "order_id", "status", "recipient_user_id", "title", "body"}
        assert payload["type"] == "instant_status" and payload["order_id"] == str(oid)
        assert payload["status"] == "done"
    else:
        payload = web[0][1]
        assert payload["session_id"] == sid and payload["binding_id"] == "b" * 32
        assert payload["url"] == "/taxi" and payload["tag"] == "instant_status"
    assert payload["title"] == title
    assert payload["body"] == ""
    serialized = json.dumps(payload, ensure_ascii=False)
    assert not any(private in serialized for private in (FROM_TEXT, TO_TEXT, "кв.", "4321", "Гагарина", "8 Марта"))


@pytest.mark.parametrize("language", ["ru", "ba"])
@pytest.mark.parametrize("status,titles,bodies", [
    (S.accepted, ("Водитель найден 🚗", "Водитель табылды 🚗"),
     ("Водитель принял заказ — уже едет к тебе", "Водитель заказды ҡабул итте — һиңә килә инде")),
    (S.arriving, ("Машина на месте!", "Машина килеп етте!"),
     ("Водитель ждёт. Бесплатное ожидание — 7 мин", "Водитель көтә. Түләүһеҙ көтөү — 7 мин")),
    (S.onboard, ("В пути", "Юлда"), ("Хорошей поездки!", "Хәйерле юл!")),
])
def test_other_status_texts_are_unchanged_at_both_transports(done_delivery, monkeypatch, language, status, titles, bodies):
    owner, oid, sid, endpoint, token, fcm, web, shares = done_delivery
    monkeypatch.setattr(settings, "wait_free_minutes", 7)
    with Session(engine) as session:
        user = session.get(User, owner["id"])
        user.language = language
        order = session.get(InstantOrder, oid)
        order.status = status
        session.add(user)
        session.add(order)
        session.commit()
        isv._notify_transition(session, order, status)
    expected = 1 if language == "ba" else 0
    assert len(fcm) == len(web) == 1
    assert fcm[0]["token"] == token
    assert web[0][0] == endpoint
    for payload in (fcm[0]["data"], web[0][1]):
        assert payload["title"] == titles[expected]
        assert payload["body"] == bodies[expected]
    assert fcm[0]["data"]["status"] == status.value
    assert fcm[0]["data"]["order_id"] == str(oid)
    assert fcm[0]["data"]["recipient_user_id"] == str(owner["id"])
    assert shares == ([(oid, "sat")] if status == S.onboard else [])


def test_real_onboard_to_done_keeps_price_and_receipt_route(client, done_delivery, user_factory):
    """Real service completion of a fixed-price ride, not router payment settlement."""
    owner, oid, sid, endpoint, token, fcm, web, shares = done_delivery
    driver = user_factory("DonePrivacyDriver", role=UserRole.driver)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.status = S.onboard
        order.driver_id = driver["id"]
        order.price_estimate = 300
        order.price_final = None
        order.waiting_fee_kop = 250
        order.pickup_fee_kop = 0
        order.options_fee_kop = 0
        order.weather_fee_kop = 0
        session.add(order)
        session.commit()
        result = isv.transition(session, oid, isv.Actor.driver, S.done, driver["id"])
        assert result.status == S.done and result.done_at is not None
        assert result.price_estimate == 300 and result.price_final == 302
        assert result.waiting_fee_kop == 250
    assert shares == [(oid, "done")]
    assert len(fcm) == len(web) == 1
    assert fcm[0]["data"]["body"] == web[0][1]["body"] == ""
    assert fcm[0]["data"]["title"] == web[0][1]["title"] == "Поездка завершена"
    for actor in (owner, driver):
        response = client.get(f"/instant/orders/{oid}", headers=actor["auth"])
        assert response.status_code == 200, response.text
        assert response.json()["from_text"] == FROM_TEXT and response.json()["to_text"] == TO_TEXT
        receipt = client.get(f"/instant/orders/{oid}/receipt", headers=actor["auth"])
        assert receipt.status_code == 200, receipt.text
        body = receipt.json()
        assert body["from_text"] == FROM_TEXT and body["to_text"] == TO_TEXT
        assert body["amount_kop"] == body["price_kop"] == 30200
        assert body["waiting_fee_kop"] == 250
