"""«Договорились на 1 ₽» — снизить сумму может только водитель (ревью 2026-10-12, P2).

Договорённость об оплате раньше записывала любая сторона и любую цифру. Пассажир после поездки
ставил 1 ₽, оплачивал картой — карта списывала 1 ₽ вместо цены, бронь становилась оплаченной,
водителю зачислялся 1 ₽. Сам водитель на это нигде не соглашался. Пока онлайн-оплата на проде
выключена, вреда нет, но с ЮKassa это прямой убыток водителя.

Правило: снижает тот, кто даёт скидку, — водитель. Поднять может любой: пассажир — чаевые,
водитель — доплату (пассажир видит и подтверждает сумму в банке). Свою цену пассажир
при бронировании не может предложить НИЖЕ цены объявления: водитель в карточке «Подтвердить»
эту сумму не видит и подтвердил бы вслепую (ревью 2026-10-13). Торг — через заявку и отклик.
"""
from __future__ import annotations

from datetime import timedelta, timezone

from sqlmodel import Session

from app.db import engine
from app.models import Booking, UserRole
from app.timeutil import utcnow

from test_booking_payment_agreement_amount import (  # noqa: F401 — fixture reuse
    _complete, _create, _rows, captured_provider,
)


def _agree(client, who, booking_id, amount):
    return client.post(f"/bookings/{booking_id}/pay-agreement", headers=who["auth"],
                       json={"pay_amount": amount})


def _amount(client, who, booking_id):
    details = client.get(f"/bookings/{booking_id}/details", headers=who["auth"])
    assert details.status_code == 200, details.text
    return details.json()["pay_amount"]


def test_пассажир_после_поездки_не_ставит_1_рубль(client, user_factory, captured_provider):
    """Сам эксплойт: снижение отклонено, карта списывает цену, водителю — цена."""
    driver, passenger, booking_id = _create(client, user_factory)
    _complete(client, driver, booking_id)

    r = _agree(client, passenger, booking_id, 1)

    assert r.status_code == 403, (
        f"пассажир сам снизил сумму до 1 ₽ после поездки: {r.status_code} {r.text}"
    )
    detail = r.json()["detail"]
    assert "водитель" in str(detail).lower()
    assert _amount(client, driver, booking_id) == 1000
    paid = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                       json={"method": "card"})
    assert paid.status_code == 200, paid.text
    assert [c["amount_kop"] for c in captured_provider] == [100_000], (
        "с карты списали не цену поездки"
    )
    assert _rows(booking_id)["ledger"][0]["amount_kop"] == 100_000


def test_пассажир_не_снижает_и_до_поездки(client, user_factory):
    """После брони цену уже принял водитель — снижать в одиночку нельзя и до поездки."""
    driver, passenger, booking_id = _create(client, user_factory)
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200

    r = _agree(client, passenger, booking_id, 500)

    assert r.status_code == 403, r.text
    assert _amount(client, passenger, booking_id) == 1000


def test_водитель_даёт_скидку(client, user_factory):
    driver, passenger, booking_id = _create(client, user_factory)
    _complete(client, driver, booking_id)

    r = _agree(client, driver, booking_id, 700)

    assert r.status_code == 200, r.text
    assert _amount(client, passenger, booking_id) == 700


def test_водитель_может_и_бесплатно(client, user_factory):
    """«Между своими»: подвезти соседа бесплатно — решение водителя."""
    driver, passenger, booking_id = _create(client, user_factory)

    r = _agree(client, driver, booking_id, 0)

    assert r.status_code == 200, r.text
    assert _amount(client, passenger, booking_id) == 0


def test_пассажир_накидывает_чаевые(client, user_factory):
    driver, passenger, booking_id = _create(client, user_factory)
    _complete(client, driver, booking_id)

    r = _agree(client, passenger, booking_id, 1200)

    assert r.status_code == 200, r.text
    assert _amount(client, driver, booking_id) == 1200


def test_водитель_записывает_доплату(client, user_factory):
    driver, passenger, booking_id = _create(client, user_factory)

    r = _agree(client, driver, booking_id, 1300)

    assert r.status_code == 200, r.text
    assert _amount(client, passenger, booking_id) == 1300


def test_пассажир_вернуть_прежнюю_сумму_и_сменить_способ_может(client, user_factory):
    """Та же сумма — не снижение: смена способа оплаты и повтор цифры работают как раньше."""
    driver, passenger, booking_id = _create(client, user_factory)

    r = client.post(f"/bookings/{booking_id}/pay-agreement", headers=passenger["auth"],
                    json={"pay_method": "cash", "pay_amount": 1000})

    assert r.status_code == 200, r.text
    assert r.json()["pay_method"] == "cash"


def _ride(client, driver, price=1000):
    depart = (utcnow() + timedelta(hours=3)).replace(tzinfo=timezone.utc).isoformat()
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart,
        "seats_total": 3, "price": price,
    })
    assert ride.status_code == 200, ride.text
    return ride.json()["id"]


def test_при_бронировании_ниже_цены_не_предложить(client, user_factory, captured_provider):
    """Вторая дверь той же дыры: 1 ₽ при брони, водитель подтверждает вслепую, карта — 1 ₽."""
    driver = user_factory("QA_BLIND_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_BLIND_PASSENGER")
    ride_id = _ride(client, driver)

    created = client.post("/bookings", headers=passenger["auth"],
                          json={"ride_id": ride_id, "seats": 1, "pay_amount": 1})

    assert created.status_code == 403, (
        f"пассажир при брони поставил 1 ₽, которые водитель не видит: {created.status_code} {created.text}"
    )
    assert "водител" in str(created.json()["detail"]).lower()


def test_при_бронировании_цена_или_больше_проходят(client, user_factory):
    driver = user_factory("QA_FAIR_DRIVER", role=UserRole.driver)
    passenger = user_factory("QA_FAIR_PASSENGER")
    ride_id = _ride(client, driver)

    ровно = client.post("/bookings", headers=passenger["auth"],
                        json={"ride_id": ride_id, "seats": 1, "pay_amount": 1000})

    assert ровно.status_code == 200, ровно.text
    assert ровно.json()["pay_amount"] == 1000


def test_пассажир_откатывает_свою_опечатку(client, user_factory):
    """Поставил 10 000 вместо 1 000 — вернуть можно, но не ниже цены поездки."""
    driver, passenger, booking_id = _create(client, user_factory)
    assert _agree(client, passenger, booking_id, 10000).status_code == 200

    assert _agree(client, passenger, booking_id, 1000).status_code == 200
    assert _agree(client, passenger, booking_id, 999).status_code == 403
    assert _amount(client, driver, booking_id) == 1000


def test_скидку_водителя_пассажир_не_углубляет(client, user_factory):
    driver, passenger, booking_id = _create(client, user_factory)
    assert _agree(client, driver, booking_id, 700).status_code == 200

    assert _agree(client, passenger, booking_id, 600).status_code == 403
    assert _agree(client, passenger, booking_id, 800).status_code == 200
    assert _amount(client, driver, booking_id) == 800


def test_снижение_цены_поездки_доходит_до_оплаты(client, user_factory, captured_provider):
    """Водитель снизил цену в объявлении — снижается и сумма к оплате, а не только «цена брони»."""
    driver, passenger, booking_id = _create(client, user_factory)
    with Session(engine) as s:
        ride_id = s.get(Booking, booking_id).ride_id

    edited = client.post(f"/rides/{ride_id}/edit", headers=driver["auth"], json={"price": 700})

    assert edited.status_code == 200, edited.text
    assert _amount(client, passenger, booking_id) == 700
    _complete(client, driver, booking_id)
    paid = client.post(f"/bookings/{booking_id}/pay", headers=passenger["auth"],
                       json={"method": "card"})
    assert paid.status_code == 200, paid.text
    assert [c["amount_kop"] for c in captured_provider] == [70_000], (
        "водитель снизил цену, а с карты списали прежнюю"
    )
