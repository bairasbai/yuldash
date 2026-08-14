"""Рейс в четыре утра принадлежит тому дню, когда он выезжает.

История. Из района в Уфу выезжают затемно — в четыре, в пять утра, чтобы успеть к открытию
больницы, МФЦ, суда. Это самый обычный сельский рейс, а не редкость.

Ильдар публикует такой рейс на 15 августа, 4:00. В базе всё хранится в мировом времени,
где это ещё 14 августа, 23:00. Дальше начиналось вот что (аудит 2026-08-08, волна 79):

* пассажирка выбирает в календаре 15 августа — и не находит рейс. Выберет 14-е — найдёт,
  но кто станет искать поездку «на вчера»;
* тот, кто подписался «карауль Баймак → Сибай на 15-е», не получает оповещения вовсе.

Для водителя это выглядит как «объявление висит, а заявок нет». Для пассажира — «никто
не едет». Оба уверены, что виноват спрос, а виноват часовой пояс.

Границы суток и сравнение дней теперь считаются по местному календарю (Уфа), как это давно
сделано в долгах, дневной сводке и учёте смены.
"""
from __future__ import annotations

import pytest

from app.models import UserRole

EARLY = "2030-08-15T04:00:00+05:00"    # 15 августа, 4 утра по Уфе → в базе 14-е, 23:00 UTC
MIDDAY = "2030-08-15T14:00:00+05:00"   # тот же день, но никуда не смещается
LATE = "2030-08-15T23:30:00+05:00"     # поздний вечер: в базе уже 15-е, 18:30 UTC


@pytest.fixture
def caught_pushes(monkeypatch):
    import app.services as svc
    sent: list[int] = []
    monkeypatch.setattr(svc, "send_push", lambda s, uid, t, b, data=None: sent.append(uid))
    return sent


def _ride(client, drv, depart_at: str, comment: str) -> int:
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart_at,
        "seats_total": 3, "price": 300, "comment": comment,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _found_on(client, day: str) -> list[int]:
    body = client.get(f"/rides?date={day}").json()
    items = body if isinstance(body, list) else body.get("items", [])
    return [x["id"] for x in items]


def test_ранний_рейс_находится_в_свой_день(client, user_factory):
    ildar = user_factory("РаннийИльдар", role=UserRole.driver)
    early = _ride(client, ildar, EARLY, "в четыре утра")

    assert early in _found_on(client, "2030-08-15"), \
        "рейс в четыре утра не виден тем, кто ищет на этот день"
    assert early not in _found_on(client, "2030-08-14"), \
        "рейс показался в чужом дне — человек поедет не тогда, когда думает"


def test_дневной_и_поздний_рейсы_остались_на_месте(client, user_factory):
    """Обратная сторона: сдвиг границ не должен утащить обычные рейсы в соседние сутки."""
    driver = user_factory("ДневнойРинат", role=UserRole.driver)
    midday = _ride(client, driver, MIDDAY, "днём")
    late = _ride(client, driver, LATE, "поздно вечером")

    on_15 = _found_on(client, "2030-08-15")
    assert midday in on_15 and late in on_15, "обычные рейсы выпали из своего дня"
    assert midday not in _found_on(client, "2030-08-16"), "дневной рейс уехал в завтра"


def test_караул_на_день_ловит_ранний_рейс(client, user_factory, caught_pushes):
    watcher = user_factory("КараулитЗухра")
    r = client.post("/route-watch", headers=watcher["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "direction": "forward",
        "watch_kind": "rides", "watch_date": "2030-08-15T00:00:00+05:00",
    })
    assert r.status_code == 200, r.text

    _ride(client, user_factory("КараулВодитель", role=UserRole.driver), EARLY, "ранний под караул")

    assert watcher["id"] in caught_pushes, \
        "подписка на 15-е пропустила рейс, который выезжает 15-го в четыре утра"


def test_караул_не_срабатывает_на_чужой_день(client, user_factory, caught_pushes):
    """Сторож на перегиб: сдвинув сутки, легко начать слать оповещения за соседние дни."""
    watcher = user_factory("КараулитАйгуль")
    r = client.post("/route-watch", headers=watcher["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "direction": "forward",
        "watch_kind": "rides", "watch_date": "2030-08-15T00:00:00+05:00",
    })
    assert r.status_code == 200, r.text

    _ride(client, user_factory("ЧужойДеньВодитель", role=UserRole.driver),
          "2030-08-16T09:00:00+05:00", "рейс следующего дня")
    # И накануне утром: если сдвиг суток сделать больше, чем есть на самом деле, именно этот
    # рейс переползёт в чужой день и оповещение придёт зря.
    _ride(client, user_factory("НаканунеВодитель", role=UserRole.driver),
          "2030-08-14T09:00:00+05:00", "рейс накануне утром")

    assert watcher["id"] not in caught_pushes, "пришло оповещение про поездку не в тот день"
