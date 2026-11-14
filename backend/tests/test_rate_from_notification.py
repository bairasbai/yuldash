from datetime import timedelta, timezone
import json

import pywebpush

from sqlmodel import Session, select

from app import rate_reminder, services
from app.db import engine
from app.models import AuthSession, Booking, BookingStatus, Rating, Ride, SafetyProfile, User, UserRole, WebPushSubscription
from app.rating_service import apply_rating
from app.services import _rating_web_push
from app.timeutil import utcnow


def _booking(user_factory, *, status=BookingStatus.done, age=timedelta(hours=2)):
    driver = user_factory("Notification rating driver", role=UserRole.driver)
    passenger = user_factory("Notification rating passenger")
    depart = utcnow() - age
    with Session(engine, expire_on_commit=False) as session:
        ride = Ride(driver_id=driver["id"], from_city="Баймак", to_city="Сибай", depart_at=depart)
        session.add(ride)
        session.flush()
        booking = Booking(ride_id=ride.id, passenger_id=passenger["id"], status=status)
        session.add(booking)
        session.commit()
        return booking.id, passenger, driver, depart


def test_notification_keeps_the_existing_rating_and_does_not_replace_review(client, user_factory):
    bid, passenger, driver, _ = _booking(user_factory)
    path = f"/bookings/{bid}/rate"
    initial = client.post(path, json={"stars": 3, "tags": "ontime", "text": "Тестовый отзыв"},
                          headers=passenger["auth"])
    assert initial.status_code == 200, initial.text
    assert initial.json()["my_stars"] == 3 and initial.json()["kept"] is False
    fast = client.post(path, json={"stars": 5, "from_notification": True, "text": "Другое"},
                       headers=passenger["auth"])
    assert fast.status_code == 200, fast.text
    assert fast.json()["my_stars"] == 3 and fast.json()["kept"] is True
    assert fast.json()["count"] == initial.json()["count"]
    with Session(engine) as session:
        review = session.exec(select(Rating).where(Rating.booking_id == bid)).one()
        assert (review.stars, review.tags, review.text) == (3, "ontime", "Тестовый отзыв")
    edited = client.post(path, json={"stars": 4}, headers=passenger["auth"])
    assert edited.json()["kept"] is False and edited.json()["my_stars"] == 4
    created = client.post(path, json={"stars": 5, "from_notification": True}, headers=driver["auth"])
    assert created.status_code == 200, created.text
    assert created.json()["my_stars"] == 5 and created.json()["kept"] is False
    repeated = client.post(path, json={"stars": 5, "from_notification": True}, headers=driver["auth"])
    assert repeated.json()["kept"] is True and repeated.json()["my_stars"] == 5


def test_notification_respects_participant_status_and_window(client, user_factory):
    bid, passenger, _, _ = _booking(user_factory, status=BookingStatus.confirmed)
    stranger = user_factory("Notification rating outsider")
    payload = {"stars": 5, "from_notification": True}
    path = f"/bookings/{bid}/rate"
    rejected = client.post(path, json=payload, headers=stranger["auth"])
    assert rejected.status_code == 403 and rejected.json()["detail"]["code"] == "rating_forbidden"
    early = client.post(path, json=payload, headers=passenger["auth"])
    assert early.status_code == 409 and early.json()["detail"]["code"] == "ride_not_rateable"
    old, member, _, _ = _booking(user_factory, age=timedelta(days=61))
    expired = client.post(f"/bookings/{old}/rate", json=payload, headers=member["auth"])
    assert expired.status_code == 409 and expired.json()["detail"]["code"] == "rating_expired"


def test_paused_passenger_can_rate_only_a_fresh_trip_from_notification(client, user_factory):
    bid, passenger, _, _ = _booking(user_factory, age=timedelta(days=3))
    with Session(engine) as session:
        session.add(SafetyProfile(user_id=passenger["id"], standing="suspended",
                                  suspended_until=utcnow() + timedelta(days=1)))
        session.commit()
    path = f"/bookings/{bid}/rate"
    payload = {"stars": 5, "from_notification": True}
    denied = client.post(path, json=payload, headers=passenger["auth"])
    assert denied.status_code == 403 and denied.json()["detail"]["code"] == "rating_paused"
    with Session(engine) as session:
        booking = session.get(Booking, bid)
        session.get(Ride, booking.ride_id).depart_at = utcnow() - timedelta(hours=1)
        session.commit()
    assert client.post(path, json=payload, headers=passenger["auth"]).status_code == 200


def test_kept_rating_releases_transaction_before_request_session_closes(client, user_factory):
    bid, passenger, driver, depart = _booking(user_factory)
    with Session(engine) as first:
        result = apply_rating(first, first.get(User, passenger["id"]), driver["id"], booking_id=bid,
                              happened_at=depart, stars=5, only_if_new=True)
        assert result.kept is False
        with Session(engine) as second:
            changed = apply_rating(second, second.get(User, passenger["id"]), driver["id"], booking_id=bid,
                                   happened_at=depart, stars=3)
            assert changed.my_stars == 3
        again = apply_rating(first, first.get(User, passenger["id"]), driver["id"], booking_id=bid,
                             happened_at=depart, stars=5, only_if_new=True)
        assert again.kept and again.my_stars == 3
        assert not first.in_transaction()


def test_web_push_delivers_rating_buttons_only_to_a_current_bound_login(client, user_factory, monkeypatch):
    bid, owner, _, _ = _booking(user_factory)
    now = utcnow()
    ids = {kind: f"rs13-{owner['id']}-{kind}" for kind in ("live", "revoked", "missing-binding")}
    with Session(engine) as session:
        for kind, sid in ids.items():
            session.add(AuthSession(id=sid, user_id=owner["id"], access_expires_at=now + timedelta(hours=1),
                                    revoked_at=now if kind == "revoked" else None))
        session.flush()
        for kind in (*ids, "legacy"):
            session.add(WebPushSubscription(user_id=owner["id"], endpoint=f"https://push.invalid/{owner['id']}/{kind}",
                                            auth_session_id=ids.get(kind), binding_id=None if kind == "missing-binding" else "a" * 32))
        session.commit()
    deliveries = {}
    monkeypatch.setattr(services.settings, "vapid_public_key", "fixture-public")
    monkeypatch.setattr(services.settings, "vapid_private_key", "fixture-private")
    monkeypatch.setattr(pywebpush, "webpush", lambda **kw: deliveries.update({
        kw["subscription_info"]["endpoint"].rsplit("/", 1)[-1]: json.loads(kw["data"]),
    }))
    metadata = {"type": "booking_done", "id": str(bid), "rate": "1", "rate_role": "passenger",
                "rate_until": str(int((now + timedelta(days=1)).replace(tzinfo=timezone.utc).timestamp()))}
    with Session(engine) as session:
        services._send_web_push(session, owner["id"], "Оцени поездку", "Тест", metadata)
    assert set(deliveries) == {"live", "legacy"}
    assert deliveries["legacy"] == {"title": "Юлдаш", "tag": "yuldash", "url": "/"}
    assert deliveries["live"]["session_id"] == ids["live"]
    assert deliveries["live"]["rate"]["recipient_user_id"] == owner["id"]
    assert deliveries["live"]["url"] == f"/trip/{bid}"
    assert [item["action"] for item in deliveries["live"]["actions"]] == ["rate_five", "rate_details"]


def test_rideshare_reminder_waits_until_morning_and_builds_bound_actions(client, user_factory, monkeypatch):
    bid, passenger, driver, depart = _booking(user_factory, age=timedelta(days=5))
    sent = []
    monkeypatch.setattr(rate_reminder, "push_notification",
                        lambda _s, uid, *_args, **kw: sent.append((uid, kw)))
    monkeypatch.setattr(rate_reminder, "suspended_user_ids", lambda _s: {passenger["id"]})
    monkeypatch.setattr(rate_reminder, "_is_quiet_hour", lambda _now: True)
    with Session(engine) as session:
        assert (bid, driver["id"]) not in rate_reminder.rate_reminder_once(session)
        assert not session.get(Booking, bid).rate_reminded
    monkeypatch.setattr(rate_reminder, "_is_quiet_hour", lambda _now: False)
    with Session(engine) as session:
        session.get(User, driver["id"]).language = "ba"
        session.commit()
        assert (bid, driver["id"]) in rate_reminder.rate_reminder_once(session)
        uid, kwargs = next((uid, kw) for uid, kw in sent if kw["ref_kind"] == "booking" and kw["ref_id"] == bid)
        assert uid == driver["id"]
        assert not any(uid == passenger["id"] and kw["ref_kind"] == "booking" and kw["ref_id"] == bid for uid, kw in sent)
        data = kwargs["data"]
        assert (data["type"], data["rate_role"], data["private"]) == ("booking_done", "driver", "1")
        expiry = int((depart + timedelta(days=60)).replace(tzinfo=timezone.utc).timestamp())
        actions = _rating_web_push(session, uid, {**data, "recipient_user_id": -1})
        assert actions["rate"] == {"booking_id": bid, "recipient_user_id": driver["id"], "until": expiry}
        assert actions["actions"][1]["title"] == "Иҫкәрмәләр бар"
        assert _rating_web_push(session, uid, {**data, "rate_until": "bad"}) == {}
        assert session.get(Booking, bid).rate_reminded
