"""Возврат «получателя не было» оплачивает курьеру дорогу (решение Александра, 2026-08-29).

Последнее место, где такси и курьер считали деньги по-разному. Пассажир не вышел — водитель
получает подачу, дорогу и ожидание. Получатель не открыл дверь — курьер вёз коробку обратно
и получал НОЛЬ: Сибай → Акъяр это 180 км туда-обратно за свой счёт и полдня времени.

Правило: платим за то, что действительно случилось. Курьер съездил — дорога оплачена; коробку
не вручили — за саму доставку не берём. И платим только по отмеченной попытке вручения:
иначе появилось бы «съездил, никого не было, поверьте на слово».

Здесь проверяем:
  • попытка была → отправитель должен курьеру километры маршрута, его подачу и ожидание;
  • попыток не было → не должен ничего;
  • подача, размер, срочность и ночь в компенсацию НЕ входят — это цена услуги, которой не было;
  • комиссию с возврата мы по-прежнему не берём;
  • сумма фиксируется при закрытии и дальше сама не растёт.
"""
import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery
from app.routers import courier as cr


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


from test_courier_c4 import _make_courier, _order  # noqa: F401 — общие помощники
from test_courier_catches_up import _посылка  # noqa: F401


def _до_двери(client, courier, sender, **ov):
    """Заказ принят, курьер в пути. Возвращает id посылки."""
    pid = _order(client, sender, **ov).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    return pid


def _вернуть(client, courier, pid):
    assert client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                       json={"reason": "Получателя нет дома"}).status_code == 200
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200


# ============================ 1. Попытка была — дорога оплачена ============================
def test_return_after_a_failed_attempt_pays_the_kilometres(client, user_factory):
    """Курьер приехал, никого не было, отвёз обратно — километры маршрута ему компенсируют."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратДорога")
    sender = user_factory("ОтпрВозвратДорога")
    pid = _до_двери(client, courier, sender)
    assert client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                       json={"reason": "Никого нет дома"}).status_code == 200
    _вернуть(client, courier, pid)

    p = _посылка(pid)
    assert p.status == "returned"
    assert p.return_fee_kop > 0, "курьер съездил впустую и не получил ничего"
    ожидаем = cr.courier_return_fee_parts_kop(p)
    assert p.return_fee_kop == ожидаем["total_kop"]
    assert ожидаем["route_kop"] > 0, "километры маршрута не посчитаны"


def test_no_attempt_no_money(client, user_factory):
    """Курьер повёз обратно, не отметив ни одной попытки — не платим.

    Иначе появится «съездил, никого не было, поверьте на слово», а проверить это нечем.
    """
    courier = _make_courier(client, user_factory, name="КурьерБезПопыток")
    sender = user_factory("ОтпрБезПопыток")
    pid = _до_двери(client, courier, sender)
    _вернуть(client, courier, pid)
    assert _посылка(pid).return_fee_kop == 0


# ============================ 2. За доставку не берём ============================
def test_return_costs_less_than_the_delivery(client, user_factory):
    """Компенсация — это дорога, а не цена услуги: подача, размер и срочность в неё не входят."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратМеньше")
    sender = user_factory("ОтпрВозвратМеньше")
    pid = _до_двери(client, courier, sender, size="large", urgency="now")
    client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"], json={"reason": "нет дома"})
    _вернуть(client, courier, pid)

    p = _посылка(pid)
    assert 0 < p.return_fee_kop < p.delivery_price_kop, (
        "возврат стоит как полная доставка — значит берём за услугу, которой не было"
    )
    части = cr.courier_return_fee_parts_kop(p)
    assert части["total_kop"] == части["route_kop"] + части["pickup_kop"] + части["waiting_kop"]
    # Подача (courier_base_kop), размер и срочность в компенсацию не попали
    assert части["total_kop"] < p.delivery_price_kop - settings.courier_base_kop + 1


def test_commission_is_still_zero_on_return(client, user_factory):
    """Комиссию с возврата мы по-прежнему не берём — услуга не оказана."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратКомиссия")
    sender = user_factory("ОтпрВозвратКомиссия")
    pid = _до_двери(client, courier, sender)
    client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"], json={"reason": "нет дома"})
    _вернуть(client, courier, pid)
    p = _посылка(pid)
    assert p.commission_kop == 0 and p.commission_paid is True


# ============================ 3. Сумма не растёт после закрытия ============================
def test_amount_is_frozen_when_the_case_is_closed(client, user_factory):
    """Закрытое дело не меняет сумму само по себе: часы ожидания остановлены."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратЧасы")
    sender = user_factory("ОтпрВозвратЧасы")
    pid = _до_двери(client, courier, sender)
    client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"], json={"reason": "нет дома"})
    assert client.post(f"/parcels/{pid}/arrived", headers=courier["auth"]).status_code == 200
    _вернуть(client, courier, pid)

    p = _посылка(pid)
    было = p.return_fee_kop
    assert p.waiting_started_at is None, "часы ожидания остались тикать после закрытия"
    assert cr.courier_return_fee_parts_kop(p)["waiting_kop"] == cr.courier_waiting_total_kop(p)
    assert _посылка(pid).return_fee_kop == было


# ============================ 4. Долг отправителя и чек ============================
def test_sender_owes_the_courier_after_return(client, user_factory):
    """Сумма попадает в «сколько отправитель должен курьеру» и в квитанцию."""
    from app.routers.parcels import owed_to_courier_kop

    courier = _make_courier(client, user_factory, name="КурьерВозвратДолг")
    sender = user_factory("ОтпрВозвратДолг")
    pid = _до_двери(client, courier, sender)
    client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"], json={"reason": "нет дома"})
    _вернуть(client, courier, pid)

    p = _посылка(pid)
    with Session(engine) as s:
        assert owed_to_courier_kop(s.get(ParcelDelivery, pid)) == p.return_fee_kop

    r = client.get(f"/parcels/{pid}/receipt", headers=sender["auth"])
    assert r.status_code == 200, r.text
    чек = r.json()
    assert чек["return_fee_kop"] == p.return_fee_kop
    assert чек["owed_to_courier_kop"] == p.return_fee_kop
    assert чек["total_kop"] == p.return_fee_kop, "в чеке возврата должна стоять дорога, а не ноль"


def test_courier_sees_the_amount_before_returning(client, user_factory):
    """Курьер видит сумму ДО возврата — иначе он читает возврат как «полдня впустую»."""
    courier = _make_courier(client, user_factory, name="КурьерВозвратПоказ")
    sender = user_factory("ОтпрВозвратПоказ")
    pid = _до_двери(client, courier, sender)
    client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"], json={"reason": "нет дома"})

    r = client.get("/parcels/carrying", headers=courier["auth"])
    assert r.status_code == 200, r.text
    строки = r.json() if isinstance(r.json(), list) else r.json().get("items", [])
    моя = next((x for x in строки if x.get("id") == pid), None)
    assert моя is not None, "посылки нет в списке «везу»"
    части = моя.get("return_fee_parts")
    assert части and части["total_kop"] > 0, "курьеру не показали, что дорога будет оплачена"
    assert части["attempts"] >= 1

    _вернуть(client, courier, pid)
    assert _посылка(pid).return_fee_kop == части["total_kop"], (
        "показали одно число, зафиксировали другое"
    )


# ============================ 5. Правило километров ============================
def test_intercity_kilometre_is_the_cheaper_one(client, user_factory):
    """Дальний перегон считается по межгородскому километру — как и в самой доставке."""
    class _Далеко:
        delivery_attempts = 1
        distance_km = float(settings.instant_intercity_km) + 50
        pickup_fee_kop = 0
        waiting_sender_kop = waiting_receiver_kop = 0
        waiting_started_at = None

    части = cr.courier_return_fee_parts_kop(_Далеко())
    assert части["route_kop"] == round(_Далеко.distance_km * settings.courier_per_km_intercity_kop)
