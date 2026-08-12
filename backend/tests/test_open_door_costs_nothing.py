# -*- coding: utf-8 -*-
"""Открытая дверь не должна стоить нам денег.

Аудит 2026-08-12, волна 34. Ручка погоды правильно открыта без входа в аккаунт — погода
не персональные данные. Но названия городов она резолвила общим помощником, у которого
последний шаг — ПЛАТНЫЙ Яндекс.Геокодер с суточным лимитом.

Пробой: 25 анонимных обращений с выдуманными названиями = 50 платных запросов. Лимит на день
выбирается за минуту, а вместе с ним ложится радиус-поиск поездок у настоящих людей — они
даже не поймут, почему «рядом никого».

Теперь погода ходит только по своим спискам. Тесты проверяют обе стороны: наружу не ходим,
но настоящие города по-прежнему находим.
"""
import pytest

from app import services
from app.config import settings


@pytest.fixture
def counted_geocoder(monkeypatch):
    """Ключ «как в проде» + счётчик исходящих запросов вместо реальных денег."""
    calls = {"n": 0}

    class _Resp:
        status_code = 200

        def json(self):
            return {"response": {"GeoObjectCollection": {"featureMember": []}}}

    def _get(url, *a, **kw):
        if "geocode-maps.yandex.ru" in url:
            calls["n"] += 1
        return _Resp()

    monkeypatch.setattr(settings, "yandex_geocoder_key", "TEST-KEY")
    import httpx
    monkeypatch.setattr(httpx, "get", _get)
    return calls


def test_аноним_не_может_потратить_наш_платный_геокодер(client, counted_geocoder):
    """Выдуманные названия из открытой двери наружу не уходят."""
    for i in range(15):
        r = client.get("/weather/route", params={"from_city": f"Такогогородатнет{i}",
                                                 "to_city": f"Иэтоготоже{i}"})
        assert r.status_code == 200, r.text
        assert r.json()["available"] is False       # честно «данных нет», а не ошибка

    assert counted_geocoder["n"] == 0, (
        f"открытая дверь сходила к платному геокодеру {counted_geocoder['n']} раз — "
        "суточный лимит выбирается за минуту, и вместе с ним ложится поиск поездок"
    )


def test_настоящие_города_погода_по_прежнему_находит(client, counted_geocoder, monkeypatch):
    """Защита не должна ломать саму фичу: свой справочник работает как работал."""
    monkeypatch.setattr(services, "CITY_COORDS", {**services.CITY_COORDS, "Баймак": (52.59, 58.31)})
    r = client.get("/weather/route", params={"from_city": "Баймак", "to_city": "Баймак"})
    assert r.status_code == 200, r.text
    assert counted_geocoder["n"] == 0               # свои списки, наружу не ходили


def test_помощник_умеет_ходить_наружу_там_где_это_разрешено(counted_geocoder):
    """Обычные (авторизованные) места как ходили к геокодеру, так и ходят — их не трогали."""
    services.geocode_city("Совершенно неизвестное место 12345")
    assert counted_geocoder["n"] == 1, "запрет протёк на остальные вызовы — так фичи сломаются"


def test_запрет_наружу_работает_на_уровне_помощника(counted_geocoder):
    """Тот же вызов с явным запретом наружу не идёт — правило живёт в одной точке."""
    assert services.geocode_city("Совершенно неизвестное место 12345", allow_external=False) is None
    assert counted_geocoder["n"] == 0
