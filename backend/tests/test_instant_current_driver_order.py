"""Cold-start lookup returns only the authenticated driver's assigned active order ID."""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole


PATH = "/instant/driver/current-order"


def _order(passenger, status, *, assigned=None, offered=None):
    with Session(engine) as session:
        order = InstantOrder(
            passenger_id=passenger["id"], status=status,
            driver_id=assigned["id"] if assigned else None,
            current_offer_driver_id=offered["id"] if offered else None,
            from_text="fixture pickup", to_text="fixture destination", price_estimate=999,
        )
        session.add(order)
        session.commit()
        session.refresh(order)
        return order.id


@pytest.mark.parametrize("status", [S.accepted, S.arriving, S.onboard])
def test_only_owned_assigned_active_order_even_off_line(client, user_factory, status):
    passenger = user_factory("Passenger")
    driver = user_factory("Driver", role=UserRole.driver)
    other = user_factory("Other", role=UserRole.driver)
    current = _order(passenger, status, assigned=driver)
    _order(passenger, S.onboard, assigned=other)
    _order(passenger, S.offered, offered=driver)
    _order(passenger, S.done, assigned=driver)

    response = client.get(PATH, headers=driver["auth"])
    assert response.status_code == 200
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {"order_id": current}
    with Session(engine) as session:
        assert session.get(InstantOrder, current).status == status


@pytest.mark.parametrize("status", [S.done, S.cancelled, S.expired])
def test_terminal_order_is_not_recoverable(client, user_factory, status):
    passenger = user_factory("Passenger")
    driver = user_factory("Driver", role=UserRole.driver)
    _order(passenger, status, assigned=driver)
    _order(passenger, S.offered, offered=driver)

    response = client.get(PATH, headers=driver["auth"])
    assert response.status_code == 200
    assert response.json() == {"order_id": None}
    assert response.headers["Cache-Control"] == "no-store"


def test_passenger_stranger_and_guest_cannot_find_assigned_order(client, user_factory):
    passenger = user_factory("Passenger")
    driver = user_factory("Assigned", role=UserRole.driver)
    stranger = user_factory("Stranger", role=UserRole.driver)
    order = _order(passenger, S.accepted, assigned=driver)

    assert client.get(PATH).status_code == 401
    for viewer in (passenger, stranger):
        response = client.get(PATH, params={"driver_id": driver["id"], "order_id": order}, headers=viewer["auth"])
        assert response.status_code == 200
        assert response.json() == {"order_id": None}


def test_inconsistent_multiple_assignments_do_not_open_arbitrary_order(client, user_factory):
    first = user_factory("First passenger")
    second = user_factory("Second passenger")
    driver = user_factory("Driver", role=UserRole.driver)
    _order(first, S.accepted, assigned=driver)
    _order(second, S.onboard, assigned=driver)

    response = client.get(PATH, headers=driver["auth"])
    assert response.status_code == 409
    assert response.headers["Cache-Control"] == "no-store"
    detail = response.json()["detail"]
    assert set(detail) == {"ru", "ba", "code"}
    assert detail["code"] == "taxi_multiple_active_orders"
