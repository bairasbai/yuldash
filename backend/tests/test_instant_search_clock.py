# -*- coding: utf-8 -*-
"""Часы поиска в заказе такси: created_at и searching_at.

Зачем: экран «ищем машину» показывает «ищем уже 3:20». Свой таймер на клиенте врёт —
свернул приложение, вернулся, и отсчёт начался заново. Честная цифра считается от меток
сервера, поэтому они обязаны быть в каждом ответе про заказ.

Правила, которые тут закреплены:
* created_at — момент, когда человек нажал «Заказать». Перезапуск поиска из очереди
  «рядом никого» его НЕ сдвигает: пассажир ждёт с самого начала.
* searching_at — начало текущего круга подбора. У предзаказа «на время» это активация,
  а не бронирование (иначе на экране будет «ищем уже 2 дня»).
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app import instant_service as isv
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _create(client, pax, **extra):
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body(**extra))
    assert r.status_code == 200, r.text
    return r.json()


def test_payload_has_both_clocks(client, user_factory):
    pax = user_factory("ClockPax")
    o = _create(client, pax)
    assert o["created_at"], "без created_at экран поиска не может честно считать время"
    assert o["searching_at"], "обычный заказ уходит в поиск сразу — метка должна быть"


def test_get_order_keeps_clocks(client, user_factory):
    """Клиент опрашивает заказ, а не только создаёт: метки нужны в КАЖДОМ ответе."""
    pax = user_factory("ClockGet")
    o = _create(client, pax)
    got = client.get(f"/instant/orders/{o['id']}", headers=pax["auth"])
    assert got.status_code == 200, got.text
    assert got.json()["created_at"] == o["created_at"]


def test_created_at_survives_search_restart(client, user_factory):
    """Очередь «рядом никого»: воркер перезапускает подбор. Пассажир ждёт с самого начала,
    поэтому created_at обязан остаться прежним, а searching_at — сдвинуться."""
    pax = user_factory("ClockRetry")
    o = _create(client, pax)
    with Session(engine) as s:
        order = s.get(InstantOrder, o["id"])
        # Отматываем метки назад — как будто заказ висит уже пять минут.
        order.created_at = utcnow() - timedelta(minutes=5)
        order.searching_at = order.created_at
        order.status = S.searching
        s.add(order)
        s.commit()
        isv.start_matching(s, s.get(InstantOrder, o["id"]), notify=False)

    again = client.get(f"/instant/orders/{o['id']}", headers=pax["auth"]).json()
    assert again["created_at"] == o["created_at"] or again["created_at"] < again["searching_at"], (
        "created_at сдвинулся при перезапуске поиска — счётчик ожидания обнулится и совравет"
    )
    assert again["searching_at"] > again["created_at"], "новый круг подбора не отметился"


def test_scheduled_has_no_search_clock_until_activation(client, user_factory):
    """Предзаказ «на время» ещё не ищет: searching_at пуст, иначе экран покажет «ищем уже 2 дня»."""
    pax = user_factory("ClockSched")
    when = utcnow() + timedelta(hours=3)
    r = client.post("/instant/schedule", headers=pax["auth"], json=_order_body(scheduled_at=when.isoformat()))
    assert r.status_code == 200, r.text
    o = r.json()
    assert o["status"] == "scheduled"
    assert o["created_at"], "момент бронирования всё равно известен"
    assert o["searching_at"] is None, "поиск ещё не начинался — метки быть не должно"
