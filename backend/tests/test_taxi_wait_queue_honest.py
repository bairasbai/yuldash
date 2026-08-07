# -*- coding: utf-8 -*-
"""Очередь «подожду машину» должна быть честной (аудит 2026-08-07).

Что такое эта очередь. Ночью в райцентре на линии два-три водителя, и оба заняты. Заказ
мгновенно уходит в `expired` («рядом никого»), но человек может нажать «Подожду машину» —
тогда заказу ставится `wait_until`, а фоновый воркер (`app/taxi_worker.py`) каждые пару минут
сам перезапускает поиск. Статус при этом остаётся `expired`, то есть формально терминальный,
а на деле — живой. На этом расхождении держались две дыры:

1. **Отменить такой заказ было невозможно.** `cancel_order` видит терминальный статус и молча
   возвращает заказ как есть — с HTTP 200. Человек нажал «Отмена», приложение сказало «ок»,
   а воркер через две минуты всё равно нашёл водителя и отправил его к подъезду. Пассажира
   там нет; водитель ждёт, жмёт «пассажир не вышел» — и невиновный человек получает страйк
   и сутки без такси, а водитель — пустой пробег.

2. **«Заказать заново» давало ДВА живых заказа.** Анти-дубль на создании заказа смотрел
   статусы `created/searching/offered/accepted/arriving/onboard` — заказа в очереди среди них
   нет. Кнопка «Заказать заново» на экране просто обнуляет карточку и создаёт новый заказ,
   а старый остаётся у воркера. К подъезду приезжают две машины: одну человек берёт, вторая
   уезжает пустой, и наказаны обе невиновные стороны.

Третья, тихая: счётчик офферов `search_round` не обнулялся при перезапуске поиска из очереди.
`instant_max_offers` — предохранитель «сколько раз предлагать заказ», но он копился за всю
жизнь заказа. Как только по заказу суммарно ушло 8 офферов, очередь молча умирала: человек
двадцать минут смотрит на живую полоску «ищем машину», а сервер уже не предлагает никому,
даже если свободный водитель стоит в ста метрах.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import taxi_worker
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

ORIG = (52.591, 58.317)      # Баймак
DEST = (52.716, 58.664)      # Сибай


@pytest.fixture
def fake_redis():
    """Свежий fakeredis на тест. Водителей на линии нет → любой заказ сразу «рядом никого»."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.taxi_worker.send_push", lambda *a, **k: None)


def _order_body():
    return {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
            "from_text": "Баймак", "to_text": "Сибай"}


def _order_in_queue(client, pax) -> int:
    """Реальный путь человека: заказал → «рядом никого» → нажал «Подожду машину»."""
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200, r.text
    oid = r.json()["id"]
    assert r.json()["status"] == "expired", "для теста нужен заказ без водителей рядом"
    w = client.post(f"/instant/orders/{oid}/wait", headers=pax["auth"])
    assert w.status_code == 200, w.text
    assert w.json()["wait_until"], "заказ не встал в очередь"
    return oid


def _alive_orders(passenger_id: int) -> list:
    """Заказы пассажира, по которым система ЕЩЁ БУДЕТ искать водителя: живые статусы плюс
    очередь ожидания (`expired` + непросроченный `wait_until` — воркер их перезапускает)."""
    now = utcnow()
    with Session(engine) as s:
        rows = s.exec(select(InstantOrder).where(InstantOrder.passenger_id == passenger_id)).all()
        return [o.id for o in rows
                if o.status in (S.created, S.searching, S.offered, S.accepted, S.arriving, S.onboard)
                or (o.status == S.expired and o.wait_until is not None and o.wait_until > now)]


def _dispatched_drivers(passenger_id: int) -> set:
    """Кому из водителей прямо сейчас едет/предложен заказ этого пассажира."""
    with Session(engine) as s:
        rows = s.exec(select(InstantOrder).where(InstantOrder.passenger_id == passenger_id)).all()
        return {o.driver_id or o.current_offer_driver_id for o in rows
                if o.status in (S.offered, S.accepted, S.arriving, S.onboard)} - {None}


def _driver_online(client, user_factory, name):
    """Водитель на линии рядом с точкой подачи."""
    from app.models import UserRole
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    return d


def _let_worker_retry(order_id: int):
    """Воркер перезапускает поиск не чаще order_retry_every_min — отматываем прошлый круг назад."""
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        o.searching_at = utcnow() - timedelta(minutes=isv.settings.order_retry_every_min + 1)
        s.add(o)
        s.commit()


# ============== 1. Отмена из очереди ==============
def test_cancel_from_wait_queue_really_cancels(client, user_factory, fake_redis):
    """Нажал «Отмена» — значит машину больше не ждём. Раньше сервер отвечал «ок» и продолжал
    искать: к человеку всё равно приезжал водитель, а «пассажир не вышел» вешало страйк."""
    pax = user_factory("ОчередьОтмена")
    oid = _order_in_queue(client, pax)

    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"],
                    json={"reason": "передумал"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "cancelled", f"отмена не сработала: {r.json()['status']}"

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.status == S.cancelled
        assert o.wait_until is None, "заказ остался в очереди — воркер продолжит искать"


def test_worker_does_not_revive_cancelled_wait(client, user_factory, fake_redis):
    """Тот же случай глазами воркера: после отмены он не должен трогать заказ вообще."""
    pax = user_factory("ОчередьВоркер")
    oid = _order_in_queue(client, pax)
    client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={"reason": "передумал"})

    with Session(engine) as s:
        assert oid not in taxi_worker.retry_waiting_orders(s), "воркер снова ищет машину отменившему"
        assert oid not in taxi_worker.finish_expired_waits(s)
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).status == S.cancelled, "воркер переписал отменённый заказ"


# ============== 2. «Заказать заново» из очереди ==============
def test_new_order_replaces_the_queued_one(client, user_factory, fake_redis):
    """«Заказать заново» = человек больше не ждёт старый заказ. Иначе к подъезду приедут
    две машины: одну он возьмёт, вторая уедет пустой и получит страйк ни за что.

    Ровно живая история: ночью машин нет → «подожду» → не дождался → «заказать заново» →
    как раз освободились двое водителей."""
    pax = user_factory("ОчередьДубль")
    first = _order_in_queue(client, pax)

    _driver_online(client, user_factory, "ДубльВодитель1")
    _driver_online(client, user_factory, "ДубльВодитель2")

    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200, r.text
    second = r.json()["id"]
    assert second != first, "новый заказ не создался"
    assert r.json()["status"] == "offered", "для теста нужен найденный водитель на новый заказ"

    _let_worker_retry(first)
    with Session(engine) as s:
        taxi_worker.retry_waiting_orders(s)

    with Session(engine) as s:
        assert s.get(InstantOrder, first).wait_until is None, (
            "старый заказ остался в очереди у воркера — он найдёт вторую машину"
        )
    alive = _alive_orders(pax["id"])
    assert len(alive) == 1, f"у одного пассажира сразу несколько живых заказов: {alive}"
    drivers = _dispatched_drivers(pax["id"])
    assert len(drivers) <= 1, f"к одному пассажиру отправлено двое водителей: {drivers}"


# ============== 3. Очередь не должна умирать молча ==============
def test_wait_queue_resets_offer_counter(client, user_factory, fake_redis):
    """`instant_max_offers` — потолок «сколько раз предлагаем заказ ЗА КРУГ». Он копился за всю
    жизнь заказа, и очередь ожидания после 8 отказов замолкала: полоска «ищем машину» живая,
    а сервер уже никому не предлагает."""
    pax = user_factory("ОчередьСчётчик")
    oid = _order_in_queue(client, pax)
    with Session(engine) as s:                       # доводим счётчик до потолка
        o = s.get(InstantOrder, oid)
        o.search_round = isv.settings.instant_max_offers
        s.add(o)
        s.commit()

    with Session(engine) as s:
        isv.start_matching(s, s.get(InstantOrder, oid), notify=False)

    with Session(engine) as s:
        assert s.get(InstantOrder, oid).search_round < isv.settings.instant_max_offers, (
            "счётчик офферов не обнулился — следующий круг поиска не начнётся никогда"
        )


def test_wait_queue_survives_until_time_is_up(client, user_factory, fake_redis):
    """Заказ в очереди живёт, пока не вышло время: воркер перезапускает поиск, а не хоронит."""
    pax = user_factory("ОчередьЖивая")
    oid = _order_in_queue(client, pax)
    _let_worker_retry(oid)
    with Session(engine) as s:
        assert oid in taxi_worker.retry_waiting_orders(s), "воркер не увидел заказ в очереди"
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.wait_until is not None and o.wait_until > utcnow()

    with Session(engine) as s:                       # время вышло → честно закрываем
        o = s.get(InstantOrder, oid)
        o.wait_until = utcnow() - timedelta(minutes=1)
        s.add(o)
        s.commit()
    with Session(engine) as s:
        assert oid in taxi_worker.finish_expired_waits(s)
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.status == S.expired and o.wait_until is None
