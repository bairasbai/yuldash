"""Отмена доставки оплачивает курьеру его дорогу и его ожидание (аудит 2026-08-29).

У такси это починили волной 28.08: отмена = подача + дорога водителя + ожидание, с потолком,
привязанным к строке, которую человек видел ДО заказа. У курьера осталось плоское число:
100 ₽ независимо ни от чего.

Дыра слово в слово та же. Курьер из соседнего города выезжает за посылкой (строка «дорога
курьера» уже зафиксирована и показана отправителю), отправитель передумывает у двери — и за
70 км порожняка курьер получает сто рублей. Плюс ожидание: он отстоял двадцать минут ровно
так же, как отстоял бы в доставке, а при отмене это время исчезало.

Здесь проверяем то, ради чего это делалось:
  • дорога и ожидание попадают в компенсацию, а не пропадают;
  • сумма, которую показали ДО нажатия «Отменить», равна той, что зафиксировали ПОСЛЕ;
  • разбор по строкам приходит на экран — одно число читается как «обобрали»;
  • потолок накрывает компенсации, а не съедает курьеру его же бензин;
  • после отмены счётчик ожидания закрыт и сумма не растёт сама по себе.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import CourierProfile, ParcelDelivery
from app.routers import courier as cr
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


from test_courier_c4 import _make_courier, _order  # noqa: F401 — общие помощники
from test_courier_catches_up import _далёкий_город, _куда, _посылка  # noqa: F401


def _ждал(pid: int, минут: int) -> None:
    """Курьер стоит на месте столько-то минут — отматываем начало ожидания назад."""
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.waiting_started_at = utcnow() - timedelta(minutes=минут)
        s.add(p)
        s.commit()


# ============================ 1. Дорога курьера не пропадает ============================
def test_cancel_pays_the_road_the_courier_already_drove(client, user_factory):
    """Курьер выехал за посылкой из другого города — отмена оплачивает эту дорогу."""
    далеко = _далёкий_город()
    courier = _make_courier(client, user_factory, name="КурьерОтменаДорога")
    _куда(courier["id"], далеко)
    sender = user_factory("ОтпрОтменаДорога")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    дорога = _посылка(pid).pickup_fee_kop
    assert дорога > 0, "дорога курьера не зафиксирована — проверять нечего"

    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    p = _посылка(pid)
    assert p.cancel_fee_kop == settings.courier_cancel_fee_kop + дорога, (
        "дорога курьера пропала при отмене — он проехал её впустую за свой счёт"
    )


def test_city_cancel_stays_the_plain_fine(client, user_factory):
    """Курьер и посылка в одном городе — компенсировать нечего, остаётся прежний штраф."""
    courier = _make_courier(client, user_factory, name="КурьерОтменаГород")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОтменаГород")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert _посылка(pid).cancel_fee_kop == settings.courier_cancel_fee_kop


# ============================ 2. Ожидание не исчезает ============================
def test_cancel_pays_the_waiting_at_the_door(client, user_factory):
    """Курьер стоял у двери и ждал — при отмене это время оплачивается, как у такси."""
    courier = _make_courier(client, user_factory, name="КурьерОтменаЖдал")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОтменаЖдал")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/arrived", headers=courier["auth"]).status_code == 200
    минут = settings.wait_free_minutes + 10
    _ждал(pid, минут)

    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    p = _посылка(pid)
    платных = минут - settings.wait_free_minutes
    ожидание = min(платных * settings.wait_fee_rub_per_min * 100, settings.wait_fee_cap_rub * 100)
    assert p.cancel_fee_kop == settings.courier_cancel_fee_kop + ожидание, (
        "курьер отстоял у двери, а при отмене это время исчезло"
    )


def test_free_minutes_are_free_on_cancel_too(client, user_factory):
    """Внутри бесплатных минут отмена стоит ровно штраф — за них не берём и здесь."""
    courier = _make_courier(client, user_factory, name="КурьерОтменаБесплатно")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОтменаБесплатно")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _ждал(pid, max(settings.wait_free_minutes - 1, 0))
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert _посылка(pid).cancel_fee_kop == settings.courier_cancel_fee_kop


def test_waiting_clock_is_closed_after_cancel(client, user_factory):
    """После отмены счётчик ожидания закрыт: сумма зафиксирована и сама не растёт."""
    courier = _make_courier(client, user_factory, name="КурьерОтменаЧасы")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОтменаЧасы")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _ждал(pid, settings.wait_free_minutes + 5)
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert _посылка(pid).waiting_started_at is None, "часы ожидания остались тикать после отмены"


# ============================ 3. Человек видит то же число ============================
def test_preview_matches_what_is_actually_charged(client, user_factory):
    """Сколько показали до нажатия «Отменить» — столько и зафиксировали после."""
    далеко = _далёкий_город()
    courier = _make_courier(client, user_factory, name="КурьерОтменаПоказ")
    _куда(courier["id"], далеко)
    sender = user_factory("ОтпрОтменаПоказ")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])

    показали = None
    r = client.get("/parcels/mine", headers=sender["auth"])
    assert r.status_code == 200, r.text
    for item in (r.json() if isinstance(r.json(), list) else r.json().get("items", [])):
        if item.get("id") == pid:
            показали = item.get("cancel_fee_preview_kop")
            части = item.get("cancel_fee_parts")
    assert показали is not None and показали > settings.courier_cancel_fee_kop, (
        "предпросмотр отмены не показывает дорогу курьера"
    )
    assert части and части["pickup_kop"] > 0, "разбор отмены не пришёл на экран"
    assert части["total_kop"] == показали

    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert _посылка(pid).cancel_fee_kop == показали, (
        "человеку показали одно число, а списали другое"
    )


def test_parts_add_up_to_the_total(client, user_factory):
    """Разбор — это та же сумма, а не «примерно»: штраф + дорога + ожидание."""
    courier = _make_courier(client, user_factory, name="КурьерОтменаЧасти")
    _куда(courier["id"], "Уфа")
    sender = user_factory("ОтпрОтменаЧасти")
    pid = _order(client, sender, from_lat=54.735, from_lng=55.958,
                 to_lat=54.750, to_lng=55.970).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/arrived", headers=courier["auth"])
    _ждал(pid, settings.wait_free_minutes + 6)
    части = cr.courier_cancel_fee_parts_kop(_посылка(pid))
    assert части["base_kop"] == settings.courier_cancel_fee_kop
    assert части["waiting_kop"] > 0
    assert части["total_kop"] == части["base_kop"] + части["pickup_kop"] + части["waiting_kop"]


# ============================ 4. Потолки ============================
def test_fixed_road_is_never_trimmed_afterwards(client, user_factory):
    """Зафиксированную дорогу отмена не режет: её потолок уже применён при начислении.

    Курьер из соседнего города везёт подачу по МЕЖГОРОДСКОМУ потолку (900 ₽), а сама
    доставка при этом городская. Накрой мы отмену ещё одним потолком «по зоне доставки»,
    курьер получил бы 300 ₽ вместо своих 900 — за уже проеханные километры.
    """
    class _Посылка:
        from_lat = from_lng = to_lat = to_lng = None
        pickup_fee_kop = settings.courier_pickup_max_intercity_kop
        waiting_sender_kop = waiting_receiver_kop = 0
        waiting_started_at = None

    части = cr.courier_cancel_fee_parts_kop(_Посылка())
    assert части["total_kop"] == (settings.courier_cancel_fee_kop
                                  + settings.courier_pickup_max_intercity_kop)


def test_waiting_keeps_its_own_cap(client, user_factory):
    """Ожидание при отмене ограничено тем же потолком, что и в доставке."""
    class _Посылка:
        from_lat = from_lng = to_lat = to_lng = None
        pickup_fee_kop = 0
        waiting_sender_kop = settings.wait_fee_cap_rub * 100 * 3   # «накопилось» втрое больше
        waiting_receiver_kop = 0
        waiting_started_at = None

    части = cr.courier_cancel_fee_parts_kop(_Посылка())
    assert части["waiting_kop"] == settings.wait_fee_cap_rub * 100


def test_no_courier_no_fee(client, user_factory):
    """Курьера ещё нет — отмена бесплатна, компенсировать некому."""
    sender = user_factory("ОтпрОтменаБезКурьера")
    pid = _order(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert _посылка(pid).cancel_fee_kop == 0
