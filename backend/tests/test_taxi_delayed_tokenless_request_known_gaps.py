"""Known compatibility gaps, NOT token enforcement or delayed-network proof.

A request prepared for offer A is sent only after a real decline/wait/retry has
produced B for the same driver. PASS documents observed legacy behavior. Accept
has no token input contract; an extra JSON body is not a supported token guard.
"""

from copy import deepcopy
from dataclasses import dataclass
from datetime import timedelta

import fakeredis
import pytest
from sqlalchemy import delete, event, select
from sqlalchemy.pool import SingletonThreadPool, StaticPool
from sqlmodel import Session

from app import instant_service as isv, services, taxi_worker
from app.db import engine
from app.models import (
    InstantOrder,
    InstantOrderStatus as S,
    InstantTriedExclusion,
    Notification,
    OfferDecline,
)
from app.timeutil import utcnow
from test_instant import ORIG, _heartbeat, _offered_order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401


@dataclass(frozen=True)
class PreparedRequest:
    path: str
    authorization: str
    body: tuple[tuple[str, str], ...] | None

    def send(self, client):
        kwargs = {} if self.body is None else {"json": dict(self.body)}
        return client.post(
            self.path, headers={"Authorization": self.authorization}, **kwargs
        )


def _read(oid):
    # Hold one connection while opening the observer: prove physical independence.
    assert not isinstance(engine.pool, (StaticPool, SingletonThreadPool))
    with engine.connect() as held, engine.connect() as observer:
        assert (
            held.connection.driver_connection
            is not observer.connection.driver_connection
        )
        return dict(
            observer.execute(
                select(InstantOrder.__table__).where(InstantOrder.id == oid)
            )
            .mappings()
            .one()
        )


@pytest.fixture
def replay(client, user_factory, monkeypatch):
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis_override", redis)
    transports = []

    def send_push(session, user_id, title, body, data=None, **kwargs):
        transports.append((user_id, title, body, deepcopy(data), deepcopy(kwargs)))

    monkeypatch.setattr(isv, "send_push", send_push)
    monkeypatch.setattr(services, "send_push", send_push)
    clock = [utcnow()]
    monkeypatch.setattr(isv, "utcnow", lambda: clock[0])
    monkeypatch.setattr(taxi_worker, "utcnow", lambda: clock[0])
    oid = None
    try:
        driver, passenger, payload = _offered_order(
            client, user_factory, redis, "ReplayDriver", "ReplayPassenger"
        )
        oid = payload["id"]
        a = _read(oid)
        assert a["status"] == S.offered and a["current_offer_driver_id"] == driver["id"]
        assert a["offer_token"] and a["offer_expires_at"] > clock[0]
        authorization = next(
            value
            for key, value in driver["auth"].items()
            if key.lower() == "authorization"
        )
        # All request representations are captured while A is still current.
        prepared = {
            "decline": PreparedRequest(
                f"/instant/orders/{oid}/decline", authorization, (("reason", "far"),)
            ),
            "old_decline": PreparedRequest(
                f"/instant/orders/{oid}/decline",
                authorization,
                (("expected_offer_token", a["offer_token"]), ("reason", "cheap")),
            ),
            "accept": PreparedRequest(
                f"/instant/orders/{oid}/accept", authorization, None
            ),
            "accept_ignored_body": PreparedRequest(
                f"/instant/orders/{oid}/accept",
                authorization,
                (("expected_offer_token", a["offer_token"]),),
            ),
        }
        assert redis.sismember(isv._tried_key(oid, a["tried_lifecycle"]), driver["id"])
        revoke = client.post(
            f"/instant/orders/{oid}/decline",
            headers=driver["auth"],
            json={"expected_offer_token": a["offer_token"]},
        )
        assert revoke.status_code == 200, revoke.text
        expired = _read(oid)
        assert (
            expired["status"] == S.expired
            and expired["current_offer_driver_id"] is None
        )
        assert not redis.exists(isv._tried_key(oid, a["tried_lifecycle"]))
        waiting = client.post(f"/instant/orders/{oid}/wait", headers=passenger["auth"])
        assert waiting.status_code == 200, waiting.text
        clock[0] += timedelta(minutes=isv.settings.order_retry_every_min, seconds=1)
        assert clock[0] < _read(oid)["wait_until"]
        _heartbeat(client, driver, ORIG)
        with Session(engine) as session:
            assert oid in taxi_worker.retry_waiting_orders(session)
        b = _read(oid)
        assert b["id"] == a["id"] and b["status"] == S.offered
        assert b["current_offer_driver_id"] == driver["id"]
        assert b["offer_token"] != a["offer_token"] and b["offer_token"]
        assert b["search_revision"] > a["search_revision"]
        assert b["offer_expires_at"] > clock[0]
        assert b["offered_at"] == clock[0]
        assert redis.sismember(isv._tried_key(oid, b["tried_lifecycle"]), driver["id"])
        yield oid, driver, a, b, prepared, redis, transports
    finally:
        if oid is not None:
            with engine.begin() as c:
                c.execute(delete(OfferDecline).where(OfferDecline.order_id == oid))
                c.execute(
                    delete(InstantTriedExclusion).where(
                        InstantTriedExclusion.order_id == oid
                    )
                )
                c.execute(
                    delete(Notification).where(
                        Notification.ref_kind == "instant", Notification.ref_id == oid
                    )
                )
                c.execute(delete(InstantOrder).where(InstantOrder.id == oid))
        redis.close()


def test_known_gap_tokenless_decline_acts_on_b_but_old_token_is_noop(client, replay):
    oid, driver, a, b, prepared, redis, transports = replay
    before_transports = deepcopy(transports)
    before_redis = {key: redis.dump(key) for key in redis.keys()}
    writes = []

    def observe_sql(connection, cursor, statement, parameters, context, executemany):
        if statement.lstrip().split(None, 1)[0].upper() in {
            "INSERT",
            "UPDATE",
            "DELETE",
        }:
            writes.append(statement)

    event.listen(engine, "before_cursor_execute", observe_sql)
    try:
        noop = prepared["old_decline"].send(client)
    finally:
        event.remove(engine, "before_cursor_execute", observe_sql)
    assert noop.status_code == 200 and noop.json()["status"] == "offered"
    assert _read(oid) == b
    assert writes == [] and transports == before_transports
    assert {key: redis.dump(key) for key in redis.keys()} == before_redis
    with engine.connect() as observer:
        assert (
            list(
                observer.execute(
                    select(OfferDecline.id).where(OfferDecline.order_id == oid)
                )
            )
            == []
        )
    response = prepared["decline"].send(client)
    assert response.status_code == 200, response.text
    after = _read(oid)
    assert after["status"] == S.expired
    assert after["search_revision"] > b["search_revision"]
    assert after["current_offer_driver_id"] is None
    with engine.connect() as observer:
        assert observer.execute(
            select(OfferDecline.reason).where(OfferDecline.order_id == oid)
        ).scalars().all() == ["far"]
    assert prepared["old_decline"].body[0][1] == a["offer_token"] != b["offer_token"]


@pytest.mark.parametrize("kind", ["accept", "accept_ignored_body"])
def test_known_gap_delayed_accept_binds_current_offer_not_prepared_generation(
    client, replay, kind
):
    oid, driver, a, b, prepared, _, _ = replay
    response = prepared[kind].send(client)
    assert response.status_code == 200, response.text
    after = _read(oid)
    assert after["status"] == S.accepted and after["driver_id"] == driver["id"]
    assert after["current_offer_driver_id"] is None
    assert after["offer_token"] == b["offer_token"] != a["offer_token"]
    assert after["search_revision"] == b["search_revision"]
    if kind == "accept_ignored_body":
        assert dict(prepared[kind].body) == {"expected_offer_token": a["offer_token"]}
    else:
        assert prepared[kind].body is None
