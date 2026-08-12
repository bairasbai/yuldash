"""Предупреждение о погоде на маршруте: гололёд, метель, туман, мороз.

Зачем. Трасса Сибай — Уфа зимой это четыре часа, и решение «ехать сегодня или переждать»
человек принимает ДО выезда. Приложение знало погоду только для цены такси (сурж), а тому,
кто садится за руль, не говорило ничего. Предупреждение не запрещает поездку и ничего не
блокирует — оно даёт факт вовремя.

Источник — Open-Meteo (https://open-meteo.com): открытые данные, БЕЗ API-ключа и регистрации.
Это сознательный выбор: в проекте уже есть погода Яндекса для сурж-коэффициента, но она
требует платный ключ и потому выключена — а предупреждение о гололёде не должно ждать
оплаченного тарифа. Ограничение источника: бесплатно для некоммерческого использования,
~10 000 запросов в сутки. Наш расход при кеше — единицы запросов в час на город.

Приватность (§8). Координаты округляем до 0.05° (~5 км) ПЕРЕД запросом: погода — явление
масштаба города, точный адрес человека внешнему сервису не нужен и не уходит. Тот же приём,
что у геокодера и у погоды в pricing.py.

Что считаем опасным (WMO weather_code + числа):
    гололёд        56,57,66,67 — переохлаждённая морось и дождь; либо дождь при t ≤ 0
    метель         снег + ветер от 10 м/с
    сильный снег   снегопад от 1 см/ч, коды 75,86
    туман          45,48
    сильный ветер  порывы от 20 м/с
    мороз          ощущается как −25 и ниже
    гроза          95,96,99
Пороги подобраны под Башкортостан: −25 здесь обычная зима, а не бедствие, поэтому планка
именно там, где становится опасно для дороги, а не «холодно».
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Iterable, Optional

import httpx

from .config import settings
from .services import cache_get_json, cache_set_json
from .timeutil import utcnow

_API_URL = "https://api.open-meteo.com/v1/forecast"
_HOURLY = (
    "temperature_2m,apparent_temperature,precipitation,snowfall,"
    "weather_code,wind_speed_10m,wind_gusts_10m,visibility"
)
# Погода — масштаб города. 0.05° ≈ 5 км: точный адрес наружу не уходит, а кеш попадает чаще.
_GRID = 0.05
_CACHE_TTL_SEC = 1800          # полчаса: прогноз на час вперёд так часто не меняется

# Где мы вообще возим людей. Кеш спасает от повторов, но ключ кеша — координаты и час выезда,
# а их задаёт тот, кто зовёт: ручка открыта без входа, и один запрос к нам = один запрос
# к Open-Meteo с НАШЕГО сервера (проба волны 35 — 40 обращений, 40 походов наружу,
# координаты в Тихом океане). У Open-Meteo бесплатный тариф с суточным потолком и баном
# по IP: выбрать его с любого телефона означало бы оставить без погоды настоящих людей.
#
# Рамка нарочно щедрая: от Калининграда до Красноярска и от Сочи до Салехарда. Любой реальный
# маршрут Юлдаша (Баймак → Сибай, Уфа → Москва, Уфа → Сочи) внутри; весь остальной мир —
# это не наши поездки, а перебор.
_BBOX_LAT = (40.0, 72.0)
_BBOX_LNG = (19.0, 100.0)


def _in_service_area(lat: float, lng: float) -> bool:
    return _BBOX_LAT[0] <= lat <= _BBOX_LAT[1] and _BBOX_LNG[0] <= lng <= _BBOX_LNG[1]
_HTTP_TIMEOUT_SEC = 6.0        # не заставляем человека ждать: нет ответа — просто нет карточки

# Коды WMO, при которых на дороге лёд. Самое опасное из всего списка: тормозной путь растёт
# в разы, и понять это по виду из окна нельзя — асфальт выглядит просто мокрым.
_ICE_CODES = {56, 57, 66, 67}
_FOG_CODES = {45, 48}
_HEAVY_SNOW_CODES = {75, 86}
_SNOW_CODES = {71, 73, 75, 77, 85, 86}
_THUNDER_CODES = {95, 96, 99}


@dataclass(frozen=True)
class Warning:
    """Одно предупреждение. `kind` — для клиента (иконка/цвет), тексты — готовые к показу."""
    kind: str
    ru: str
    ba: str
    severe: bool = False        # true → красная карточка, false → жёлтая


@dataclass
class RouteWeather:
    """Что показать перед поездкой. Пусто и `available=False` — карточки нет вовсе."""
    available: bool = False
    warnings: list[Warning] = field(default_factory=list)
    temperature_c: Optional[float] = None
    source: str = "open-meteo"


def _grid(value: float) -> float:
    """Округление координаты до сетки ~5 км (и заодно ключ кеша)."""
    return round(round(value / _GRID) * _GRID, 3)


def _ms(kmh: Any) -> Optional[float]:
    """Open-Meteo отдаёт ветер в км/ч, а пороги у нас в м/с — как принято у синоптиков."""
    try:
        return float(kmh) / 3.6
    except (TypeError, ValueError):
        return None


def _num(value: Any) -> Optional[float]:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _pick_hour(hourly: dict[str, Any], when: datetime) -> dict[str, Any]:
    """Значения на нужный час. Прогноз почасовой, время выезда — любое: берём ближайший час.

    Если время выезда за пределами прогноза (заявка на неделю вперёд), берём последний
    доступный час, а не падаем: лучше показать погоду «на конец прогноза», чем ничего.
    """
    times: list[str] = hourly.get("time") or []
    if not times:
        return {}
    target = when.strftime("%Y-%m-%dT%H:00")
    idx = 0
    for i, t in enumerate(times):
        if t <= target:
            idx = i
        else:
            break
    return {key: (values[idx] if isinstance(values, list) and idx < len(values) else None)
            for key, values in hourly.items() if key != "time"}


def _warnings_for(hour: dict[str, Any]) -> list[Warning]:
    """Числа и код погоды → человеческие предупреждения. Порядок = порядок важности."""
    out: list[Warning] = []
    code = int(_num(hour.get("weather_code")) or -1)
    temp = _num(hour.get("temperature_2m"))
    feels = _num(hour.get("apparent_temperature"))
    snow = _num(hour.get("snowfall")) or 0.0            # см/ч
    rain = _num(hour.get("precipitation")) or 0.0       # мм/ч
    wind = _ms(hour.get("wind_speed_10m"))
    gust = _ms(hour.get("wind_gusts_10m"))
    visibility = _num(hour.get("visibility"))           # метры

    icy = code in _ICE_CODES or (rain >= 0.1 and temp is not None and temp <= 0.0)
    if icy:
        out.append(Warning(
            "ice",
            "Гололёд на дороге. Тормозной путь длиннее в разы — держи дистанцию больше обычной.",
            "Юлда быҙлауыҡ. Туҡтау юлы бермә-бер оҙонораҡ — ғәҙәттәгенән ҙурыраҡ ара тот.",
            severe=True,
        ))

    blizzard = code in _SNOW_CODES and wind is not None and wind >= 10.0
    if blizzard:
        out.append(Warning(
            "blizzard",
            "Метель: снег с сильным ветром. Дорогу может переметать, видимость падает.",
            "Буран: ҡар менән көслө ел. Юлды ҡар баҫыуы мөмкин, күренеү кәмей.",
            severe=True,
        ))
    elif code in _HEAVY_SNOW_CODES or snow >= 1.0:
        out.append(Warning(
            "snow",
            "Сильный снегопад. Ехать дольше обычного — заложи запас времени.",
            "Көслө ҡар яуа. Ғәҙәттәгенән оҙағыраҡ барырһың — ваҡытты запас менән ал.",
        ))

    if code in _FOG_CODES or (visibility is not None and visibility <= 500):
        out.append(Warning(
            "fog",
            "Туман: видимость меньше 500 метров. Ближний свет и без обгонов.",
            "Томан: күренеү 500 метрҙан кәм. Яҡын ут менән бар, уҙып китмә.",
        ))

    if gust is not None and gust >= 20.0:
        out.append(Warning(
            "wind",
            "Порывы ветра больше 20 м/с. На трассе может сносить, особенно с прицепом.",
            "Ел ҡағыуы 20 м/с-тан ашыу. Трассала машинаны ситкә этеүе мөмкин.",
        ))

    if feels is not None and feels <= -25.0:
        out.append(Warning(
            "frost",
            f"Мороз: ощущается как {round(feels)}°. Если станешь на трассе — помощи ждать долго.",
            f"Һыуыҡ: {round(feels)}° кеүек тойола. Трассала туҡтаһаң — ярҙам оҙаҡ көтөләсәк.",
            severe=feels <= -35.0,
        ))

    if code in _THUNDER_CODES:
        out.append(Warning(
            "thunder",
            "Гроза с ливнем. Возможны лужи и плохая видимость.",
            "Йәшенле ямғыр. Күлдәүектәр һәм насар күренеү булыуы мөмкин.",
        ))

    return out


def _fetch(points: list[tuple[float, float]], client: Any = None) -> Optional[dict[str, Any]]:
    """Один запрос на все точки маршрута (Open-Meteo принимает списки координат).

    Сеть отвалилась, сервис ответил ошибкой, формат другой — возвращаем None. Предупреждение
    о погоде НИКОГДА не должно мешать поездке: нет данных — просто нет карточки.
    """
    params = {
        "latitude": ",".join(f"{lat}" for lat, _ in points),
        "longitude": ",".join(f"{lng}" for _, lng in points),
        "hourly": _HOURLY,
        "forecast_days": 3,
        "timezone": "UTC",
    }
    try:
        getter = client.get if client is not None else httpx.get
        response = getter(_API_URL, params=params, timeout=_HTTP_TIMEOUT_SEC)
        if getattr(response, "status_code", 500) != 200:
            return None
        return response.json()
    except Exception:  # noqa: BLE001 — любой сбой источника = «погоды нет», а не ошибка поездки
        return None


def route_weather(
    points: Iterable[tuple[float, float]],
    when: Optional[datetime] = None,
    client: Any = None,
) -> RouteWeather:
    """Предупреждения по маршруту на время выезда.

    `points` — начало и конец (можно больше). Берём худшее по маршруту: если на выезде чисто,
    а под Сибаем метель, человек должен узнать про метель, а не про «ясно».
    """
    if not settings.weather_warnings_enabled:
        return RouteWeather()
    grid_points: list[tuple[float, float]] = []
    for lat, lng in points:
        if lat is None or lng is None:
            continue
        pair = (_grid(float(lat)), _grid(float(lng)))
        if not _in_service_area(*pair):
            continue          # не наша география: наружу за такой погодой не ходим (волна 35)
        if pair not in grid_points:
            grid_points.append(pair)
    if not grid_points:
        return RouteWeather()
    grid_points = grid_points[:4]     # больше четырёх точек не спрашиваем: погода не так дробится

    at = (when or utcnow()).replace(minute=0, second=0, microsecond=0)
    # Прогноз дальше трёх суток источник не отдаёт — на такие заявки карточки просто не будет.
    if at > utcnow() + timedelta(days=3):
        return RouteWeather()
    # И назад тоже не смотрим: погода нужна ПЕРЕД выездом. Час назад — нормальный запас на то,
    # что человек открыл экран заранее; всё, что глубже, это перебор ключей кеша, а не поездка.
    if at < utcnow() - timedelta(hours=1):
        return RouteWeather()

    cache_key = "weather:warn:" + ";".join(f"{a},{b}" for a, b in grid_points) + f"@{at.isoformat()}"
    if client is None:
        cached = cache_get_json(cache_key)
        if cached is not None:
            return RouteWeather(
                available=bool(cached.get("available")),
                warnings=[Warning(**w) for w in cached.get("warnings", [])],
                temperature_c=cached.get("temperature_c"),
            )

    payload = _fetch(grid_points, client=client)
    if payload is None:
        return RouteWeather()

    # Одна точка → объект, несколько → массив. Приводим к списку, чтобы разбор был один.
    blocks = payload if isinstance(payload, list) else [payload]
    seen: set[str] = set()
    result = RouteWeather(available=True)
    temps: list[float] = []
    for block in blocks:
        hour = _pick_hour(block.get("hourly") or {}, at)
        if not hour:
            continue
        t = _num(hour.get("temperature_2m"))
        if t is not None:
            temps.append(t)
        for warning in _warnings_for(hour):
            if warning.kind in seen:
                continue          # одно и то же по всему маршруту — говорим один раз
            seen.add(warning.kind)
            result.warnings.append(warning)
    if temps:
        result.temperature_c = round(sum(temps) / len(temps), 1)
    # Тяжёлое — вперёд: человек читает первую строку, и это должен быть гололёд, а не туман.
    result.warnings.sort(key=lambda w: (not w.severe,))

    if client is None:
        cache_set_json(
            cache_key,
            {
                "available": result.available,
                "temperature_c": result.temperature_c,
                "warnings": [w.__dict__ for w in result.warnings],
            },
            _CACHE_TTL_SEC,
        )
    return result
