"""Real PostgreSQL witness for the inactive outstanding-offer retirement."""
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Event
from uuid import uuid4

import pytest
from sqlalchemy import create_engine, delete, event, select, text, update
from sqlalchemy.exc import OperationalError
from sqlmodel import Session
from test_taxi_offered_waypoint_preview import _STOP
from test_taxi_offered_waypoint_preview import route as synthetic_route  # noqa: F401 - pytest fixture
from test_taxi_offered_waypoint_retire import (
    _CHILD,
    _O,
    _T,
    _args,
    _children,
    _forbid_external,
    _row,
)
from test_taxi_route_resume_deadline_postgres import _clock, _until

from app import instant_service as isv
from app import taxi_route_edit_outcome as outcome
from app.db import engine
from app.models import InstantOrder, Tariff, User
from app.models import InstantOrderStatus as S
from app.taxi_offered_waypoint_retire import OfferedRetirement, retire_offered_waypoints

_USERS = User.__table__
pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql" or os.environ.get("AUDIT_PG_CI_SERVICE") != "1",
    reason="isolated PostgreSQL CI service required",
)


@pytest.fixture
def offered_pg(request):
    url = engine.url
    assert (url.drivername == "postgresql+psycopg2" and url.username == "yuldash"
            and url.host in ("localhost", "127.0.0.1", "::1") and url.port == 5432
            and url.database == "yuldash_run" and not url.query), "expected the disposable CI PostgreSQL DSN"
    with engine.connect() as conn:
        assert tuple(conn.execute(text("SELECT current_database(), current_user")).one()) == ("yuldash_run", "yuldash")
    request.getfixturevalue("client")  # schema only after checking the disposable target
    writer = create_engine(url, hide_parameters=True)
    reader = create_engine(url, hide_parameters=True)
    try:
        fixture_id = uuid4()
        seed = fixture_id.int % (10**7 - 2)
        category = f"pg-fixture-{fixture_id.hex}"
        with reader.connect() as conn, Session(bind=conn) as s:
            at = _clock(conn)
            owner = User(phone=f"+7999{seed:07d}")
            recipient = User(phone=f"+7999{seed + 1:07d}")
            tariff = Tariff(zone="city", category=category, base=80, per_km=10, per_min=4)
            s.add_all((owner, recipient, tariff))
            s.flush()
            uid, recipient_id, tariff_id = owner.id, recipient.id, tariff.id
            order = InstantOrder(
                passenger_id=uid, current_offer_driver_id=recipient_id, tariff_id=tariff_id, category=category,
                status=S.offered, search_round=1, created_at=at - timedelta(seconds=3),
                searching_at=at - timedelta(seconds=2), offered_at=at - timedelta(seconds=1),
                offer_expires_at=at + timedelta(seconds=60),
                from_lat=54.7, from_lng=55.8, from_text="SYNTHETIC A",
                to_lat=54.71, to_lng=55.81, to_text="SYNTHETIC B",
                ride_price=240, ride_base_price=210, price_estimate=280,
                pickup_fee_kop=4000, promo_discount_kop=10000, distance_km=8, eta_min=11,
            )
            s.add(order)
            s.flush()
            oid = order.id
            s.commit()
            conn.commit()
        try:
            # Lifespan has already seeded default tariffs. Prove the exact
            # canonical selector chooses our sole matching active row, not a
            # seeded standard tariff or a fallback.
            with Session(reader) as s:
                matching = s.execute(select(Tariff.id).where(
                    Tariff.zone == "city", Tariff.category == category, Tariff.active.is_(True),
                )).scalars().all()
                selected = isv.active_tariff(s, "city", category)
                assert matching == [tariff_id]
                assert selected is not None and (selected.id, selected.category) == (tariff_id, category)
            yield writer, reader, (reader, uid, oid, recipient_id, None, tariff_id, at)
        finally:
            with reader.begin() as conn:
                conn.execute(delete(_CHILD).where(_CHILD.c.order_id == oid))
                conn.execute(delete(_O).where(_O.c.id == oid))
                conn.execute(delete(_T).where(_T.c.id == tariff_id))
                conn.execute(delete(_USERS).where(_USERS.c.id.in_((uid, recipient_id))))
    finally:
        reader.dispose()
        writer.dispose()


def test_pg_retire_shares_one_clock_and_publishes_one_receipt(offered_pg, request, monkeypatch):
    route = request.getfixturevalue("synthetic_route")
    writer, reader, data = offered_pg
    args = _args(data)
    assert len(route) == 2  # exact real price function called with synthetic routing
    _forbid_external(monkeypatch)
    _, owner, oid, previous_driver, *_ = data
    prior = _row(reader, oid)
    with reader.connect() as conn:
        before = _clock(conn)
    assert retire_offered_waypoints(writer, **args) is OfferedRetirement.COMMITTED
    with reader.connect() as conn:
        after = _clock(conn)
    row = _row(reader, oid)
    assert row.status is S.searching and row.driver_id is None and row.accepted_at is None
    assert row.current_offer_driver_id is None and row.offer_expires_at is None
    assert row.route_resume_previous_driver_id == previous_driver
    assert row.route_resume_id and row.route_resume_claim_id is None
    assert (row.tariff_id, row.searching_at, row.offered_at, row.search_round) == (
        prior.tariff_id, prior.searching_at, prior.offered_at, prior.search_round,
    )
    assert before <= row.route_resume_retired_at <= after
    assert row.route_resume_retired_at == row.route_resume_ready_at == row.destination_changed_at
    assert row.route_resume_deadline_at - row.route_resume_retired_at == timedelta(seconds=120)
    assert (row.ride_price, row.ride_base_price, row.price_estimate, row.pickup_fee_kop) == (170, 170, 210, 4000)
    assert row.promo_discount_kop == args["preview"].promo_discount_kop
    assert isv.parse_waypoints(row.waypoints_json) == [_STOP | {"done": False}]
    receipts = _children(reader, oid)
    assert len(receipts) == 1 and receipts[0]["expires_at"] == args["receipt_expires_at"]
    assert outcome.committed_evidence(
        reader, owner_id=owner, order_id=oid, attempt_id=args["attempt_id"],
        binding=args["agreement"].binding, now=after,
    ) is outcome.CommittedEvidence.COMMITTED
    assert retire_offered_waypoints(writer, **args) is OfferedRetirement.UNKNOWN
    assert (_row(reader, oid), _children(reader, oid)) == (row, receipts)


@pytest.mark.parametrize("case", ("order-lease", "tariff-quote", "tariff-changed"))
def test_pg_lock_before_deadline_or_tariff_recheck(offered_pg, request, case):
    route = request.getfixturevalue("synthetic_route")
    writer, reader, data = offered_pg
    _, _, oid, _, _, tariff_id, _ = data
    if case == "order-lease":
        with reader.begin() as conn:
            conn.execute(update(_O).where(_O.c.id == oid).values(offer_expires_at=_clock(conn) + timedelta(seconds=7)))
    args = _args(data, duration=7 if case == "tariff-quote" else None)
    assert len(route) == 2
    prior = _row(reader, oid)
    cutoff = args["agreement"].valid_until
    assert (cutoff == prior.offer_expires_at) is (case != "tariff-quote")
    table, pk, select_name, clause = (
        (_O, oid, "instantorder", "FOR UPDATE") if case == "order-lease" else (_T, tariff_id, "tariff", "FOR SHARE")
    )
    attempted = Event()
    pid = {}

    def at_lock(conn, cursor, statement, parameters, context, executemany):
        if f"FROM {select_name}" in statement and clause in statement:
            pid["writer"] = conn.connection.driver_connection.get_backend_pid()
            attempted.set()

    with ThreadPoolExecutor(max_workers=1) as pool, reader.connect() as blocker:
        blocker.execute(select(table.c.id).where(table.c.id == pk).with_for_update()).one()
        blocker_pid = blocker.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        event.listen(writer, "before_cursor_execute", at_lock)
        try:
            future = pool.submit(retire_offered_waypoints, writer, **args)
            try:
                assert attempted.wait(5), "expected SELECT row lock not attempted"
                with reader.connect() as observer:
                    observer_pid = observer.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
                    assert len({blocker_pid, pid["writer"], observer_pid}) == 3

                    def blocked():
                        assert not future.done(), "retirement did not wait on the real row lock"
                        return observer.execute(text("SELECT :owner = ANY(pg_blocking_pids(:writer))"),
                                                {"owner": blocker_pid, "writer": pid["writer"]}).scalar_one()

                    _until(blocked, "PostgreSQL never reported the expected lock wait")
                    assert _clock(observer) < cutoff
                    if case.startswith("tariff-"):
                        # SHARE waiter must already hold the order's FOR UPDATE.
                        with reader.connect() as probe:
                            with pytest.raises(OperationalError) as busy:
                                probe.execute(select(_O.c.id).where(_O.c.id == oid).with_for_update(nowait=True)).one()
                            assert busy.value.orig.pgcode == "55P03"  # lock_not_available
                    if case == "tariff-changed":
                        blocker.execute(update(_T).where(_T.c.id == tariff_id).values(per_min=64))
                        blocker.commit()
                    else:
                        _until(lambda: _clock(observer) >= cutoff, "database clock never reached cutoff")
                        assert blocked()
            finally:
                blocker.rollback()
            assert future.result(timeout=8) is OfferedRetirement.UNKNOWN
        finally:
            event.remove(writer, "before_cursor_execute", at_lock)
    assert _row(reader, oid) == prior
    assert _children(reader, oid) == []
    if case == "tariff-changed":
        with reader.connect() as conn:
            assert conn.scalar(select(_T.c.per_min).where(_T.c.id == tariff_id)) == 64
