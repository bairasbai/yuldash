# -*- coding: utf-8 -*-
"""Погоду спрашиваем только там, где возим людей.

Аудит 2026-08-12, волна 35. Ручка погоды открыта без входа — так и надо. Но кеш её защищает
только от ПОВТОРОВ: ключ кеша это координаты (клетка 5 км) и час выезда, а их задаёт тот,
кто зовёт. Значит один запрос к нам = один запрос к Open-Meteo с нашего сервера.

Проба: 40 анонимных обращений с координатами по всему миру → 40 походов наружу, в том числе
за погодой в Тихом океане. У Open-Meteo бесплатный тариф с суточным потолком и баном по IP:
выбрать его с одного телефона означало бы оставить без погоды настоящих людей — и они увидят
не «сервис перегружен», а просто пустое место там, где было предупреждение о гололёде.

Починка — рамка нашей географии (от Калининграда до Красноярска, от Сочи до Салехарда)
и запрет спрашивать погоду в прошлом.
"""
import pytest

from app import weather_warn


@pytest.fixture
def counted_meteo(monkeypatch):
    """Считаем походы к Open-Meteo вместо настоящих. Ответ — пустой, но валидный."""
    calls = {"n": 0, "sent": []}

    class _Resp:
        status_code = 200

        def json(self):
            return {"hourly": {"time": [], "temperature_2m": []}}

    def _get(url, params=None, timeout=None, **kw):
        calls["n"] += 1
        calls["sent"].append((params or {}).get("latitude"))
        return _Resp()

    monkeypatch.setattr(weather_warn.httpx, "get", _get)
    return calls


def test_чужая_география_наружу_не_уходит(client, counted_meteo):
    """Перебор координат по миру не превращает наш сервер в насос по чужому сервису."""
    lat, lng = 10.0, 10.0
    for _ in range(20):
        lat += 0.7
        lng += 1.3
        r = client.get("/weather/route", params={
            "from_lat": lat % 80 - 40, "from_lng": lng % 170 - 85,
            "to_lat": (lat + 3) % 80 - 40, "to_lng": (lng + 3) % 170 - 85,
        })
        assert r.status_code == 200
        assert r.json()["available"] is False        # честно «данных нет», а не ошибка

    assert counted_meteo["n"] == 0, (
        f"сходили наружу {counted_meteo['n']} раз за погодой вне нашей географии — "
        "так выбирается суточный лимит и приезжает бан по IP"
    )


def test_наши_маршруты_погоду_получают(client, counted_meteo):
    """Защита не должна ломать саму фичу: Баймак → Сибай спрашиваем как спрашивали."""
    r = client.get("/weather/route", params={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.71, "to_lng": 58.66,
    })
    assert r.status_code == 200, r.text
    assert counted_meteo["n"] == 1, "погода по нашему маршруту перестала спрашиваться"


def test_дальняя_поездка_из_башкирии_тоже_считается_нашей(client, counted_meteo):
    """Уфа → Москва и Уфа → Сочи — реальные поездки, рамка обязана их пропускать."""
    for to_lat, to_lng in ((55.75, 37.62), (43.58, 39.72)):     # Москва, Сочи
        r = client.get("/weather/route", params={
            "from_lat": 54.73, "from_lng": 55.97, "to_lat": to_lat, "to_lng": to_lng,
        })
        assert r.status_code == 200, r.text
    assert counted_meteo["n"] == 2, "дальние, но настоящие маршруты потеряли погоду"


def test_погода_в_прошлом_нам_не_нужна(client, counted_meteo):
    """Погода нужна ПЕРЕД выездом. Запрос про позавчера — это перебор ключей, а не поездка."""
    r = client.get("/weather/route", params={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.71, "to_lng": 58.66,
        "at": "2020-01-01T10:00:00",
    })
    assert r.status_code == 200
    assert r.json()["available"] is False
    assert counted_meteo["n"] == 0


def test_смешанный_маршрут_берёт_только_свою_часть(client, counted_meteo):
    """Начало наше, конец на другом конце света: спрашиваем погоду по нашей точке, не роняя экран."""
    r = client.get("/weather/route", params={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": -33.86, "to_lng": 151.20,   # Сидней
    })
    assert r.status_code == 200, r.text
    assert counted_meteo["n"] == 1
    assert counted_meteo["sent"][0] == "52.6", counted_meteo["sent"]   # ушла только наша точка
