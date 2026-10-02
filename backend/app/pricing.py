"""Failure-safe external and local signals for the instant-taxi price engine.

The passenger never supplies a price.  This module enriches the server tariff with:
- actual road distance and traffic-aware duration from Yandex Routing API;
- current adverse weather from Yandex Weather API;
- pickup difficulty derived from the nearest live driver.

Every external dependency fails open: no key, timeout, 429 or malformed JSON returns the
existing deterministic haversine/average-speed calculation and a neutral weather factor.
Coordinates are never logged.  Short-lived in-process cache keys are rounded to reduce
precision and external API load.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from threading import Lock
from time import monotonic
from typing import Any, Optional

import httpx

from .config import settings
from .services import haversine_km


@dataclass(frozen=True)
class RouteMetrics:
    distance_km: float
    duration_min: float
    source: str = "fallback"          # fallback | yandex
    traffic_type: str = "unknown"     # realtime | forecast | disabled | unknown
    traffic_k: float = 1.0             # display-only: duration vs configured free-flow speed
    has_tolls: bool = False


@dataclass(frozen=True)
class WeatherMetrics:
    k: float = 1.0
    code: str = ""
    condition: str = ""
    available: bool = False
    temperature_c: Optional[float] = None
    feels_like_c: Optional[float] = None
    wind_gust_ms: Optional[float] = None


_cache: dict[tuple, tuple[float, object]] = {}
_cache_lock = Lock()


def _cache_get(key: tuple) -> Optional[object]:
    now = monotonic()
    with _cache_lock:
        row = _cache.get(key)
        if row is None:
            return None
        expires_at, value = row
        if expires_at <= now:
            _cache.pop(key, None)
            return None
        return value


def _cache_put(key: tuple, value: object, ttl_sec: int) -> None:
    if ttl_sec <= 0:
        return
    with _cache_lock:
        _cache[key] = (monotonic() + ttl_sec, value)
        # A hard ceiling prevents unbounded process memory if coordinates are highly diverse.
        if len(_cache) > 2048:
            oldest = min(_cache, key=lambda k: _cache[k][0])
            _cache.pop(oldest, None)


def fallback_route(frm: tuple[float, float], to: tuple[float, float]) -> RouteMetrics:
    distance_km = max(
        haversine_km(frm[0], frm[1], to[0], to[1]) * settings.instant_road_k,
        0.5,
    )
    duration_min = distance_km / max(settings.instant_avg_speed_kmh, 1.0) * 60
    return RouteMetrics(
        distance_km=round(distance_km, 3),
        duration_min=round(duration_min, 3),
    )


def _parse_route_payload(payload: dict[str, Any]) -> RouteMetrics:
    route = payload.get("route")
    if not isinstance(route, dict):
        routes = payload.get("routes")
        route = routes[0] if isinstance(routes, list) and routes else None
    if not isinstance(route, dict):
        raise ValueError("route is missing")

    legs = route.get("legs")
    if not isinstance(legs, list) or not legs:
        raise ValueError("route legs are missing")

    distance_m = 0.0
    duration_sec = 0.0
    for leg in legs:
        if not isinstance(leg, dict) or leg.get("status") != "OK":
            raise ValueError("route leg failed")
        steps = leg.get("steps")
        if not isinstance(steps, list):
            raise ValueError("route steps are missing")
        for step in steps:
            if not isinstance(step, dict):
                continue
            distance_m += max(float(step.get("length") or 0.0), 0.0)
            duration_sec += max(float(step.get("duration") or 0.0), 0.0)

    # `<= 0` не ловит NaN/Infinity: NaN‑сравнения всегда False, а `inf <= 0` тоже False.
    # Python's json.loads (а значит и response.json()) по умолчанию принимает нестандартные
    # литералы Infinity/NaN/-Infinity — независимая проверка (Opus 5.5, 2026-10-02) нашла, что
    # такой ответ проходил бы дальше и отравлял и цену, и короткий кэш координат.
    if (distance_m <= 0 or duration_sec <= 0
            or not math.isfinite(distance_m) or not math.isfinite(duration_sec)):
        raise ValueError("route metrics are empty")

    distance_km = distance_m / 1000.0
    duration_min = duration_sec / 60.0
    free_flow_min = distance_km / max(settings.instant_avg_speed_kmh, 1.0) * 60
    traffic_k = max(0.5, min(duration_min / max(free_flow_min, 1.0), 3.0))
    flags = route.get("flags") if isinstance(route.get("flags"), dict) else {}
    return RouteMetrics(
        distance_km=round(distance_km, 3),
        duration_min=round(duration_min, 3),
        source="yandex",
        traffic_type=str(payload.get("traffic_type") or "unknown"),
        traffic_k=round(traffic_k, 3),
        has_tolls=bool(flags.get("hasTolls") or flags.get("hasNonTransactionalTolls")),
    )


def route_metrics(
    frm: tuple[float, float],
    to: tuple[float, float],
    client: Any = None,
) -> RouteMetrics:
    """Return traffic-aware road metrics, with a deterministic local fallback."""
    fallback = fallback_route(frm, to)
    key_value = (settings.yandex_router_key or "").strip()
    if not settings.taxi_external_pricing_enabled or not key_value:
        return fallback

    # About 100 m precision is enough for price estimation and avoids caching exact addresses.
    cache_key = (
        "route",
        round(frm[0], 3), round(frm[1], 3),
        round(to[0], 3), round(to[1], 3),
    )
    if client is None:
        cached = _cache_get(cache_key)
        if isinstance(cached, RouteMetrics):
            return cached

    params = {
        "apikey": key_value,
        "waypoints": f"{frm[0]:.6f},{frm[1]:.6f}|{to[0]:.6f},{to[1]:.6f}",
        "mode": "driving",
        "traffic": "enabled",
        "avoid_tolls": "true" if settings.taxi_avoid_tolls else "false",
    }
    try:
        getter = client.get if client is not None else httpx.get
        response = getter(
            "https://api.routing.yandex.net/v2/route",
            params=params,
            timeout=settings.taxi_external_timeout_sec,
        )
        response.raise_for_status()
        metrics = _parse_route_payload(response.json())
    except (httpx.HTTPError, KeyError, TypeError, ValueError, OverflowError):
        return fallback

    if client is None:
        _cache_put(cache_key, metrics, settings.taxi_route_cache_sec)
    return metrics


_EXTREME_WEATHER = {
    "heavy-rain", "showers", "snow-showers", "hail",
    "thunderstorm", "thunderstorm-with-rain", "thunderstorm-with-hail",
    "freezing-rain", "blowing-snow", "tornado",
}
_ADVERSE_WEATHER = {
    "rain", "wet-snow", "snow", "light-snow", "light-rain",
    "fog", "mist", "drifting-snow", "ice-pellets",
}


def _weather_metrics_from_fact(fact: dict[str, Any]) -> WeatherMetrics:
    condition = str(fact.get("condition") or "")
    phenomenon = str(
        fact.get("phenom_condition")
        or fact.get("phenom-condition")
        or ""
    )
    code = phenomenon or condition
    temp = _optional_float(fact.get("temp"))
    feels = _optional_float(fact.get("feels_like"))
    gust = _optional_float(fact.get("wind_gust"))
    strength = _optional_float(fact.get("prec_strength")) or 0.0
    thunder = bool(fact.get("is_thunder"))

    extreme = (
        condition in _EXTREME_WEATHER
        or phenomenon in _EXTREME_WEATHER
        or thunder
        or strength >= 0.75
        or (gust is not None and gust >= 20.0)
        or (feels is not None and feels <= -30.0)
    )
    adverse = (
        condition in _ADVERSE_WEATHER
        or phenomenon in _ADVERSE_WEATHER
        or strength >= 0.25
        or (gust is not None and gust >= 12.0)
        or (feels is not None and feels <= -20.0)
    )
    k = settings.taxi_weather_max_k if extreme else (1.05 if adverse else 1.0)
    k = max(1.0, min(float(k), settings.taxi_weather_max_k))
    return WeatherMetrics(
        k=round(k, 3),
        code=code,
        condition=condition,
        available=True,
        temperature_c=temp,
        feels_like_c=feels,
        wind_gust_ms=gust,
    )


def _optional_float(value: Any) -> Optional[float]:
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def weather_metrics(lat: float, lng: float, client: Any = None) -> WeatherMetrics:
    """Return a capped adverse-weather factor; neutral when the provider is unavailable."""
    neutral = WeatherMetrics()
    key_value = (settings.yandex_weather_key or "").strip()
    if not settings.taxi_external_pricing_enabled or not key_value:
        return neutral

    # Weather is city-scale. A ~1 km key avoids retaining an address-level coordinate.
    cache_key = ("weather", round(lat, 2), round(lng, 2))
    if client is None:
        cached = _cache_get(cache_key)
        if isinstance(cached, WeatherMetrics):
            return cached

    try:
        getter = client.get if client is not None else httpx.get
        response = getter(
            "https://api.weather.yandex.ru/v2/forecast",
            params={
                "lat": f"{lat:.5f}",
                "lon": f"{lng:.5f}",
                "lang": "ru_RU",
                "limit": 1,
                "hours": "false",
                "extra": "true",
            },
            headers={"X-Yandex-Weather-Key": key_value},
            timeout=settings.taxi_external_timeout_sec,
        )
        response.raise_for_status()
        payload = response.json()
        fact = payload.get("fact") if isinstance(payload, dict) else None
        if not isinstance(fact, dict):
            return neutral
        metrics = _weather_metrics_from_fact(fact)
    except (httpx.HTTPError, KeyError, TypeError, ValueError, OverflowError):
        return neutral

    if client is None:
        _cache_put(cache_key, metrics, settings.taxi_weather_cache_sec)
    return metrics


def pickup_k_for(pickup_eta_min: Optional[int]) -> float:
    """Smoothly raise the factor only when a known live car has a long pickup."""
    if pickup_eta_min is None:
        return 1.0
    free_min = max(int(settings.taxi_pickup_free_min), 0)
    full_min = max(int(settings.taxi_pickup_full_min), free_min + 1)
    if pickup_eta_min <= free_min:
        return 1.0
    progress = min((pickup_eta_min - free_min) / (full_min - free_min), 1.0)
    value = 1.0 + (settings.taxi_pickup_max_k - 1.0) * progress
    return round(max(1.0, min(value, settings.taxi_pickup_max_k)), 3)


def combine_market_factors(*factors: float, max_k: Optional[float] = None) -> float:
    """Multiply independent signals once and enforce the product-wide fairness cap."""
    value = 1.0
    for factor in factors:
        value *= max(float(factor or 1.0), 1.0)
    cap = float(max_k if max_k is not None else settings.surge_max_k)
    return round(min(value, max(cap, 1.0)), 3)
