"""Один человек не должен вызывать к себе все машины района сразу.

История. Предзаказ такси — это запись «подай машину к семи утра». Правило «один живой заказ
на человека» стояло на обычном заказе: жмёшь «Заказать» второй раз — получаешь тот же заказ,
а не новый. Предзаказ его обходил: на одно и то же время можно было оформить сколько угодно,
и в час X все они уходили в поиск разом (аудит 2026-08-08, волна 76).

Чем это плохо для живых людей. Двенадцать предзаказов на семь утра — и каждый водитель,
вышедший на линию, получает оффер от одного и того же пассажира. Уедет он с одним. Остальные
доехали до точки зря: время, бензин, а в деревне это ещё и очередь, которую они пропустили.
Хуже того, кто уже принял заказ и не дождался: у него копится «брошенный принятый», а за них
даётся пауза. Один человек мог наказать половину водителей района, формально ничего не нарушив.

Поэтому предзаказ теперь не отменяется, а ЖДЁТ: пока человек едет, следующая запись остаётся
в очереди и активируется, когда он освободится. Заказы уходят по одному.
"""
from __future__ import annotations

from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import select

from app import instant_service as isv
from app.db import get_session
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow


@pytest.fixture
def taxi_on(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    yield


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _driver_on_line(client, user_factory, name: str, shift: float):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": 54.735 + shift, "lng": 55.958}).status_code == 200
    return d


def _schedule(client, psg, i: int) -> int:
    r = client.post("/instant/schedule", headers=psg["auth"], json={
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,
        "from_text": f"Уфа {i}", "to_text": "Стерлитамак",
        "scheduled_at": (utcnow() + timedelta(hours=3)).isoformat(),
        "category": "standard",
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _time_has_come(order_ids: list[int]):
    """Час подачи настал — двигаем время в прошлое (в тесте иначе не подождать)."""
    s = next(get_session())
    for oid in order_ids:
        o = s.get(InstantOrder, oid)
        o.scheduled_at = utcnow() - timedelta(minutes=1)
        s.add(o)
    s.commit()


def _live_orders(passenger_id: int) -> list[InstantOrder]:
    s = next(get_session())
    return list(s.exec(select(InstantOrder).where(
        InstantOrder.passenger_id == passenger_id,
        InstantOrder.status.in_(isv.LIVE_ORDER_STATUSES),
    )).all())


def test_пачка_предзаказов_будит_только_одного_водителя(client, user_factory, taxi_on, fake_redis):
    drivers = [_driver_on_line(client, user_factory, f"ПачкаВодитель{i}", i * 0.001) for i in range(4)]
    psg = user_factory("ПачкаПассажир")

    ids = [_schedule(client, psg, i) for i in range(6)]
    _time_has_come(ids)

    body = client.get("/instant/scheduled", headers=psg["auth"]).json()
    assert len(body["activated"]) == 1, \
        f"в поиск ушла пачка заказов от одного человека: {len(body['activated'])}"
    assert len(_live_orders(psg["id"])) == 1, "у человека одновременно больше одной живой поездки"

    called = sum(1 for d in drivers
                 if (client.get("/instant/driver/offer", headers=d["auth"]).json() or {}).get("offer"))
    assert called == 1, f"к одному человеку вызвано водителей: {called} из {len(drivers)}"


def test_остальные_предзаказы_не_пропали_а_ждут(client, user_factory, taxi_on, fake_redis):
    """Отменять их нельзя: человек оформил их сам, они актуальны. Они должны остаться
    в очереди и уехать по одному — иначе поездка просто исчезнет без объяснения."""
    _driver_on_line(client, user_factory, "ОчередьВодитель", 0.0)
    psg = user_factory("ОчередьПассажир")

    ids = [_schedule(client, psg, i) for i in range(3)]
    _time_has_come(ids)

    body = client.get("/instant/scheduled", headers=psg["auth"]).json()
    waiting = [o["id"] for o in body["scheduled"]]
    assert len(waiting) == 2, f"предзаказы пропали из очереди: осталось {len(waiting)} из 2"

    s = next(get_session())
    for oid in waiting:
        assert s.get(InstantOrder, oid).status == S.scheduled, "ждущий предзаказ отменили"


def test_один_предзаказ_едет_как_прежде(client, user_factory, taxi_on, fake_redis):
    """Обратная сторона: очередь не должна мешать обычному случаю — один предзаказ
    в назначенный час обязан уйти в поиск и найти машину."""
    _driver_on_line(client, user_factory, "ОдинВодитель", 0.0)
    psg = user_factory("ОдинПассажир")

    oid = _schedule(client, psg, 0)
    _time_has_come([oid])

    body = client.get("/instant/scheduled", headers=psg["auth"]).json()
    assert len(body["activated"]) == 1, "единственный предзаказ не поехал в назначенный час"
    assert body["activated"][0]["status"] in ("searching", "offered"), body["activated"][0]["status"]
