"""Ручка /weather/route: та же проверка, но снаружи — как её зовёт приложение."""
from __future__ import annotations

import app.routers.weather as weather_router
from app.weather_warn import RouteWeather, Warning


def _fake(monkeypatch, result: RouteWeather):
    monkeypatch.setattr(weather_router, "route_weather", lambda *a, **k: result)


def test_маршрут_по_городам_без_координат(client, monkeypatch):
    """В форме публикации человек печатает названия — координат у клиента нет вовсе."""
    _fake(monkeypatch, RouteWeather(
        available=True,
        warnings=[Warning("ice", "Гололёд на дороге.", "Юлда быҙлауыҡ.", severe=True)],
        temperature_c=-3.0,
    ))
    r = client.get("/weather/route", params={"from_city": "Баймак", "to_city": "Сибай"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["available"] is True
    assert body["warnings"][0]["kind"] == "ice"
    assert body["warnings"][0]["severe"] is True
    assert body["warnings"][0]["ru"] and body["warnings"][0]["ba"]


def test_незнакомый_город_это_не_ошибка(client):
    """Человек ещё дописывает название — форму ронять из-за погоды нельзя."""
    r = client.get("/weather/route", params={"from_city": "Такогогородатнет"})
    assert r.status_code == 200
    assert r.json()["available"] is False


def test_без_параметров_тоже_не_ошибка(client):
    r = client.get("/weather/route")
    assert r.status_code == 200 and r.json()["available"] is False


def test_кривое_время_не_роняет(client, monkeypatch):
    """Клиент прислал мусор вместо ISO — смотрим погоду на сейчас, а не падаем."""
    _fake(monkeypatch, RouteWeather(available=True))
    r = client.get("/weather/route", params={"from_lat": 52.59, "from_lng": 58.31, "at": "вчера"})
    assert r.status_code == 200 and r.json()["available"] is True


# Тестов «погода по id поездки» нет: ручку убрали. Причина — в app/routers/weather.py внизу:
# отвечая на любой id, она подтверждала посторонему существование чужой поездки.
