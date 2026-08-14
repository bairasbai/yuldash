"""Караул маршрута слышит оба написания города.

История. Зухра подписалась: «скажи, когда кто-нибудь поедет Стерлитамак → Учалы». Ильдар
публикует такой рейс, но у него приложение на башкирском, и город уходит как «Стәрлетамаҡ».
Оповещение не приходило (аудит 2026-08-08, волна 93).

Это тише и обиднее, чем поломка поиска из волны 92: там человек хотя бы видит пустой экран
и может поискать иначе. Здесь он просто ждёт и делает вывод, что по его маршруту никто
не ездит, — а рейсы идут каждый день.

Свёртки букв тут мало. Она сводит «Баймаҡ» и «Баймак», но «Стәрлетамаҡ» и «Стерлитамак»
отличаются ещё и гласными — сравнивать надо через справочник населённых пунктов.

Проверяем обе стороны (подписка на русском ↔ рейс на башкирском и наоборот), заявки
пассажиров тем же правилом и границу: чужой маршрут оповещения не вызывает.
"""
from __future__ import annotations

import pytest

from app.models import UserRole

BASHKIR = "Стәрлетамаҡ"
RUSSIAN = "Стерлитамак"
TO_CITY = "Учалы"


@pytest.fixture
def caught_pushes(monkeypatch):
    import app.services as svc
    sent: list[int] = []
    monkeypatch.setattr(svc, "send_push", lambda s, uid, t, b, data=None: sent.append(uid))
    return sent


def _watch(client, who, from_city: str, kind: str = "rides", to_city: str = TO_CITY):
    r = client.post("/route-watch", headers=who["auth"], json={
        "from_city": from_city, "to_city": to_city, "direction": "forward", "watch_kind": kind,
    })
    assert r.status_code == 200, r.text


def _ride(client, driver, from_city: str, day: str, to_city: str = TO_CITY):
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": from_city, "to_city": to_city,
        "depart_at": f"{day}T10:00:00", "seats_total": 3, "price": 300,
    })
    assert r.status_code == 200, r.text


def test_подписка_на_русском_слышит_башкирский_рейс(client, user_factory, caught_pushes):
    zuhra = user_factory("КараулЗухра")
    _watch(client, zuhra, RUSSIAN)

    _ride(client, user_factory("БашкирВодитель", role=UserRole.driver), BASHKIR, "2030-07-01")

    assert zuhra["id"] in caught_pushes, \
        "подписка на Стерлитамак не услышала рейс, опубликованный по-башкирски"


def test_подписка_на_башкирском_слышит_русский_рейс(client, user_factory, caught_pushes):
    aigul = user_factory("КараулАйгуль")
    _watch(client, aigul, BASHKIR)

    _ride(client, user_factory("РусскийВодитель", role=UserRole.driver), RUSSIAN, "2030-07-02")

    assert aigul["id"] in caught_pushes, \
        "подписка по-башкирски не услышала рейс, опубликованный по-русски"


def test_заявки_пассажиров_тоже_доходят(client, user_factory, caught_pushes):
    """Вторая половина сделки: водитель караулит пассажиров на своём направлении."""
    driver = user_factory("КараулВодитель", role=UserRole.driver)
    _watch(client, driver, RUSSIAN, kind="requests")

    passenger = user_factory("ЗаявкаБашкирАйгуль")
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": BASHKIR, "to_city": TO_CITY,
        "depart_at": "2030-07-03T10:00:00", "seats": 1,
    })
    assert r.status_code == 200, r.text

    assert driver["id"] in caught_pushes, \
        "водитель не узнал о пассажире, который написал город по-башкирски"


def test_чужой_маршрут_не_будит(client, user_factory, caught_pushes):
    """Сторож на перегиб: сведя написания, легко начать слать оповещения по всем городам."""
    watcher = user_factory("ЧужойМаршрутЗухра")
    _watch(client, watcher, RUSSIAN)

    _ride(client, user_factory("ДругойГородВодитель", role=UserRole.driver),
          "Сибай", "2030-07-04", to_city="Баймак")

    assert watcher["id"] not in caught_pushes, "оповещение пришло по совершенно другому маршруту"
