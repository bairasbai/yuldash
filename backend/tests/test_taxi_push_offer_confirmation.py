"""An account-bound check for a queued push; a read must not move the matcher."""
from datetime import datetime, timedelta, timezone

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.routers import instant

PATH = "/instant/driver/offer-notification/v1"
NOW = datetime(2026, 11, 27, 6, 30)
DEADLINE = NOW + timedelta(seconds=20, microseconds=123456)


@pytest.fixture
def offers(monkeypatch, user_factory):
    monkeypatch.setattr(instant, "utcnow", lambda: NOW)
    monkeypatch.setattr(instant.isv, "reconcile_offer", lambda *_: pytest.fail("push check changed matching"))
    user = user_factory("Notification passenger")
    driver = user_factory("Notification driver", role=UserRole.driver)
    other = user_factory("Other driver", role=UserRole.driver)
    with Session(engine) as session:
        order = InstantOrder(passenger_id=user["id"], current_offer_driver_id=driver["id"],
                             status=S.offered, offer_expires_at=DEADLINE)
        session.add(order)
        session.commit()
        order_id = order.id
    return order_id, user, driver, other


def check(client, order_id, auth, deadline=DEADLINE.isoformat()):
    response = client.get(PATH, headers=auth, params={"order_id": order_id, "offer_expires_at": deadline})
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    return response.json()


def test_push_check_identifies_exact_live_offer_and_discloses_nothing_else(client, offers):
    order_id, user, driver, other = offers
    assert check(client, order_id, driver["auth"]) == {"current": True}
    assert check(client, order_id, driver["auth"], DEADLINE.replace(tzinfo=timezone.utc).isoformat()) == {
        "current": True,
    }
    assert check(client, order_id, driver["auth"], DEADLINE.isoformat(timespec="milliseconds")) == {"current": False}
    for stranger in (user, other):
        assert check(client, order_id, stranger["auth"]) == {"current": False}
    assert check(client, 2**31 - 1, driver["auth"]) == {"current": False}
    assert client.get(PATH, params={"order_id": order_id, "offer_expires_at": DEADLINE.isoformat()}).status_code == 401


@pytest.mark.parametrize("change", ["cancelled", "accepted", "other_driver", "new_deadline", "expired", "newer_offer"])
def test_replaced_push_is_not_current_and_matcher_does_not_advance(client, offers, monkeypatch, change):
    order_id, user, driver, other = offers
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        if change == "newer_offer":
            replacement = InstantOrder(passenger_id=user["id"], current_offer_driver_id=driver["id"],
                                       status=S.offered, offer_expires_at=DEADLINE)
            session.add(replacement)
            session.flush()
            replacement_id = replacement.id
        elif change in ("cancelled", "accepted"):
            order.status = S(change)
        elif change == "other_driver":
            order.current_offer_driver_id = other["id"]
        elif change == "new_deadline":
            order.offer_expires_at += timedelta(seconds=10)
        session.commit()
        snapshot = (order.status, order.current_offer_driver_id, order.offer_expires_at, order.search_round)
    if change == "expired":
        monkeypatch.setattr(instant, "utcnow", lambda: DEADLINE)
    assert check(client, order_id, driver["auth"]) == {"current": False}
    if change == "newer_offer":
        assert check(client, replacement_id, driver["auth"]) == {"current": True}
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        assert (order.status, order.current_offer_driver_id, order.offer_expires_at, order.search_round) == snapshot


@pytest.mark.parametrize("gate", ["documents", "debt", "rest", "quality_pause", "review_pause", "pickup"])
def test_push_confirmation_respects_live_driver_offer_gates(client, offers, monkeypatch, gate):
    order_id, _, driver, _ = offers
    assert check(client, order_id, driver["auth"]) == {"current": True}
    if gate == "documents":
        monkeypatch.setattr(instant.taxi_mod, "is_approved_taxi_driver", lambda *_: False)
    elif gate == "debt":
        monkeypatch.setattr(instant.debt_mod, "taxi_block_reason", lambda *_: "debt")
    elif gate == "rest":
        monkeypatch.setattr(instant.workday_mod, "blocking_workday", lambda *_: True)
    elif gate == "quality_pause":
        monkeypatch.setattr(instant.quality_mod, "taxi_pause_until", lambda *_: DEADLINE)
    elif gate == "review_pause":
        def paused(*_, refresh=True):
            assert not refresh, "push verification must not refresh or write safety state"
            return True

        monkeypatch.setattr(instant, "account_paused", paused)
    else:
        monkeypatch.setattr(instant.taxi_mod, "availability", lambda *_: {"enabled": False})
    assert check(client, order_id, driver["auth"]) == {"current": False}
    with Session(engine) as session:
        order = session.get(InstantOrder, order_id)
        assert (order.status, order.current_offer_driver_id, order.offer_expires_at) == (S.offered, driver["id"], DEADLINE)
