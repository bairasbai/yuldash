"""Diagnostic PASS means current recovery gaps are reproduced, NOT fixed.

The second case models process interruption after a real advance COMMIT and
before matcher entry. It is not a real process kill or network failure test.
No production continuation or guaranteed delivery is implemented here.
"""

from datetime import timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy import select, text
from sqlalchemy.pool import SingletonThreadPool, StaticPool
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order
from test_taxi_search_revision_postgres import _disposable_pg_before_client  # noqa: F401

RESUME = (
    "matching_resume_revision",
    "matching_resume_next_at",
    "matching_resume_attempts",
)


@pytest.fixture
def gap_orders(client):
    # conftest normally uses a per-run file SQLite DB. Never present one shared
    # DBAPI connection as independent commit visibility evidence.
    if isinstance(engine.pool, (StaticPool, SingletonThreadPool)):
        pytest.skip("diagnostic needs independent physical connections")
    if engine.dialect.name == "sqlite":
        assert engine.url.database and engine.url.database != ":memory:"
    ids = []

    def create(passenger, **kwargs):
        oid = _order(passenger["id"], S.offered, **kwargs)
        ids.append(oid)
        return oid

    yield create
    with engine.begin() as connection:
        for oid in ids:
            connection.execute(
                text("DELETE FROM instanttriedexclusion WHERE order_id=:id"),
                {"id": oid},
            )
            connection.execute(
                text("DELETE FROM instantorder WHERE id=:id"), {"id": oid}
            )


def _fresh_snapshot(oid, caller_connection=None):
    with engine.connect() as observer:
        if caller_connection is not None:
            assert (
                observer.connection.driver_connection
                is not caller_connection.connection.driver_connection
            )
        return dict(
            observer.execute(
                select(InstantOrder.__table__).where(InstantOrder.id == oid)
            )
            .mappings()
            .one()
        )


def test_known_gap_real_account_delete_leaves_offered_without_recipient_or_resume(
    client, user_factory, monkeypatch, gap_orders
):
    passenger = user_factory("ResumeGapPassenger")
    driver = user_factory("ResumeGapDeletedDriver", role=UserRole.driver)
    oid = gap_orders(
        passenger,
        current_offer_driver_id=driver["id"],
        offer_token="a" * 32,
        offered_at=utcnow(),
        offer_expires_at=utcnow() + timedelta(minutes=10),
        search_revision=20,
        search_round=2,
    )
    before = _fresh_snapshot(oid)
    external = []
    monkeypatch.setattr(
        "app.account._safe_unlink_media",
        lambda url: external.append(("unlink", url)),
    )

    def storage():
        external.append("storage")
        return SimpleNamespace(
            iter_owned=lambda *args: [],
            delete=lambda *args: external.append("unexpected media delete"),
        )

    monkeypatch.setattr("app.account.get_storage", storage)
    monkeypatch.setattr(
        isv, "presence_offline", lambda *args: external.append("presence")
    )
    unauthorized = client.post("/me/delete")
    assert unauthorized.status_code == 401
    assert _fresh_snapshot(oid) == before and external == []
    response = client.post("/me/delete", headers=driver["auth"])
    assert response.status_code == 200 and response.json() == {"ok": True}
    assert client.get("/me", headers=driver["auth"]).status_code == 401
    with engine.connect() as observer:
        assert (
            observer.execute(
                text('SELECT count(*) FROM "user" WHERE id=:id'), {"id": driver["id"]}
            ).scalar_one()
            == 0
        )
    after = _fresh_snapshot(oid)
    assert after["status"] == S.offered
    assert after["current_offer_driver_id"] is None
    assert after["offer_token"] == before["offer_token"] == "a" * 32
    assert after["offer_expires_at"] == before["offer_expires_at"]
    assert after["search_revision"] == 20 and after["search_round"] == 2
    assert tuple(after[name] for name in RESUME) == (None, None, None)
    # Actual User avatar defaults to ''; the four TaxiApplication media defaults
    # are None. Empty synthetic media never reaches real storage/network.
    assert external == [("unlink", "")] + [("unlink", None)] * 4 + [
        "storage",
        "presence",
    ]


class ModeledCrash(RuntimeError):
    """Test-only interruption at matcher entry, after actual advance COMMIT."""


def test_known_gap_actual_advance_commit_survives_interruption_without_resume_intent(
    user_factory, monkeypatch, gap_orders
):
    passenger = user_factory("ResumeAdvanceGapPassenger")
    driver = user_factory("ResumeAdvanceGapDriver", role=UserRole.driver)
    oid = gap_orders(
        passenger,
        current_offer_driver_id=driver["id"],
        offer_token="b" * 32,
        offered_at=utcnow(),
        offer_expires_at=utcnow() + timedelta(minutes=10),
        searching_at=utcnow() - timedelta(seconds=20),
        search_revision=20,
        search_round=2,
    )
    before = _fresh_snapshot(oid)
    observed = []
    # Keep the physical caller connection checked out across the real commit, so
    # independent readback cannot silently reuse that same DBAPI connection.
    with engine.connect() as caller_connection:
        with Session(bind=caller_connection) as caller:

            def interrupt_before_lookup(session, order, notify=True):
                assert session is caller and order.id == oid
                fresh = _fresh_snapshot(oid, caller_connection)
                assert fresh["status"] == S.searching
                assert fresh["search_revision"] == 21
                assert fresh["current_offer_driver_id"] is None
                assert fresh["offer_expires_at"] is None
                assert tuple(fresh[name] for name in RESUME) == (None, None, None)
                observed.append(fresh)
                raise ModeledCrash("before matcher lookup, after actual advance commit")

            # Sole service replacement: downstream entry throws before any lookup.
            # advance/CAS/commit/ownership checks are all the actual implementation.
            monkeypatch.setattr(isv, "try_offer_next", interrupt_before_lookup)
            with pytest.raises(ModeledCrash, match="before matcher lookup"):
                isv.advance_after_no_accept(caller, caller.get(InstantOrder, oid))
            caller.rollback()
            after = _fresh_snapshot(oid, caller_connection)
    assert len(observed) == 1 and after == observed[0]
    assert after["status"] == S.searching and after["search_revision"] == 21
    assert after["search_round"] == before["search_round"] == 2
    assert after["tried_lifecycle"] == before["tried_lifecycle"] is None
    assert after["searching_at"] == before["searching_at"]
    assert after["offer_token"] == before["offer_token"] == "b" * 32
    assert tuple(after[name] for name in RESUME) == (None, None, None)
    assert after["expired_at"] is None and after["driver_id"] is None
