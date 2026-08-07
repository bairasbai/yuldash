# -*- coding: utf-8 -*-
"""Проверка «Я на месте» по координатам должна реально работать (аудит 2026-08-07).

Зачем эта проверка вообще. С кнопки «Я на месте» начинается платное ожидание пассажира, а
через 8 минут водителю открывается «пассажир не вышел» — со штраф-подачей и страйком. Без
проверки координат недобросовестный водитель жмёт её из дома: человек стоит у подъезда, а
ему капают деньги в минус и блокировка такси на сутки.

Почему она не работала. Проверку писали с двумя источниками координат: тело запроса, а если
клиент их не прислал — последняя известная позиция водителя из Redis (её кладёт WS-трек
поездки). Второй источник читал ключ `livepos:instant:{id}`, а WS-трек пишет `livepos:order:{id}`.
Ключи разные → позиция всегда None → проверка молча пропускалась. Тесты этого не ловили:
один передавал координаты в теле явно, второй проверял только, что без координат ручка
отвечает 200.

Отдельно: Android в этой ручке шлёт пустое тело, хотя свою позицию знает (он же гонит её в
WS-трек). Пока клиент не научится её класть, единственный живой источник — Redis, поэтому
опечатка в ключе означала «проверки нет вообще».
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import livepos
from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, UserRole

PICKUP = (52.591, 58.317)        # Баймак, точка подачи
UFA = (54.735, 55.958)           # Уфа — 300+ км, «жму кнопку из дома»


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)


@pytest.fixture
def accepted(client, user_factory):
    """Принятый заказ: водитель назначен, «Я на месте» ему доступно."""
    pax = user_factory("ГеоПассажир")
    drv = user_factory("ГеоВодитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], car_make="Lada", car_model="Vesta",
                            car_plate="Т777ТТ102", rating=4.9))
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.accepted,
                         from_lat=PICKUP[0], from_lng=PICKUP[1], to_lat=52.716, to_lng=58.664,
                         from_text="Баймак, Ленина 12", to_text="Сибай", price_estimate=300)
        s.add(o)
        s.commit()
        s.refresh(o)
        return {"id": o.id, "pax": pax, "drv": drv}


def test_arrived_rejects_driver_who_is_still_at_home(client, accepted, fake_redis):
    """Клиент координат не шлёт — берём последнюю позицию из WS-трека. Водитель в Уфе → нельзя."""
    livepos.livepos_set("order", accepted["id"], UFA[0], UFA[1])

    r = client.post(f"/instant/orders/{accepted['id']}/arrived", headers=accepted["drv"]["auth"])

    assert r.status_code == 409, (
        f"водитель отметился «на месте» за 300 км — платное ожидание пошло зря: {r.text}"
    )
    with Session(engine) as s:
        o = s.get(InstantOrder, accepted["id"])
        assert o.status == S.accepted and o.waiting_started_at is None


def test_arrived_passes_when_driver_really_arrived(client, accepted, fake_redis):
    """Водитель у подъезда — кнопка работает как раньше."""
    livepos.livepos_set("order", accepted["id"], PICKUP[0] + 0.001, PICKUP[1] + 0.001)

    r = client.post(f"/instant/orders/{accepted['id']}/arrived", headers=accepted["drv"]["auth"])

    assert r.status_code == 200, r.text
    assert r.json()["status"] == "arriving"


def test_arrived_still_works_without_any_position(client, accepted, fake_redis):
    """Ни тела, ни позиции в Redis (GPS в селе пропал) — работу не рубим."""
    r = client.post(f"/instant/orders/{accepted['id']}/arrived", headers=accepted["drv"]["auth"])
    assert r.status_code == 200, r.text


def test_stale_position_does_not_block_an_honest_driver(client, accepted, fake_redis):
    """Связь пропала на подъезде: последняя точка осталась в километре позади. Судить по ней
    нельзя — водитель уже у подъезда, а кнопка бы не нажалась."""
    import json

    from app.timeutil import utcnow
    stale = {"lat": UFA[0], "lng": UFA[1], "bearing": None,
             "ts": (utcnow() - timedelta(minutes=2)).isoformat()}
    fake_redis.set(f"livepos:order:{accepted['id']}", json.dumps(stale), ex=300)

    r = client.post(f"/instant/orders/{accepted['id']}/arrived", headers=accepted["drv"]["auth"])
    assert r.status_code == 200, r.text
