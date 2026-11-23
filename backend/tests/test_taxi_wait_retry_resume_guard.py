"""Ordinary retry owns its SQL entry/outcome; marked/newer work is not its queue."""
from datetime import timedelta
import os
from uuid import uuid4

import fakeredis
import pytest
from sqlalchemy import create_engine, event, select, text, update
from sqlalchemy.pool import NullPool
from sqlalchemy.schema import CreateSchema, DropSchema
from sqlmodel import Session, SQLModel

from app import instant_service as isv, taxi_worker as worker
from app.db import engine as app_engine
from app.models import InstantOrder, InstantOrderStatus as S, User
from app.timeutil import utcnow
from test_taxi_wait_route_resume_guard import _marker


_O = InstantOrder.__table__


@pytest.fixture
def queue_db(tmp_path):
    """Each witness owns a real database namespace and its independent connections."""
    if app_engine.dialect.name == "sqlite":
        db = create_engine(f"sqlite:///{tmp_path / 'isolated-wait-retry.sqlite3'}",
                           poolclass=NullPool, hide_parameters=True)
        event.listen(db, "connect", lambda raw, _: raw.execute("PRAGMA foreign_keys=ON").close())
        try:
            SQLModel.metadata.create_all(db)
            yield db
        finally:
            db.dispose()
        return

    # The non-interleaving controls still exercise real PG in the known CI
    # service, never a configured production/unknown DSN or SQLite fallback.
    url = app_engine.url
    if not (os.environ.get("AUDIT_PG_CI_SERVICE") == "1"
            and url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
            and url.database == "yuldash_run" and not url.query):
        pytest.skip("isolated PostgreSQL CI service required")
    db = create_engine(url, poolclass=NullPool, hide_parameters=True)
    schema = f"tf04_wait_retry_{uuid4().hex}"
    created = False
    try:
        with db.begin() as conn:
            assert conn.execute(text("SELECT current_database(), current_user")).one() == ("yuldash_run", "yuldash")
            conn.execute(CreateSchema(schema))
        created = True
        scoped = db.execution_options(schema_translate_map={None: schema})
        SQLModel.metadata.create_all(scoped)
        yield scoped
    finally:
        try:
            if created:
                with db.begin() as conn:
                    conn.execute(DropSchema(schema, cascade=True))
        finally:
            db.dispose()


@pytest.fixture
def user_factory(queue_db):
    def make(name):
        with Session(queue_db) as s:
            row = User(name=name, phone=f"wait-retry-{uuid4().hex}", verified=True)
            s.add(row)
            s.flush()
            uid = row.id
            s.commit()
        return {"id": uid}
    return make


def _run(db, dry=False):
    with Session(db) as s:
        return worker.retry_waiting_orders(s, dry_run=dry)


def _seed_wait(db, owner, **kw):
    now = utcnow()
    with Session(db) as s:
        row = InstantOrder(passenger_id=owner, status=S.expired, wait_until=now+timedelta(minutes=15),
                           created_at=now-timedelta(minutes=2), searching_at=now-timedelta(minutes=1),
                           from_text="SYNTHETIC START", to_text="SYNTHETIC END", **kw)
        s.add(row)
        s.flush()
        oid = row.id
        s.commit()
    return oid


def _snapshot(db, oid):
    with db.connect() as conn:
        return conn.execute(select(_O).where(_O.c.id == oid)).one()


@pytest.fixture
def effects(monkeypatch):
    monkeypatch.setattr(worker.settings, "order_retry_every_min", 0)
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    monkeypatch.setattr(isv, "_redis", lambda: redis)
    sent = {"passenger": [], "driver": [], "tried": [], "cleanup": []}
    monkeypatch.setattr(worker, "push_notification", lambda *a, **kw: sent["passenger"].append(kw["ref_id"]))
    monkeypatch.setattr(isv, "_push_offer", lambda s, o, d: sent["driver"].append((o.id, d)))
    monkeypatch.setattr(isv, "_remember_offered_driver", lambda r, o, d: sent["tried"].append((o, d)))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda o: sent["cleanup"].append(o))
    return sent


@pytest.mark.parametrize("partial", (False, True))
def test_marked_ordinary_wait_is_not_selected_or_counted(queue_db, user_factory, effects, monkeypatch, partial):
    owner, prior = (user_factory(label) for label in ("RetryMarkedPassenger", "RetryMarkedPrior"))
    oid = _seed_wait(queue_db, owner["id"], **({"route_resume_claim_id": ""} if partial else _marker(prior["id"])))
    before = _snapshot(queue_db, oid)
    counter = []
    monkeypatch.setattr(isv, "candidates", lambda *a, **kw: counter.append(1) or [])
    assert oid not in _run(queue_db, dry=True)
    assert oid not in _run(queue_db)
    assert counter == [] and _snapshot(queue_db, oid) == before and not any(effects.values())


@pytest.mark.skipif(app_engine.dialect.name != "sqlite", reason="real separate SQLite writer at the retry entry")
@pytest.mark.parametrize("rival", ("marker", "renewed", "new_round", "accepted", "new_owner"))
def test_actual_rival_after_selection_before_retry_entry_preserved(queue_db, user_factory, effects, monkeypatch, rival):
    owner, driver = (user_factory(label) for label in ("RetryRaceOwner", "RetryRaceOther"))
    oid = _seed_wait(queue_db, owner["id"])
    entered, looked = [], []
    changes = {
        "marker": _marker(driver["id"]),
        "renewed": dict(wait_until=utcnow()+timedelta(minutes=45)),
        "new_round": dict(search_round=3, searching_at=utcnow()),
        "accepted": dict(status=S.accepted, driver_id=driver["id"], accepted_at=utcnow()),
        "new_owner": dict(passenger_id=driver["id"]),
    }[rival]
    monkeypatch.setattr(isv, "candidates", lambda *a, **kw: looked.append(1) or [driver["id"]])

    def hook(conn, cursor, statement, parameters, context, many):
        sql = statement.strip().upper().split(" WHERE ", 1)[0]
        if sql.startswith("UPDATE INSTANTORDER SET") and "SEARCHING_AT" in sql and not entered:
            entered.append(True)
            with queue_db.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                other.execute(update(_O).where(_O.c.id == oid).values(**changes))

    event.listen(queue_db, "before_cursor_execute", hook)
    try:
        found = _run(queue_db)
    finally:
        event.remove(queue_db, "before_cursor_execute", hook)
    assert entered == [True] and oid not in found and looked == [] and not any(effects.values())
    row = _snapshot(queue_db, oid)
    assert row.retry_count == 0 and all(getattr(row, k) == v for k, v in changes.items())


@pytest.mark.skipif(app_engine.dialect.name != "sqlite", reason="real independent marker/renewal during GEO")
@pytest.mark.parametrize("rival, driver_found", (("marker", True), ("marker", False),
                                                  ("renewed", True), ("third_party_offer", True)))
def test_actual_rival_during_geo_cannot_publish_or_notify_for_other_episode(
    queue_db, user_factory, effects, monkeypatch, rival, driver_found,
):
    owner, driver = (user_factory(label) for label in ("RetryGeoOwner", "RetryGeoOther"))
    oid = _seed_wait(queue_db, owner["id"])
    changes = {
        "marker": _marker(driver["id"]),
        "renewed": dict(wait_until=utcnow()+timedelta(minutes=45)),
        "third_party_offer": dict(status=S.offered, current_offer_driver_id=driver["id"],
                                  offered_at=utcnow(), offer_expires_at=utcnow()+timedelta(minutes=2)),
    }[rival]
    seen = []

    def geo(redis, session, order, exclude):
        assert order.id == oid
        with queue_db.begin() as other:
            assert other.connection.driver_connection is not session.connection().connection.driver_connection
            other.execute(update(_O).where(_O.c.id == oid).values(**changes))
        seen.append(True)
        return [driver["id"]] if driver_found else []

    monkeypatch.setattr(isv, "candidates", geo)
    assert oid not in _run(queue_db)
    assert seen == [True] and not any(effects.values())
    row = _snapshot(queue_db, oid)
    assert all(getattr(row, k) == v for k, v in changes.items())
    assert row.retry_count == 1  # real owned entry, no id-only late update


def test_ordinary_dry_throttled_and_eligible_offer_once(queue_db, user_factory, effects, monkeypatch):
    owner, driver = (user_factory(label) for label in ("RetryNormallyOwner", "RetryNormallyDriver"))
    oid = _seed_wait(queue_db, owner["id"])
    monkeypatch.setattr(worker.settings, "order_retry_every_min", 90)
    monkeypatch.setattr(isv, "candidates", lambda *a, **kw: [driver["id"]])
    assert oid not in _run(queue_db, dry=True) and not any(effects.values())
    monkeypatch.setattr(worker.settings, "order_retry_every_min", 0)
    assert oid in _run(queue_db, dry=True) and not any(effects.values()) and _snapshot(queue_db, oid).retry_count == 0
    assert oid in _run(queue_db)
    assert oid not in _run(queue_db)
    row = _snapshot(queue_db, oid)
    assert row.status is S.offered and row.current_offer_driver_id == driver["id"]
    assert row.search_round == 1 and row.retry_count == 1
    assert effects == {"driver": [(oid, driver["id"])], "passenger": [oid],
                       "tried": [(oid, driver["id"])], "cleanup": []}


@pytest.mark.skipif(app_engine.dialect.name != "sqlite", reason="separate SQL right before suspension cancel write")
def test_queue_pause_cancel_loses_on_marker_without_notices_or_release(queue_db, user_factory, effects, monkeypatch):
    owner = user_factory("RetryPauseOwner")
    oid = _seed_wait(queue_db, owner["id"], promo_discount_kop=5000)
    entered, other_effects = [], []
    monkeypatch.setattr("app.safety_logic.account_paused", lambda *a, **kw: True)
    monkeypatch.setattr("app.services.push_notification", lambda *a, **kw: other_effects.append("pause"))
    monkeypatch.setattr(isv, "_notify_cancel", lambda *a, **kw: other_effects.append("cancel"))
    monkeypatch.setattr("app.promo_ride.release", lambda *a, **kw: other_effects.append("promo"))
    monkeypatch.setattr(isv, "candidates", lambda *a, **kw: pytest.fail("paused order reached GEO"))

    def hook(conn, cursor, statement, params, context, many):
        sql = statement.strip().upper().split(" WHERE ", 1)[0]
        if sql.startswith("UPDATE INSTANTORDER SET") and "CANCELLED_AT" in sql and not entered:
            entered.append(True)
            with queue_db.begin() as other:
                assert other.connection.driver_connection is not conn.connection.driver_connection
                other.execute(update(_O).where(_O.c.id == oid).values(route_resume_claim_id="partial"))

    event.listen(queue_db, "before_cursor_execute", hook)
    try:
        assert oid not in _run(queue_db)
    finally:
        event.remove(queue_db, "before_cursor_execute", hook)
    saved = _snapshot(queue_db, oid)
    assert entered == [True] and other_effects == [] and not any(effects.values())
    assert saved.route_resume_claim_id == "partial" and saved.status is S.searching
    assert saved.retry_count == 1 and saved.promo_discount_kop == 5000


@pytest.mark.skipif(app_engine.dialect.name != "sqlite", reason="actual independent update after commit")
def test_pax_notice_requires_same_committed_offer_after_driver_callback(queue_db, user_factory, effects, monkeypatch):
    owner, driver, newer = (user_factory(label) for label in ("RetryNoticeOwner", "RetryNoticeDriver", "RetryNoticeNewer"))
    oid = _seed_wait(queue_db, owner["id"])
    monkeypatch.setattr(isv, "candidates", lambda *a, **kw: [driver["id"]])
    driver_send = isv._push_offer

    def driver_then_supersede(s, o, d):
        driver_send(s, o, d)
        with queue_db.begin() as other:
            assert other.connection.driver_connection is not s.connection().connection.driver_connection
            other.execute(update(_O).where(_O.c.id == oid).values(current_offer_driver_id=newer["id"]))

    monkeypatch.setattr(isv, "_push_offer", driver_then_supersede)
    assert oid in _run(queue_db)  # an owned offer did commit; it was superseded before passenger notice
    saved = _snapshot(queue_db, oid)
    assert saved.current_offer_driver_id == newer["id"] and saved.retry_count == 1
    assert effects["driver"] == [(oid, driver["id"])] and effects["passenger"] == []
