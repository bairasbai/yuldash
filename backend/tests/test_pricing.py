"""Unit contracts for the server-owned taxi pricing engine v2."""
import httpx
import pytest

from app import pricing
from app.config import settings


def _route_payload(*, traffic_type="realtime", tolls=False):
    return {
        "traffic_type": traffic_type,
        "route": {
            "flags": {"hasTolls": tolls},
            "legs": [
                {
                    "status": "OK",
                    "steps": [
                        {"length": 7_000, "duration": 1_200},
                        {"length": 5_000, "duration": 600},
                    ],
                }
            ],
        },
    }


def test_parse_route_uses_road_steps_traffic_and_tolls():
    route = pricing._parse_route_payload(_route_payload(tolls=True))

    assert route.source == "yandex"
    assert route.distance_km == 12.0
    assert route.duration_min == 30.0
    assert route.traffic_type == "realtime"
    assert route.traffic_k == pytest.approx(1.667, abs=0.001)
    assert route.has_tolls is True


def test_parse_route_rejects_incomplete_provider_payload():
    with pytest.raises(ValueError):
        pricing._parse_route_payload({"route": {"legs": []}})


def test_fallback_route_is_positive_and_deterministic(monkeypatch):
    monkeypatch.setattr(settings, "instant_road_k", 1.25)
    monkeypatch.setattr(settings, "instant_avg_speed_kmh", 40.0)

    first = pricing.fallback_route((52.591, 58.317), (52.716, 58.664))
    second = pricing.fallback_route((52.591, 58.317), (52.716, 58.664))

    assert first == second
    assert first.source == "fallback"
    assert first.distance_km > 0
    assert first.duration_min == pytest.approx(first.distance_km / 40.0 * 60, abs=0.002)


def test_route_provider_timeout_falls_back_without_breaking_order(monkeypatch):
    class TimeoutClient:
        def get(self, *args, **kwargs):
            raise httpx.TimeoutException("routing timed out")

    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", True)
    monkeypatch.setattr(settings, "yandex_router_key", "test-key")

    result = pricing.route_metrics((52.591, 58.317), (52.716, 58.664), client=TimeoutClient())

    assert result.source == "fallback"
    assert result.distance_km > 0
    assert result.duration_min > 0


def test_route_request_uses_traffic_and_toll_policy(monkeypatch):
    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return _route_payload()

    class RecordingClient:
        def __init__(self):
            self.kwargs = None

        def get(self, *args, **kwargs):
            self.kwargs = kwargs
            return Response()

    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", True)
    monkeypatch.setattr(settings, "yandex_router_key", "test-key")
    monkeypatch.setattr(settings, "taxi_avoid_tolls", True)
    client = RecordingClient()

    result = pricing.route_metrics((52.591, 58.317), (52.716, 58.664), client=client)

    assert result.source == "yandex"
    assert client.kwargs["params"]["traffic"] == "enabled"
    assert client.kwargs["params"]["avoid_tolls"] == "true"
    assert client.kwargs["timeout"] == settings.taxi_external_timeout_sec


@pytest.mark.parametrize(
    ("fact", "expected"),
    [
        ({"condition": "clear", "wind_gust": 2}, 1.0),
        ({"condition": "rain", "wind_gust": 5}, 1.05),
        ({"condition": "clear", "is_thunder": True}, None),
        ({"condition": "clear", "feels_like": -31}, None),
    ],
)
def test_weather_factor_is_neutral_adverse_or_capped(monkeypatch, fact, expected):
    monkeypatch.setattr(settings, "taxi_weather_max_k", 1.10)

    weather = pricing._weather_metrics_from_fact(fact)

    assert weather.available is True
    assert weather.k == pytest.approx(1.10 if expected is None else expected)


def test_pickup_factor_is_smooth_and_capped(monkeypatch):
    monkeypatch.setattr(settings, "taxi_pickup_free_min", 5)
    monkeypatch.setattr(settings, "taxi_pickup_full_min", 15)
    monkeypatch.setattr(settings, "taxi_pickup_max_k", 1.12)

    assert pricing.pickup_k_for(None) == 1.0
    assert pricing.pickup_k_for(5) == 1.0
    assert pricing.pickup_k_for(10) == pytest.approx(1.06)
    assert pricing.pickup_k_for(15) == pytest.approx(1.12)
    assert pricing.pickup_k_for(60) == pytest.approx(1.12)


def test_all_market_factors_share_one_global_cap():
    value = pricing.combine_market_factors(1.5, 1.10, 1.12, 1.20, max_k=1.5)

    assert value == 1.5
