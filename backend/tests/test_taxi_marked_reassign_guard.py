"""Keep an explicit driver cancellation out of the wrong reassignment lane."""

from datetime import timedelta

import pytest
from sqlalchemy import delete, update
from sqlmodel import Session

from app import instant_service as isv, promo_ride
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S, PromoCode, PromoRedemption, SafetyProfile
from app.timeutil import utcnow

import test_dropped_order_keeps_looking as dropped

fake_redis = dropped.fake_redis


@pytest.mark.parametrize("when", (
    "ordinary", "already_marked", "before_reassign", "after_reassign_commit", "before_notice",
))
def test_marked_generation_never_passes_legacy_reassign_side_effects(
        client, user_factory, fake_redis, monkeypatch, request, when):
    driver, passenger, oid = dropped._accepted(client, user_factory, "MarkedDropDriver", "MarkedDropPassenger")
    def cleanup():
        with engine.begin() as connection:
            connection.execute(delete(DriverCancel).where(DriverCancel.order_id == oid))
            connection.execute(delete(InstantOrder).where(InstantOrder.id == oid))

    request.addfinalizer(cleanup)
    next_driver = dropped._driver_online(client, user_factory, "MarkedDropReplacement")
    dropped._heartbeat(client, next_driver)
    before = dropped._db_order(oid)
    # A partial marker is not a legacy order. Do not create a usable sealed
    # continuation or confuse these synthetic states with valid TF04 data.
    marker = "a" * 32

    def mark(connection, phase):
        assert connection.execute(update(InstantOrder).where(
            InstantOrder.id == oid, InstantOrder.status == phase,
        ).values(route_resume_claim_id=marker)).rowcount == 1
        connection.commit()

    if when == "already_marked":
        with engine.connect() as connection:
            mark(connection, S.accepted)

    observed = []
    notify = []
    wrote_marker = []
    request_sessions = []
    real_reassign = isv._reassign_after_driver_cancel
    real_remember = fake_redis.sadd
    real_expire = fake_redis.expire
    real_notify = isv._notify_reassign

    def sadd(key, *values):
        if key == isv._tried_key(oid):
            observed.append(("tried", values))
        return real_remember(key, *values)

    def expire(key, seconds):
        if key == isv._tried_key(oid):
            observed.append(("ttl", seconds))
        result = real_expire(key, seconds)
        if key == isv._tried_key(oid) and when == "before_notice" and not wrote_marker:
            with engine.connect() as other:
                assert other.connection.driver_connection is not request_sessions[0].connection().connection.driver_connection
                mark(other, S.searching)
            wrote_marker.append(True)
            observed.append(("committed", S.searching))
        return result

    def record_notify(session, order):
        notify.append(order.id)
        return real_notify(session, order)

    def interleave(session, order_id, cancelled_driver_id, *args, **kwargs):
        if order_id == oid and when == "before_notice":
            request_sessions.append(session)
        if order_id != oid or when in ("ordinary", "already_marked", "before_notice"):
            return real_reassign(session, order_id, cancelled_driver_id, *args, **kwargs)
        with engine.connect() as other:
            assert other.connection.driver_connection is not session.connection().connection.driver_connection
            if when == "before_reassign":
                mark(other, S.cancelled)
                observed.append(("committed", S.cancelled))
                return real_reassign(session, order_id, cancelled_driver_id, *args, **kwargs)
            real_commit = session.commit

            def commit_then_mark():
                real_commit()
                if not observed:
                    mark(other, S.searching)
                    observed.append(("committed", S.searching))

            with monkeypatch.context() as local:
                local.setattr(session, "commit", commit_then_mark)
                return real_reassign(session, order_id, cancelled_driver_id, *args, **kwargs)

    monkeypatch.setattr(fake_redis, "sadd", sadd)
    monkeypatch.setattr(fake_redis, "expire", expire)
    monkeypatch.setattr(isv, "_notify_reassign", record_notify)
    monkeypatch.setattr(isv, "_reassign_after_driver_cancel", interleave)
    http = client.post(f"/instant/orders/{oid}/cancel", headers=driver["auth"],
                       json={"reason": "synthetic cancellation"})
    assert http.status_code == (409 if when in ("after_reassign_commit", "before_notice") else 200), http.text
    response = http.json()
    latest = dropped._db_order(oid)
    assert latest.passenger_id == passenger["id"] and latest.price_estimate == before.price_estimate
    events = dropped._события(driver["id"])
    assert len(events) == 1 and events[0].order_id == oid and events[0].reason == "synthetic cancellation"
    if when == "ordinary":
        assert latest.status == S.offered and response["status"] == "offered"
        assert latest.route_resume_claim_id is None and latest.reassigns == before.reassigns + 1
        assert latest.current_offer_driver_id == next_driver["id"]
        assert ("tried", (driver["id"],)) in observed and ("ttl", 3600) in observed
        assert notify == [oid]
        return

    assert latest.route_resume_claim_id == marker and latest.route_resume_deadline_at is None
    assert latest.current_offer_driver_id is None and latest.offer_expires_at is None
    assert "route_resume_claim_id" not in response and marker not in str(response)
    if when in ("after_reassign_commit", "before_notice"):
        # The ordinary SQL transition was committed before the independent
        # change. Its worker has lost ownership; never undo the other writer.
        previous_effects = ([("tried", (driver["id"],)), ("ttl", 3600)] if when == "before_notice" else [])
        assert observed == [*previous_effects, ("committed", S.searching)]
        assert latest.status == S.searching and latest.reassigns == before.reassigns + 1
    else:
        assert observed == ([("committed", S.cancelled)] if when == "before_reassign" else [])
        assert latest.status == S.cancelled and response["status"] == "cancelled"
        assert latest.driver_id == driver["id"] and latest.reassigns == before.reassigns
        assert latest.cancel_by == "driver" and latest.cancel_reason == "synthetic cancellation"
    assert notify == []


@pytest.mark.parametrize("change", ("new_live", "other_cancel", "marked_exact", "ordinary_exact",
                                      "new_cancel_during_promo", "new_live_during_match"))
def test_only_exact_ordinary_terminal_event_can_release_the_promo_or_tried_key(
        client, user_factory, fake_redis, monkeypatch, request, change):
    driver, passenger, oid = dropped._accepted(client, user_factory, "CancelFenceDriver", "CancelFencePassenger")
    other = dropped._driver_online(client, user_factory, "CancelFenceOtherDriver")
    with Session(engine) as session:
        campaign = PromoCode(code=f"REASSIGN-{oid}")
        session.add(campaign)
        session.flush()
        cid = campaign.id
        redemption = PromoRedemption(
            promo_id=cid, user_id=passenger["id"], discount_kop=3000, used_order_id=oid, used_at=utcnow(),
        )
        session.add(redemption)
        session.get(InstantOrder, oid).promo_discount_kop = 3000
        session.commit()
        rid = redemption.id

    def cleanup():
        with engine.begin() as connection:
            connection.execute(delete(PromoRedemption).where(PromoRedemption.id == rid))
            connection.execute(delete(PromoCode).where(PromoCode.id == cid))
            connection.execute(delete(DriverCancel).where(DriverCancel.order_id == oid))
            connection.execute(delete(InstantOrder).where(InstantOrder.id == oid))

    request.addfinalizer(cleanup)
    key = isv._tried_key(oid)
    fake_redis.sadd(key, other["id"])
    notices = []
    rival_commits = []
    real_reassign = isv._reassign_after_driver_cancel
    real_release = promo_ride.release
    real_notice = isv._notify_cancel
    real_write = isv._write_search_outcome
    sensitive = "SYNTHETIC_PRIVATE_RIVAL_ROUTE_42"

    def rival(session, *, marker_only=False, phase=S.cancelled):
        live = change in ("new_live", "new_live_during_match")
        with engine.connect() as writer:
            assert writer.connection.driver_connection is not session.connection().connection.driver_connection
            changes = (
                {"route_resume_claim_id": "a" * 32} if marker_only else
                {"status": S.accepted if live else S.cancelled,
                 "cancel_by": "" if live else "driver",
                 "cancel_reason": "other event", "driver_id": other["id"], "cancelled_at": utcnow(),
                 "from_text": sensitive, "reassigns": InstantOrder.reassigns + 1}
            )
            assert writer.execute(update(InstantOrder).where(
                InstantOrder.id == oid, InstantOrder.status == phase,
            ).values(**changes)).rowcount == 1
            writer.commit()
        rival_commits.append(True)

    def reassign(session, order_id, driver_id, *args, **kwargs):
        if change == "new_live_during_match":
            return real_reassign(session, order_id, driver_id, *args, **kwargs)
        if change in ("new_live", "other_cancel", "marked_exact"):
            rival(session, marker_only=change == "marked_exact")
            return real_reassign(session, order_id, driver_id, *args, **kwargs)
        # Force the ordinary terminal fallback: a separate writer sets a partial
        # marker before the CAS and corrects it before eligible terminal work.
        rival(session, marker_only=True)
        assert real_reassign(session, order_id, driver_id, *args, **kwargs) is None
        with engine.begin() as writer:
            assert writer.execute(update(InstantOrder).where(InstantOrder.id == oid)
                                  .values(route_resume_claim_id=None)).rowcount == 1
        return None

    def release(session, order, **kwargs):
        if change == "new_cancel_during_promo":
            rival(session)
        return real_release(session, order, **kwargs)

    def write(session, owner, **values):
        if change == "new_live_during_match":
            rival(session, phase=S.searching)
        return real_write(session, owner, **values)

    def record(session, order, actor):
        notices.append((order.id, actor))
        return real_notice(session, order, actor)

    monkeypatch.setattr(isv, "_reassign_after_driver_cancel", reassign)
    monkeypatch.setattr(isv, "_notify_cancel", record)
    monkeypatch.setattr(promo_ride, "release", release)
    monkeypatch.setattr(isv, "_write_search_outcome", write)
    http = client.post(f"/instant/orders/{oid}/cancel", headers=driver["auth"])
    assert rival_commits
    latest = dropped._db_order(oid)
    with Session(engine) as session:
        applied = session.get(PromoRedemption, rid)
        assert applied.used_order_id == (None if change == "ordinary_exact" else oid)
        assert (applied.used_at is None) is (change == "ordinary_exact")
    assert latest.promo_discount_kop == (0 if change == "ordinary_exact" else 3000)
    assert (fake_redis.exists(key) == 0) is (change == "ordinary_exact")
    assert len(dropped._события(driver["id"])) == 1  # explicit cancellation still committed
    if change in ("marked_exact", "ordinary_exact"):
        assert http.status_code == 200 and http.json()["status"] == "cancelled", http.text
        assert notices == [(oid, isv.Actor.driver)]
    else:
        assert http.status_code == 409, http.text
        assert http.json()["detail"]["code"] == "taxi_cancel_committed_order_changed"
        assert notices == [] and sensitive not in http.text
        assert latest.driver_id == other["id"] and latest.from_text == sensitive


@pytest.mark.parametrize("rival", (False, True))
def test_reassign_returns_own_committed_passenger_suspension_only(
        client, user_factory, fake_redis, monkeypatch, request, rival):
    driver, passenger, oid = dropped._accepted(client, user_factory, "SuspendedReassignD", "SuspendedReassignP")
    with Session(engine) as session:
        session.add(SafetyProfile(user_id=passenger["id"], suspended_until=utcnow() + timedelta(days=3)))
        session.commit()

    def cleanup():
        with engine.begin() as conn:
            conn.execute(delete(SafetyProfile).where(SafetyProfile.user_id == passenger["id"]))
            conn.execute(delete(DriverCancel).where(DriverCancel.order_id == oid))
            conn.execute(delete(InstantOrder).where(InstantOrder.id == oid))

    request.addfinalizer(cleanup)
    real_cancel = isv.cancel_order
    committed_rivals = []
    secret = "SYNTHETIC_RIVAL_SUSPENSION_ROUTE"

    def cancel(session, order_id, actor, user_id, reason="", **kwargs):
        if rival and order_id == oid and reason == "passenger_suspended":
            with engine.connect() as writer:
                assert writer.connection.driver_connection is not session.connection().connection.driver_connection
                assert writer.execute(update(InstantOrder).where(
                    InstantOrder.id == oid, InstantOrder.status == S.searching,
                ).values(status=S.cancelled, cancel_by="passenger", cancel_reason="passenger_suspended",
                         cancelled_at=utcnow(), from_text=secret)).rowcount == 1
                writer.commit()
            committed_rivals.append(True)
        return real_cancel(session, order_id, actor, user_id, reason, **kwargs)

    monkeypatch.setattr(isv, "cancel_order", cancel)
    response = client.post(f"/instant/orders/{oid}/cancel", headers=driver["auth"])
    current = dropped._db_order(oid)
    assert (current.status, current.cancel_by, current.cancel_reason) == (S.cancelled, "passenger", "passenger_suspended")
    assert len(dropped._события(driver["id"])) == 1
    if rival:
        assert committed_rivals == [True] and current.from_text == secret
        assert response.status_code == 409 and secret not in response.text
        assert response.json()["detail"]["code"] == "taxi_cancel_committed_order_changed"
    else:
        assert response.status_code == 200, response.text
        assert response.json()["status"] == "cancelled" and current.reassigns == 1
