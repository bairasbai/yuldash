"""Ручки смены адреса: кто что может и что видит.

Отдельно от `test_destination_change.py`: там проверяется арифметика цены, здесь — двери.
Пускать в чужой заказ или давать пассажиру нажать «Понял» за водителя нельзя ничуть не
меньше, чем ошибиться в рублях.
"""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from datetime import timedelta

UFA = (54.7351, 55.9587)
UFA_NEAR = (54.7450, 55.9700)


def _make_order(passenger_id: int, driver_id: int | None = None,
                status=S.onboard, **kw) -> int:
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id, status=status,
                         from_lat=UFA[0], from_lng=UFA[1],
                         to_lat=UFA_NEAR[0], to_lng=UFA_NEAR[1],
                         distance_km=3.0, eta_min=8.0, price_estimate=200,
                         category="standard", surge_k=1.0, pricing_k=1.0,
                         onboard_at=utcnow() - timedelta(minutes=10), driven_km=6.0, **kw)
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _body(**kw):
    return {"to_lat": 54.80, "to_lng": 56.02, "to_text": "Проспект Октября 100", **kw}


# ------------------------------ превью ------------------------------
def test_preview_shows_the_price_without_changing_anything(client, user_factory):
    """Человек должен увидеть новую цену ДО того, как согласится.

    Превью считает и показывает, но заказ не трогает: передумал — ничего не случилось.
    """
    pax = user_factory("DestPax")
    drv = user_factory("DestDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"],
                    json=_body(preview=True))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["applied"] is False
    assert body["price"] > 0
    assert body["driven_km"] >= 6.0, "проеденное не показано — человек не поймёт цену"

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.to_lat == pytest.approx(UFA_NEAR[0]), "превью изменило заказ"
        assert order.destination_changes == 0


# ------------------------------ смена ------------------------------
def test_passenger_changes_the_address(client, user_factory):
    """Обычная смена в пределах города: применяется сразу, водителя не спрашиваем."""
    pax = user_factory("DestPax2")
    drv = user_factory("DestDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"], json=_body())
    assert r.status_code == 200, r.text
    assert r.json()["applied"] is True

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.to_lat == pytest.approx(54.80)
        assert order.destination_changes == 1
        assert order.destination_ack_at is None, "подтверждение водителя не должно быть заранее"


def test_a_stranger_cannot_touch_someone_elses_ride(client, user_factory):
    """Чужой заказ — чужой. Иначе адрес можно менять любому, кто угадает номер."""
    pax = user_factory("DestOwner")
    drv = user_factory("DestDrv3", role=UserRole.driver)
    stranger = user_factory("DestStranger")
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination", headers=stranger["auth"], json=_body())
    assert r.status_code == 404


def test_the_driver_cannot_change_the_address_for_the_passenger(client, user_factory):
    """Маршрут выбирает тот, кто едет. Водитель — не он."""
    pax = user_factory("DestPax4")
    drv = user_factory("DestDrv4", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination", headers=drv["auth"], json=_body())
    assert r.status_code == 404


def test_double_tap_is_refused(client, user_factory):
    """Второе нажатие за секунду — это дрожащий палец, а не решение."""
    pax = user_factory("DestPax5")
    drv = user_factory("DestDrv5", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    first = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"], json=_body())
    assert first.status_code == 200
    again = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"], json=_body())
    assert again.status_code == 429


def test_finished_ride_cannot_be_rewritten(client, user_factory):
    """Завершённую поездку задним числом не переписывают."""
    pax = user_factory("DestPax6")
    drv = user_factory("DestDrv6", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"], status=S.done)

    r = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"], json=_body())
    assert r.status_code == 409


# ------------------------------ водитель видел ------------------------------
def test_driver_confirms_he_saw_the_new_address(client, user_factory):
    """«Понял» снимает с пассажира тревогу «а он вообще знает?»."""
    pax = user_factory("AckPax")
    drv = user_factory("AckDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"],
                      destination_changed_at=utcnow() - timedelta(minutes=2))

    r = client.post(f"/instant/orders/{oid}/destination/ack", headers=drv["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["order"]["destination_ack"] is True

    with Session(engine) as s:
        assert s.get(InstantOrder, oid).destination_ack_at is not None


def test_passenger_cannot_confirm_for_the_driver(client, user_factory):
    """Иначе «водитель видел» перестаёт что-либо значить."""
    pax = user_factory("AckPax2")
    drv = user_factory("AckDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination/ack", headers=pax["auth"])
    assert r.status_code == 404


# ------------------------------ «не смогу» ------------------------------
def test_driver_ends_the_ride_early_and_gets_paid(client, user_factory):
    """Водитель сошёл с маршрута: поездка ЗАВЕРШАЕТСЯ, деньги за проеденное причитаются.

    Не отмена. Работа сделана — километры накручены. Назвать это отменой значило бы
    наказать водителя за честное предупреждение вместо высадки посреди дороги.
    """
    pax = user_factory("EarlyPax")
    drv = user_factory("EarlyDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination/decline", headers=drv["auth"],
                    json={"reason": "shift_end"})
    assert r.status_code == 200, r.text
    assert r.json()["finished_early"] is True

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.status == S.done, "поездка должна быть завершена, а не отменена"
        assert order.price_final is not None and order.price_final > 0, (
            "водитель остался без денег за проеденное — так появляется способ возить бесплатно"
        )
        assert order.early_finish_reason == "shift_end"


def test_passenger_cannot_end_the_ride_this_way(client, user_factory):
    """У пассажира для этого есть отмена — со своими правилами и штрафами."""
    pax = user_factory("EarlyPax2")
    drv = user_factory("EarlyDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination/decline", headers=pax["auth"],
                    json={"reason": "other"})
    assert r.status_code == 404


# ------------------------------ крупная смена ждёт водителя ------------------------------
def test_a_jump_to_another_city_waits_for_the_driver(client, user_factory):
    """«Вези в Сибай» — сначала слово водителю: это пять часов и ночёвка в чужом городе."""
    pax = user_factory("BigPax")
    drv = user_factory("BigDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"],
                    json={"to_lat": 52.7160, "to_lng": 58.6630, "to_text": "Сибай"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["applied"] is False
    assert body["waiting_driver"] is True

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.to_lat == pytest.approx(UFA_NEAR[0]), "адрес сменился без согласия водителя"
        assert order.pending_to_lat is not None
        assert order.pending_reason == "zone"


def test_driver_accepts_the_big_change(client, user_factory):
    """Согласился — адрес меняется, и подтверждать «видел» отдельно уже не надо."""
    pax = user_factory("BigPax2")
    drv = user_factory("BigDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])
    client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"],
                json={"to_lat": 52.7160, "to_lng": 58.6630, "to_text": "Сибай"})

    r = client.post(f"/instant/orders/{oid}/destination/accept", headers=drv["auth"])
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.to_lat == pytest.approx(52.7160)
        assert order.pending_to_lat is None
        assert order.destination_ack_at is not None


def test_driver_refuses_the_big_change_and_the_ride_goes_on(client, user_factory):
    """Отказ от НОВОГО маршрута не рвёт тот, на который человек соглашался."""
    pax = user_factory("BigPax3")
    drv = user_factory("BigDrv3", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])
    client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"],
                json={"to_lat": 52.7160, "to_lng": 58.6630, "to_text": "Сибай"})

    r = client.post(f"/instant/orders/{oid}/destination/decline", headers=drv["auth"],
                    json={"reason": "out_of_zone"})
    assert r.status_code == 200, r.text
    assert r.json()["kept_old_destination"] is True

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert order.status == S.onboard, "поездку прервали, хотя отказались лишь от нового адреса"
        assert order.to_lat == pytest.approx(UFA_NEAR[0])
        assert order.pending_to_lat is None


def test_nothing_to_accept_is_a_clear_error(client, user_factory):
    """Подтверждать нечего — говорим прямо, а не делаем вид, что сработало."""
    pax = user_factory("BigPax4")
    drv = user_factory("BigDrv4", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/destination/accept", headers=drv["auth"])
    assert r.status_code == 409
