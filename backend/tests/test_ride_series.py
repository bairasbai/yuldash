from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta, timezone
from threading import Barrier
from uuid import uuid4

import pytest
from sqlmodel import Session, select

from app import idempotency, route_watch_dispatch
from app.command_fingerprint import wire_fingerprint
from app.db import engine
from app.models import CommandReceipt, Ride, TextFlag, UserRole
from app.routers import rides
from app.route_watch_models import RouteWatchEvent
from app.timeutil import utcnow


@pytest.fixture
def series(user_factory, monkeypatch):
    driver = user_factory("Синтетический водитель серии", role=UserRole.driver)
    monkeypatch.setattr(rides, "_ride_coordinates", lambda *_: {})
    draft = {"from_city": "Исянгулово", "to_city": "Кумертау", "seats_total": 3, "price": 500}
    headers = {**driver["auth"], "Idempotency-Key": "cmd1_" + uuid4().hex}
    start = (utcnow() + timedelta(days=2)).replace(second=0, microsecond=0, tzinfo=timezone.utc)
    return driver, draft, headers, start


def _ride_ids(owner_id):
    with Session(engine) as session:
        return session.exec(select(Ride.id).where(Ride.driver_id == owner_id).order_by(Ride.depart_at)).all()


def _detail(response, status, code):
    assert response.status_code == status, response.text
    assert response.json()["detail"]["code"] == code


def test_series_normalizes_dates_and_saves_each_ride_with_its_event(client, series, monkeypatch):
    driver, draft, headers, start = series
    seen_moderation = []
    geo_calls = []

    def moderate(value, **kwargs):
        seen_moderation.append(value)
        return "warn" if value == "Синтетическая метка" else ""

    monkeypatch.setattr(rides, "moderate_text", moderate)
    monkeypatch.setattr(rides, "_ride_coordinates", lambda *pair: geo_calls.append(pair) or {})
    draft = {**draft, "depart_at": "ignored", "comment": "Синтетическая метка"}
    later = start + timedelta(days=1)
    another_zone = timezone(timedelta(hours=5))
    body = {"ride": draft, "departures": [
        later.isoformat(), start.astimezone(another_zone).isoformat(), start.isoformat(),
        (start - timedelta(days=3)).isoformat(),
    ]}
    result = client.post("/rides/series", json=body, headers=headers)
    assert result.status_code == 200, result.text
    answer = result.json()
    assert (answer["published"], answer["failed"], answer["unresolved"]) == (2, 1, 0)
    refused, first, second = answer["items"]
    assert refused["code"] == "time_passed"
    assert refused["ru"] and refused["ba"]
    assert not first["already"] and not second["already"]
    assert [first["ride_id"], second["ride_id"]] == _ride_ids(driver["id"])
    assert len(seen_moderation) == 3 and len(geo_calls) == 1
    base = headers["Idempotency-Key"]
    with Session(engine) as session:
        events = session.exec(select(RouteWatchEvent).where(
            RouteWatchEvent.ride_id.in_([first["ride_id"], second["ride_id"]]),
        ).order_by(RouteWatchEvent.ride_id)).all()
        assert len(events) == 2
        assert {e.group_key for e in events} == {route_watch_dispatch._key(driver["id"], "series", base)}
        for event, departure in zip(events, (start, later)):
            key = f"{base}-{departure:%Y%m%d%H%M}"
            assert event.source_key == route_watch_dispatch._key(
                driver["id"], idempotency.Scope.RIDE_CREATE.value, key, event.ride_id, event.terms_version,
            )
            receipt = session.exec(select(CommandReceipt).where(
                CommandReceipt.user_id == driver["id"], CommandReceipt.request_key == key,
            )).one()
            assert receipt.ref_id == event.ride_id
        flags = session.exec(select(TextFlag).where(TextFlag.user_id == driver["id"])).all()
        assert [(flag.place, flag.ref_id) for flag in flags] == [("ride_comment", first["ride_id"])]

    replay = client.post("/rides/series", json={**body, "departures": [start.isoformat()]}, headers=headers).json()
    assert replay["published"] == 1 and replay["items"][0]["already"]
    assert replay["items"][0]["ride_id"] == first["ride_id"]
    assert len(seen_moderation) == 3 and len(geo_calls) == 1


def test_missing_long_key_and_invalid_batch_are_rejected(client, series):
    driver, draft, headers, start = series
    body = {"ride": draft, "departures": [start.isoformat()]}
    _detail(client.post("/rides/series", json=body, headers=driver["auth"]), 422, "series_key_required")
    _detail(client.post("/rides/series", json=body, headers={**headers, "Idempotency-Key": "a" * 116}),
            422, "idempotency_key_invalid")
    _detail(client.post("/rides/series", json={**body, "ride": {**draft, "recurrence": "weekly"}}, headers=headers),
            400, "series_with_recurrence")
    _detail(client.post("/rides/series", json={**body, "departures": body["departures"] * 11}, headers=headers),
            400, "series_too_many")
    assert _ride_ids(driver["id"]) == []


def test_changed_saved_date_rejects_new_dates_before_any_write(client, series):
    driver, draft, headers, start = series
    body = {"ride": draft, "departures": [start.isoformat()]}
    result = client.post("/rides/series", json=body, headers=headers)
    assert result.status_code == 200, result.text
    ride_ids = _ride_ids(driver["id"])
    new_earlier = (start - timedelta(days=1)).isoformat()
    changed = {**body, "ride": {**draft, "price": 600}, "departures": [new_earlier, start.isoformat()]}
    _detail(client.post("/rides/series", json=changed, headers=headers), 409, "idempotency_key_reused")
    assert _ride_ids(driver["id"]) == ride_ids
    disjoint = {**changed, "departures": [new_earlier]}
    _detail(client.post("/rides/series", json=disjoint, headers=headers), 409, "idempotency_key_reused")
    assert _ride_ids(driver["id"]) == ride_ids


@pytest.mark.parametrize("after_commit", [False, True])
def test_lost_date_can_be_resolved_by_repeating_only_that_date(client, series, monkeypatch, after_commit):
    driver, draft, headers, start = series
    later = start + timedelta(days=2)
    key = f"{headers['Idempotency-Key']}-{later:%Y%m%d%H%M}"
    executor = idempotency.execute
    interrupted = False

    def execute(session, owner, command, apply, **kwargs):
        nonlocal interrupted
        if command.key != key or interrupted:
            return executor(session, owner, command, apply, **kwargs)
        interrupted = True
        if after_commit:
            executor(session, owner, command, apply, **kwargs)
            raise ConnectionError("synthetic response loss")

        def staged_then_interrupted():
            apply()
            raise ConnectionError("synthetic interruption before commit")

        return executor(session, owner, command, staged_then_interrupted, **kwargs)

    monkeypatch.setattr(idempotency, "execute", execute)
    body = {"ride": draft, "departures": [start.isoformat(), later.isoformat()]}
    initial = client.post("/rides/series", json=body, headers=headers)
    assert initial.status_code == 200, initial.text
    first, second = initial.json()["items"]
    assert first["outcome"] == "applied"
    assert second["outcome"] == ("applied" if after_commit else "unknown")
    state = client.get(f"/command-intents/{key}", headers=driver["auth"]).json()
    assert state["state"] == ("applied" if after_commit else "unseen")
    repeated = client.post("/rides/series", json={**body, "departures": [later.isoformat()]}, headers=headers)
    assert repeated.status_code == 200, repeated.text
    item, = repeated.json()["items"]
    assert item["outcome"] == "applied" and item["already"] == after_commit
    assert _ride_ids(driver["id"]) == [first["ride_id"], item["ride_id"]]
    if after_commit:
        assert item["ride_id"] == second["ride_id"]
    with Session(engine) as session:
        events = session.exec(select(RouteWatchEvent).where(RouteWatchEvent.ride_id.in_(
            [first["ride_id"], item["ride_id"]],
        ))).all()
        assert len(events) == 2 and len({event.group_key for event in events}) == 1


def test_two_same_date_posts_publish_one_ride(client, series, monkeypatch):
    driver, draft, headers, start = series
    body = {"ride": draft, "departures": [start.isoformat()]}
    barrier = Barrier(2)
    executor = idempotency.execute

    def simultaneous(*args, **kwargs):
        barrier.wait(timeout=8)
        return executor(*args, **kwargs)

    monkeypatch.setattr(idempotency, "execute", simultaneous)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(client.post, "/rides/series", json=body, headers=headers) for _ in range(2)]
        results = [task.result(timeout=15) for task in pending]
    assert all(result.status_code == 200 for result in results), [result.text for result in results]
    outcomes = [result.json()["items"][0] for result in results]
    assert [item["outcome"] for item in outcomes] == ["applied", "applied"]
    assert sorted(item["already"] for item in outcomes) == [False, True]
    assert {item["ride_id"] for item in outcomes} == set(_ride_ids(driver["id"]))
    assert len(_ride_ids(driver["id"])) == 1


def test_expired_receipt_and_removed_ride_keep_the_original_result(client, series):
    driver, draft, headers, start = series
    key = f"{headers['Idempotency-Key']}-{start:%Y%m%d%H%M}"
    body = {"ride": draft, "departures": [start.isoformat()]}
    first = client.post("/rides/series", json=body, headers=headers)
    assert first.status_code == 200, first.text
    ride_id = first.json()["items"][0]["ride_id"]
    with Session(engine) as session:
        receipt = session.exec(select(CommandReceipt).where(CommandReceipt.request_key == key)).one()
        receipt.created_at -= timedelta(days=2)
        session.commit()
    result = client.post("/rides/series", json=body, headers=headers).json()["items"][0]
    assert result["already"] and result["ride_id"] == ride_id

    with Session(engine) as session:
        session.delete(session.get(Ride, ride_id))
        session.commit()
    result = client.post("/rides/series", json=body, headers=headers).json()["items"][0]
    assert result["outcome"] == "applied" and result["code"] == "idempotency_result_gone"
    assert result["ride_id"] == ride_id and _ride_ids(driver["id"]) == []


def test_close_races_publication_with_the_same_client_fingerprint(client, series, monkeypatch):
    driver, draft, headers, start = series
    stamp = start.isoformat(timespec="seconds").replace("+00:00", "Z")
    key = f"{headers['Idempotency-Key']}-{start:%Y%m%d%H%M}"
    body = {"ride": draft, "departures": [stamp]}
    scope = idempotency.Scope.RIDE_CREATE.value
    close_body = {"scope": scope, "target_id": None,
                  "payload_hash": wire_fingerprint(scope, None, {"ride": draft, "depart_at": stamp})}
    barrier = Barrier(2)
    lock = idempotency._lock

    def synchronized(session, owner_id, request_key):
        if request_key == key:
            barrier.wait(timeout=8)
        return lock(session, owner_id, request_key)

    monkeypatch.setattr(idempotency, "_lock", synchronized)
    with ThreadPoolExecutor(max_workers=2) as pool:
        publish = pool.submit(client.post, "/rides/series", json=body, headers=headers)
        close = pool.submit(client.post, f"/command-intents/{key}/close", json=close_body, headers=driver["auth"])
        published, closed = publish.result(timeout=15), close.result(timeout=15)
    assert published.status_code == closed.status_code == 200, (published.text, closed.text)
    result = published.json()["items"][0]
    if result["outcome"] == "applied":
        assert closed.json()["state"] == "applied"
        assert _ride_ids(driver["id"]) == [result["ride_id"]]
    else:
        assert result["outcome"] == "rejected" and result["code"] == "idempotency_closed_no_effect"
        assert closed.json()["state"] == "closed_no_effect"
        assert _ride_ids(driver["id"]) == []


def test_series_capacity_refusal_keeps_its_receipt_after_limit_changes(client, series, monkeypatch):
    driver, draft, headers, start = series
    monkeypatch.setattr(rides.settings, "flood_active_rides_max", 1)
    later = (start + timedelta(days=1)).isoformat()
    body = {"ride": draft, "departures": [start.isoformat(), later]}
    response = client.post("/rides/series", json=body, headers=headers)
    assert response.status_code == 200, response.text
    applied, rejected = response.json()["items"]
    assert applied["outcome"] == "applied"
    assert rejected["outcome"] == "rejected" and rejected["code"] == "too_many_rides"
    monkeypatch.setattr(rides.settings, "flood_active_rides_max", 5)
    replay = client.post("/rides/series", json={**body, "departures": [later]}, headers=headers)
    assert replay.json()["items"][0]["code"] == "too_many_rides"
    assert _ride_ids(driver["id"]) == [applied["ride_id"]]
