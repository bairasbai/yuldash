# -*- coding: utf-8 -*-
"""Обещания Юлдаша — то, что человек читает в приложении, проверенное сквозным сценарием.

Зачем отдельный файл, если каждая дыра и так закрыта своим регресс-тестом. Тех тестов много,
и они устроены «от кода»: имя функции, статус, поле. Здесь наоборот — «от человека»: одно
обещание = один сценарий целиком, от входа до результата. Такой файл читается как список
того, что сервис реально гарантирует, и падает, когда очередная правка тихо отменяет одно
из обещаний.

Пятнадцать волн аудита 2026-08-08 нашли около двадцати мест, где обещание расходилось с кодом.
Половина — не «дыра в защите», а «защиту поставили на первый шаг и забыли про второй». Такое
не ловится чтением диффа, только сквозной проверкой.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, User, UserRole
from app.timeutil import utcnow
from test_instant import fake_redis  # noqa: F401 — фикстура Redis для такси-сценариев
from test_suspension_reaches_everywhere import _suspend


def _ride(client, drv, **extra):
    body = {
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    }
    body.update(extra)
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()["id"]


# ---------------------------------------------------------------------------
# «Телефон и точка сбора открываются только после подтверждения поездки»
# ---------------------------------------------------------------------------
def test_обещание_телефон_закрыт_до_подтверждения(client, user_factory):
    drv = user_factory("Обещание: водитель", role=UserRole.driver)
    rid = _ride(client, drv, pickup="У третьего подъезда",
                pickup_lat=52.5911, pickup_lng=58.3178)
    pax = user_factory("Обещание: пассажир")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]

    before = client.get(f"/bookings/{bid}/details", headers=pax["auth"])
    assert before.status_code == 200, before.text
    assert not before.json().get("driver_phone"), "телефон открыт до подтверждения"
    assert before.json().get("pickup_lat") is None, "точная точка сбора открыта до подтверждения"

    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    after = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert after.get("driver_phone"), "после подтверждения телефон обязан открыться"
    assert after.get("pickup_lat") is not None, "после подтверждения точка сбора обязана открыться"


# ---------------------------------------------------------------------------
# «Только женщины» = за рулём женщина И в салоне женщины
# ---------------------------------------------------------------------------
def test_обещание_только_женщины_держит_обе_стороны(client, user_factory):
    woman = user_factory("Обещание: женщина за рулём", role=UserRole.driver, gender="female")
    rid = _ride(client, woman, women_only=True)

    man = user_factory("Обещание: мужчина", gender="male")
    assert client.post("/bookings", headers=man["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 403

    unknown = user_factory("Обещание: без пола")
    denied = client.post("/bookings", headers=unknown["auth"], json={"ride_id": rid, "seats": 1})
    assert denied.status_code == 403
    assert "профил" in denied.json()["detail"]["ru"].lower(), "отказ должен подсказывать, что делать"

    she = user_factory("Обещание: пассажирка", gender="female")
    assert client.post("/bookings", headers=she["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 200

    # И мужчина не может поставить такую отметку своей поездке.
    man_drv = user_factory("Обещание: мужчина за рулём", role=UserRole.driver, gender="male")
    r = client.post("/rides", headers=man_drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r.status_code == 403, "мужчина поставил отметку «только женщины»"


# ---------------------------------------------------------------------------
# «Отстранённый разбором не возит» — на всех шагах сделки, а не только на первом
# ---------------------------------------------------------------------------
def test_обещание_пауза_доводит_дело_до_конца(client, user_factory):
    drv = user_factory("Обещание: отстранённый", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("Обещание: его пассажир")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]

    _suspend(drv["id"])

    # 1) новую поездку не опубликует
    assert client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=2)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    }).status_code == 403
    # 2) уже висящую бронь не подтвердит
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 403
    # 3) его поездка уходит из ленты — человек не тратит время впустую
    feed = client.get("/rides", headers=pax["auth"]).json()
    feed = feed.get("items", feed) if isinstance(feed, dict) else feed
    assert rid not in {x["id"] for x in feed}, "поездка отстранённого осталась в ленте"
    # 4) и по прямой ссылке к нему не забронируешь
    other = user_factory("Обещание: другой пассажир")
    assert client.post("/bookings", headers=other["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 409


# ---------------------------------------------------------------------------
# «Заблокировал — значит не пересечёмся»
# ---------------------------------------------------------------------------
def test_обещание_блокировка_не_обходится_вторым_шагом(client, user_factory):
    pax = user_factory("Обещание: закрылась")
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1})
    assert req.status_code == 200, req.text
    rid = req.json()["id"]

    drv = user_factory("Обещание: неприятный", role=UserRole.driver)
    resp = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 500})
    assert resp.status_code == 200, resp.text
    resp_id = resp.json()["id"]
    assert client.post(f"/responses/{resp_id}/counter", headers=pax["auth"],
                       json={"price": 400}).status_code == 200

    assert client.post("/blocks", headers=pax["auth"],
                       json={"blocked_user_id": drv["id"]}).status_code == 200

    # ни новым откликом, ни принятием уже сделанного предложения
    assert client.post(f"/requests/{rid}/respond", headers=drv["auth"],
                       json={"price": 450}).status_code == 403
    a = client.post(f"/responses/{resp_id}/accept", headers=drv["auth"])
    assert a.status_code == 403, "заблокированный довёл сделку до поездки"
    # и отказ не сообщает о самой блокировке
    assert "блок" not in a.json()["detail"]["ru"].lower()


# ---------------------------------------------------------------------------
# «Удалить аккаунт — значит стереть данные», все три хранилища
# ---------------------------------------------------------------------------
def test_обещание_удаление_стирает_базу_диск_и_кэш(client, user_factory, fake_redis):
    from app.account import delete_user_account
    from app.instant_service import PRESENCE_KEY
    from app.storage import get_storage
    from conftest import upload_evidence
    from test_instant import _driver_online, _heartbeat, ORIG

    d = _driver_online(client, user_factory, "Обещание: удаляется")
    _heartbeat(client, d, ORIG)
    photo = upload_evidence(client, d["auth"])
    name = photo.rsplit("/", 1)[-1]

    storage = get_storage()
    assert storage.exists(f"evidence/{name}"), "файл не сохранился — тест ничего не проверит"
    assert f"driver:{d['id']}" in set(fake_redis.zrange(PRESENCE_KEY, 0, -1))

    with Session(engine) as s:
        delete_user_account(s, s.get(User, d["id"]))

    with Session(engine) as s:
        assert s.get(User, d["id"]) is None, "строка пользователя осталась"
    assert not storage.exists(f"evidence/{name}"), "файл пережил удаление аккаунта"
    assert f"driver:{d['id']}" not in set(fake_redis.zrange(PRESENCE_KEY, 0, -1)), \
        "координаты остались в кэше"


# ---------------------------------------------------------------------------
# «Фото из разбора видят только его стороны»
# ---------------------------------------------------------------------------
def test_обещание_чужое_фото_доказательства_недоступно(client, user_factory):
    from conftest import upload_evidence

    victim = user_factory("Обещание: чужое фото")
    url = upload_evidence(client, victim["auth"])
    name = url.rsplit("/", 1)[-1]

    stranger = user_factory("Обещание: посторонний")
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403

    # и приложить чужой снимок к своему разбору тоже нельзя
    drv = user_factory("Обещание: водитель разбора", role=UserRole.driver)
    rid = _ride(client, drv)
    b = client.post("/bookings", headers=stranger["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    inc = client.post("/incidents", headers=stranger["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": b.json()["id"],
        "description": "повод выдуман", "evidence_urls": [url],
    })
    assert inc.status_code == 403, "чужое фото приложилось к разбору"
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403


# ---------------------------------------------------------------------------
# «Точный адрес — только тому, кто взял заказ»
# ---------------------------------------------------------------------------
def test_обещание_адрес_назначения_до_принятия_приблизителен(client, user_factory, fake_redis):
    from test_instant import _driver_online, _heartbeat, _create_order, ORIG, DEST

    d = _driver_online(client, user_factory, "Обещание: таксист")
    _heartbeat(client, d, ORIG)
    pax = user_factory("Обещание: пассажирка такси")
    order = _create_order(client, pax, to_text="Сибай, ул. Горького, 15")
    assert order["status"] == "offered"

    offer = client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"]
    assert offer is not None, "оффер не пришёл — тест ничего не проверяет"
    assert offer["to_text"] == "Сибай, ул. Горького", "номер дома виден до принятия"
    assert offer["to_lat"] == round(DEST[0], 2), "точная точка назначения видна до принятия"
    assert offer["price_estimate"], "но решать, брать ли заказ, водителю по-прежнему есть по чему"

    acc = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"]).json()
    assert acc["to_text"] == "Сибай, ул. Горького, 15", "после принятия адрес обязан открыться"
