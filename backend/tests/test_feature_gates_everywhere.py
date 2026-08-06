"""Выключатели такси и курьера должны держать ВСЕ входы, а не большинство.

Такси и доставка включаются флагом и списком городов. Это не украшение: возить людей
за деньги без разрешения — нарушение 580-ФЗ, а включённая доставка там, где нет курьеров,
это обещание, которое некому выполнить.

Проблема та же, что уже трижды находилась в других правилах: проверка стоит ПОШТУЧНО
на каждой ручке. Забыли одну — и через неё сервис работает там, где выключен. Снаружи это
не видно: экран кнопку не покажет, приложение туда не пойдёт, тесты зелёные.

Здесь каждый вход проверяется отдельно, при выключенном флаге, — и отдельно проверяется
обратное: с включённым флагом всё то же самое работает (иначе тест зеленел бы просто потому,
что сценарий не запускается).
"""
from __future__ import annotations

import fakeredis
import pytest

from app import instant_service as isv
from app.config import settings
from app.models import UserRole

ORIG = (52.5911, 58.3178)   # Баймак
DEST = (52.9128, 58.6689)   # Сибай


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def taxi_off(monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", False, raising=False)
    yield


@pytest.fixture
def courier_off(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", False, raising=False)
    yield


@pytest.fixture
def courier_on(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    yield


# ---------- Такси выключено ----------

def _estimate(client, user):
    return client.post("/instant/estimate", headers=user["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })


def _order(client, user):
    return client.post("/instant/orders", headers=user["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })


def _schedule(client, user):
    from datetime import timedelta

    from app.timeutil import utcnow
    when = (utcnow() + timedelta(hours=6)).replace(microsecond=0).isoformat()
    return client.post("/instant/schedule", headers=user["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай", "scheduled_at": when,
    })


def test_контроль_с_включённым_такси_заказ_создаётся(client, user_factory, fake_redis):
    """Без этого контроля все проверки ниже могли бы проходить потому, что такси не работает
    вообще никогда."""
    pax = user_factory("GateOnPax")
    r = _order(client, pax)
    assert r.status_code == 200, f"при включённом такси заказ не создался: {r.text[:200]}"


@pytest.mark.parametrize("what", ["estimate", "order", "schedule"])
def test_при_выключенном_такси_пассажирские_входы_закрыты(client, user_factory, taxi_off,
                                                          fake_redis, what):
    pax = user_factory(f"GateOffPax{what}")
    fn = {"estimate": _estimate, "order": _order, "schedule": _schedule}[what]
    r = fn(client, pax)
    assert r.status_code == 403, (
        f"«{what}» работает при выключенном такси: {r.status_code} {r.text[:200]}"
    )


def test_при_выключенном_такси_водитель_не_выходит_на_линию(client, user_factory, taxi_off,
                                                            fake_redis):
    """Водитель на линии при выключенном такси — это машина, которая ждёт заказов,
    которых по закону быть не должно."""
    driver = user_factory("GateOffDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    r = client.post("/instant/presence", headers=driver["auth"],
                    json={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 403, (
        f"водитель вышел на линию при выключенном такси: {r.status_code} {r.text[:200]}"
    )


def test_при_выключенном_такси_нельзя_принять_заказ(client, user_factory, fake_redis, monkeypatch):
    """Заказ создан, пока такси работало, а флаг выключили. Принять его уже нельзя."""
    pax = user_factory("GateMidPax")
    driver = user_factory("GateMidDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    oid = _order(client, pax).json()["id"]

    monkeypatch.setattr(settings, "taxi_enabled", False, raising=False)
    r = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert r.status_code == 403, (
        f"водитель принял заказ при выключенном такси: {r.status_code} {r.text[:200]}"
    )


def test_попутка_выключенным_такси_не_задета(client, user_factory, taxi_off):
    """Важная обратная сторона: флаг такси НЕ должен рубить попутки — это разные услуги,
    и попутка не требует разрешения перевозчика."""
    from test_api import _ride
    driver = user_factory("GateRideDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("GateRidePax")
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code == 200, (
        f"выключенное такси заодно сломало попутки: {r.status_code} {r.text[:200]}"
    )


# ---------- Доставка выключена ----------

def _parcel(client, user, phone="+79990007101"):
    return client.post("/parcels", headers=user["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": phone, "rules_accepted": True,
    })


def test_контроль_с_включённой_доставкой_посылка_создаётся(client, user_factory, courier_on):
    sender = user_factory("GateParcelOnSender")
    r = _parcel(client, sender)
    assert r.status_code == 200, f"при включённой доставке посылка не создалась: {r.text[:200]}"


def test_при_выключенной_доставке_курьерский_заказ_не_создать(client, user_factory, courier_off):
    """Профессиональный режим («вызвать курьера») закрыт флагом — иначе человек платит
    за услугу, которую некому оказать."""
    sender = user_factory("GateCourierOffSender")
    r = client.post("/courier/orders", headers=sender["auth"], json={
        "from_city": "Акъяр", "to_city": "Сибай", "size": "small",
        "description": "тест", "receiver_name": "Х", "receiver_phone": "+79990007102",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
    })
    assert r.status_code == 403, (
        f"курьерский заказ создался при выключенной доставке: {r.status_code} {r.text[:200]}"
    )


def test_при_выключенной_доставке_лента_курьера_закрыта(client, user_factory, courier_off):
    courier = user_factory("GateCourierOffDrv", role=UserRole.driver)
    r = client.get("/courier/available", headers=courier["auth"])
    assert r.status_code == 403, (
        f"лента курьерских заказов открыта при выключенной доставке: {r.status_code}"
    )
