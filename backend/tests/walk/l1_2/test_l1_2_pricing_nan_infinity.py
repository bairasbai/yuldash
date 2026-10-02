"""leaf-1.2 — F4 (независимое ревью Opus 5.5, 2026-10-02): `_parse_route_payload` пропускает
NaN и бесконечность.

Python-овский `json.loads` (а значит и `response.json()` у httpx) по умолчанию принимает
нестандартные литералы `Infinity`/`NaN`/`-Infinity` как часть JSON. `_parse_route_payload`
проверяет только `length_m <= 0` — `float("inf")` эту проверку проходит (inf > 0), и абсурдное
расстояние уходит в `RouteMetrics`, а оттуда — в кэш и в расчёт цены. У `traffic_k` есть потолок
(test_коэффициент_пробок_не_улетает_в_космос), у самого РАССТОЯНИЯ — не было.
"""
from __future__ import annotations

import math

import pytest

from app import pricing
from app.config import settings

UFA = (54.7388, 55.9721)
SIBAY = (52.9128, 58.6689)


class FakeClient:
    def __init__(self, payload):
        self.payload = payload

    def get(self, url, **kwargs):
        class R:
            def raise_for_status(_self):
                return None

            def json(_self):
                return self.payload

        return R()


@pytest.fixture
def external_on(monkeypatch):
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", True, raising=False)
    monkeypatch.setattr(settings, "yandex_router_key", "test-router-key", raising=False)
    pricing._cache.clear()
    yield
    pricing._cache.clear()


def _payload_with(length, duration=600):
    return {"traffic_type": "jams", "route": {"legs": [{"status": "OK", "steps": [
        {"length": length, "duration": duration}]}]}}


@pytest.mark.parametrize("length", [float("inf"), float("nan")])
def test_infinite_or_nan_distance_falls_back_instead_of_poisoning_the_price(external_on, length):
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(_payload_with(length)))
    assert m.source != "yandex", f"мусорное расстояние {length} было принято за настоящее"
    assert math.isfinite(m.distance_km) and m.distance_km > 0


def test_infinite_duration_also_falls_back(external_on):
    m = pricing.route_metrics(UFA, SIBAY, client=FakeClient(_payload_with(10_000, duration=float("inf"))))
    assert m.source != "yandex"
    assert math.isfinite(m.duration_min) and math.isfinite(m.traffic_k)


def test_infinite_distance_is_not_cached_for_the_next_request(external_on):
    """Если бы мусор прошёл, он лёг бы в кэш и отравил бы СЛЕДУЮЩИЙ запрос по тем же координатам
    даже после того, как Яндекс перестал бы врать."""
    pricing.route_metrics(UFA, SIBAY, client=FakeClient(_payload_with(float("inf"))))
    cache_key = ("route", round(UFA[0], 3), round(UFA[1], 3), round(SIBAY[0], 3), round(SIBAY[1], 3))
    cached = pricing._cache_get(cache_key)
    assert cached is None or (isinstance(cached, pricing.RouteMetrics) and math.isfinite(cached.distance_km))
