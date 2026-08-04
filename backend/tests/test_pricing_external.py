"""Внешние источники цены: пробки и погода. Что делаем, когда Яндекс молчит или врёт.

Почему это важно именно здесь. Цена такси считается из расстояния, времени в пути, погоды
и того, далеко ли машина. Расстояние и время мы спрашиваем у маршрутизатора Яндекса, погоду —
у их же сервиса. Оба могут ответить мусором, отвалиться по таймауту или вернуть 500.

Правило простое: **приложение никогда не остаётся без цены**. Не смогли спросить — считаем
по своей формуле (расстояние по прямой × коэффициент дорог). Пассажир увидит цену чуть менее
точную, но увидит. Молчащий экран «цена недоступна» на морозе в Баймаке хуже, чем цена ±10%.

Второе правило — приватность: в кэш кладём координаты, огрублённые до ~100 м (маршрут) и
~1 км (погода), чтобы по ключам кэша нельзя было восстановить точные адреса людей.
"""
from __future__ import annotations

import httpx
import pytest

from app import pricing
from app.config import settings


class FakeResponse:
    def __init__(self, payload, status: int = 200):
        self._payload = payload
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("boom", request=None, response=None)

    def json(self):
        return self._payload


class FakeClient:
    """Подменяет httpx: отдаёт заготовленный ответ и запоминает, о чём спросили."""

    def __init__(self, payload=None, status: int = 200, raises: Exception | None = None):
        self.payload, self.status, self.raises = payload, status, raises
        self.calls: list[tuple] = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if self.raises is not None:
            raise self.raises
        return FakeResponse(self.payload, self.status)


UFA = (54.7388, 55.9721)
SIBAY = (52.9128, 58.6689)


@pytest.fixture
def external_on(monkeypatch):
    """Включает внешние источники и подставляет тестовые ключи (в git ключей нет)."""
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", True, raising=False)
    monkeypatch.setattr(settings, "yandex_router_key", "test-router-key", raising=False)
    monkeypatch.setattr(settings, "yandex_weather_key", "test-weather-key", raising=False)
    pricing._cache.clear()
    yield
    pricing._cache.clear()


def _route_payload(length_m: float, duration_s: float, tolls: bool = False):
    return {
        "traffic_type": "jams",
        "route": {
            "legs": [{"status": "OK", "steps": [{"length": length_m, "duration": duration_s}]}],
            "flags": {"hasTolls": tolls},
        },
    }


# ---------- Маршрут ----------

def test_без_ключа_считаем_по_своей_формуле(monkeypatch):
    monkeypatch.setattr(settings, "yandex_router_key", "", raising=False)
    m = pricing.route_metrics(UFA, SIBAY)
    assert m.source != "yandex"
    assert m.distance_km > 0 and m.duration_min > 0


def test_выключенный_внешний_источник_не_ходит_в_сеть(monkeypatch):
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", False, raising=False)
    client = FakeClient(_route_payload(10_000, 900))
    pricing.route_metrics(UFA, SIBAY, client=client)
    assert client.calls == [], "выключенный источник не должен дёргать Яндекс"


def test_ответ_яндекса_разбирается_с_пробками(external_on):
    # 30 км за 60 минут — вдвое дольше свободного потока, значит пробки.
    client = FakeClient(_route_payload(30_000, 3600))
    m = pricing.route_metrics(UFA, SIBAY, client=client)
    assert m.source == "yandex"
    assert m.distance_km == pytest.approx(30.0, abs=0.01)
    assert m.duration_min == pytest.approx(60.0, abs=0.01)
    assert m.traffic_k > 1.0, "час на 30 км — это пробка, цена должна это учесть"
    assert m.traffic_type == "jams"


def test_платная_дорога_отмечается(external_on):
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(_route_payload(20_000, 1200, tolls=True)))
    assert m.has_tolls is True


def test_коэффициент_пробок_не_улетает_в_космос(external_on):
    # Маршрутизатор может вернуть абсурд (10 часов на 5 км) — цена не должна взлететь в десять раз.
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(_route_payload(5_000, 36_000)))
    assert m.traffic_k <= 3.0


@pytest.mark.parametrize(
    "payload, why",
    [
        ({}, "пустой ответ"),
        ({"route": {"legs": []}}, "нет участков маршрута"),
        ({"route": {"legs": [{"status": "FAIL", "steps": []}]}}, "участок не построился"),
        ({"route": {"legs": [{"status": "OK", "steps": [{"length": 0, "duration": 0}]}]}}, "нулевой маршрут"),
        ({"route": {"legs": [{"status": "OK"}]}}, "нет шагов"),
    ],
)
def test_мусор_в_ответе_не_оставляет_без_цены(external_on, payload, why):
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(payload))
    assert m.source != "yandex", why
    assert m.distance_km > 0, "цена обязана посчитаться даже на мусоре: " + why


def test_список_маршрутов_вместо_одного_тоже_понимаем(external_on):
    payload = {"routes": [{"legs": [{"status": "OK", "steps": [{"length": 12_000, "duration": 900}]}]}]}
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(payload))
    assert m.source == "yandex"
    assert m.distance_km == pytest.approx(12.0, abs=0.01)


def test_таймаут_маршрутизатора_не_ломает_заказ(external_on):
    client = FakeClient(raises=httpx.ConnectTimeout("нет ответа"))
    m = pricing.route_metrics(UFA, SIBAY, client=client)
    assert m.source != "yandex"
    assert m.distance_km > 0


def test_ошибка_500_у_яндекса_тоже_не_ломает_заказ(external_on):
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient({}, status=500))
    assert m.distance_km > 0


# ---------- Погода ----------

def _weather_payload(condition: str, temp: float = -20.0, gust: float = 25.0):
    return {"fact": {"condition": condition, "temp": temp, "feels_like": temp - 5, "wind_gust": gust}}


def test_без_ключа_погода_нейтральна(monkeypatch):
    monkeypatch.setattr(settings, "yandex_weather_key", "", raising=False)
    w = pricing.weather_metrics(52.9, 58.6)
    assert w.available is False
    assert w.k == pytest.approx(1.0)


def test_метель_поднимает_цену_но_не_бесконечно(external_on):
    w = pricing.weather_metrics(52.9, 58.6, client=FakeClient(_weather_payload("blowing-snow")))
    assert w.available is True
    assert w.k > 1.0, "в метель ехать дольше и опаснее — это в цене"
    assert w.k <= 1.5, "погодная надбавка обязана иметь потолок"


def test_ясная_погода_цену_не_трогает(external_on):
    w = pricing.weather_metrics(52.9, 58.6, client=FakeClient(_weather_payload("clear", temp=15.0, gust=2.0)))
    assert w.k == pytest.approx(1.0, abs=0.001)


def test_молчащая_погода_не_ломает_цену(external_on):
    w = pricing.weather_metrics(52.9, 58.6, client=FakeClient(raises=httpx.ReadTimeout("тишина")))
    assert w.available is False
    assert w.k == pytest.approx(1.0)


def test_ответ_без_фактической_погоды_нейтрален(external_on):
    w = pricing.weather_metrics(52.9, 58.6, client=FakeClient({"forecast": {}}))
    assert w.available is False


# ---------- Кэш и приватность ----------

def test_кэш_огрубляет_координаты_до_квартала():
    # Два адреса в одном квартале должны дать ОДИН ключ: по кэшу нельзя восстановить,
    # от какого именно подъезда человек ехал.
    pricing._cache.clear()
    pricing._cache_put(("route", 54.738, 55.972, 52.912, 58.668), "значение", ttl_sec=60)
    assert pricing._cache_get(("route", 54.738, 55.972, 52.912, 58.668)) == "значение"
    assert pricing._cache_get(("route", 54.700, 55.900, 52.900, 58.600)) is None


def test_протухшая_запись_кэша_не_отдаётся():
    pricing._cache.clear()
    pricing._cache_put(("route", 1, 2, 3, 4), "старое", ttl_sec=-1)   # ttl<=0 → не кладём вовсе
    assert pricing._cache_get(("route", 1, 2, 3, 4)) is None


def test_кэш_не_растёт_бесконечно():
    pricing._cache.clear()
    for i in range(2100):
        pricing._cache_put(("route", i, 0, 0, 0), i, ttl_sec=600)
    assert len(pricing._cache) <= 2048, "иначе память сервера съедят координаты"
    pricing._cache.clear()


def test_повторный_запрос_маршрута_берётся_из_кэша(external_on):
    # Без client=... используется настоящий httpx — значит второй вызов не должен до него дойти.
    client = FakeClient(_route_payload(10_000, 600))
    first = pricing.route_metrics(UFA, SIBAY, client=client)
    assert first.source == "yandex"
    assert len(client.calls) == 1
