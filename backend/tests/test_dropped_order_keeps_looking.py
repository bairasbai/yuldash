# -*- coding: utf-8 -*-
"""Брошенный водителем заказ ищет машину дальше, а не умирает (2026-08-29).

БЫЛО. Водитель принимал заказ и отменял — заказ уходил в `cancelled` навсегда. Пассажиру
при этом приходил бодрый пуш «Водитель отменил заказ. Ищем другого?», а экран приложения
отвечал прямо противоположное: «Попробуй заказать снова». Никто ничего не искал. Женщина
с ребёнком у подъезда в мороз вбивала адреса заново, теряя и зафиксированную цену, и очередь.
Обещание в интерфейсе и поведение кода разъехались — та же болезнь, что уже в `lessons.md`.

СТАЛО. Заказ сам возвращается в поиск: те же адреса, тот же класс, та же цена, тот же
промокод. Бросивший попадает в «уже предлагали», чтобы круг не вернул заказ ему же.

ГЛАВНАЯ ЛОВУШКА, которую здесь стерегут. Наказание за брошенные заказы (пауза офферов)
читалось прямо с заказа: `status == cancelled AND cancel_by == 'driver'`. У переназначенного
заказа эти поля перезаписывает СЛЕДУЮЩИЙ водитель — почини мы переназначение наивно, и
страйки исчезли бы: бросай сколько хочешь. Поэтому поступок пишется отдельным событием
(`DriverCancel`), и тест ниже это стережёт.

Три границы переназначения — каждая про человека, а не про технику:
  • из `onboard` не возвращаем: пассажир уже в машине, «тот же заказ» от старой точки А — ложь;
  • `no_show` не возвращаем: пассажира на месте нет, следующий приедет к пустому подъезду;
  • не больше `taxi_reassign_limit` кругов: заказ, который перекидывают, честнее закрыть.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import DriverCancel, InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _taxi_on():
    prev = settings.taxi_enabled
    settings.taxi_enabled = True
    yield
    settings.taxi_enabled = prev


from test_money_rules import (  # noqa: E402,F401 — общие помощники живого прогона
    fake_redis, _db_order, _driver_online, _heartbeat, _order_body, _shift,
)


def _accepted(client, user_factory, dname, pname, **extra):
    """Заказ принят живым водителем: полный путь, без посева в обход матчера."""
    d = _driver_online(client, user_factory, dname)
    _heartbeat(client, d)
    pax = user_factory(pname)
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(**extra)).json()
    assert order["status"] == "offered", order
    r = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
    assert r.status_code == 200, r.text
    return d, pax, order["id"]


def _отменил_водитель(client, d, oid, reason=""):
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"],
                    json={"reason": reason} if reason else {})
    assert r.status_code == 200, r.text
    return r.json()


def _события(driver_id: int) -> list:
    with Session(engine) as s:
        return list(s.exec(select(DriverCancel).where(DriverCancel.driver_id == driver_id)).all())


# ==================== 1. Заказ не умирает ====================
def test_a_dropped_order_goes_to_the_next_driver(client, user_factory, fake_redis):
    """Водитель бросил принятый заказ — заказ уходит следующему, а не закрывается.

    Второй водитель на линии здесь обязателен: это и есть проверяемый сценарий. Без него
    тест доказывал бы только «заказ не похоронили», но не «человек получил машину».
    """
    d, pax, oid = _accepted(client, user_factory, "БросилВодитель", "ЖдётПассажир")
    сосед = _driver_online(client, user_factory, "СоседнийВодитель")
    _heartbeat(client, сосед)

    _отменил_водитель(client, d, oid)

    o = _db_order(oid)
    assert o.status != S.cancelled, "заказ похоронили — человек начинает всё заново"
    assert o.status == S.offered, f"заказ не предложили следующему: {o.status}"
    assert o.current_offer_driver_id == сосед["id"], "предложили не тому, кто рядом"
    assert o.driver_id is None, "бросивший всё ещё числится водителем заказа"
    assert o.reassigns == 1


def test_with_nobody_left_the_order_waits_instead_of_dying(client, user_factory, fake_redis):
    """Свободных машин больше нет — заказ уходит в ожидание, а не в «отменён».

    Разница для человека огромная: «ищем дальше» оставляет ему очередь и цену, «отменён»
    отправляет вбивать адреса заново. Дальше работает обычная очередь «подожду машину».
    """
    d, pax, oid = _accepted(client, user_factory, "ЕдинственныйВод", "ОдинокийПассажир")
    _отменил_водитель(client, d, oid)

    o = _db_order(oid)
    assert o.status != S.cancelled, "единственная машина отменила — и заказ похоронили"
    assert o.status in (S.searching, S.offered, S.expired), o.status
    assert o.reassigns == 1


def test_the_passenger_keeps_his_price_and_his_route(client, user_factory, fake_redis):
    """Возвращаем ТОТ ЖЕ заказ: адреса, класс и цена не меняются на ровном месте."""
    d, pax, oid = _accepted(client, user_factory, "БросилЦена", "ЦенаПассажир")
    было = _db_order(oid)
    цена, откуда, куда, класс = (было.price_estimate, было.from_lat, было.to_lat, было.category)

    _отменил_водитель(client, d, oid)

    стало = _db_order(oid)
    assert стало.price_estimate == цена, "цена уехала, хотя заказ тот же"
    assert (стало.from_lat, стало.to_lat) == (откуда, куда)
    assert стало.category == класс


def test_the_one_who_dropped_it_is_not_offered_it_again(client, user_factory, fake_redis):
    """Бросивший не получает этот же заказ обратно через минуту.

    Круг подбора начинается заново, и без пометки «этому уже предлагали» заказ вернулся бы
    ровно тому, кто от него только что отказался, — а пассажир увидел бы ту же машину.
    """
    d, pax, oid = _accepted(client, user_factory, "БросилИЖдёт", "ОпятьПассажир")
    сосед = _driver_online(client, user_factory, "ЗапаснойВодитель")
    _heartbeat(client, сосед)

    _отменил_водитель(client, d, oid)

    o = _db_order(oid)
    assert o.current_offer_driver_id != d["id"], "заказ вернулся тому, кто его бросил"
    assert int(d["id"]) in isv._tried_set(isv._redis(), oid)


# ==================== 2. Наказание не потерялось ====================
def test_the_strike_survives_the_reassignment(client, user_factory, fake_redis):
    """Главное: заказ ушёл дальше, а поступок водителя записан.

    Наивная починка стёрла бы след — `driver_id` и `cancelled_at` на заказе перезапишет
    следующий водитель, — и бросать заказы стало бы бесплатно.
    """
    d, pax, oid = _accepted(client, user_factory, "СтрайкВодитель", "СтрайкПассажир")
    _отменил_водитель(client, d, oid)

    события = _события(d["id"])
    assert len(события) == 1, "поступок не записан — наказание потерялось"
    assert события[0].order_id == oid and события[0].no_show is False

    with Session(engine) as s:
        strikes = isv.driver_cancel_times(s, d["id"], utcnow() - timedelta(days=7))
    assert len(strikes) == 1, "счётчик страйков не видит брошенный заказ"


def test_repeated_drops_still_pause_the_driver(client, user_factory, fake_redis):
    """Несколько брошенных заказов подряд по-прежнему ставят офферы на паузу."""
    d = _driver_online(client, user_factory, "ПаузаПослеБросков")
    _heartbeat(client, d)
    for i in range(int(settings.driver_cancel_limit)):
        pax = user_factory(f"ПаузаПас{i}")
        order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
        if order.get("status") != "offered":
            continue
        client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
        client.post(f"/instant/orders/{order['id']}/cancel", headers=d["auth"], json={})

    with Session(engine) as s:
        assert isv.driver_pause_until(s, d["id"]) is not None, (
            "водитель бросил лимит заказов и не получил паузу"
        )


# ==================== 3. Границы ====================
def test_a_passenger_already_in_the_car_is_not_re_searched(client, user_factory, fake_redis):
    """Из «пассажир в машине» заказ не возвращаем: половина дороги позади.

    Такое высаживание посреди пути — отдельный разговор со своей ценой, а не работа матчера:
    «тот же заказ» от старой точки А был бы неправдой.
    """
    d, pax, oid = _accepted(client, user_factory, "ВысадилВодитель", "ВысаженПассажир")
    for path in ("arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"]).status_code == 200

    _отменил_водитель(client, d, oid)
    o = _db_order(oid)
    assert o.status == S.cancelled, "заказ ушёл в поиск, хотя пассажир был в машине"
    assert o.reassigns == 0


def test_no_show_does_not_send_anyone_to_an_empty_porch(client, user_factory, fake_redis):
    """«Пассажир не вышел» — не переназначаем: следующий приехал бы к пустому подъезду."""
    d, pax, oid = _accepted(client, user_factory, "НеВышелВодитель", "НеВышелПассажир")
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).status_code == 200
    # Отматываем ожидание назад, чтобы «пассажир не вышел» стал доступен по таймингу.
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=30),
           arriving_at=utcnow() - timedelta(minutes=30))

    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"],
                    json={"reason": isv.NO_SHOW_REASON})
    assert r.status_code == 200, r.text

    o = _db_order(oid)
    assert o.status == S.cancelled and o.no_show is True
    assert o.reassigns == 0, "поехали искать машину человеку, которого нет на месте"
    # И это по-прежнему НЕ страйк водителю: он всё сделал правильно.
    with Session(engine) as s:
        assert isv.driver_cancel_times(s, d["id"], utcnow() - timedelta(days=7)) == []


def test_an_order_does_not_bounce_forever(client, user_factory, fake_redis):
    """Круги переназначения кончаются: заказ честнее закрыть, чем держать в поиске час."""
    d, pax, oid = _accepted(client, user_factory, "КругПервый", "КругПассажир")
    _shift(oid, reassigns=int(settings.taxi_reassign_limit))

    _отменил_водитель(client, d, oid)
    o = _db_order(oid)
    assert o.status == S.cancelled, "заказ скачет по кругу сверх предела"
    assert o.reassigns == int(settings.taxi_reassign_limit)


def test_a_passenger_cancel_is_still_just_a_cancel(client, user_factory, fake_redis):
    """Пассажир отменил сам — никакого поиска, заказ закрыт. Не сломали обычный путь."""
    d, pax, oid = _accepted(client, user_factory, "ПассОтменилВод", "ПассОтменилСам")
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={})
    assert r.status_code == 200, r.text

    o = _db_order(oid)
    assert o.status == S.cancelled and o.reassigns == 0
    assert _события(d["id"]) == [], "пассажирскую отмену записали как поступок водителя"
