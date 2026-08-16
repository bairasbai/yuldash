"""Скидка должна доходить до того, кому её сделали, а история поездок — не быть открытой.

**Скидка.** Ильдар по-соседски снизил цену с 300 до 150 — уже после того, как Гульнара
забронировала место. Пассажирке приходил пуш «цена изменена», в объявлении стояло 150,
а в её брони и в квитанции — по-прежнему 300 (аудит 2026-08-08, волна 119). У подъезда спор,
и единственная запись, заведённая ради таких споров, показывает старую цифру.

**История.** Номера поездок идут подряд. Посторонний без входа перебирал их и получал все
отменённые и завершённые рейсы человека за месяцы: дата, час, маршрут, комментарий вроде
«забираю у школы», имя, машина. Живую поездку по номеру видеть нужно — это витрина, по ней
и находят попутку. А историю — только тем, кто в ней ехал.

Проверено пробой: аноним запрашивал отменённую поездку и получал 200 со всеми полями.
"""
from __future__ import annotations

import pytest

from app.models import UserRole

from test_api import _ride


@pytest.fixture
def бронь(client, user_factory):
    """Гульнара забронировала место у Ильдара за 300 ₽."""
    ильдар = user_factory("ЦенаИльдар", role=UserRole.driver)
    гульнара = user_factory("ЦенаГульнара")
    ride_id = _ride(client, ильдар, comment="еду утром")
    bid = client.post("/bookings", headers=гульнара["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=ильдар["auth"]).status_code == 200
    return ильдар, гульнара, ride_id, bid


def _цена_в_брони(client, кто, bid) -> int:
    r = client.get(f"/bookings/{bid}/details", headers=кто["auth"])
    assert r.status_code == 200, r.text
    return r.json()["price"]


def test_скидка_доходит_до_пассажира(client, бронь):
    ильдар, гульнара, ride_id, bid = бронь
    было = _цена_в_брони(client, гульнара, bid)

    assert client.post(f"/rides/{ride_id}/edit", headers=ильдар["auth"],
                       json={"price": 150}).status_code == 200

    стало = _цена_в_брони(client, гульнара, bid)
    assert стало < было and стало == 150, (
        f"в объявлении 150, а в брони пассажирки {стало}: у подъезда будет спор, и запись, "
        "заведённая ради таких споров, покажет старую цифру"
    )


def test_скидка_считается_на_все_места(client, user_factory):
    """Двое едут — скидка касается обоих мест, а не одного."""
    ильдар = user_factory("ЦенаИльдар2", role=UserRole.driver)
    семья = user_factory("ЦенаСемья")
    ride_id = _ride(client, ильдар, comment="еду утром")
    bid = client.post("/bookings", headers=семья["auth"],
                      json={"ride_id": ride_id, "seats": 2}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=ильдар["auth"])

    client.post(f"/rides/{ride_id}/edit", headers=ильдар["auth"], json={"price": 150})

    assert _цена_в_брони(client, семья, bid) == 300, "за два места должно стать 150 × 2"


def test_поднять_цену_под_бронью_по_прежнему_нельзя(client, бронь):
    """Обратная сторона: правка цены вниз — подарок, вверх — обман. Планка на месте."""
    ильдар, гульнара, ride_id, bid = бронь

    r = client.post(f"/rides/{ride_id}/edit", headers=ильдар["auth"], json={"price": 900})

    assert r.status_code == 409, "цену подняли под живой бронью"
    assert _цена_в_брони(client, гульнара, bid) == 300, "цена в брони поехала от отклонённой правки"


def test_отменённую_поездку_посторонний_не_прочитает(client, бронь, user_factory):
    ильдар, _, ride_id, _ = бронь
    assert client.post(f"/rides/{ride_id}/cancel", headers=ильдар["auth"]).status_code == 200
    сосед = user_factory("ЦенаСосед")

    без_входа = client.get(f"/rides/{ride_id}")
    посторонний = client.get(f"/rides/{ride_id}", headers=сосед["auth"])

    assert без_входа.status_code == 404, (
        "перебором номеров собирается архив передвижений: даты, часы, маршруты и комментарии "
        "вроде «забираю у школы»"
    )
    assert посторонний.status_code == 404, "вошедший посторонний читает чужую историю"


def test_свою_прошлую_поездку_участники_видят(client, бронь):
    """Обратная сторона важнее: человеку нужны своя история и квитанция."""
    ильдар, гульнара, ride_id, _ = бронь
    client.post(f"/rides/{ride_id}/cancel", headers=ильдар["auth"])

    assert client.get(f"/rides/{ride_id}", headers=ильдар["auth"]).status_code == 200, \
        "водитель потерял доступ к собственной поездке"
    assert client.get(f"/rides/{ride_id}", headers=гульнара["auth"]).status_code == 200, \
        "пассажирка не может открыть поездку, на которую у неё была бронь"


def test_живую_поездку_по_номеру_видят_все(client, бронь):
    """И главное: витрина осталась витриной — иначе ссылка на рейс перестанет работать."""
    _, _, ride_id, _ = бронь

    assert client.get(f"/rides/{ride_id}").status_code == 200
