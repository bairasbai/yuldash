"""Unit tests for shared service helpers without real network calls."""

import base64
import asyncio
import sys
import types

import httpx
import pytest
from fastapi import HTTPException
from sqlmodel import Session, SQLModel, create_engine, select

from app.config import settings
from app.db import engine as app_engine
from app.models import Block, Booking, DeviceToken, DriverProfile, Rating, Ride, UploadEvent, User, UserRole
from app import services


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text="ok"):
        self.status_code = status_code
        self.payload = payload if payload is not None else {}
        self.text = text

    def json(self):
        return self.payload


class FakeCache:
    def __init__(self):
        self.store = {}
        self.published = []

    def get(self, key):
        return self.store.get(key)

    def set(self, key, value, ex):
        self.store[key] = value
        self.last_ttl = ex

    def publish(self, channel, value):
        self.published.append((channel, value))


class FakeWebSocket:
    def __init__(self, fail=False):
        self.fail = fail
        self.sent = []

    async def send_json(self, data):
        if self.fail:
            raise RuntimeError("closed")
        self.sent.append(data)


def test_public_and_secure_urls_use_configured_base(monkeypatch):
    monkeypatch.setattr(settings, "media_base_url", "https://example.test/")
    assert services.public_media_url("/voice/a.m4a") == "https://example.test/media/voice/a.m4a"
    assert services.secure_docs_url("doc.jpg") == "https://example.test/secure/docs/doc.jpg"


def test_upload_validation_and_base64_decoding(monkeypatch):
    monkeypatch.setattr(settings, "max_upload_mb", 1)
    # тип определяется по СОДЕРЖИМОМУ (magic-bytes), а не по заявленному расширению
    assert services._detect_image_ext(b"\xff\xd8\xffdata") == "jpg"
    assert services._detect_image_ext(b"\x89PNG\r\n\x1a\nrest") == "png"
    assert services._detect_image_ext(b"RIFFxxxxWEBPrest") == "webp"
    assert services._detect_image_ext(b"bad") is None

    data_url = "data:image/png;base64," + base64.b64encode(b"\x89PNG\r\n\x1a\nrest").decode()
    data, ext = services.decode_upload_b64(data_url, {"png"}, "png", "photo", sniff_image=True)
    assert data.startswith(b"\x89PNG")
    assert ext == "png"

    with pytest.raises(HTTPException) as bad_b64:
        services.decode_upload_b64("not-base64", {"jpg"}, "jpg", "photo")
    assert bad_b64.value.status_code == 400

    with pytest.raises(HTTPException) as bad_ext:
        services.decode_upload_b64(base64.b64encode(b"data").decode(), {"jpg"}, "exe", "photo")
    assert bad_ext.value.status_code == 400

    with pytest.raises(HTTPException) as bad_magic:
        services.decode_upload_b64(base64.b64encode(b"not-image").decode(), {"jpg"}, "jpg", "photo", sniff_image=True)
    assert bad_magic.value.status_code == 400


def test_smsdar_send_formats_phone_and_detects_success(monkeypatch):
    captured = {}

    def fake_post(url, json, timeout):
        captured["url"] = url
        captured["json"] = json
        captured["timeout"] = timeout
        return FakeResponse(200, [{"id": "sms-id"}], "sent")

    monkeypatch.setattr(httpx, "post", fake_post)
    monkeypatch.setattr(settings, "smsdar_id", "id")
    monkeypatch.setattr(settings, "smsdar_password", "password")
    monkeypatch.setattr(settings, "smsdar_sender", "Yuldash")
    ok, info = services._smsdar_send("89990000000", "hello")
    assert ok is True
    assert "200" in info
    assert captured["json"]["pack"][0]["phone"] == "79990000000"
    assert captured["json"]["pack"][0]["sender"] == "Yuldash"


def test_notify_admin_telegram_is_noop_without_config_and_sends_with_config(monkeypatch):
    calls = []
    monkeypatch.setattr(settings, "telegram_bot_token", "")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    services.notify_admin_telegram("noop")

    def fake_post(url, json, timeout):
        calls.append((url, json, timeout))
        return FakeResponse()

    monkeypatch.setattr(httpx, "post", fake_post)
    monkeypatch.setattr(settings, "telegram_bot_token", "token")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "chat")
    services.notify_admin_telegram("hello", reply_markup={"inline_keyboard": []})
    assert calls[0][0] == "https://api.telegram.org/bottoken/sendMessage"
    assert calls[0][1] == {"chat_id": "chat", "text": "hello", "reply_markup": {"inline_keyboard": []}}


def test_send_text_and_send_sms_branches(monkeypatch):
    calls = []

    def fake_get(url, params, timeout):
        calls.append((url, params, timeout))
        return FakeResponse(payload={"sms": {params["to"]: {"status_code": 100, "status_text": "OK"}}})

    monkeypatch.setattr(httpx, "get", fake_get)
    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "sms_provider", "smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", "api")
    monkeypatch.setattr(settings, "sms_from", "Yuldash")
    services.send_text("+79990000000", "text")
    services.send_sms("+79990000000", "1234")
    assert calls[0][1]["from"] == "Yuldash"
    assert calls[1][1]["msg"] == "Yuldash: kod 1234"

    monkeypatch.setattr(settings, "sms_provider", "mock")
    monkeypatch.setattr(settings, "env", "prod")
    with pytest.raises(HTTPException) as unavailable:
        services.send_sms("+79990000000", "1234")
    assert unavailable.value.status_code == 503


def test_geocode_city_local_remote_and_failure(monkeypatch):
    assert services.geocode_city("") is None
    assert services.geocode_city("Уфа") == (54.735, 55.958)
    monkeypatch.setattr(settings, "yandex_geocoder_key", "")
    assert services.geocode_city("Unknown City") is None

    def fake_get(url, params, timeout):
        return FakeResponse(payload={
            "response": {
                "GeoObjectCollection": {
                    "featureMember": [
                        {"GeoObject": {"Point": {"pos": "58.664 52.716"}}},
                    ],
                },
            },
        })

    monkeypatch.setattr(httpx, "get", fake_get)
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    assert services.geocode_city("Remote City") == (52.716, 58.664)

    monkeypatch.setattr(httpx, "get", lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("network")))
    assert services.geocode_city("Broken City") is None


def test_cache_helpers_and_map_notification(monkeypatch):
    fake = FakeCache()
    monkeypatch.setattr(services, "_cache", fake)
    monkeypatch.setattr(services, "_cache_tried", True)

    assert services.cache_get_json("missing") is None
    services.cache_set_json("key", {"ok": True}, 42)
    assert services.cache_get_json("key") == {"ok": True}
    assert fake.last_ttl == 42

    fake.store["bad"] = "{bad-json"
    assert services.cache_get_json("bad") is None

    services.notify_map_changed()
    assert fake.published
    assert fake.published[0][0] == "yuldash:chat"


def test_cache_client_init_failure_is_safe(monkeypatch):
    monkeypatch.setattr(settings, "redis_url", "redis://broken")
    monkeypatch.setattr(services, "_cache", None)
    monkeypatch.setattr(services, "_cache_tried", False)

    def fail_from_url(*_args, **_kwargs):
        raise RuntimeError("redis down")

    import redis

    monkeypatch.setattr(redis, "from_url", fail_from_url)
    assert services._cache_client() is None
    assert services._cache_tried is True


def test_connection_manager_local_and_redis_fallback(monkeypatch):
    manager = services.ConnectionManager()
    ok = FakeWebSocket()
    broken = FakeWebSocket(fail=True)
    manager.register(10, ok)
    manager.register(10, broken)

    asyncio.run(manager.local_broadcast(10, {"type": "refresh"}))
    assert ok.sent == [{"type": "refresh"}]

    class BrokenRedis:
        async def publish(self, *_args, **_kwargs):
            raise RuntimeError("redis down")

    ok.sent.clear()
    monkeypatch.setattr(services, "_redis_pub", BrokenRedis())
    asyncio.run(manager.broadcast(10, {"type": "fallback"}))
    assert ok.sent == [{"type": "fallback"}]

    manager.disconnect(10, ok)
    manager.disconnect(10, broken)
    assert 10 not in manager.active_connections


def test_public_ride_payload_hides_pickup_for_dict_and_model(client, user_factory):
    driver = user_factory("PayloadDriver", role=UserRole.driver)
    created = client.post("/rides", headers=driver["auth"], json={
        "from_city": "A",
        "to_city": "B",
        "depart_at": "2030-01-01T10:00:00",
        "seats_total": 1,
        "price": 100,
        "pickup": "Private address",
        "pickup_lat": 54.1,
        "pickup_lng": 55.2,
    })
    assert created.status_code == 200
    payload = services.public_ride_payload(created.json())
    assert payload["pickup"] == ""
    assert payload["pickup_lat"] is None
    assert payload["pickup_lng"] is None


def test_seed_demo_populates_empty_database():
    engine = create_engine("sqlite:///:memory:")
    SQLModel.metadata.create_all(engine)
    with Session(engine) as session:
        services.seed_demo(session)
        rides = session.exec(select(Ride)).all()
        assert len(rides) == 4
        services.seed_demo(session)
        assert len(session.exec(select(Ride)).all()) == 4


def test_geocode_city_handles_remote_bad_position(monkeypatch):
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")

    def bad_get(*_args, **_kwargs):
        return FakeResponse(payload={
            "response": {
                "GeoObjectCollection": {
                    "featureMember": [{"GeoObject": {"Point": {"pos": "bad pos"}}}],
                },
            },
        })

    monkeypatch.setattr(httpx, "get", bad_get)
    assert services.geocode_city("Bad Remote City") is None


def test_upload_quota_blocks_after_daily_limit(monkeypatch, user_factory):
    user = user_factory("QuotaUser")
    monkeypatch.setattr(settings, "max_uploads_per_day", 1)
    with Session(app_engine) as session:
        services.enforce_upload_quota(session, user["id"])
        assert session.exec(select(UploadEvent).where(UploadEvent.user_id == user["id"])).first()
        with pytest.raises(HTTPException) as limited:
            services.enforce_upload_quota(session, user["id"])
    assert limited.value.status_code == 429


def test_blocks_and_user_bookings_helpers_cover_both_sides(client, user_factory):
    driver = user_factory("SvcDriver", role=UserRole.driver)
    passenger = user_factory("SvcPassenger")
    response = client.post(
        "/rides",
        headers=driver["auth"],
        json={
            "from_city": "SvcA",
            "to_city": "SvcB",
            "depart_at": "2030-01-01T10:00:00",
            "seats_total": 2,
            "price": 100,
        },
    )
    assert response.status_code == 200, response.text
    ride_id = response.json()["id"]
    booking = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()

    with Session(app_engine) as session:
        session.add(Block(user_id=driver["id"], blocked_user_id=passenger["id"]))
        # Artificial duplicate path: the same user is both passenger and driver, so user_bookings must de-dupe.
        session.add(Booking(ride_id=ride_id, passenger_id=driver["id"], seats=1, price=100))
        session.commit()

        driver_user = session.get(User, driver["id"])
        passenger_user = session.get(User, passenger["id"])
        assert services.is_blocked(session, driver["id"], passenger["id"]) is True
        assert services.is_blocked(session, passenger["id"], driver["id"]) is True
        assert services.blocked_user_ids(session, passenger["id"]) == {driver["id"]}
        assert booking["id"] in {b.id for b in services.user_bookings(session, passenger_user)}

        driver_booking_ids = [b.id for b in services.user_bookings(session, driver_user)]
        assert len(driver_booking_ids) == len(set(driver_booking_ids))


def test_driver_bundle_and_ride_out_use_profile_and_real_rating(client, user_factory):
    driver = user_factory("BundleDriver", role=UserRole.driver)
    passenger = user_factory("BundlePassenger")
    response = client.post(
        "/rides",
        headers=driver["auth"],
        json={
            "from_city": "BundleA",
            "to_city": "BundleB",
            "depart_at": "2030-01-01T10:00:00",
            "seats_total": 2,
            "price": 100,
        },
    )
    assert response.status_code == 200, response.text
    ride_id = response.json()["id"]
    booking = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()

    with Session(app_engine) as session:
        session.add(DriverProfile(
            user_id=driver["id"],
            online=True,
            rating=4.1,
            car_make="Kia",
            car_model="Rio",
        ))
        session.add(Rating(booking_id=booking["id"], rater_id=passenger["id"], ratee_id=driver["id"], stars=5, comment="ok"))
        session.add(Rating(booking_id=booking["id"], rater_id=driver["id"], ratee_id=driver["id"], stars=4, comment="self test"))
        session.commit()

        ride = session.get(Ride, ride_id)
        users, profiles, rating_agg, trips_agg = services.drivers_bundle(session, {driver["id"]})
        out = services.ride_out_with(ride, users, profiles, rating_agg, trips_agg)
        assert out.driver_name == "BundleDriver"
        assert out.driver_car == "Kia Rio"
        assert out.driver_online is True
        assert out.driver_rating == 4.5
        assert services.drivers_bundle(session, set()) == ({}, {}, {}, {})


def test_send_push_initializes_firebase_and_ignores_per_token_errors(monkeypatch, user_factory):
    user = user_factory("PushUser")
    with Session(app_engine) as session:
        session.add(DeviceToken(user_id=user["id"], token="ok-token"))
        session.add(DeviceToken(user_id=user["id"], token="bad-token"))
        session.commit()

    sent = []
    initialized = []

    firebase_admin = types.ModuleType("firebase_admin")
    credentials = types.ModuleType("firebase_admin.credentials")
    messaging = types.ModuleType("firebase_admin.messaging")

    class Certificate:
        def __init__(self, path):
            self.path = path

    class Notification:
        def __init__(self, title, body):
            self.title = title
            self.body = body

    class Message:
        def __init__(self, notification, token):
            self.notification = notification
            self.token = token

    def initialize_app(cert):
        initialized.append(cert.path)
        return object()

    class SendResponse:
        def __init__(self, exc=None):
            self.success = exc is None
            self.exception = exc

    class BatchResponse:
        def __init__(self, responses):
            self.responses = responses

    def send_each(messages):
        # batch-рассылка (send_each): ошибка одного токена не роняет остальные, она в его SendResponse.
        for m in messages:
            sent.append(m.token)
        return BatchResponse([SendResponse(None if m.token == "ok-token"
                                           else RuntimeError("fcm send failed")) for m in messages])

    credentials.Certificate = Certificate
    messaging.Notification = Notification
    messaging.Message = Message
    messaging.send_each = send_each
    firebase_admin.initialize_app = initialize_app
    firebase_admin.credentials = credentials
    firebase_admin.messaging = messaging

    monkeypatch.setitem(sys.modules, "firebase_admin", firebase_admin)
    monkeypatch.setitem(sys.modules, "firebase_admin.credentials", credentials)
    monkeypatch.setitem(sys.modules, "firebase_admin.messaging", messaging)
    monkeypatch.setattr(settings, "firebase_credentials", "firebase-test.json")
    monkeypatch.setattr(services, "_fcm_app", None)

    with Session(app_engine) as session:
        services.send_push(session, user["id"], "Title", "Body")

    assert initialized == ["firebase-test.json"]
    assert sent == ["ok-token", "bad-token"]
