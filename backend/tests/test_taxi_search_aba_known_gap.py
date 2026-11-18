"""Executable boundary of the snapshot fix, pending a durable search generation.

These strict xfails record a known gap, not a passing safety guarantee.
"""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401


class KnownSearchABA(AssertionError):
    """Only the reproduced writer overwrite is an expected failure."""


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
@pytest.mark.xfail(strict=True, raises=KnownSearchABA, reason="Identical search snapshot ABA needs a durable generation; plan only")
def test_identical_search_restart_rejects_old_candidate_result(client, user_factory, monkeypatch, has_candidate):
    passenger = user_factory("IdenticalSearchPassenger")
    driver = user_factory("IdenticalSearchDriver", role=UserRole.driver)
    fixed = utcnow()
    monkeypatch.setattr(isv, "utcnow", lambda: fixed)
    oid = _order(passenger["id"], S.searching, searching_at=fixed, search_round=0)
    writer = isv.try_offer_next
    observed = {}
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)

    def restart_in_same_clock_tick(*args, **kwargs):
        with Session(engine) as newer:
            with monkeypatch.context() as pause:
                pause.setattr(isv, "try_offer_next", lambda session, order, notify=True: order)
                result = isv.start_matching(newer, newer.get(InstantOrder, oid))
                assert result.status == S.searching, "restart must remain paused in searching"
                observed["after"] = (result.searching_at, result.search_round, result.offer_token)
        return [driver["id"]] if has_candidate else []

    monkeypatch.setattr(isv, "candidates", restart_in_same_clock_tick)
    with Session(engine) as old:
        original = old.get(InstantOrder, oid)
        before = (original.searching_at, original.search_round, original.offer_token)
        result = writer(old, original)
        assert observed["after"] == before, "fixture must reproduce the indistinguishable snapshot"
        if result.status != S.searching:
            assert result.status == (S.offered if has_candidate else S.expired), "unexpected failure mode"
            raise KnownSearchABA(f"old lookup overwrote the new search: {result.status.value}")
