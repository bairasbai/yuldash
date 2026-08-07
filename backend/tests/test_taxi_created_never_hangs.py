# -*- coding: utf-8 -*-
"""Заказ, застрявший в `created`, не должен блокировать человеку такси навсегда
(аудит 2026-08-07).

Статус `created` живёт доли секунды — между записью заказа в базу и запуском поиска. Но если
ровно там что-то оборвалось (упало списание промокода, отвалился Redis, человек потерял сеть),
заказ остаётся в `created` навсегда: фоновый воркер закрывал только заказы «в работе»
(`searching/offered/accepted/arriving/onboard`), а `created` в этот список не входил.

Чем это кончалось для человека. Анти-дубль на создании заказа считает `created` активным
заказом и возвращает его вместо нового. То есть один сорвавшийся заказ — и «Заказать» больше
НИКОГДА не работает: каждый тап отдаёт тот же мёртвый заказ. Выбраться можно только ручной
отменой, если экран вообще покажет кнопку в этом состоянии.

Починка: воркер закрывает и такие заказы — по тому же таймауту, что и остальные.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import taxi_worker
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow

ORIG = (52.591, 58.317)      # Баймак
DEST = (52.716, 58.664)      # Сибай


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.taxi_worker.send_push", lambda *a, **k: None)


def _stuck_created(pax_id: int, age_days: int = 30) -> int:
    """Заказ, который так и не дошёл до поиска (обрыв ровно между записью и matcher'ом)."""
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax_id, status=S.created,
                         from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                         from_text="Баймак", to_text="Сибай", price_estimate=300,
                         created_at=utcnow() - timedelta(days=age_days))
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def test_stuck_created_order_gets_closed(client, user_factory, fake_redis):
    """Месяц в `created` — заказ мёртв, и воркер обязан его закрыть."""
    pax = user_factory("ЗависшийСоздан")
    oid = _stuck_created(pax["id"])

    with Session(engine) as s:
        assert oid in taxi_worker.close_stuck_orders(s), "воркер не видит зависший заказ"
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).status == S.expired


def test_stuck_created_order_stops_blocking_new_orders(client, user_factory, fake_redis):
    """Главное последствие: человек снова может вызвать такси."""
    pax = user_factory("ЗависшийТакси")
    oid = _stuck_created(pax["id"])
    with Session(engine) as s:
        taxi_worker.close_stuck_orders(s)

    r = client.post("/instant/orders", headers=pax["auth"],
                    json={"from_lat": ORIG[0], "from_lng": ORIG[1],
                          "to_lat": DEST[0], "to_lng": DEST[1],
                          "from_text": "Баймак", "to_text": "Сибай"})
    assert r.status_code == 200, r.text
    assert r.json()["id"] != oid, "«Заказать» снова отдаёт тот же мёртвый заказ"


def test_fresh_created_order_is_not_touched(client, user_factory, fake_redis):
    """Только что созданный заказ воркер трогать не должен — он ещё в работе."""
    pax = user_factory("СвежийСоздан")
    oid = _stuck_created(pax["id"], age_days=0)

    with Session(engine) as s:
        assert oid not in taxi_worker.close_stuck_orders(s)
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).status == S.created
