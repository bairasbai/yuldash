"""Способ расчёта — договорённость, о которой знают обе стороны.

Деньги через приложение не идут: пассажир платит водителю сам, наличными или переводом
по СБП. До этой волны об этом не договаривались вовсе — разговор случался на высадке, и
«я думал, ты переводом» была самой частой ссорой в конце поездки.

Здесь проверяется ровно то, ради чего всё делалось:
  • способ уходит в заказ и виден ОБЕИМ сторонам;
  • менять можно, пока поездка не завершена, — про наличные вспоминают уже в машине;
  • водитель узнаёт о смене, а не обнаруживает её на высадке;
  • карты и корпоративный счёт нельзя выбрать, пока их не включили: заготовка в коде есть,
    но принимать деньги без договора с банком мы не будем.
"""
from __future__ import annotations

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, UserRole

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _order(client, pax, **extra):
    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай", **extra,
    })
    assert r.status_code == 200, r.text
    return r.json()


def test_способ_уходит_в_заказ(client, user_factory):
    pax = user_factory("PayPax")
    order = _order(client, pax, payment_method="cash")
    assert order["payment_method"] == "cash", order


def test_без_выбора_договоримся_на_месте(client, user_factory):
    """Старый клиент поля не шлёт. Это не ошибка — это сегодняшнее поведение приложения."""
    pax = user_factory("PayPaxOld")
    order = _order(client, pax)
    assert order["payment_method"] == "negotiate", order


def test_карту_выбрать_нельзя_пока_не_включили(client, user_factory):
    """Заготовка в коде есть, но эквайринга нет: договор с банком, касса, чеки.

    Молча подставляем «договоримся», а не падаем ошибкой: клиент мог прислать способ,
    который мы ещё не включили, и ронять из-за этого заказ — хуже, чем честно разойтись
    на «договоримся на месте».
    """
    pax = user_factory("PayPaxCard")
    order = _order(client, pax, payment_method="card")
    assert order["payment_method"] == "negotiate", order


def test_меняем_в_поездке_и_водитель_узнаёт(client, user_factory, fake_redis):
    """Смена посреди поездки разрешена, но тихой она быть не может."""
    d = user_factory("PayDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    pax = user_factory("PayPaxTrip")
    order = _order(client, pax, payment_method="cash")
    for шаг in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{order['id']}/{шаг}",
                           headers=d["auth"]).status_code == 200

    r = client.post(f"/instant/orders/{order['id']}/payment",
                    headers=pax["auth"], json={"method": "sbp"})
    assert r.status_code == 200, r.text
    assert r.json() == {"payment_method": "sbp", "changed": True}

    with Session(engine) as s:
        assert s.get(InstantOrder, order["id"]).payment_method == "sbp"

    # Водитель видит новый способ в своей выдаче заказа — не только в уведомлении.
    его = client.get(f"/instant/orders/{order['id']}", headers=d["auth"])
    assert его.status_code == 200, его.text
    assert его.json()["payment_method"] == "sbp"


def test_после_завершения_менять_нечего(client, user_factory, fake_redis):
    """Поездка закончилась — договариваться уже поздно, деньги переданы."""
    d = user_factory("PayDrvDone", role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    pax = user_factory("PayPaxDone")
    order = _order(client, pax, payment_method="cash")
    for шаг in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{order['id']}/{шаг}",
                           headers=d["auth"]).status_code == 200

    r = client.post(f"/instant/orders/{order['id']}/payment",
                    headers=pax["auth"], json={"method": "sbp"})
    assert r.status_code == 409, r.text


def test_чужой_заказ_не_тронешь(client, user_factory):
    """Способ расчёта меняет только тот, кто платит."""
    pax = user_factory("PayOwner")
    other = user_factory("PayStranger")
    order = _order(client, pax, payment_method="cash")
    r = client.post(f"/instant/orders/{order['id']}/payment",
                    headers=other["auth"], json={"method": "sbp"})
    assert r.status_code == 404, r.text
    with Session(engine) as s:
        assert s.get(InstantOrder, order["id"]).payment_method == "cash"
