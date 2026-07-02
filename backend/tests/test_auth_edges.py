from datetime import timedelta

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import DeviceToken, OtpCode, TgAuth, User, UserRole
from app.timeutil import utcnow


def test_admin_promotion_by_phone_and_telegram(monkeypatch, client):
    monkeypatch.setattr(settings, "admin_phones", "+79990001010")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "admin-tg-id")

    phone_code = client.post("/auth/request-code", json={"phone": "+79990001010"}).json()["dev_code"]
    by_phone = client.post(
        "/auth/verify",
        json={"phone": "+79990001010", "code": phone_code, "name": "Phone Admin"},
    )
    assert by_phone.status_code == 200, by_phone.text
    assert by_phone.json()["user"]["role"] == "admin"

    start = client.post("/auth/tg/start").json()["request_id"]
    webhook = client.post(
        "/telegram/webhook",
        json={
            "message": {
                "text": f"/start {start}",
                "from": {"id": "admin-tg-id", "first_name": "Tg Admin"},
                "chat": {"id": "admin-tg-id"},
            }
        },
    )
    assert webhook.status_code == 200
    with Session(engine) as session:
        row = session.exec(select(TgAuth).where(TgAuth.request_id == start)).first()
        row.shared_phone = "+79990001011"
        session.add(row)
        session.commit()
        code = row.code

    verified = client.post("/auth/tg/verify", json={"request_id": start, "code": code})
    assert verified.status_code == 200, verified.text
    assert verified.json()["user"]["role"] == "admin"


def test_auth_verify_rejects_missing_expired_and_too_many_attempts(client):
    assert client.post("/auth/verify", json={"phone": "+79990002020", "code": "000000"}).status_code == 400

    with Session(engine) as session:
        expired = OtpCode(
            phone="+79990002021",
            code="111111",
            expires_at=utcnow() - timedelta(seconds=1),
        )
        limited = OtpCode(
            phone="+79990002022",
            code="222222",
            attempts=5,
            expires_at=utcnow() + timedelta(minutes=5),
        )
        session.add(expired)
        session.add(limited)
        session.commit()

    assert client.post("/auth/verify", json={"phone": "+79990002021", "code": "111111"}).status_code == 400
    assert client.post("/auth/verify", json={"phone": "+79990002022", "code": "222222"}).status_code == 429


def test_tg_verify_states_and_phone_conflict(monkeypatch, client):
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    with Session(engine) as session:
        existing = User(phone="+79990003030", name="Existing", verified=True)
        session.add(existing)
        waiting = TgAuth(
            request_id="tg-waiting",
            status="waiting",
            expires_at=utcnow() + timedelta(minutes=5),
        )
        expired = TgAuth(
            request_id="tg-expired",
            status="sent",
            telegram_id="tg-expired-id",
            code="333333",
            expires_at=utcnow() - timedelta(seconds=1),
        )
        limited = TgAuth(
            request_id="tg-limited",
            status="sent",
            telegram_id="tg-limited-id",
            code="444444",
            attempts=5,
            expires_at=utcnow() + timedelta(minutes=5),
        )
        wrong = TgAuth(
            request_id="tg-wrong",
            status="sent",
            telegram_id="555000",
            code="555555",
            shared_phone="+79990003030",
            expires_at=utcnow() + timedelta(minutes=5),
        )
        session.add(waiting)
        session.add(expired)
        session.add(limited)
        session.add(wrong)
        session.commit()

    assert client.post("/auth/tg/verify", json={"request_id": "tg-waiting", "code": "000000"}).status_code == 409
    assert client.post("/auth/tg/verify", json={"request_id": "tg-expired", "code": "333333"}).status_code == 410
    assert client.post("/auth/tg/verify", json={"request_id": "tg-limited", "code": "444444"}).status_code == 429
    assert client.post("/auth/tg/verify", json={"request_id": "tg-wrong", "code": "000000"}).status_code == 400

    conflict = client.post("/auth/tg/verify", json={"request_id": "tg-wrong", "code": "555555"})
    assert conflict.status_code == 403
    with Session(engine) as session:
        user = session.exec(select(User).where(User.telegram_id == "555000")).first()
        assert user.phone == "tg555000"


def test_telegram_webhook_secret_contact_and_unknown_start(monkeypatch, client):
    monkeypatch.setattr(settings, "telegram_webhook_secret", "secret")
    forbidden = client.post("/telegram/webhook", json={"message": {}})
    assert forbidden.status_code == 403

    headers = {"x-telegram-bot-api-secret-token": "secret"}
    wrong_contact = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "message": {
                "from": {"id": 123},
                "chat": {"id": 123},
                "contact": {"user_id": 999, "phone_number": "+79990004040"},
            }
        },
    )
    assert wrong_contact.status_code == 200
    assert wrong_contact.json()["method"] == "sendMessage"

    unknown_start = client.post(
        "/telegram/webhook",
        headers=headers,
        json={"message": {"text": "/start unknown", "from": {"id": 123}, "chat": {"id": 123}}},
    )
    assert unknown_start.status_code == 200
    assert unknown_start.json()["method"] == "sendMessage"


def test_account_refresh_profile_and_push_edges(client, user_factory):
    first = user_factory("PushFirst")
    second = user_factory("PushSecond")

    assert client.post("/auth/refresh", json={"refresh_token": "   "}).status_code == 400
    assert client.post("/auth/vk-callback").status_code == 501
    assert client.post("/auth/whatsapp-callback").status_code == 501
    assert client.post("/push/register", headers=first["auth"], json={"token": "   "}).status_code == 400

    assert client.post("/push/register", headers=first["auth"], json={"token": "shared-token"}).status_code == 200
    assert client.post("/push/register", headers=second["auth"], json={"token": "shared-token"}).status_code == 200
    with Session(engine) as session:
        token = session.exec(select(DeviceToken).where(DeviceToken.token == "shared-token")).first()
        assert token.user_id == second["id"]

    updated = client.post(
        "/me/update",
        headers=second["auth"],
        json={"name": "   ", "avatar_url": " https://example.test/a.jpg "},
    )
    assert updated.status_code == 200
    assert updated.json()["name"] == "PushSecond"
    assert updated.json()["avatar_url"] == "https://example.test/a.jpg"
