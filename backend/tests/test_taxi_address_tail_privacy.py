"""Recognized exact address tails must not leak in taxi offers."""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, User, UserRole
from test_taxi_status_never_resurrects import _order, fake_redis  # noqa: F401
from test_taxi_accept_cleanup_generation import _cleanup_accept_orders  # noqa: F401


def test_destination_house_before_apartment_is_removed():
    assert isv.street_only("ул. Гагарина, 5, кв. 9") == "ул. Гагарина"


def test_pickup_numeric_street_name_is_preserved():
    assert isv.pickup_street_only("ул. 8 Марта, 12, кв. 9") == "ул. 8 Марта"


@pytest.fixture
def address_order(user_factory):
    passenger = user_factory("TailPrivacyPassenger")
    driver = user_factory("TailPrivacyDriver", role=UserRole.driver)
    oid = _order(passenger["id"], S.offered, current_offer_driver_id=driver["id"],
                 from_text="ул. 8 Марта, 12, кв. 9", to_text="ул. Гагарина, 5, кв. 9")
    return oid, passenger, driver


def test_real_offer_push_has_literal_safe_addresses(client, address_order, monkeypatch):
    oid, passenger, driver = address_order
    sent = []
    monkeypatch.setattr(isv, "send_push", lambda session, user_id, title, body, **kwargs:
                        sent.append((title, body, kwargs["data"], kwargs["data_only"])))
    with Session(engine) as session:
        isv._push_offer(session, session.get(InstantOrder, oid), driver["id"])
    assert len(sent) == 1
    title, body, data, data_only = sent[0]
    assert title == "Новый заказ" and data_only is True
    assert data["to"] == "ул. Гагарина"
    assert data["from"] == "ул. 8 Марта"
    assert body.startswith("ул. 8 Марта → ул. Гагарина ·")
    assert "кв." not in body and ", 5" not in body and ", 12" not in body


def test_offered_driver_blurred_destination_has_no_house(client, address_order):
    oid, passenger, driver = address_order
    with Session(engine) as session:
        payload = isv.order_payload(session, session.get(InstantOrder, oid), session.get(User, driver["id"]))
    assert payload["to_text"] == "ул. Гагарина"
    assert payload["from_text"] == "ул. 8 Марта, 12, кв. 9", "offered payload pickup contract stays unchanged"


ADDRESS_CASES = [
    ("ул. Гагарина, 5, кв. 9", "ул. Гагарина"),
    ("ул. Гагарина, д. 12", "ул. Гагарина"),
    ("ул. Гагарина дом 12, квартира 9", "ул. Гагарина"),
    ("ул. Гагарина, д12", "ул. Гагарина"),
    ("ул. Гагарина, корп. 2", "ул. Гагарина"),
    ("ул. Гагарина, корпус 2", "ул. Гагарина"),
    ("ул. Гагарина, к. 2", "ул. Гагарина"),
    ("ул. Гагарина, стр. 3", "ул. Гагарина"),
    ("ул. Гагарина строение 3", "ул. Гагарина"),
    ("ул. Гагарина, кв. 9", "ул. Гагарина"),
    ("ул. Гагарина, квартира 9", "ул. Гагарина"),
    ("ул. Гагарина, подъезд 2", "ул. Гагарина"),
    ("ул. Гагарина, под. 2", "ул. Гагарина"),
    ("ул. Гагарина, этаж 4", "ул. Гагарина"),
    ("ул. Гагарина, эт. 4", "ул. Гагарина"),
    ("ул. Гагарина, домофон 123", "ул. Гагарина"),
    ("ул. Гагарина, код 123", "ул. Гагарина"),
    ("  ул. Гагарина, Д.\u00a012, КВ.\u00a09  ", "ул. Гагарина"),
    ("ул. Гагарина, 5к2", "ул. Гагарина"),
    ("ул. Гагарина, 5а", "ул. Гагарина"),
    ("ул. Гагарина, 5/2", "ул. Гагарина"),
    ("ул. Гагарина, 5-2", "ул. Гагарина"),
    ("ул. Гагарина, 5 к.2", "ул. Гагарина"),
    ("ул. Гагарина, 5 корпус2", "ул. Гагарина"),
    ("ул. Гагарина, 5 КОРПУС 2", "ул. Гагарина"),
    ("улица 8", "улица"),
    ("12 микрорайон", "12 микрорайон"),
    ("Пушкина 12", "Пушкина"),
    ("Пушкина 12к2", "Пушкина"),
    ("12", ""), ("д. 12", ""), ("д12", ""), ("кв. 9", ""),
    ("", ""), ("   ", ""),
    ("Дом культуры", "Дом культуры"),
    ("Уфа, вокзал", "Уфа, вокзал"),
    ("ул. Подъездная", "ул. Подъездная"),
    ("ул. Домовая", "ул. Домовая"),
]


@pytest.mark.parametrize("wrapper", ["street_only", "pickup_street_only"])
@pytest.mark.parametrize("raw,expected", ADDRESS_CASES)
def test_recognized_address_tail_matrix(wrapper, raw, expected):
    assert getattr(isv, wrapper)(raw) == expected


@pytest.mark.parametrize("wrapper", ["street_only", "pickup_street_only"])
@pytest.mark.parametrize("street", ["8 Марта", "50 лет Октября", "1905 года", "9 Января", "40-летия Победы"])
@pytest.mark.parametrize("suffix", ["", ", 12", ", дом 12, кв. 9", " 12к2"])
def test_numeric_street_names_survive_recognized_house_suffix(wrapper, street, suffix):
    address = f"ул. {street}"
    assert getattr(isv, wrapper)(address + suffix) == address


@pytest.mark.usefixtures("fake_redis")
@pytest.mark.parametrize("viewer_kind", ["passenger", "accepted_driver"])
def test_authorized_exact_addresses_are_not_sanitized(client, address_order, monkeypatch, viewer_kind):
    oid, passenger, driver = address_order
    monkeypatch.setattr(isv, "push_bilingual", lambda *args, **kwargs: None)
    monkeypatch.setattr("app.services.push_notification", lambda *args, **kwargs: None)
    with Session(engine) as session:
        if viewer_kind == "accepted_driver":
            isv.transition(session, oid, isv.Actor.driver, S.accepted, driver["id"], idempotent=False)
        viewer_id = passenger["id"] if viewer_kind == "passenger" else driver["id"]
        payload = isv.order_payload(session, session.get(InstantOrder, oid), session.get(User, viewer_id))
    assert payload["from_text"] == "ул. 8 Марта, 12, кв. 9"
    assert payload["to_text"] == "ул. Гагарина, 5, кв. 9"


@pytest.mark.parametrize("exact", ["12", "кв.9"])
def test_only_exact_addresses_use_empty_data_and_existing_push_fallback(client, address_order, monkeypatch, exact):
    oid, passenger, driver = address_order
    sent = []
    monkeypatch.setattr(isv, "send_push", lambda session, user_id, title, body, **kwargs:
                        sent.append((title, body, kwargs["data"], kwargs["data_only"])))
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        order.from_text = exact
        order.to_text = exact
        session.add(order)
        session.commit()
        isv._push_offer(session, order, driver["id"])
    assert len(sent) == 1
    title, body, data, data_only = sent[0]
    assert title == "Новый заказ" and data_only is True
    assert data["from"] == "" and data["to"] == ""
    assert body.startswith("Точка А → Точка Б · чистыми ")
    assert exact not in body
