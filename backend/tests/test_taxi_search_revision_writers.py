"""Durable search generations: writers, legacy rows and delayed lookup CAS.

SQLite interleavings prove conditions/commit ordering, not PostgreSQL row locks.
"""
import pytest
from fastapi import HTTPException
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_route_offer_ordering import _apply

MAX_REVISION = 2**63 - 1


@pytest.fixture(autouse=True)
def _cleanup_revision_orders(client):
    """Synthetic MAX/active rows must not become another worker test's input."""
    with Session(engine) as session:
        existing = set(session.exec(select(InstantOrder.id)).all())
    yield
    with Session(engine) as session:
        for order in session.exec(select(InstantOrder)).all():
            if order.id not in existing:
                session.delete(order)
        session.commit()


def _entry(session, order, entry):
    if entry == "start":
        return isv.start_matching(session, order)
    if entry == "advance":
        return isv.advance_after_no_accept(session, order)
    return isv._reassign_after_driver_cancel(session, order.id, None)


def _pause_matching(monkeypatch):
    seen = []
    def observe(session, order, *args, **kwargs):
        with Session(engine) as reader:
            committed = reader.get(InstantOrder, order.id)
            assert committed.status == S.searching
            assert committed.search_revision == order.search_revision
            seen.append(committed.search_revision)
        return order
    monkeypatch.setattr(isv, "try_offer_next", observe)
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: None)
    return seen


@pytest.mark.parametrize("entry,status", [("start", S.created), ("advance", S.offered), ("reassign", S.cancelled)])
@pytest.mark.parametrize("revision", [None, 2**40])
@pytest.mark.usefixtures("fake_redis")
def test_entry_rotates_revision_before_continuation(client, user_factory, monkeypatch, entry, status, revision):
    pax = user_factory("RevisionEntry")
    oid = _order(pax["id"], status, search_revision=revision, search_round=3)
    seen = _pause_matching(monkeypatch)
    with Session(engine) as session:
        result = _entry(session, session.get(InstantOrder, oid), entry)
        assert result.search_revision == (revision or 0) + 1
        assert result.search_round == (3 if entry == "advance" else 0)
    assert seen == [(revision or 0) + 1]


@pytest.mark.parametrize("entry,status", [("start", S.searching), ("advance", S.offered), ("reassign", S.cancelled)])
@pytest.mark.usefixtures("fake_redis")
def test_increment_uses_database_not_stale_orm(client, user_factory, monkeypatch, entry, status):
    pax = user_factory("RevisionStale")
    oid = _order(pax["id"], status, search_revision=5)
    seen = _pause_matching(monkeypatch)
    with Session(engine) as stale_session:
        stale = stale_session.get(InstantOrder, oid)
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.search_revision = 19
            newer.add(current)
            newer.commit()
        result = _entry(stale_session, stale, entry)
        assert result.search_revision == 20
    assert seen == [20]


@pytest.mark.parametrize("exit_path", ["candidate", "empty", "no_redis", "limit"])
@pytest.mark.usefixtures("fake_redis")
def test_revision_only_change_rejects_every_lookup_exit_without_effects(client, user_factory, monkeypatch, exit_path):
    pax = user_factory("RevisionCAS")
    driver = user_factory("RevisionCASDriver", role=UserRole.driver)
    rounds = isv.settings.instant_max_offers if exit_path == "limit" else 0
    oid = _order(pax["id"], S.searching, search_revision=8, search_round=rounds)
    effects = []
    redis = isv._redis()
    monkeypatch.setattr(redis, "sadd", lambda *args: effects.append("tried"))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    def change_revision(*args):
        with Session(engine) as newer:
            current = newer.get(InstantOrder, oid)
            current.search_revision = 9
            newer.add(current)
            newer.commit()
    monkeypatch.setattr(isv, "cancel_for_suspended_passenger", change_revision)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]] if exit_path == "candidate" else [])
    if exit_path == "no_redis":
        monkeypatch.setattr(isv, "_redis", lambda: None)
    with Session(engine) as session:
        result = isv.try_offer_next(session, session.get(InstantOrder, oid))
        assert result.status == S.searching
        assert result.search_revision == 9
        assert result.search_round == rounds
    assert effects == []


@pytest.mark.usefixtures("fake_redis")
def test_legacy_null_is_committed_before_lookup(client, user_factory, monkeypatch):
    pax = user_factory("RevisionLegacy")
    oid = _order(pax["id"], S.searching, search_revision=None)
    seen = []
    def candidates(*args, **kwargs):
        with Session(engine) as observer:
            current = observer.get(InstantOrder, oid)
            assert current.search_revision == 1
            seen.append(current.search_revision)
        return []
    monkeypatch.setattr(isv, "candidates", candidates)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    with Session(engine) as session:
        result = isv.try_offer_next(session, session.get(InstantOrder, oid))
        assert result.search_revision == 1
    assert seen == [1]


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_identical_clock_snapshot_restart_changes_revision(client, user_factory, monkeypatch, has_candidate):
    pax = user_factory("RevisionABA")
    driver = user_factory("RevisionABADriver", role=UserRole.driver)
    fixed = utcnow()
    monkeypatch.setattr(isv, "utcnow", lambda: fixed)
    oid = _order(pax["id"], S.searching, searching_at=fixed, search_round=0, search_revision=10)
    writer = isv.try_offer_next
    effects = []
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    def restart(*args, **kwargs):
        with Session(engine) as newer, monkeypatch.context() as pause:
            pause.setattr(isv, "try_offer_next", lambda session, order, *a, **k: order)
            current = isv.start_matching(newer, newer.get(InstantOrder, oid))
            assert current.search_revision == 11
            assert (current.searching_at, current.search_round, current.offer_token) == (fixed, 0, None)
        return [driver["id"]] if has_candidate else []
    monkeypatch.setattr(isv, "candidates", restart)
    with Session(engine) as session:
        result = writer(session, session.get(InstantOrder, oid))
        assert result.status == S.searching
        assert result.search_revision == 11
    assert not effects
    assert not isv._redis().sismember(isv._tried_key(oid), driver["id"])


@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.parametrize("status", [S.searching, S.offered])
@pytest.mark.usefixtures("fake_redis")
def test_route_criteria_rotate_and_commit_before_continuation(client, user_factory, monkeypatch, kind, status):
    pax = user_factory("RevisionRoute")
    oid = _order(pax["id"], status, search_revision=31, search_round=2)
    seen = _pause_matching(monkeypatch)
    with Session(engine) as session:
        result = _apply(session, session.get(InstantOrder, oid), kind)
        assert result.search_revision == 32
        assert result.search_round == 2
    assert seen == [32]


@pytest.mark.usefixtures("fake_redis")
def test_searching_fallback_without_price_drop_rotates_and_continues(client, user_factory, monkeypatch):
    pax = user_factory("RevisionFallback")
    oid = _order(pax["id"], S.searching, category="comfort", search_revision=40, ride_price=1, price_estimate=1)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    seen = _pause_matching(monkeypatch)
    with Session(engine) as session:
        isv.add_fallback_category(session, session.get(InstantOrder, oid), "standard")
        current = session.get(InstantOrder, oid)
        assert current.search_revision == 41
        assert current.ride_price == 1
        assert "standard" in isv.order_categories(current)
    assert seen == [41]


@pytest.mark.parametrize("entry,status", [("start", S.created), ("advance", S.offered), ("reassign", S.cancelled)])
@pytest.mark.usefixtures("fake_redis")
def test_overflow_is_fail_closed_without_continuation(client, user_factory, monkeypatch, entry, status):
    pax = user_factory("RevisionOverflow")
    oid = _order(pax["id"], status, search_revision=MAX_REVISION)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("overflow started matching"))
    with Session(engine) as session:
        try:
            _entry(session, session.get(InstantOrder, oid), entry)
        except HTTPException as error:
            assert error.status_code == 409
            session.rollback()
    with Session(engine) as reader:
        saved = reader.get(InstantOrder, oid)
        assert saved.status == status
        assert saved.search_revision == MAX_REVISION


@pytest.mark.parametrize("entry,status", [("start", S.created), ("advance", S.offered), ("reassign", S.cancelled)])
@pytest.mark.usefixtures("fake_redis")
def test_failed_commit_never_continues_or_publishes_revision(client, user_factory, monkeypatch, entry, status):
    pax = user_factory("RevisionRollback")
    oid = _order(pax["id"], status, search_revision=15)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("failed commit started matching"))
    with Session(engine) as session:
        def fail_commit():
            raise RuntimeError("simulated commit failure")
        monkeypatch.setattr(session, "commit", fail_commit)
        with pytest.raises(RuntimeError, match="simulated commit failure"):
            _entry(session, session.get(InstantOrder, oid), entry)
        session.rollback()
    with Session(engine) as reader:
        saved = reader.get(InstantOrder, oid)
        assert saved.status == status
        assert saved.search_revision == 15


@pytest.mark.parametrize("status", [S.created, S.accepted])
@pytest.mark.parametrize("kind", ["destination", "waypoints"])
@pytest.mark.usefixtures("fake_redis")
def test_non_search_route_change_does_not_start_matching(client, user_factory, monkeypatch, status, kind):
    pax = user_factory("RevisionNonSearchRoute")
    oid = _order(pax["id"], status, search_revision=50)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("non-search route started matching"))
    with Session(engine) as session:
        result = _apply(session, session.get(InstantOrder, oid), kind)
        assert result.status == status
        assert result.search_revision == 50


@pytest.mark.usefixtures("fake_redis")
def test_duplicate_fallback_without_price_change_does_not_rotate(client, user_factory, monkeypatch):
    pax = user_factory("RevisionDuplicateFallback")
    oid = _order(pax["id"], S.searching, category="comfort", search_revision=55,
                 fallback_categories=isv.cc.dump_classes({isv.cc.category_to_class("standard")}),
                 ride_price=1, price_estimate=1)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("unchanged fallback restarted search"))
    with Session(engine) as session:
        isv.add_fallback_category(session, session.get(InstantOrder, oid), "standard")
        assert session.get(InstantOrder, oid).search_revision == 55


@pytest.mark.parametrize("initial_revision", [None, 60])
@pytest.mark.usefixtures("fake_redis")
def test_stale_matcher_does_not_adopt_new_generation_before_lookup(client, user_factory, monkeypatch, initial_revision):
    pax = user_factory("RevisionStaleInitializer")
    oid = _order(pax["id"], S.searching, search_revision=initial_revision)
    effects = []
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("expired"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    with Session(engine) as old:
        stale = old.get(InstantOrder, oid)
        with Session(engine) as newer, monkeypatch.context() as pause:
            pause.setattr(isv, "try_offer_next", lambda session, order, *a, **k: order)
            current = isv.start_matching(newer, newer.get(InstantOrder, oid))
            expected_revision = (initial_revision or 0) + 1
            assert current.search_revision == expected_revision
        result = isv.try_offer_next(old, stale)
        assert result.status == S.searching
        assert result.search_revision == expected_revision
    assert effects == []


@pytest.mark.parametrize("entry,status", [("start", S.created), ("advance", S.offered), ("reassign", S.cancelled)])
@pytest.mark.usefixtures("fake_redis")
def test_superseded_postcommit_entry_has_no_continuation_effects(client, user_factory, monkeypatch, entry, status):
    pax = user_factory("RevisionCommitOwner")
    driver = user_factory("RevisionCancelledDriver", role=UserRole.driver)
    oid = _order(pax["id"], status, search_revision=70)
    effects = []
    monkeypatch.setattr(isv._redis(), "sadd", lambda *args: effects.append("tried"))
    monkeypatch.setattr(isv._redis(), "expire", lambda *args: effects.append("tried_ttl"))
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: effects.append("reassign_notice"))
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: effects.append("matching"))
    with Session(engine) as session:
        real_commit = session.commit
        def supersede_after_commit():
            real_commit()
            with Session(engine) as newer:
                current = newer.get(InstantOrder, oid)
                assert current.search_revision == 71
                current.search_revision = 72
                newer.add(current)
                newer.commit()
        monkeypatch.setattr(session, "commit", supersede_after_commit)
        if entry == "reassign":
            isv._reassign_after_driver_cancel(session, oid, driver["id"])
        else:
            _entry(session, session.get(InstantOrder, oid), entry)
    assert effects == []
    with Session(engine) as reader:
        saved = reader.get(InstantOrder, oid)
        assert saved.search_revision == 72 and saved.status == S.searching


@pytest.mark.usefixtures("fake_redis")
def test_two_legacy_callers_only_initializer_winner_may_lookup(client, user_factory, monkeypatch):
    pax = user_factory("RevisionTwoInitializers")
    oid = _order(pax["id"], S.searching, search_revision=None)
    effects = []
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("push"))
    class StopWinnerLookup(Exception):
        pass
    def stop_lookup(*args, **kwargs):
        raise StopWinnerLookup
    with Session(engine) as loser, Session(engine) as winner:
        stale_loser = loser.get(InstantOrder, oid)
        stale_winner = winner.get(InstantOrder, oid)
        with monkeypatch.context() as pause:
            pause.setattr(isv, "candidates", stop_lookup)
            with pytest.raises(StopWinnerLookup):
                isv.try_offer_next(winner, stale_winner)
        # Initialization must already be durable, independently of lookup success.
        with Session(engine) as observer:
            assert observer.get(InstantOrder, oid).search_revision == 1
        monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: pytest.fail("losing NULL initializer adopted the winner"))
        result = isv.try_offer_next(loser, stale_loser)
        assert result.status == S.searching and result.search_revision == 1
    assert effects == []


@pytest.mark.parametrize("has_candidate", [True, False])
@pytest.mark.usefixtures("fake_redis")
def test_existing_max_revision_can_finish_without_increment(client, user_factory, monkeypatch, has_candidate):
    pax = user_factory("RevisionMaxMatcher")
    driver = user_factory("RevisionMaxDriver", role=UserRole.driver)
    oid = _order(pax["id"], S.searching, search_revision=MAX_REVISION)
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]] if has_candidate else [])
    monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    with Session(engine) as session:
        result = isv.try_offer_next(session, session.get(InstantOrder, oid))
        assert result.status == (S.offered if has_candidate else S.expired)
        assert result.search_revision == MAX_REVISION


@pytest.mark.parametrize("entry,status", [("start", S.created), ("advance", S.offered), ("reassign", S.cancelled), ("matcher", S.searching)])
@pytest.mark.usefixtures("fake_redis")
def test_negative_revision_fails_closed(client, user_factory, monkeypatch, entry, status):
    pax = user_factory("RevisionNegative")
    oid = _order(pax["id"], status, search_revision=-1)
    effects = []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: effects.append("lookup") or [])
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: effects.append("reassign"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("push"))
    monkeypatch.setattr(isv, "_cleanup_tried", lambda *args: effects.append("cleanup"))
    with Session(engine) as session:
        try:
            if entry == "matcher":
                isv.try_offer_next(session, session.get(InstantOrder, oid))
            else:
                _entry(session, session.get(InstantOrder, oid), entry)
        except HTTPException as error:
            assert error.status_code == 409
            session.rollback()
    with Session(engine) as reader:
        saved = reader.get(InstantOrder, oid)
        assert saved.status == status and saved.search_revision == -1
    assert effects == []


@pytest.mark.parametrize("kind", ["destination", "waypoints", "fallback"])
@pytest.mark.parametrize("status", [S.searching, S.offered])
@pytest.mark.usefixtures("fake_redis")
def test_overflow_criteria_change_preserves_price_route_and_offer(client, user_factory, monkeypatch, kind, status):
    pax = user_factory("RevisionCriteriaOverflow")
    oid = _order(pax["id"], status, category="comfort", search_revision=MAX_REVISION,
                 ride_price=500, price_estimate=500, offer_token="d" * 32)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("overflow matched changed criteria"))
    def snapshot(order):
        return (order.status, order.search_revision, order.to_lat, order.to_lng,
                order.to_text, order.waypoints_json, order.fallback_categories,
                order.ride_price, order.price_estimate, order.promo_discount_kop,
                order.offer_token, order.current_offer_driver_id, order.offer_expires_at)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        before = snapshot(order)
        try:
            if kind == "fallback":
                isv.add_fallback_category(session, order, "standard")
            else:
                _apply(session, order, kind)
        except HTTPException as error:
            assert error.status_code == 409
            session.rollback()
    with Session(engine) as reader:
        assert snapshot(reader.get(InstantOrder, oid)) == before


def _fallback_snapshot(order):
    return (order.status, order.search_revision, order.fallback_categories,
            order.ride_price, order.price_estimate, order.promo_discount_kop,
            order.offer_token, order.current_offer_driver_id, order.offer_expires_at)


@pytest.mark.parametrize("cheaper", [False, True])
@pytest.mark.usefixtures("fake_redis")
def test_offered_fallback_rotates_revision_and_only_reprices_offer_when_cheaper(client, user_factory, monkeypatch, cheaper):
    pax = user_factory("RevisionOfferedFallback")
    driver = user_factory("RevisionOfferedFallbackDriver", role=UserRole.driver)
    expiry = utcnow()
    oid = _order(pax["id"], S.offered, category="comfort", search_revision=81,
                 ride_price=500, price_estimate=500, search_round=3,
                 current_offer_driver_id=driver["id"], offer_expires_at=expiry,
                 offer_token="f" * 32)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100 if cheaper else 600)
    seen = _pause_matching(monkeypatch)
    with Session(engine) as session:
        isv.add_fallback_category(session, session.get(InstantOrder, oid), "standard")
        current = session.get(InstantOrder, oid)
        assert current.search_revision == 82
        assert current.search_round == 3
        assert "standard" in isv.order_categories(current)
        if cheaper:
            assert current.status == S.searching
            assert current.current_offer_driver_id is None and current.offer_expires_at is None
            assert current.ride_price == 100
        else:
            assert current.status == S.offered
            assert current.ride_price == 500
            assert (current.offer_token, current.current_offer_driver_id, current.offer_expires_at) == ("f" * 32, driver["id"], expiry)
    assert seen == ([82] if cheaper else [])


@pytest.mark.parametrize("status", [S.searching, S.offered])
@pytest.mark.usefixtures("fake_redis")
def test_fallback_failed_commit_rolls_back_criteria_price_revision_and_effects(client, user_factory, monkeypatch, status):
    pax = user_factory("RevisionFallbackRollback")
    oid = _order(pax["id"], status, category="comfort", search_revision=90,
                 ride_price=500, price_estimate=500, promo_discount_kop=100000,
                 offer_token="e" * 32)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    effects = []
    monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: effects.append("matching"))
    monkeypatch.setattr(isv._redis(), "sadd", lambda *args: effects.append("tried"))
    monkeypatch.setattr(isv, "_push_offer", lambda *args: effects.append("offer"))
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: effects.append("push"))
    with Session(engine) as session:
        current = session.get(InstantOrder, oid)
        before = _fallback_snapshot(current)
        def fail_commit():
            raise RuntimeError("simulated fallback commit failure")
        monkeypatch.setattr(session, "commit", fail_commit)
        with pytest.raises(RuntimeError, match="simulated fallback commit failure"):
            isv.add_fallback_category(session, current, "standard")
        session.rollback()
    with Session(engine) as observer:
        assert _fallback_snapshot(observer.get(InstantOrder, oid)) == before
    assert effects == []


@pytest.mark.parametrize("status", [S.searching, S.offered])
@pytest.mark.usefixtures("fake_redis")
def test_fallback_new_price_and_revision_are_not_visible_before_reclamp(client, user_factory, monkeypatch, status):
    pax = user_factory("RevisionFallbackAtomicClamp")
    oid = _order(pax["id"], status, category="comfort", search_revision=100,
                 ride_price=500, price_estimate=500, promo_discount_kop=100000,
                 offer_token="c" * 32)
    monkeypatch.setattr(isv.settings, "car_class_min_drivers", 0)
    monkeypatch.setattr(isv, "_category_price", lambda *args: 100)
    seen = _pause_matching(monkeypatch)
    observed = []
    real_reclamp = isv.promo_ride.reclamp
    with Session(engine) as reader:
        before = _fallback_snapshot(reader.get(InstantOrder, oid))
    def observe_before_reclamp(session, order, **kwargs):
        assert order.ride_price == 100 and order.search_revision == 101
        with Session(engine) as observer:
            assert _fallback_snapshot(observer.get(InstantOrder, oid)) == before
        result = real_reclamp(session, order, **kwargs)
        observed.append(result)
        return result
    monkeypatch.setattr(isv.promo_ride, "reclamp", observe_before_reclamp)
    with Session(engine) as session:
        isv.add_fallback_category(session, session.get(InstantOrder, oid), "standard")
    assert len(observed) == 1 and observed[0] < 100000
    assert seen == [101]
    with Session(engine) as reader:
        saved = reader.get(InstantOrder, oid)
        assert saved.search_revision == 101 and saved.ride_price == 100
        assert saved.promo_discount_kop == observed[0]
