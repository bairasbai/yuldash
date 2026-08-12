"""Погода на маршруте: предупреждение о гололёде, метели, тумане, морозе.

Проверяем ровно то, ради чего это сделано:
  • опасное на дороге названо и названо ПЕРВЫМ (гололёд выше тумана);
  • обычная погода молчит — карточка не должна мозолить глаза каждый день;
  • сбой источника НЕ ломает поездку (нет данных → нет карточки, а не ошибка);
  • точные координаты человека не уходят наружу (округление до сетки ~5 км).
"""
from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from app import weather_warn
from app.timeutil import utcnow


class _Resp:
    def __init__(self, payload: Any, status: int = 200):
        self._payload = payload
        self.status_code = status

    def json(self):
        return self._payload


class _Client:
    """Подставной HTTP: помнит, с какими параметрами позвали, и отдаёт заготовку."""

    def __init__(self, payload: Any, status: int = 200):
        self._payload = payload
        self._status = status
        self.last_params: dict[str, Any] | None = None
        self.calls = 0

    def get(self, url: str, params=None, timeout=None):  # noqa: ANN001
        self.calls += 1
        self.last_params = params or {}
        if self._status != 200:
            return _Resp({}, self._status)
        return _Resp(self._payload, 200)


def _hourly(**values: Any) -> dict[str, Any]:
    """Один час прогноза на нужное время. Остальное — нули (нормальная погода)."""
    at = utcnow().replace(minute=0, second=0, microsecond=0)
    base = {
        "temperature_2m": [5.0],
        "apparent_temperature": [4.0],
        "precipitation": [0.0],
        "snowfall": [0.0],
        "weather_code": [0],
        "wind_speed_10m": [5.0],
        "wind_gusts_10m": [7.0],
        "visibility": [20000.0],
    }
    base.update({k: [v] for k, v in values.items()})
    return {"hourly": {"time": [at.strftime("%Y-%m-%dT%H:00")], **base}}


def _kinds(result) -> list[str]:
    return [w.kind for w in result.warnings]


# --------------------------- опасное названо ---------------------------

def test_гололёд_назван_и_помечен_серьёзным():
    """Переохлаждённый дождь (WMO 66) — самое опасное на дороге: асфальт выглядит мокрым."""
    client = _Client(_hourly(weather_code=66, temperature_2m=-1.0, precipitation=0.4))
    result = weather_warn.route_weather([(52.59, 58.31)], client=client)
    assert result.available is True
    assert "ice" in _kinds(result)
    ice = next(w for w in result.warnings if w.kind == "ice")
    assert ice.severe is True
    assert ice.ru and ice.ba and ice.ru != ice.ba      # обе строки есть и они разные


def test_дождь_при_нуле_это_тоже_гололёд():
    """Кода гололёда может не быть, а лёд на дороге — есть: дождь при температуре ≤ 0."""
    client = _Client(_hourly(weather_code=61, temperature_2m=0.0, precipitation=0.5))
    assert "ice" in _kinds(weather_warn.route_weather([(52.59, 58.31)], client=client))


def test_метель_это_снег_плюс_ветер():
    """Снег сам по себе — «сильный снегопад», снег с ветром от 10 м/с — уже метель."""
    snow_only = _Client(_hourly(weather_code=73, snowfall=1.2, wind_speed_10m=10.0))
    assert "snow" in _kinds(weather_warn.route_weather([(52.59, 58.31)], client=snow_only))

    blizzard = _Client(_hourly(weather_code=73, snowfall=1.2, wind_speed_10m=54.0))  # 15 м/с
    kinds = _kinds(weather_warn.route_weather([(52.59, 58.31)], client=blizzard))
    assert "blizzard" in kinds and "snow" not in kinds   # не дублируем одно другим


def test_туман_и_сильный_ветер_и_мороз():
    fog = _Client(_hourly(weather_code=45, visibility=200.0))
    assert "fog" in _kinds(weather_warn.route_weather([(52.59, 58.31)], client=fog))

    wind = _Client(_hourly(wind_gusts_10m=90.0))          # 25 м/с
    assert "wind" in _kinds(weather_warn.route_weather([(52.59, 58.31)], client=wind))

    frost = _Client(_hourly(temperature_2m=-30.0, apparent_temperature=-33.0))
    assert "frost" in _kinds(weather_warn.route_weather([(52.59, 58.31)], client=frost))


def test_самое_опасное_идёт_первым():
    """Человек читает первую строку. Там должен быть гололёд, а не туман."""
    client = _Client(_hourly(weather_code=66, temperature_2m=-1.0, precipitation=0.4,
                             visibility=200.0))
    result = weather_warn.route_weather([(52.59, 58.31)], client=client)
    assert result.warnings[0].kind == "ice"
    assert "fog" in _kinds(result)


# --------------------------- молчание в обычный день ---------------------------

def test_нормальная_погода_ничего_не_говорит():
    """Плюс пять и ясно — карточки быть не должно. Иначе её перестанут читать вовсе."""
    client = _Client(_hourly())
    result = weather_warn.route_weather([(52.59, 58.31)], client=client)
    assert result.available is True
    assert result.warnings == []


# --------------------------- сбой источника не ломает поездку ---------------------------

@pytest.mark.parametrize("status", [429, 500, 503])
def test_ошибка_источника_это_просто_нет_карточки(status: int):
    client = _Client({}, status=status)
    result = weather_warn.route_weather([(52.59, 58.31)], client=client)
    assert result.available is False and result.warnings == []


def test_источник_упал_совсем():
    class _Broken:
        def get(self, *a, **k):  # noqa: ANN002, ANN003
            raise RuntimeError("сеть отвалилась")

    result = weather_warn.route_weather([(52.59, 58.31)], client=_Broken())
    assert result.available is False


def test_без_координат_ничего_не_спрашиваем():
    client = _Client(_hourly())
    result = weather_warn.route_weather([], client=client)
    assert result.available is False
    assert client.calls == 0     # пустой маршрут не должен ходить в сеть


# --------------------------- приватность ---------------------------

def test_точные_координаты_наружу_не_уходят():
    """Погода — явление масштаба города. Внешнему сервису адрес человека знать незачем."""
    client = _Client(_hourly())
    weather_warn.route_weather([(52.593817, 58.317422)], client=client)
    sent_lat = float(client.last_params["latitude"])
    sent_lng = float(client.last_params["longitude"])
    assert sent_lat != 52.593817 and sent_lng != 58.317422
    assert abs(sent_lat - 52.593817) <= weather_warn._GRID
    assert abs(sent_lng - 58.317422) <= weather_warn._GRID


def test_маршрут_спрашивается_одним_запросом():
    """Начало и конец — один запрос, а не два: у источника лимит, а у нас лишняя задержка."""
    client = _Client([_hourly(), _hourly(weather_code=66, temperature_2m=-1.0, precipitation=0.4)])
    result = weather_warn.route_weather([(52.59, 58.31), (53.68, 58.66)], client=client)
    assert client.calls == 1
    assert "," in client.last_params["latitude"]
    # Худшее по маршруту: на выезде чисто, под Сибаем гололёд — сказать надо про гололёд.
    assert "ice" in _kinds(result)


def test_одинаковое_по_маршруту_говорим_один_раз():
    client = _Client([_hourly(weather_code=45, visibility=200.0),
                      _hourly(weather_code=45, visibility=150.0)])
    result = weather_warn.route_weather([(52.59, 58.31), (53.68, 58.66)], client=client)
    assert _kinds(result).count("fog") == 1


# --------------------------- время выезда ---------------------------

def test_далёкое_будущее_не_спрашиваем():
    """Прогноз дальше трёх суток источник не отдаёт — не выдумываем и не тратим запрос."""
    client = _Client(_hourly())
    result = weather_warn.route_weather([(52.59, 58.31)], when=utcnow() + timedelta(days=10),
                                        client=client)
    assert result.available is False and client.calls == 0
