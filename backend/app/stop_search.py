"""Поиск поездок по остановкам (решение Александра 18.10.2026, PR2 серии «место посадки»).

Что было. Поездку «Сибай → Акъяр через Ургазу» человек из Ургазы, ищущий «Ургаза → Акъяр», не
находил: лента сравнивала только начало и конец маршрута. Остановки были текстом в карточке.

Что стало. Поездка подходит запросу «Откуда → Куда», если на её маршруте есть место посадки раньше
места высадки (`route_fit`): начало→конец, остановка→конец, начало→остановка, остановка i→j (i<j).
«Откуда» не может быть концом маршрута, «Куда» — началом.

Как. В SQL — широкое условие по столбцам и подстроке `waypoints` (с `autoescape`: «%» и «_» в
запросе — буквы, а не шаблон), в Python — точная проверка `route_fit` только по ДОПУСТИМЫМ
остановкам (`ride_stops.parse_stops`): «ул. Ургаза 5» в старой поездке подстрокой совпадёт, но
остановкой не считается. Запрос с цифрами по остановкам не ищем вовсе.

Что узнаёт человек: `matched_stop` — название остановки, где он сядет по СВОЕМУ запросу, и
`matched_from_start` — садится в начале маршрута. Это выводится только из его запроса; бронь и
место посадки других людей сюда не попадают никогда.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

from sqlalchemy import or_

from .geo import name_variants, normalize_place
from .ride_stops import _f, _place_keys, parse_stops

NAME_MAX = 120          # длина имени места в запросе (как у города в публикации)
MAX_VARIANTS = 6        # написаний одного места в SQL (тёзок-сёл бывает десяток — хватит главных)
MAX_PAIR_VARIANTS = 3   # для попарного LIKE «откуда…куда» в общей ленте: не больше 3×3 = 9 пар
_DIGIT = re.compile(r"\d")
_LETTER = re.compile(r"[^\W\d_]")


def variants(session, name: Optional[str], entry: dict) -> list[str]:
    """Написания места из запроса (как спросили + русское + башкирское), не больше MAX_VARIANTS."""
    raw = (name or "").strip()[:NAME_MAX]
    if not raw:
        return []
    return name_variants(session, normalize_place(raw), entry)[:MAX_VARIANTS]


def stop_searchable(name: Optional[str]) -> bool:
    """Искать ли это место среди остановок: только название (буквы), без цифр (P3)."""
    t = (name or "").strip()
    return bool(t) and not _DIGIT.search(t) and bool(_LETTER.search(t))


def _escape_like(text: str) -> str:
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def column_match(column, names: list[str]):
    """Столбец содержит любое из написаний — `contains(autoescape=True)`, как `_city_match`."""
    conds = [column.contains(v, autoescape=True) for v in names if v]
    return or_(*conds) if conds else None


def pair_match(column, froms: list[str], tos: list[str]):
    """`waypoints` содержит «откуда…куда» именно в этом порядке (подстрокой; точный порядок —
    `route_fit`). Не больше MAX_PAIR_VARIANTS² пар; спецсимволы LIKE экранированы."""
    conds = [column.like(f"%{_escape_like(a)}%{_escape_like(b)}%", escape="\\")
             for a in froms[:MAX_PAIR_VARIANTS] for b in tos[:MAX_PAIR_VARIANTS] if a and b]
    return or_(*conds) if conds else None


@dataclass(frozen=True)
class Fit:
    """Подходит ли поездка запросу и где человек садится.

    `pickups` — все позиции посадки, после которых по маршруту есть высадка: -1 — начало,
    0..n-1 — остановки (`stops`). Для «Ближайших» без «Откуда» — кандидаты для расстояния."""
    ok: bool
    stops: tuple = ()
    pickups: tuple = ()
    matched_stop: Optional[str] = None
    matched_from_start: bool = False
    extra: dict = field(default_factory=dict)


NO_FIT = Fit(False)


def _endpoint(value: Optional[str], names: Optional[list[str]]) -> bool:
    """Начало/конец маршрута совпадает с запросом — тем же правилом, что SQL `contains`."""
    v = value or ""
    return any(n and n in v for n in (names or []))


def route_fit(ride, fv: Optional[list[str]], tv: Optional[list[str]], entry: dict,
              *, from_stops: bool = True, to_stops: bool = True) -> Fit:
    """Подходит ли поездка запросу «откуда (fv) → куда (tv)». None — фильтра нет.

    Позиции маршрута: -1 начало, 0..n-1 допустимые остановки, n конец. Посадка — начало или
    совпавшая остановка (не конец), высадка — совпавшая остановка или конец (не начало); посадка
    строго раньше высадки. `from_stops`/`to_stops` = False — запрос с цифрами: по остановкам не ищем.
    """
    stops = tuple(parse_stops(ride, entry))
    n = len(stops)
    stop_keys = [_place_keys(s, entry) for s in stops]

    def stop_hits(names: Optional[list[str]], allowed: bool) -> list[int]:
        if not names or not allowed:
            return []
        asked: set = set()
        for nm in names:
            asked |= _place_keys(nm, entry)
        return [i for i, keys in enumerate(stop_keys) if keys & asked]

    if fv:
        froms = ([-1] if _endpoint(_f(ride, "from_city"), fv) else []) + stop_hits(fv, from_stops)
    else:
        froms = [-1] + list(range(n))
    if tv:
        tos = stop_hits(tv, to_stops) + ([n] if _endpoint(_f(ride, "to_city"), tv) else [])
    else:
        tos = list(range(n)) + [n]
    if not froms or not tos:
        return NO_FIT
    last_drop = max(tos)
    pickups = tuple(p for p in froms if p < last_drop)
    if not pickups:
        return NO_FIT
    return make_fit(stops, pickups, named=bool(fv))


def make_fit(stops: tuple, pickups: tuple, *, named: bool) -> Fit:
    """`Fit` по позициям посадки. `named` — человек назвал «Откуда»: тогда `matched_*` — начало, если
    оно подходит, иначе первая подходящая остановка. Без «Откуда» место не выбираем."""
    if not named:
        return Fit(True, stops, pickups)
    chosen = -1 if -1 in pickups else min(pickups)
    return Fit(True, stops, pickups,
               matched_stop=stops[chosen] if chosen >= 0 else None,
               matched_from_start=chosen == -1)
