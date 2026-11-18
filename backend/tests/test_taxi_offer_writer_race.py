"""Diagnostic regression: matcher must not overwrite an intervening cancellation.

Isolated SQLite and mocked candidate lookup; no external services.
"""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_candidate_lookup_cancellation_is_not_overwritten(client, user_factory, monkeypatch, has_candidate):
    passenger = user_factory("WriterRacePassenger")
    driver = user_factory("WriterRaceDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching)
    pushes = []
    cleanups = []
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: cleanups.append(args))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: pushes.append("expired"))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: pushes.append(args[-1]))

    def candidates_after_cancel(*args, **kwargs):
        # Candidate lookup can block while the separate cancel request commits.
        with Session(engine) as other:
            cancelled = isv.cancel_order(other, oid, isv.Actor.passenger, passenger["id"], "changed_mind")
            assert cancelled.status == S.cancelled
        return [driver["id"]] if has_candidate else []

    monkeypatch.setattr(isv, "candidates", candidates_after_cancel)
    with Session(engine) as session:
        original = session.get(InstantOrder, oid)
        result = isv.try_offer_next(session, original, notify=True)
        assert result.status == S.cancelled
    with Session(engine) as observer:
        actual = observer.get(InstantOrder, oid)
        assert actual.status == S.cancelled, "matcher resurrected a cancelled order"
    assert not pushes, "cancelled order sent to a driver"
    assert len(cleanups) == 1, "only cancellation may clean tried-set"
    assert not isv._redis().sismember(isv._tried_key(oid), driver["id"])


@pytest.mark.parametrize("status", [S.cancelled, S.accepted, S.offered])
@pytest.mark.parametrize("exit_path", ["candidate", "empty", "no_redis", "limit"])
@pytest.mark.usefixtures("fake_redis")
def test_stale_search_writer_leaves_changed_status_and_effects_alone(
    client, user_factory, monkeypatch, status, exit_path,
):
    passenger = user_factory("StaleWriterPassenger")
    driver = user_factory("StaleWriterDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching)
    pushes, cleanups, tried = [], [], []
    redis = isv._redis()
    monkeypatch.setattr(redis, "sadd", lambda *args: tried.append(args))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: pushes.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: pushes.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: cleanups.append(args))
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]] if exit_path == "candidate" else [])
    with Session(engine) as session:
        stale = session.get(InstantOrder, oid)
        if exit_path == "limit":
            stale.search_round = isv.settings.instant_max_offers
            session.add(stale)
            session.commit()
            session.refresh(stale)
        with Session(engine) as other:
            current = other.get(InstantOrder, oid)
            current.status = status
            current.offer_token = "b" * 32
            current.current_offer_driver_id = driver["id"]
            other.add(current)
            other.commit()
        if exit_path == "no_redis":
            monkeypatch.setattr(isv, "_redis", lambda: None)
        result = isv.try_offer_next(session, stale)
        assert result.status == status
        assert result.offer_token == "b" * 32
        assert result.current_offer_driver_id == driver["id"]
    assert not pushes
    assert not cleanups
    assert not tried


@pytest.mark.usefixtures("fake_redis")
def test_winning_writer_records_tried_before_offer_commit(client, user_factory, monkeypatch):
    passenger = user_factory("WinningWriterPassenger")
    driver = user_factory("WinningWriterDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching)
    redis = isv._redis()
    original_sadd = redis.sadd
    seen = []

    def record_before_publication(*args):
        with Session(engine) as observer:
            seen.append(observer.get(InstantOrder, oid).status)
        return original_sadd(*args)

    monkeypatch.setattr(redis, "sadd", record_before_publication)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    with Session(engine) as session:
        result = isv.try_offer_next(session, session.get(InstantOrder, oid))
        assert result.status == S.offered
    assert seen == [S.searching], "tried must be recorded before offered becomes visible"
    assert redis.sismember(isv._tried_key(oid), driver["id"])
