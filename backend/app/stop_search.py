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

from .geo import bare_name
from .ride_stops import MEMO_KEY, _district, _f, _lookup_place, _place_keys, _place_variants, parse_stops

NAME_MAX = 120          # длина имени места в запросе (как у города в публикации)
MAX_VARIANTS = 6        # написаний одного места в SQL (тёзок-сёл бывает десяток — хватит главных)
MAX_PAIR_VARIANTS = 3   # для попарного LIKE «откуда…куда» в общей ленте: не больше 3×3 = 9 пар
_LETTER = re.compile(r"[^\W\d_]")


def request_snapshot(entry: dict) -> dict:
    """Снимок справочника на ОДИН запрос: те же таблицы (они общие, не копируются) плюс своя запоминалка
    ключей мест. Названий в базе тысячи, а разных — десятки: без неё разбор остановок 12 000 поездок
    считал одно и то же заново (P2-2, приёмка PR #188). Общий снимок не меняется — запоминалка живёт и
    умирает с запросом."""
    return {**entry, MEMO_KEY: {}}


def variants(session, name: Optional[str], entry: dict) -> list[str]:
    """Написания места из запроса (как спросили + русское + башкирское), не больше MAX_VARIANTS."""
    raw = (name or "").strip()[:NAME_MAX]
    if not raw:
        return []
    return _place_variants(raw, entry)[:MAX_VARIANTS]


def stop_searchable(name: Optional[str]) -> bool:
    """Искать ли это место среди остановок: только название (буквы), без цифр (P3).

    «Без цифр» — как в публикации остановки (`ride_stops.stop_text_ok`, S-P3-1): `str.isnumeric`, а не
    регулярка `\\d`. Обведённые «⑤», надстрочные «⁵» и римские «Ⅴ» для `\\d` — «буквы», и «Ургаза (⑤)»
    искалась бы как остановка «Ургаза»."""
    t = (name or "").strip()
    return bool(_LETTER.search(t)) and not any(ch.isnumeric() for ch in t)


def _escape_like(text: str) -> str:
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def column_match(column, names: list[str]):
    """Столбец содержит любое из написаний — `contains(autoescape=True)`, как `_city_match`."""
    # SQL — только широкий отбор. Район остаётся в names для точного route_fit ниже:
    # уникальное село водитель мог записать без района, а чужую тёзку Python отбросит.
    conds = [column.contains(v, autoescape=True) for v in dict.fromkeys(bare_name(n) for n in names) if v]
    return or_(*conds) if conds else None


def pair_match(column, froms: list[str], tos: list[str]):
    """`waypoints` содержит «откуда…куда» именно в этом порядке (подстрокой; точный порядок —
    `route_fit`). Не больше MAX_PAIR_VARIANTS² пар; спецсимволы LIKE экранированы."""
    conds = [column.like(f"%{_escape_like(bare_name(a))}%{_escape_like(bare_name(b))}%", escape="\\")
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
    matched_dropoff_stop: Optional[str] = None
    matched_to_end: bool = False
    exact_pickups: tuple = ()           # позиции, названные запросом точно (`make_fit` выбирает из них)
    exact_drops: tuple = ()
    extra: dict = field(default_factory=dict)


NO_FIT = Fit(False)


def exact_place(value: Optional[str], names: Optional[list[str]], entry: dict) -> bool:
    """Полное название/RU/BA и район. Тёзку без района не выбираем даже если она одна в рейсе."""
    if not value or (not _district(value) and len(_lookup_place(entry, value)) > 1):
        return False
    keys = _place_keys(value, entry)
    return any(not (not _district(n) and len(_lookup_place(entry, n)) > 1)
               and bool(keys & _place_keys(n, entry)) for n in (names or []))


def _endpoint(value: Optional[str], names: Optional[list[str]], entry: dict, *, strict=False) -> bool:
    """Голый запрос — подстрока как раньше; явно названный район — точное место."""
    if strict:
        return exact_place(value, names, entry)
    v = value or ""
    return any((_place_keys(n, entry) & _place_keys(v, entry)) if _district(n) else n and n in v
               for n in (names or []))


def route_fit(ride, fv: Optional[list[str]], tv: Optional[list[str]], entry: dict,
              *, from_stops: bool = True, to_stops: bool = True, strict: bool = False) -> Fit:
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
        if strict:
            return [i for i, stop in enumerate(stops) if exact_place(stop, names, entry)]
        asked: set = set()
        bare_asked: set = set()
        for nm in names:
            if _district(nm):
                asked |= _place_keys(nm, entry)
            else:
                bare_asked |= _place_keys(nm, entry)
        return [i for i, keys in enumerate(stop_keys)
                if keys & asked or _place_keys(bare_name(stops[i]), entry) & bare_asked]

    if fv:
        froms = ([-1] if _endpoint(_f(ride, "from_city"), fv, entry, strict=strict) else []) + stop_hits(fv, from_stops)
    else:
        froms = [-1] + list(range(n))
    if tv:
        tos = stop_hits(tv, to_stops) + ([n] if _endpoint(_f(ride, "to_city"), tv, entry, strict=strict) else [])
    else:
        tos = list(range(n)) + [n]
    if not froms or not tos:
        return NO_FIT
    last_drop = max(tos)
    pickups = tuple(p for p in froms if p < last_drop)
    if not pickups:
        return NO_FIT
    # Подсказка — только из точного выбора человека, даже когда общий поиск разрешает префикс.
    def hint_place(value, names):
        if exact_place(value, names, entry):
            return True
        # Старый поиск разрешает полное голое имя: в ЭТОМ рейсе должна быть ровно одна
        # такая остановка. Префикс не превращается в выбор; строгий подбор требует район.
        return not strict and any(not _district(n) and bool(
            _place_keys(bare_name(value), entry) & _place_keys(n, entry)) for n in (names or []))

    exact_froms = tuple(p for p in pickups if fv and hint_place(
        _f(ride, "from_city") if p == -1 else stops[p], fv))
    exact_tos = tuple(p for p in tos if tv and hint_place(
        _f(ride, "to_city") if p == n else stops[p], tv))
    return make_fit(stops, pickups, exact_froms, exact_tos)


def make_fit(stops: tuple, pickups: tuple, exact_froms: tuple = (), exact_tos: tuple = ()) -> Fit:
    """`Fit` по позициям. `exact_froms` / `exact_tos` — места, которые запрос человека называет точно.
    Где сядет (`matched_stop` / `matched_from_start`) — если такое место посадки ровно одно; где выйдет
    (`matched_dropoff_stop` / `matched_to_end`) — если ровно одно строго после посадки (после выбранного
    места, а если оно не выбрано — после самого раннего). Иначе не выбираем: человек скажет сам."""
    n = len(stops)
    chosen = exact_froms[0] if len(exact_froms) == 1 else None
    after = chosen if chosen is not None else min(pickups)
    later = [p for p in exact_tos if p > after]
    drop = later[0] if len(later) == 1 else None
    return Fit(True, stops, pickups,
               matched_stop=stops[chosen] if chosen is not None and chosen >= 0 else None,
               matched_from_start=chosen == -1,
               matched_dropoff_stop=stops[drop] if drop is not None and drop < n else None,
               matched_to_end=drop == n,
               exact_pickups=exact_froms, exact_drops=exact_tos)
