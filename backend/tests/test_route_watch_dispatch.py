from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, time, timedelta
import json
from threading import Barrier
from types import SimpleNamespace
from uuid import uuid4

from firebase_admin import messaging
import pywebpush
import pytest
from sqlmodel import Session, select

from app import route_watch_dispatch as dispatch, route_watch_worker, services
from app.db import engine
from app.models import Block, DeviceToken, Notification, Ride, RideStatus, RouteWatch, SafetyProfile, User, UserRole
from app.routers import rides
from app.route_watch_models import RouteWatchEvent, RouteWatchNotice
from app.security import issue_tokens
from app.timeutil import local_date, utcnow


def _route(driver_id):
    marker = str(driver_id).translate(str.maketrans("0123456789", "абвгдежзик"))
    return {"from_city": f"Синтетика {marker}с", "to_city": "Проверка"}


def _watch(owner_id, driver_id, now, **kwargs):
    with Session(engine) as session:
        row = RouteWatch(user_id=owner_id, expires_at=now + timedelta(days=6),
                         **_route(driver_id), **kwargs)
        session.add(row)
        session.commit()
        return row.id


def _ride(driver_id, departure):
    return Ride(
        driver_id=driver_id, **_route(driver_id),
        depart_at=departure, seats_total=3, seats_left=3, price=500,
    )


def _group(driver_id, now, *, group=None, size=1):
    with Session(engine) as session:
        packed = [_ride(driver_id, now + timedelta(days=index + 2)) for index in range(size)]
        session.add_all(packed)
        events = dispatch.enqueue_route_watch(
            session, packed[0], source_key="synthetic-" + uuid4().hex, group_key=group, siblings=packed[1:],
        )
        ride_ids = [row.id for row in packed]
        session.commit()
        return events, ride_ids


def _notices(watch_id, ride_ids):
    with Session(engine) as session:
        return session.exec(select(RouteWatchNotice).where(
            RouteWatchNotice.watch_id == watch_id, RouteWatchNotice.ride_id.in_(ride_ids),
        ).order_by(RouteWatchNotice.ride_id)).all()


def test_regular_publication_keeps_five_events_in_the_same_group(client, user_factory, monkeypatch):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    watch_id = _watch(user_factory()["id"], driver["id"], now)
    # A request can already be gone when the timer starts.
    monkeypatch.setattr(rides, "notify_route_watchers", lambda *_: 0)
    key = "cmd1_" + uuid4().hex
    headers = {**driver["auth"], "Idempotency-Key": key}
    body = {
        **_route(driver["id"]), "seats_total": 3, "price": 500,
        "depart_at": (now + timedelta(days=2)).isoformat() + "Z", "recurrence": "weekly",
    }
    response = client.post("/rides/commands/v1", headers=headers, json=body)
    assert response.status_code == 200, response.text
    primary_id = response.json()["id"]
    with Session(engine) as session:
        unrelated = _ride(driver["id"], now + timedelta(days=1))
        session.add(unrelated)
        session.flush()
        unrelated_id = unrelated.id
        session.commit()
        events = session.exec(select(RouteWatchEvent).join(Ride).where(
            Ride.driver_id == driver["id"],
        )).all()
    assert len(events) == 5
    assert len({row.source_key for row in events}) == 5
    assert {row.group_key for row in events} == {dispatch._key(driver["id"], "recurrence", key)}
    assert unrelated_id not in {row.ride_id for row in events}
    assert all(row.source_key == dispatch._key(driver["id"], "ride.create", key, row.ride_id, 0) for row in events)
    assert client.post("/rides/commands/v1", headers=headers, json=body).json()["id"] == primary_id
    # Find the committed group after publication, with an independent worker session.
    pushed = []
    monkeypatch.setattr(services, "send_push",
                        lambda session, user_id, title, body, data, **kwargs: pushed.append((user_id, data)))
    checked_at = utcnow() + timedelta(seconds=1)
    route_watch_worker.run_due(engine, now=checked_at)
    ride_ids = [row.ride_id for row in events]
    notices = _notices(watch_id, ride_ids)
    assert {row.ride_id for row in notices} == set(ride_ids)
    assert len({row.notification_id for row in notices}) == 1
    with Session(engine) as session:
        notification = session.get(Notification, notices[0].notification_id)
        assert notification.ref_id == primary_id
        assert "Сохранённых рейсов: 5" in notification.body_ru
        assert all(session.get(RouteWatchEvent, event.id).finished_at is not None for event in events)
    assert sum(data["id"] == str(primary_id) for _, data in pushed) == 1
    route_watch_worker.run_due(engine, now=checked_at + timedelta(days=1))
    assert len(_notices(watch_id, ride_ids)) == 5


def test_series_with_a_date_watch_announces_only_its_matching_ride(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    date = local_date(now + timedelta(days=4))
    watch_id = _watch(user_factory()["id"], driver["id"], now, watch_date=datetime.combine(date, time.min))
    events, ride_ids = _group(driver["id"], now, size=5, group=dispatch.GroupKey("recurrence", uuid4().hex))
    result = dispatch.dispatch_event(engine, events[0], now=now + timedelta(seconds=1))
    assert [note.ride_ids for note in result if note.watch_id == watch_id] == [(ride_ids[2],)]
    notices = _notices(watch_id, ride_ids)
    assert [note.ride_id for note in notices] == [ride_ids[2]]
    with Session(engine) as session:
        note = session.get(Notification, notices[0].notification_id)
        assert note.ref_id == ride_ids[2]
        assert "Сохранённых рейсов" not in note.body_ru


def test_group_loads_previous_notices_in_two_batches(client, user_factory, monkeypatch):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    watch_ids = {_watch(user_factory()["id"], driver["id"], now) for _ in range(20)}
    events, ride_ids = _group(driver["id"], now, size=5, group=dispatch.GroupKey("recurrence", uuid4().hex))
    batches = []
    previous = dispatch._notified_pairs

    def batched(session, matching_ids):
        batches.append(matching_ids)
        return previous(session, matching_ids)

    monkeypatch.setattr(dispatch, "_notified_pairs", batched)
    notices = dispatch.dispatch_event(engine, events[0], now=now + timedelta(seconds=1))
    assert {note.watch_id for note in notices} == watch_ids
    assert all(note.ride_ids == tuple(ride_ids) for note in notices)
    assert batches == [ride_ids, ride_ids]


@pytest.mark.parametrize("unavailable", ["paused", "cancelled", "past"])
def test_unavailable_ride_does_not_create_a_notice(client, user_factory, unavailable):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    watch_id = _watch(user_factory()["id"], driver["id"], now)
    events, ride_ids = _group(driver["id"], now)
    with Session(engine) as session:
        ride = session.get(Ride, ride_ids[0])
        if unavailable == "paused":
            session.add(SafetyProfile(user_id=driver["id"], suspended_until=now + timedelta(days=1)))
        elif unavailable == "cancelled":
            ride.status = RideStatus.cancelled
        else:
            ride.depart_at = now - timedelta(hours=3)
        session.commit()
    assert dispatch.dispatch_event(engine, events[0], now=now + timedelta(seconds=1)) == []
    assert _notices(watch_id, ride_ids) == []


def test_group_preserves_a_late_committed_sibling_until_quota_is_available(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    group = dispatch.GroupKey("series", "synthetic-base-" + uuid4().hex)
    watch_id = _watch(user_factory()["id"], driver["id"], now)
    first, ride_ids = _group(driver["id"], now, group=group)
    first_at = now + timedelta(seconds=1)
    assert len(dispatch.dispatch_event(engine, first[0], now=first_at)) == 1
    late, late_ride = _group(driver["id"], now + timedelta(days=1), group=group)
    assert dispatch.dispatch_event(engine, late[0], now=first_at + timedelta(seconds=1)) == []
    due_at = first_at + dispatch.NOTICE_INTERVAL
    with Session(engine) as session:
        event = session.get(RouteWatchEvent, late[0])
        assert event.finished_at is None and event.next_attempt_at == due_at
    assert len(dispatch.dispatch_event(engine, late[0], now=due_at)) == 1
    rows = _notices(watch_id, ride_ids + late_ride)
    assert [row.ride_id for row in rows] == ride_ids + late_ride
    assert len({row.notification_id for row in rows}) == 2


@pytest.mark.parametrize("same_group", [True, False])
def test_concurrent_workers_use_one_watch_quota(client, user_factory, monkeypatch, same_group):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    watch_id = _watch(user_factory()["id"], driver["id"], now)
    group = dispatch.GroupKey("series", "race-" + uuid4().hex) if same_group else None
    if same_group:
        events, ride_ids = _group(driver["id"], now, group=group, size=2)
    else:
        first, first_ride = _group(driver["id"], now)
        second, second_ride = _group(driver["id"], now)
        events, ride_ids = first + second, first_ride + second_ride
    barrier = Barrier(2)
    real = dispatch._announce_watch

    def synchronized(bind, group, watched, checked_at, entry):
        if watched == watch_id:
            barrier.wait(timeout=8)
        return real(bind, group, watched, checked_at, entry)

    monkeypatch.setattr(dispatch, "_announce_watch", synchronized)
    checked_at = now + timedelta(seconds=1)
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(lambda event_id: dispatch.dispatch_event(engine, event_id, now=checked_at), events))
    assert sum(note.watch_id == watch_id for outcome in outcomes for note in outcome) == 1
    notices = _notices(watch_id, ride_ids)
    assert len(notices) == (2 if same_group else 1)
    assert len({note.notification_id for note in notices}) == 1


def test_rollback_removes_events_and_enqueue_replay_preserves_identity(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    with Session(engine) as session:
        ride = _ride(driver["id"], now + timedelta(days=1))
        session.add(ride)
        events = dispatch.enqueue_route_watch(session, ride, source_key="rollback")
        assert len(events) == 1
        assert dispatch.enqueue_route_watch(session, ride, source_key="rollback") == []
        assert dispatch.enqueue_route_watch(session, ride, source_key="other-key-for-same-version") == []
        ride_id = ride.id
        session.rollback()
    with Session(engine) as session:
        assert session.get(Ride, ride_id) is None
        assert session.get(RouteWatchEvent, events[0]) is None


def test_notice_survives_inbox_deletion_and_suppresses_a_new_version(client, user_factory):
    driver = user_factory(role=UserRole.driver)
    now = utcnow()
    watch_id = _watch(user_factory()["id"], driver["id"], now)
    events, ride_ids = _group(driver["id"], now)
    checked_at = now + timedelta(seconds=1)
    assert len(dispatch.dispatch_event(engine, events[0], now=checked_at)) == 1
    note = _notices(watch_id, ride_ids)[0]
    with Session(engine) as session:
        session.delete(session.get(Notification, note.notification_id))
        ride = session.get(Ride, ride_ids[0])
        ride.terms_version += 1
        version_event, = dispatch.enqueue_route_watch(session, ride, source_key="new-test-version")
        session.commit()
    assert dispatch.dispatch_event(engine, version_event, now=checked_at + dispatch.NOTICE_INTERVAL) == []
    final = _notices(watch_id, ride_ids)
    assert [(row.id, row.notification_id) for row in final] == [(note.id, None)]


def test_quiet_hours_and_late_block_suppress_push_after_notice_commit(client, user_factory, monkeypatch):
    driver = user_factory(role=UserRole.driver)
    watcher = user_factory()
    now = utcnow()
    watch_id = _watch(watcher["id"], driver["id"], now)
    events, ride_ids = _group(driver["id"], now)
    result = [note for note in dispatch.dispatch_event(engine, events[0], now=now + timedelta(seconds=1))
              if note.watch_id == watch_id]
    assert len(result) == 1
    assert len(_notices(watch_id, ride_ids)) == 1
    monkeypatch.setattr(services, "_is_quiet_hour", lambda *args: True)
    assert list(dispatch.push_items(engine, result)) == []
    with Session(engine) as session:
        assert session.get(RouteWatch, watch_id).last_notified_at is not None
        user = session.get(User, watcher["id"])
        user.language = "ba"
        session.add(user)
        session.commit()
    monkeypatch.setattr(services, "_is_quiet_hour", lambda *args: False)
    assert next(dispatch.push_items(engine, result))[1] == "Сәфәр барлыҡҡа килде"
    with Session(engine) as session:
        session.add(Block(user_id=driver["id"], blocked_user_id=watcher["id"]))
        session.commit()
    assert list(dispatch.push_items(engine, result)) == []
    assert len(_notices(watch_id, ride_ids)) == 1


@pytest.mark.parametrize("immediate", [True, False])
def test_late_block_between_pushes_is_respected(client, user_factory, monkeypatch, immediate):
    driver = user_factory(role=UserRole.driver)
    first, second = user_factory(), user_factory()
    now = utcnow()
    _watch(first["id"], driver["id"], now)
    second_watch = _watch(second["id"], driver["id"], now)
    events, ride_ids = _group(driver["id"], now)
    pushed = []

    def send(session, user_id, title, body, data, **kwargs):
        if data["id"] != str(ride_ids[0]):
            return
        pushed.append(user_id)
        if user_id == first["id"]:
            with Session(engine) as change:
                change.add(Block(user_id=driver["id"], blocked_user_id=second["id"]))
                change.commit()

    monkeypatch.setattr(services, "send_push", send)
    if immediate:
        class InlineThread:
            def __init__(self, *, target, daemon):
                self.target = target

            def start(self):
                self.target()

        monkeypatch.setattr(services, "threading", SimpleNamespace(Thread=InlineThread))
        with Session(engine) as session:
            assert services.notify_route_watchers(session, events[0]) == 2
    else:
        route_watch_worker.run_due(engine, now=now + timedelta(seconds=1))
    assert pushed == [first["id"]]
    assert len(_notices(second_watch, ride_ids)) == 1


@pytest.mark.parametrize("block_during_web", [True, False])
def test_each_browser_and_fcm_send_rechecks_route_visibility(client, user_factory, monkeypatch, block_during_web):
    driver = user_factory(role=UserRole.driver)
    watcher = user_factory()
    now = utcnow()
    watch_id = _watch(watcher["id"], driver["id"], now)
    for browser in range(2):
        with Session(engine) as session:
            pair = issue_tokens(session, watcher["id"])
            session.commit()
        response = client.post(
            "/push/web/subscribe", headers={"Authorization": "Bearer " + pair["access_token"]},
            json={"endpoint": f"https://push.example/route-{watch_id}-{browser}", "session_bound": True,
                  "keys": {"p256dh": "synthetic", "auth": "synthetic"}},
        )
        assert response.status_code == 200, response.text
    own_token = f"synthetic-route-watch-{watch_id}"
    with Session(engine) as session:
        session.add(DeviceToken(user_id=watcher["id"], token=own_token))
        session.commit()
    _, ride_ids = _group(driver["id"], now)
    monkeypatch.setattr(services.settings, "vapid_public_key", "local-test")
    monkeypatch.setattr(services.settings, "vapid_private_key", "local-test")
    monkeypatch.setattr(services.settings, "firebase_credentials", "local-test")
    monkeypatch.setattr(services, "_fcm_app", object())
    web, fcm = [], []

    def send_web(**kwargs):
        payload = json.loads(kwargs["data"])
        if payload["url"] != f"/rides/{ride_ids[0]}":
            return
        web.append(payload)
        if block_during_web and len(web) == 1:
            with Session(engine) as session:
                session.add(Block(user_id=driver["id"], blocked_user_id=watcher["id"]))
                session.commit()

    def send_fcm(messages):
        fcm.extend(message.data for message in messages if message.token == own_token)
        return SimpleNamespace(responses=[SimpleNamespace(success=True, exception=None) for _ in messages])

    monkeypatch.setattr(pywebpush, "webpush", send_web)
    monkeypatch.setattr(messaging, "send_each", send_fcm)
    route_watch_worker.run_due(engine, now=utcnow() + timedelta(seconds=1))
    assert len(web) == (1 if block_during_web else 2)
    assert len(fcm) == (0 if block_during_web else 1)
    assert len(_notices(watch_id, ride_ids)) == 1
