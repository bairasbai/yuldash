# -*- coding: utf-8 -*-
"""Координата, пришедшая от клиента, обязана существовать на Земле.

Аудит 2026-08-12, волна 41. Правило простое: широта −90..90, долгота −180..180. В заказе такси
и у курьера оно стояло, в остальных местах — нет: бронь, посылка, точка сбора, карточка бизнеса
и САМ СИГНАЛ SOS принимали что угодно.

Чем это плохо не «в теории»: из координат мы делаем ссылку на карту. У SOS эта ссылка — то, что
Александр открывает первым, когда человеку плохо. Битая точка означает потерянные минуты именно
в тот момент, когда они дороже всего. У посылки и брони — сломанный маршрут и расстояние,
посчитанное по мусору.

Приложение такого не пришлёт. Но приложение — не защита: запрос можно послать напрямую,
а показывать сохранённое будут наши же экраны.
"""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import SosEvent, UserRole

BAD = [
    ("широта больше 90", {"lat": 1000.0, "lng": 58.3}),
    ("долгота меньше −180", {"lat": 52.6, "lng": -2000.0}),
    ("широта на полюсе+1", {"lat": 90.5, "lng": 58.3}),
]


@pytest.mark.parametrize("title,coords", BAD, ids=[t for t, _ in BAD])
def test_sos_с_невозможной_точкой_не_принимается(client, user_factory, title, coords):
    """Сигнал бедствия с битой точкой = потерянные минуты там, где они дороже всего."""
    victim = user_factory("Человек в беде с битой точкой")
    r = client.post("/sos", headers=victim["auth"],
                    json={"category": "other", "note": "Помогите", **coords})
    assert r.status_code == 422, f"{title}: сервер принял невозможную координату ({r.status_code})"


def test_sos_с_нормальной_точкой_проходит(client, user_factory):
    """Защита не смеет мешать помощи: обычный сигнал с координатами Баймака проходит."""
    victim = user_factory("Человек в беде")
    r = client.post("/sos", headers=victim["auth"],
                    json={"category": "other", "note": "Помогите", "lat": 52.5911, "lng": 58.3169})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        events = s.exec(select(SosEvent).where(SosEvent.user_id == victim["id"])).all()
    assert len(events) == 1


def test_sos_без_координат_по_прежнему_работает(client, user_factory):
    """Место может быть неизвестно (нет разрешения, нет сигнала) — сигнал всё равно уходит."""
    victim = user_factory("Человек в беде без гео")
    r = client.post("/sos", headers=victim["auth"], json={"category": "other", "note": "Помогите"})
    assert r.status_code == 200, r.text


def test_посылка_с_невозможной_точкой_не_создаётся(client, user_factory):
    """Маршрут посылки рисуется на карте и считается в километрах — мусор туда не пускаем."""
    sender = user_factory("Отправитель с битой точкой")
    r = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "description": "Документы", "receiver_name": "Марат", "receiver_phone": "+79990000000",
        "from_lat": 999.0, "from_lng": 58.3, "to_lat": 52.7, "to_lng": 58.6,
    })
    assert r.status_code == 422, r.text
