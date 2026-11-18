"""A candidate lookup from an earlier search must not finish a restarted search."""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_old_candidate_result_does_not_finish_restarted_search(client, user_factory, monkeypatch, has_candidate):
    passenger = user_factory("RestartSnapshotPassenger")
    driver = user_factory("RestartSnapshotDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, searching_at=utcnow() - timedelta(minutes=1), search_round=2)
    pushes, cleanups = [], []
    monkeypatch.setattr(isv, "_push_offer", lambda *args: pushes.append("offered"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: pushes.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: cleanups.append(args))
    writer = isv.try_offer_next

    def restarted_while_lookup_waited(*args, **kwargs):
        with Session(engine) as newer:
            # Real start_matching transition; pause its next writer after the commit.
            with monkeypatch.context() as pause:
                pause.setattr(isv, "try_offer_next", lambda session, order, notify=True: order)
                restarted = isv.start_matching(newer, newer.get(InstantOrder, oid))
                assert restarted.status == S.searching and restarted.search_round == 0
        return [driver["id"]] if has_candidate else []

    monkeypatch.setattr(isv, "candidates", restarted_while_lookup_waited)
    with Session(engine) as old:
        result = writer(old, old.get(InstantOrder, oid))
        assert result.status == S.searching, "old lookup finished a newer search"
        assert result.search_round == 0
    assert not pushes
    assert not cleanups
    assert not isv._redis().sismember(isv._tried_key(oid), driver["id"])


@pytest.mark.parametrize("field", ["searching_at", "search_round", "offer_token"])
@pytest.mark.parametrize("exit_path", ["candidate", "empty", "no_redis", "limit"])
@pytest.mark.usefixtures("fake_redis")
def test_each_snapshot_field_is_checked_on_every_writer_exit(client, user_factory, monkeypatch, field, exit_path):
    passenger = user_factory("FieldSnapshotPassenger")
    driver = user_factory("FieldSnapshotDriver", role=UserRole.driver)
    initial_round = isv.settings.instant_max_offers if exit_path == "limit" else 0
    oid = _order(passenger["id"], S.searching, search_round=initial_round)
    redis = isv._redis()
    effects = []
    monkeypatch.setattr(isv, "_remember_tried", lambda *args: effects.append("tried"))
    monkeypatch.setattr(redis, "pipeline", lambda *args, **kwargs: effects.append("pipeline"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    expected = {}

    def change_after_snapshot(*args):
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            setattr(current, field, {"searching_at": utcnow(), "search_round": initial_round + 1,
                                     "offer_token": "b" * 32}[field])
            newer.add(current)
            newer.commit()
            expected["tuple"] = (current.searching_at, current.search_round, current.offer_token)
        return None

    monkeypatch.setattr(isv, "cancel_for_suspended_passenger", change_after_snapshot)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]] if exit_path == "candidate" else [])
    if exit_path == "no_redis":
        monkeypatch.setattr(isv, "_redis", lambda: None)
    with Session(engine) as old:
        result = isv.try_offer_next(old, old.get(InstantOrder, oid))
        assert result.status == S.searching
        assert (result.searching_at, result.search_round, result.offer_token) == expected["tuple"]
    assert not effects


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_old_lookup_does_not_overwrite_offered_then_declined_generation(client, user_factory, monkeypatch, has_candidate):
    passenger = user_factory("CycleSnapshotPassenger")
    first_driver = user_factory("CycleSnapshotFirst", role=UserRole.driver)
    next_driver = user_factory("CycleSnapshotNext", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching)
    writer = isv.try_offer_next
    effects, expected = [], {}
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))

    def intervening_offer_and_decline(*args, **kwargs):
        with Session(engine) as newer:
            with monkeypatch.context() as offer:
                offer.setattr(isv, "candidates", lambda *args, **kwargs: [next_driver["id"]])
                offered = writer(newer, newer.get(InstantOrder, oid))
                assert offered.status == S.offered
            with monkeypatch.context() as pause:
                pause.setattr(isv, "try_offer_next", lambda session, order, notify=True: order)
                searching = isv.decline_offer(newer, oid, next_driver["id"], expected_offer_token=offered.offer_token)
                assert searching.status == S.searching
                expected["tuple"] = (searching.searching_at, searching.search_round, searching.offer_token)
        effects.clear()  # Only the delayed old writer is forbidden to emit effects.
        return [first_driver["id"]] if has_candidate else []

    monkeypatch.setattr(isv, "candidates", intervening_offer_and_decline)
    with Session(engine) as old:
        result = writer(old, old.get(InstantOrder, oid))
        assert result.status == S.searching
        assert (result.searching_at, result.search_round, result.offer_token) == expected["tuple"]
    assert not effects
    assert not isv._redis().sismember(isv._tried_key(oid), first_driver["id"])
    assert isv._redis().sismember(isv._tried_key(oid), next_driver["id"])
