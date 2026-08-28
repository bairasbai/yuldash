"""Regression tests for discovery/feed/geocode/media endpoints."""

import base64

import httpx

from app.config import settings
from app.models import UserRole

from test_flows import _book, _publish


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def json(self):
        return self.payload


def test_popular_routes_and_feed_return_cache_hits(client, monkeypatch):
    def fake_cache_get(key):
        if key == "popular_routes:v1":
            return [{"from_city": "CachedA", "to_city": "CachedB", "count": 9}]
        if key == "feed:v2":
            return {"today": 1, "week": 2, "month": 3, "year": 4, "drivers": 5, "top_route": None, "donations_total": 6}
        return None

    monkeypatch.setattr("app.routers.discovery.cache_get_json", fake_cache_get)
    monkeypatch.setattr("app.routers.discovery.cache_set_json", lambda *_args, **_kwargs: None)

    assert client.get("/popular-routes").json() == [{"from_city": "CachedA", "to_city": "CachedB", "count": 9}]
    assert client.get("/feed").json()["donations_total"] == 6


def test_geocode_uses_cache_and_parses_remote_response(client, monkeypatch, user_factory):
    auth = user_factory("ГеоКеш")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda key: {"items": [{"title": "Cached", "lat": 1, "lon": 2}]} if key.endswith("cached") else None)
    cached = client.get("/geocode", headers=auth, params={"q": "cached"}).json()
    assert cached == {"items": [{"title": "Cached", "lat": 1, "lon": 2}]}

    saved = {}

    def fake_set(key, value, ttl):
        saved["key"] = key
        saved["value"] = value
        saved["ttl"] = ttl

    def fake_get(url, params, timeout):
        return FakeResponse({
            "response": {
                "GeoObjectCollection": {
                    "featureMember": [
                        {"GeoObject": {"name": "Ufa", "description": "Bashkortostan", "Point": {"pos": "55.958 54.735"}}},
                        {"GeoObject": {"name": "Bad", "Point": {"pos": "broken"}}},
                        {"GeoObject": {"name": "", "Point": {"pos": "58.664 52.716"}}},
                    ],
                },
            },
        })

    monkeypatch.setattr("app.routers.discovery.cache_set_json", fake_set)
    monkeypatch.setattr(httpx, "get", fake_get)
    body = client.get("/geocode", headers=auth, params={"q": "remote"}).json()
    assert body == {"items": [{"title": "Ufa, Bashkortostan", "lat": 54.735, "lon": 55.958}]}
    assert saved["key"] == "geocode:v1:remote"
    assert saved["ttl"] == 86400

    monkeypatch.setattr(httpx, "get", lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("network")))
    assert client.get("/geocode", headers=auth, params={"q": "error"}).json() == {"items": []}


def test_my_routes_counts_passenger_history(client, user_factory):
    driver = user_factory("MyRoutesDriver", role=UserRole.driver)
    passenger = user_factory("MyRoutesPassenger")
    # Две поездки по одному маршруту, но РАЗНЫЕ (комментарий свой): байт-в-байт одинаковые
    # схлопываются как двойной тап (2026-08-06), а тут нужны именно две настоящие поездки.
    ride_a = _publish(client, driver, frm="RouteA", to="RouteB", comment="рейс 1")
    ride_b = _publish(client, driver, frm="RouteA", to="RouteB", comment="рейс 2")
    ride_c = _publish(client, driver, frm="RouteC", to="RouteD")
    _book(client, passenger, ride_a["id"])
    _book(client, passenger, ride_b["id"])
    _book(client, passenger, ride_c["id"])

    routes = client.get("/my-routes", headers=passenger["auth"]).json()
    assert routes[0] == {"from_city": "RouteA", "to_city": "RouteB", "count": 2}
    assert {"from_city": "RouteC", "to_city": "RouteD", "count": 1} in routes


def test_chat_photo_upload_accepts_real_image_and_rejects_fake_image(client, user_factory):
    user = user_factory("ChatPhotoUser")
    png = base64.b64encode(b"\x89PNG\r\n\x1a\nimage-bytes").decode()
    ok = client.post("/upload/chat-photo", headers=user["auth"], json={"photo_b64": png, "ext": "png"})
    assert ok.status_code == 200
    assert "/media/chat/" in ok.json()["url"]
    assert ok.json()["url"].endswith(".png")

    fake = base64.b64encode(b"not-a-jpeg").decode()
    bad = client.post("/upload/chat-photo", headers=user["auth"], json={"photo_b64": fake, "ext": "jpg"})
    assert bad.status_code == 400


def test_reverse_geocode_returns_house_address(client, monkeypatch, user_factory):
    """Пин на карте превращается в «улица, дом».

    Без этого в заказ уходило безымянное «Точка на карте»: водитель в списке заказов
    не отличал одну такую точку от другой, а пассажир через месяц не понимал, куда ездил.
    """
    auth = user_factory("ГеоОбратный")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)

    saved = {}
    monkeypatch.setattr("app.routers.discovery.cache_set_json",
                        lambda key, value, ttl: saved.update(key=key, value=value, ttl=ttl))

    sent = {}

    def fake_get(url, params, timeout):
        sent.update(params)
        return FakeResponse({
            "response": {"GeoObjectCollection": {"featureMember": [
                {"GeoObject": {"name": "Ленина, 12", "description": "Баймак, Башкортостан"}},
            ]}},
        })

    monkeypatch.setattr(httpx, "get", fake_get)
    body = client.get("/geocode/reverse", headers=auth, params={"lat": 52.5905, "lng": 58.314}).json()

    assert body == {"title": "Ленина, 12"}
    # Яндексу координаты идут «долгота,широта» — обратным порядком. Перепутать легко,
    # а результат будет правдоподобным: адрес найдётся, но в другой точке мира.
    assert sent["geocode"] == "58.314,52.5905"
    assert saved["ttl"] == 86400
    assert saved["key"] == "revgeo:v1:52.59050,58.31400"


def test_reverse_geocode_survives_network_failure(client, monkeypatch, user_factory):
    """Сеть отвалилась — отвечаем пустым, а не пятисотим и не кешируем ошибку.

    Пустой ответ клиент показывает как прежнее «Точка на карте»: координаты у водителя
    есть, заказ из-за адреса ломать нельзя. В деревне связь пропадает, а ехать надо сейчас.
    """
    auth = user_factory("ГеоСбой")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)

    written = []
    monkeypatch.setattr("app.routers.discovery.cache_set_json",
                        lambda *a, **k: written.append(a))
    monkeypatch.setattr(httpx, "get",
                        lambda *_a, **_k: (_ for _ in ()).throw(RuntimeError("network")))

    r = client.get("/geocode/reverse", headers=auth, params={"lat": 52.59, "lng": 58.31})
    assert r.status_code == 200 and r.json() == {"title": ""}
    assert written == [], "ошибку сети кешировать нельзя — в следующий раз надо попробовать снова"


def test_reverse_geocode_rejects_impossible_coordinates(client, monkeypatch, user_factory):
    """Координаты вне глобуса — не запрос, а мусор. Платный вызов на них не тратим."""
    auth = user_factory("ГеоМусор")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    called = []
    monkeypatch.setattr(httpx, "get", lambda *_a, **_k: called.append(1))

    assert client.get("/geocode/reverse", headers=auth,
                      params={"lat": 999, "lng": 0}).json() == {"title": ""}
    assert client.get("/geocode/reverse", headers=auth,
                      params={"lat": 0, "lng": -900}).json() == {"title": ""}
    assert called == [], "до Яндекса такие координаты доходить не должны"


def test_reverse_geocode_returns_house_address(client, monkeypatch, user_factory):
    """Пин на карте превращается в «улица, дом».

    Без этого в заказ уходило безымянное «Точка на карте»: водитель в списке заказов
    не отличал одну такую точку от другой, а пассажир через месяц не понимал, куда ездил.
    """
    auth = user_factory("ГеоОбратный")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)

    saved = {}
    monkeypatch.setattr("app.routers.discovery.cache_set_json",
                        lambda key, value, ttl: saved.update(key=key, value=value, ttl=ttl))

    sent = {}

    def fake_get(url, params, timeout):
        sent.update(params)
        return FakeResponse({
            "response": {"GeoObjectCollection": {"featureMember": [
                {"GeoObject": {"name": "Ленина, 12", "description": "Баймак, Башкортостан"}},
            ]}},
        })

    monkeypatch.setattr(httpx, "get", fake_get)
    body = client.get("/geocode/reverse", headers=auth, params={"lat": 52.5905, "lng": 58.314}).json()

    assert body == {"title": "Ленина, 12"}
    # Яндексу координаты идут «долгота,широта» — обратным порядком. Перепутать легко,
    # а результат будет правдоподобным: адрес найдётся, но в другой точке мира.
    assert sent["geocode"] == "58.314,52.5905"
    assert saved["ttl"] == 86400
    assert saved["key"] == "revgeo:v1:52.59050,58.31400"


def test_reverse_geocode_survives_network_failure(client, monkeypatch, user_factory):
    """Сеть отвалилась — отвечаем пустым, а не пятисотим и не кешируем ошибку.

    Пустой ответ клиент показывает как прежнее «Точка на карте»: координаты у водителя
    есть, заказ из-за адреса ломать нельзя. В деревне связь пропадает, а ехать надо сейчас.
    """
    auth = user_factory("ГеоСбой")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    monkeypatch.setattr("app.routers.discovery.cache_get_json", lambda _key: None)

    written = []
    monkeypatch.setattr("app.routers.discovery.cache_set_json",
                        lambda *a, **k: written.append(a))
    monkeypatch.setattr(httpx, "get",
                        lambda *_a, **_k: (_ for _ in ()).throw(RuntimeError("network")))

    r = client.get("/geocode/reverse", headers=auth, params={"lat": 52.59, "lng": 58.31})
    assert r.status_code == 200 and r.json() == {"title": ""}
    assert written == [], "ошибку сети кешировать нельзя — в следующий раз надо попробовать снова"


def test_reverse_geocode_rejects_impossible_coordinates(client, monkeypatch, user_factory):
    """Координаты вне глобуса — не запрос, а мусор. Платный вызов на них не тратим."""
    auth = user_factory("ГеоМусор")["auth"]
    monkeypatch.setattr(settings, "yandex_geocoder_key", "key")
    called = []
    monkeypatch.setattr(httpx, "get", lambda *_a, **_k: called.append(1))

    assert client.get("/geocode/reverse", headers=auth,
                      params={"lat": 999, "lng": 0}).json() == {"title": ""}
    assert client.get("/geocode/reverse", headers=auth,
                      params={"lat": 0, "lng": -900}).json() == {"title": ""}
    assert called == [], "до Яндекса такие координаты доходить не должны"
