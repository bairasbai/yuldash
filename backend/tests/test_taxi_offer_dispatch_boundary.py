"""Diagnostics of known postcommit dispatch gaps, NOT protection tests.

PASS intentionally proves a stale recipient/new-generation metadata mismatch or
an offer sent after a committed cancellation. These SQLite interleavings model
the exact server ordering; no real FCM delivery or client behavior is exercised.
"""
from datetime import timedelta, timezone

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


def _utc_z(value):
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _assignment(order):
    return (order.status, order.current_offer_driver_id, order.offer_token,
            order.offer_expires_at, order.search_revision, order.search_round)


@pytest.mark.parametrize("intervening", ["new_offer", "cancelled"])
@pytest.mark.usefixtures("fake_redis")
def test_known_postcommit_boundary_dispatches_to_old_candidate_after_assignment_changed(client, user_factory, monkeypatch, intervening):
    passenger = user_factory("DispatchBoundaryPassenger")
    old_driver = user_factory("DispatchBoundaryOldDriver", role=UserRole.driver)
    new_driver = user_factory("DispatchBoundaryNewDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.searching, search_revision=10,
                 from_text="ул. 8 Марта, 12", to_text="ул. Гагарина, 5, кв. 9")
    original, replacement, transported, hook_calls = [], [], [], []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [old_driver["id"]])

    def transport(session, user_id, title, body, **kwargs):
        if kwargs.get("data", {}).get("type") != "instant_offer":
            return
        with Session(engine) as observer:
            current = observer.get(InstantOrder, oid)
            transported.append((user_id, title, body, dict(kwargs["data"]),
                                kwargs["data_only"], _assignment(current)))
    monkeypatch.setattr(isv, "send_push", transport)
    key = isv._tried_key(oid)
    try:
        with Session(engine) as old_session:
            real_commit = old_session.commit
            def transition_after_winner_commit():
                real_commit()
                hook_calls.append(True)
                assert len(hook_calls) == 1, "interleaving is only after matcher winner commit"
                with Session(engine) as newer:
                    current = newer.get(InstantOrder, oid)
                    # Snapshot actual committed scalar values BEFORE changing them.
                    original.append(_assignment(current))
                    assert current.status == S.offered
                    assert current.current_offer_driver_id == old_driver["id"]
                    assert current.offer_token is not None and current.offer_expires_at is not None
                    if intervening == "new_offer":
                        current.current_offer_driver_id = new_driver["id"]
                        current.offer_token = "f" * 32
                        assert current.offer_token != original[0][2]
                        current.offered_at = utcnow()
                        current.offer_expires_at = original[0][3] + timedelta(minutes=2)
                        current.search_revision += 1
                        current.search_round += 1
                        newer.add(current)
                        newer.commit()
                    else:
                        # Actual authorized cancellation, including its cleanup and
                        # notification helpers. Spy filters non-offer transport.
                        current = isv.cancel_order(newer, oid, isv.Actor.passenger,
                                                   passenger["id"], "changed_mind")
                        assert current.status == S.cancelled
                    newer.refresh(current)
                    replacement.append(_assignment(current))
            monkeypatch.setattr(old_session, "commit", transition_after_winner_commit)
            result = isv.try_offer_next(old_session, old_session.get(InstantOrder, oid))
            assert _assignment(result) == replacement[0]
        assert len(transported) == 1
        recipient, title, body, data, data_only, visible_assignment = transported[0]
        assert visible_assignment == replacement[0]
        assert recipient == original[0][1] == old_driver["id"]
        assert title == "Новый заказ" and data_only is True
        assert data["type"] == "instant_offer" and data["order_id"] == str(oid)
        assert data["from"] == "ул. 8 Марта" and data["to"] == "ул. Гагарина"
        assert all(isinstance(value, str) for value in data.values())
        if intervening == "new_offer":
            assert visible_assignment[1] == new_driver["id"] != recipient
            assert data["offer_token"] == replacement[0][2] != original[0][2]
            assert data["offer_expires_at"] == _utc_z(replacement[0][3])
            assert data["offer_expires_at"] != _utc_z(original[0][3])
            print("KNOWN DISPATCH GAP observed: old recipient receives newly committed owner's token/expiry")
        else:
            assert visible_assignment[0] == S.cancelled and visible_assignment[1] is None
            assert data["offer_token"] == original[0][2]
            assert "offer_expires_at" not in data
            print("KNOWN DISPATCH GAP observed: instant_offer sent after committed cancellation")
    finally:
        isv._redis().delete(key)
