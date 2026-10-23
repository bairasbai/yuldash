"""Остановки поездки и место посадки пассажира (решение Александра 18.10.2026).

Две функции «есть ли остановки» — нарочно разные (вторая проверка безопасности, P2-В):

* `ride_has_stops_raw` — для ПРАВИЛ безопасности («Подъезжаю», «Не пришёл», окно живой точки).
  Смотрит на сырой текст `Ride.waypoints`, как правило PR184: любая часть с буквой, кроме частей,
  равных началу или концу маршрута. Очистка текста для показа не вправе выключить правила
  остановок у старой поездки: «ул. Ленина 5» пассажиру не покажем, но поездка остаётся «с
  остановками», и бронь без места живёт по правилам PR184, а не «начала».
* `parse_stops` — для ВЫБОРА, ПОИСКА и ПОКАЗА: только допустимые части (буквы, пробел, дефис,
  точка, скобки; без цифр; до 60 знаков), без повторов и без начала/конца маршрута.

Координаты остановки не хранятся: каждый раз из своего справочника сёл (`geo`, в памяти).
Яндекс-геокодер для остановок не зовём — платно и без кэша. Сёла-тёзки без района разрешаются
близостью к прямой «откуда → куда»; неоднозначно — остановка «неизвестна» (без GPS-отказа и
раннего предела), это честнее, чем сверять машину с чужим селом.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Optional

from .config import settings
from .geo import bare_name, fold, lookup, name_variants, normalize_place, snapshot_cached
from .services import haversine_km

SEP = "|"
MAX_STOPS = 4          # как в клиентах: «+ остановка» пропадает после четвёртой
MAX_STOP_LEN = 60
# Допустимая остановка: буквы любого алфавита (русский, башкирский, латиница), пробел, дефис,
# точка, скобки. Цифр нет — номер дома так не пройдёт, а списка «запретных слов» нет нарочно:
# «Улу-Теляк», «Кваркено», «Домашкино» — честные сёла (P3 второй проверки).
_STOP_OK = re.compile(r"^(?:[^\W\d_]|[ \-.()])+$")
_LETTER = re.compile(r"[^\W\d_]")

# Виды остановок по справочнику: у села и райцентра GPS-отказ и «подтверждено» есть, у города
# и соседа-города — нет (город большой, центр не подставляем — как у пина, P2-1).
VILLAGE_KINDS = ("village", "district_center")


def _f(obj, name, default=None):
    """Поле у Ride/RideOut (атрибут) или у dict (кеш ленты) — одинаково."""
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def split_raw(waypoints: Optional[str]) -> list[str]:
    """Сырые части `waypoints` (через «|»), обрезанные, без пустых."""
    return [p.strip() for p in (waypoints or "").split(SEP) if p.strip()]


def stop_text_ok(part: str) -> bool:
    """Допустимый текст остановки (правило публикации и показа).

    «\\d» в регулярке — только десятичные цифры; обведённые «⑧⑨①», надстрочные «⁸⁹¹» и римские «Ⅻ»
    для неё «буквы». Телефон, набранный ими, прошёл бы как название села — поэтому отдельно: ни одного
    символа-числа (`str.isnumeric`, S-P3-1)."""
    t = (part or "").strip()
    return (0 < len(t) <= MAX_STOP_LEN and bool(_STOP_OK.match(t)) and bool(_LETTER.search(t))
            and not any(ch.isnumeric() for ch in t))


def _key(name: str) -> str:
    """Ключ места с районом: только приставка «д./с.» и регистр не отличают остановки."""
    return fold(normalize_place(name or ""))


def _district(name: str) -> str:
    raw = (name or "").strip()
    return raw.rpartition("(")[2][:-1].strip() if raw.endswith(")") and "(" in raw else ""


def _lookup_place(entry: dict, name: str) -> list:
    """Явный район обязателен: неизвестный район не разрешает fallback к чужой деревне."""
    raw = normalize_place(name)
    district = _district(raw)
    if district:
        hit = entry.get("with_district", {}).get((fold(bare_name(raw)), fold(district)))
        return [hit] if hit is not None else []
    return lookup(entry, raw)


def _place_variants(name: str, entry: dict) -> list[str]:
    """RU/BA написания с сохранением явно названного района, без расширения к тёзкам."""
    raw = normalize_place(name or "")
    if not raw:
        return []
    if not _district(raw):
        return name_variants(None, raw, entry)
    out = [raw]
    for row in _lookup_place(entry, raw):
        for candidate in (row.name_ru, row.name_ba):
            full = f"{candidate} ({row.district})" if candidate else ""
            if full and full not in out:
                out.append(full)
    return out


def _place_keys(name: str, entry: dict) -> set[str]:
    """Ключи всех написаний места: как написано + русское + башкирское по справочнику."""
    keys = {_key(v) for v in _place_variants(name, entry)}
    # Голое имя можно связать с районом, только если справочник знает ровно одно место.
    # У тёзок без района остаётся прежняя неоднозначность; первую деревню не выбираем.
    rows = _lookup_place(entry, name) if not _district(name) else []
    if len(rows) == 1 and rows[0].district:
        row = rows[0]
        keys.update(_key(f"{v} ({row.district})") for v in (row.name_ru, row.name_ba) if v)
    keys.discard("")
    return keys


def _ends(ride, entry: dict) -> tuple[set[str], set[str]]:
    return _place_keys(_f(ride, "from_city", ""), entry), _place_keys(_f(ride, "to_city", ""), entry)


def ride_has_stops_raw(ride, entry: Optional[dict] = None) -> bool:
    """Есть ли у поездки остановки — для правил безопасности (по сырому тексту, см. шапку)."""
    parts = [p for p in split_raw(_f(ride, "waypoints")) if any(ch.isalpha() for ch in p)]
    if not parts:
        return False
    entry = entry or snapshot_cached()
    start, end = _ends(ride, entry)
    return any(not (_place_keys(p, entry) & (start | end)) for p in parts)


def parse_stops(ride, entry: Optional[dict] = None) -> list[str]:
    """Допустимые остановки в порядке маршрута — для выбора, поиска и показа."""
    parts = split_raw(_f(ride, "waypoints"))
    if not parts:
        return []
    entry = entry or snapshot_cached()
    start, end = _ends(ride, entry)
    out: list[str] = []
    seen: set[str] = set()
    for p in parts:
        if not stop_text_ok(p):
            continue
        keys = _place_keys(p, entry)
        if not keys or keys & (start | end | seen):
            continue
        seen.update(keys)
        out.append(p)
    return out


def clean_waypoints(ride, entry: Optional[dict] = None) -> str:
    """Остановки для публичной выдачи: недопустимые части старых поездок не показываем."""
    if not split_raw(_f(ride, "waypoints")):
        return ""
    return " | ".join(parse_stops(ride, entry))


def is_start(ride, raw: str, entry: Optional[dict] = None) -> bool:
    """Пассажир назвал начало маршрута — по любому написанию (башкирское тоже)."""
    entry = entry or snapshot_cached()
    return bool(_place_keys(raw, entry) & _ends(ride, entry)[0])


def is_end(ride, raw: str, entry: Optional[dict] = None) -> bool:
    entry = entry or snapshot_cached()
    return bool(_place_keys(raw, entry) & _ends(ride, entry)[1])


def canonical_stop(ride, raw: str, entry: Optional[dict] = None) -> Optional[str]:
    """Название остановки так, как оно записано в поездке, или None — такой остановки нет.
    Пассажир не может вписать своё: подходит только остановка из `parse_stops`."""
    entry = entry or snapshot_cached()
    asked = _place_keys(raw, entry)
    if not asked:
        return None
    matches = [stop for stop in parse_stops(ride, entry) if asked & _place_keys(stop, entry)]
    return matches[0] if len(matches) == 1 else None


# ─────────────────────────────── Геометрия ───────────────────────────────

@dataclass(frozen=True)
class StopPoint:
    lat: float
    lng: float
    kind: str          # village | district_center | city | neighbor


def _to_segment_km(p: tuple, a: tuple, b: tuple) -> float:
    """Расстояние от точки до отрезка A–B, км (плоская проекция у точки — для коридора 25 км
    этого хватает с запасом)."""
    lat0 = math.radians(p[0])
    kx, ky = 111.32 * math.cos(lat0), 110.57

    def xy(q):
        return ((q[1] - p[1]) * kx, (q[0] - p[0]) * ky)

    ax, ay = xy(a)
    bx, by = xy(b)
    dx, dy = bx - ax, by - ay
    len2 = dx * dx + dy * dy
    t = 0.0 if len2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
    cx, cy = ax + t * dx, ay + t * dy
    return math.hypot(cx, cy)


def _route_coords(ride) -> Optional[tuple]:
    """Концы маршрута по координатам поездки (геокодятся при публикации). Нет — None."""
    vals = [_f(ride, k) for k in ("from_lat", "from_lng", "to_lat", "to_lng")]
    if any(v is None for v in vals):
        return None
    return (vals[0], vals[1]), (vals[2], vals[3])


def stop_point(ride, name: str, entry: Optional[dict] = None) -> Optional[StopPoint]:
    """Координаты и вид остановки по справочнику или None — «неизвестна».

    Неизвестна, если: справочник имени не знает; у поездки нет координат концов, а район в
    названии не указан (тёзок не отличить); ни одного или больше одного кандидата в коридоре
    `stop_corridor_km` от прямой «откуда → куда» (P2-2)."""
    entry = entry or snapshot_cached()
    cands = _lookup_place(entry, name)
    if not cands:
        return None
    ends = _route_coords(ride)
    if ends is None:
        with_district = normalize_place(name).endswith(")") and len(cands) == 1
        return StopPoint(cands[0].lat, cands[0].lng, cands[0].kind) if with_district else None
    near = [c for c in cands if _to_segment_km((c.lat, c.lng), ends[0], ends[1]) <= settings.stop_corridor_km]
    if len(near) != 1:
        return None
    c = near[0]
    return StopPoint(c.lat, c.lng, c.kind)


def start_point(ride, entry: Optional[dict] = None) -> Optional[tuple]:
    """Откуда считать путь до остановки (только для сроков внутри сервера, наружу не уходит):
    пин точки сбора → координаты начала → справочник."""
    if _f(ride, "pickup_lat") is not None and _f(ride, "pickup_lng") is not None:
        return (_f(ride, "pickup_lat"), _f(ride, "pickup_lng"))
    if _f(ride, "from_lat") is not None and _f(ride, "from_lng") is not None:
        return (_f(ride, "from_lat"), _f(ride, "from_lng"))
    cands = _lookup_place(entry or snapshot_cached(), _f(ride, "from_city", ""))
    return (cands[0].lat, cands[0].lng) if len(cands) == 1 else None


def stop_km(ride, point: Optional[StopPoint], entry: Optional[dict] = None) -> Optional[float]:
    """Прямая от начала маршрута до остановки, км — нижняя граница настоящей дороги."""
    if point is None:
        return None
    start = start_point(ride, entry)
    if start is None:
        return None
    return haversine_km(start[0], start[1], point.lat, point.lng)


def estimate_km(ride, stop: str, entry: Optional[dict] = None) -> Optional[float]:
    """Оценка пути до НЕИЗВЕСТНОЙ справочнику остановки: по её порядку на прямой
    «откуда → куда» — (i+1)/(n+1) расстояния (P3). Только для срока неявки и окна точки."""
    entry = entry or snapshot_cached()
    stops = parse_stops(ride, entry)
    keys = _place_keys(stop, entry)
    idx = next((i for i, s in enumerate(stops) if keys & _place_keys(s, entry)), None)
    ends = _route_coords(ride)
    if idx is None or ends is None:
        return None
    total = haversine_km(ends[0][0], ends[0][1], ends[1][0], ends[1][1])
    return total * (idx + 1) / (len(stops) + 1)


# ─────────────────────────── Место посадки брони ───────────────────────────

@dataclass(frozen=True)
class Pickup:
    """Где садится пассажир этой брони — в терминах правил.

    kind: "start" — начало маршрута (и любая поездка без остановок); "stop" — остановка;
    "unknown" — поездка с остановками, а место не указано (NULL) → правила PR184."""
    kind: str
    stop: Optional[str] = None
    point: Optional[StopPoint] = None     # известна справочнику
    km: Optional[float] = None            # прямая до известной остановки
    est_km: Optional[float] = None        # оценка до неизвестной (по порядку на прямой)

    @property
    def travel_km(self) -> Optional[float]:
        """Путь до остановки для сроков: прямая до известной или оценка до неизвестной."""
        return self.km if self.km is not None else self.est_km


START = Pickup("start")
UNKNOWN = Pickup("unknown")


def pickup_of(ride, booking, entry: Optional[dict] = None) -> Pickup:
    """Место посадки брони для правил. Поездка без остановок — всегда начало, что бы ни лежало
    в брони; поездка с остановками и NULL — «неизвестно» (PR184)."""
    stop = getattr(booking, "pickup_stop", None) if booking is not None else None
    if not (_f(ride, "waypoints") or "").strip():
        return START                       # быстрый путь: без остановок и без справочника
    entry = entry or snapshot_cached()
    if not ride_has_stops_raw(ride, entry):
        return START
    if booking is None:
        return START                       # старые вызовы без брони — как раньше
    if stop is None:
        return UNKNOWN
    if stop == "":
        return START
    point = stop_point(ride, stop, entry)
    if point is not None:
        return Pickup("stop", stop, point, stop_km(ride, point, entry), None)
    return Pickup("stop", stop, None, None, estimate_km(ride, stop, entry))
