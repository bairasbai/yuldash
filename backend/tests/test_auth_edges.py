from datetime import timedelta

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (
    Ad, AppReview, Block, Booking, DeviceToken, DriverProfile, Message, OtpCode,
    Payment, Rating, RefreshToken, Report, Ride, RideRequest, SosEvent, TgAuth,
    TrustedContact, UploadEvent, User, UserRole,
)
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


def test_otp_is_single_use(client):
    """P1: код входа одноразовый — после успешной сверки повторный вход тем же кодом не проходит."""
    phone = "+79990002030"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    ok = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Раз"})
    assert ok.status_code == 200, ok.text
    # тот же код второй раз — код погашен (удалён) → 400, не пускаем
    again = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert again.status_code == 400


def test_seed_demo_forbidden_in_prod(monkeypatch):
    """P2: прод-гвард запрещает seed_demo (фейковые водители в реальной БД)."""
    from app.config import Settings
    # taxi_enabled/redis_url — явно: иначе Settings() тянет локальный .env разработчика,
    # и прод-гвард такси роняет тест, который про seed_demo.
    s = Settings(env="prod", jwt_secret="x" * 20, cors_origins="https://yulbash.ru",
                 payments_provider="sbp_manual", sbp_phone="+79990000000",
                 database_url="postgresql://x", media_base_url="https://yulbash.ru",
                 seed_demo=True, taxi_enabled=False, redis_url="")
    try:
        s.validate_production()
        assert False, "ожидали RuntimeError на seed_demo=True в проде"
    except RuntimeError as e:
        assert "SEED_DEMO" in str(e)
    # с выключенным seed_demo та же конфигурация валидна
    s2 = s.model_copy(update={"seed_demo": False})
    s2.validate_production()   # не бросает


def test_weak_jwt_secret_forbidden_in_prod():
    """P2: прод-гвард ловит dev-секрет, даже если он длиннее 16 (фолбэк docker-compose)."""
    from app.config import Settings
    base = dict(env="prod", cors_origins="https://yulbash.ru", payments_provider="sbp_manual",
                sbp_phone="+79990000000", database_url="postgresql://x",
                media_base_url="https://yulbash.ru", seed_demo=False,
                taxi_enabled=False, redis_url="")   # не зависим от локального .env
    # compose-фолбэк — длинный, но dev → должен отвергаться
    s = Settings(jwt_secret="dev-secret-change-me-please-1234", **base)
    try:
        s.validate_production()
        assert False, "ожидали RuntimeError на dev-секрет в проде"
    except RuntimeError as e:
        assert "JWT_SECRET" in str(e)
    # нормальный длинный секрет — проходит
    Settings(jwt_secret="k7x9Qp2mZr4tLw8nBv6yHc3s", **base).validate_production()


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
    assert conflict.status_code == 200, conflict.text
    assert conflict.json()["user"]["phone"] == "+79990003030"
    with Session(engine) as session:
        user = session.exec(select(User).where(User.telegram_id == "555000")).first()
        assert user.phone == "+79990003030"


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


def test_admin_telegram_callback_moderates_driver(monkeypatch, client):
    monkeypatch.setattr(settings, "telegram_webhook_secret", "secret")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "5141534025")
    monkeypatch.setattr("app.routers.auth._telegram_api", lambda method, payload: None)
    with Session(engine) as session:
        driver = User(phone="+79990005050", name="Driver", verified=False)
        session.add(driver)
        session.commit()
        session.refresh(driver)
        session.add(DriverProfile(user_id=driver.id, docs_status="pending"))
        session.commit()
        driver_id = driver.id

    headers = {"x-telegram-bot-api-secret-token": "secret"}
    forbidden = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "cb0",
                "from": {"id": 1},
                "data": f"drv:ok:{driver_id}",
                "message": {"message_id": 10, "chat": {"id": 1}},
            }
        },
    )
    assert forbidden.status_code == 200
    with Session(engine) as session:
        assert session.get(User, driver_id).verified is False

    approved = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "cb1",
                "from": {"id": 5141534025},
                "data": f"drv:ok:{driver_id}",
                "message": {"message_id": 11, "chat": {"id": 5141534025}},
            }
        },
    )
    assert approved.status_code == 200
    with Session(engine) as session:
        user = session.get(User, driver_id)
        profile = session.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()
        assert user.verified is True
        assert profile.docs_status == "verified"


def test_admin_telegram_callback_moderates_ad(monkeypatch, client):
    monkeypatch.setattr(settings, "telegram_webhook_secret", "secret")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "5141534025")
    monkeypatch.setattr("app.routers.auth._telegram_api", lambda method, payload: None)
    monkeypatch.setattr("app.routers.auth.send_push", lambda session, user_id, title, body: None)
    with Session(engine) as session:
        owner = User(phone="+79990006060", name="Partner", verified=True)
        session.add(owner)
        session.commit()
        session.refresh(owner)
        ad = Ad(
            owner_id=owner.id,
            created_by=owner.id,
            title="Test Ad",
            text="Text",
            status="pending_review",
            package="city",
            budget_kop=100000,
            period_days=30,
        )
        session.add(ad)
        session.commit()
        session.refresh(ad)
        ad_id = ad.id

    headers = {"x-telegram-bot-api-secret-token": "secret"}
    forbidden = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "ad0",
                "from": {"id": 1},
                "data": f"ad:ok:{ad_id}",
                "message": {"message_id": 20, "chat": {"id": 1}},
            }
        },
    )
    assert forbidden.status_code == 200
    with Session(engine) as session:
        assert session.get(Ad, ad_id).status == "pending_review"

    approved = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "ad1",
                "from": {"id": 5141534025},
                "data": f"ad:ok:{ad_id}",
                "message": {"message_id": 21, "chat": {"id": 5141534025}},
            }
        },
    )
    assert approved.status_code == 200
    with Session(engine) as session:
        ad = session.get(Ad, ad_id)
        assert ad.status == "active"
        assert ad.reject_reason == ""
        assert ad.reviewed_at is not None


def test_admin_telegram_callback_confirms_and_rejects_payment(monkeypatch, client):
    monkeypatch.setattr(settings, "telegram_webhook_secret", "secret")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "5141534025")
    monkeypatch.setattr("app.routers.auth._telegram_api", lambda method, payload: None)
    with Session(engine) as session:
        driver = User(phone="+79990007070", name="Pay Driver", verified=True)
        donor = User(phone="+79990007071", name="Donor", verified=True)
        session.add(driver)
        session.add(donor)
        session.commit()
        session.refresh(driver)
        session.refresh(donor)
        ride = Ride(driver_id=driver.id, from_city="Ufa", to_city="Sibay", depart_at=utcnow() + timedelta(days=1))
        session.add(ride)
        session.commit()
        session.refresh(ride)
        boost_payment = Payment(
            user_id=driver.id,
            purpose="boost",
            ride_id=ride.id,
            tier="day",
            amount_kop=49000,
            status="pending",
        )
        donate_payment = Payment(user_id=donor.id, purpose="donate", amount_kop=10000, status="pending")
        session.add(boost_payment)
        session.add(donate_payment)
        session.commit()
        session.refresh(boost_payment)
        session.refresh(donate_payment)
        boost_payment_id = boost_payment.id
        donate_payment_id = donate_payment.id
        ride_id = ride.id

    headers = {"x-telegram-bot-api-secret-token": "secret"}
    forbidden = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "pay0",
                "from": {"id": 1},
                "data": f"pay:ok:{boost_payment_id}",
                "message": {"message_id": 30, "chat": {"id": 1}},
            }
        },
    )
    assert forbidden.status_code == 200
    with Session(engine) as session:
        assert session.get(Payment, boost_payment_id).status == "pending"
        assert session.get(Ride, ride_id).boosted_until is None

    approved = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "pay1",
                "from": {"id": 5141534025},
                "data": f"pay:ok:{boost_payment_id}",
                "message": {"message_id": 31, "chat": {"id": 5141534025}},
            }
        },
    )
    assert approved.status_code == 200
    with Session(engine) as session:
        assert session.get(Payment, boost_payment_id).status == "succeeded"
        ride = session.get(Ride, ride_id)
        assert ride.boosted_until is not None
        assert ride.boost_tier == "day"

    rejected = client.post(
        "/telegram/webhook",
        headers=headers,
        json={
            "callback_query": {
                "id": "pay2",
                "from": {"id": 5141534025},
                "data": f"pay:no:{donate_payment_id}",
                "message": {"message_id": 32, "chat": {"id": 5141534025}},
            }
        },
    )
    assert rejected.status_code == 200
    with Session(engine) as session:
        assert session.get(Payment, donate_payment_id).status == "canceled"


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


def test_me_update_city(client, user_factory):
    """Родной город: /me/update принимает city (тримит, режет по 80), /me его отдаёт,
    пустая строка сбрасывает. По умолчанию город пустой."""
    u = user_factory("CityMan")
    # По умолчанию — пусто.
    assert client.get("/me", headers=u["auth"]).json()["city"] == ""
    # Сохранили город (с лишними пробелами — должны обрезаться).
    r = client.post("/me/update", headers=u["auth"], json={"city": "  Сибай  "})
    assert r.status_code == 200
    assert r.json()["city"] == "Сибай"
    assert client.get("/me", headers=u["auth"]).json()["city"] == "Сибай"
    # Слишком длинный город отклоняется валидацией (max_length=80), как и имя.
    assert client.post("/me/update", headers=u["auth"], json={"city": "Г" * 200}).status_code == 422
    # Город при этом не изменился.
    assert client.get("/me", headers=u["auth"]).json()["city"] == "Сибай"
    # Обновление только имени НЕ трогает город.
    client.post("/me/update", headers=u["auth"], json={"name": "CityManRenamed"})
    assert client.get("/me", headers=u["auth"]).json()["city"] == "Сибай"
    # Пустая строка сбрасывает город.
    r = client.post("/me/update", headers=u["auth"], json={"city": "   "})
    assert r.json()["city"] == ""


def test_delete_account_wipes_all_data(client, user_factory):
    """POST /me/delete стирает аккаунт и ВСЕ его данные во всех таблицах,
    отвязывает рефералов, а старый токен после этого получает 401."""
    me = user_factory("ToDelete")
    other = user_factory("Survivor")
    uid, oid = me["id"], other["id"]

    with Session(engine) as s:
        u = s.get(User, uid)
        phone = u.phone
        # other приглашён мной → после удаления referred_by должен обнулиться, сам other остаться.
        surv = s.get(User, oid)
        surv.referred_by = uid
        s.add(surv)

        ride = Ride(driver_id=uid, from_city="Уфа", to_city="Баймаҡ", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        booking = Booking(ride_id=ride.id, passenger_id=uid)
        s.add(booking)
        s.commit()
        s.refresh(booking)

        s.add(DriverProfile(user_id=uid, car_make="Lada"))
        s.add(Message(booking_id=booking.id, sender_id=uid, text="привет"))
        s.add(Rating(booking_id=booking.id, rater_id=uid, ratee_id=oid, stars=5))
        s.add(TrustedContact(user_id=uid, name="Мама", phone="+70000000000"))
        s.add(RideRequest(passenger_id=uid, from_city="Уфа", to_city="Сибай"))
        s.add(RefreshToken(user_id=uid, token_hash=f"hash-{uid}", expires_at=utcnow() + timedelta(days=1)))
        s.add(DeviceToken(user_id=uid, token=f"fcm-{uid}"))
        s.add(OtpCode(phone=phone, code="123456", expires_at=utcnow() + timedelta(minutes=5)))
        s.add(UploadEvent(user_id=uid))
        s.add(AppReview(user_id=uid, name="ToDelete", stars=5, text="норм"))
        s.add(SosEvent(user_id=uid, booking_id=booking.id))
        s.add(Report(reporter_id=uid, target_user_id=oid))
        s.add(Report(reporter_id=oid, target_user_id=uid))
        s.add(Block(user_id=uid, blocked_user_id=oid))
        s.commit()

    assert client.post("/me/delete", headers=me["auth"]).status_code == 200

    with Session(engine) as s:
        assert s.get(User, uid) is None                       # аккаунт удалён
        survivor = s.get(User, oid)
        assert survivor is not None                           # чужой аккаунт цел
        assert survivor.referred_by is None                   # реф-связь отвязана

        def cnt(model, cond):
            return len(s.exec(select(model).where(cond)).all())

        assert cnt(DriverProfile, DriverProfile.user_id == uid) == 0
        assert cnt(Ride, Ride.driver_id == uid) == 0
        assert cnt(Booking, Booking.passenger_id == uid) == 0
        assert cnt(Message, Message.sender_id == uid) == 0
        assert cnt(Rating, (Rating.rater_id == uid) | (Rating.ratee_id == uid)) == 0
        assert cnt(TrustedContact, TrustedContact.user_id == uid) == 0
        assert cnt(RideRequest, RideRequest.passenger_id == uid) == 0
        assert cnt(RefreshToken, RefreshToken.user_id == uid) == 0
        assert cnt(DeviceToken, DeviceToken.user_id == uid) == 0
        assert cnt(OtpCode, OtpCode.phone == phone) == 0
        assert cnt(UploadEvent, UploadEvent.user_id == uid) == 0
        assert cnt(AppReview, AppReview.user_id == uid) == 0
        assert cnt(SosEvent, SosEvent.user_id == uid) == 0
        assert cnt(Report, (Report.reporter_id == uid) | (Report.target_user_id == uid)) == 0
        assert cnt(Block, (Block.user_id == uid) | (Block.blocked_user_id == uid)) == 0

    # Старый токен больше не работает — юзера нет.
    assert client.get("/me", headers=me["auth"]).status_code == 401
