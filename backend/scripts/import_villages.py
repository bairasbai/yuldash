#!/usr/bin/env python3
"""Импорт сельских НП из OpenStreetMap → app/data/villages_rb.json + villages_border.json.

Зачем: справочник НП (app/geo.py) держит 21 город + 54 райцентра + приграничные города.
Этот скрипт дозаливает СЁЛА, чтобы человек из деревни выбирал свою точку, а не ближайший
райцентр (ядро продукта — попутки между сёлами):
  • villages_rb.json     — все сельские НП Башкортостана (~4500), с районом и координатами;
  • villages_border.json — сёла соседних регионов в полосе ~50 км от границы РБ
    (Челябинская, Оренбургская, Свердловская обл., Пермский край, Татарстан, Удмуртия).

Как работает (каждый ответ Overpass кладётся в кеш на диск — перезапуск продолжает с места
обрыва, а не качает заново):
  1. по каждому региону одним запросом — все узлы place=village|hamlet|town|…;
  2. по каждому региону одним запросом — контуры районов (admin_level=6);
  3. село привязывается к району точкой-в-полигоне (точно, без «на глазок»);
  4. геометрия границы РБ → соседние сёла дальше --border-km отбрасываем;
  5. дедуп по (имя, регион, район) → два JSON.
Всего ~15 запросов вместо сотни — полный импорт занимает пару минут.

Источник — OSM, лицензия ODbL: где показываем деревни, нужна атрибуция «© OpenStreetMap».
Разбор узлов — общий с бэкендом (app.geo.parse_overpass_elements), покрыт tests/test_villages.py.

Запуск (из каталога backend/):
    python -m scripts.import_villages --osm                  # полный импорт
    python -m scripts.import_villages --osm --border-km 40   # уже полоса у соседей
    python -m scripts.import_villages --from-file dump.json  # разобрать готовый дамп Overpass

Сид в БД — отдельно, в lifespan (app.geo.seed_villages), при следующем деплое.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

# Разбор OSM — тот же, что в рантайме (единый источник правды, покрыт тестами).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.geo import parse_overpass_elements, _VILLAGES_JSON, _VILLAGES_BORDER_JSON  # noqa: E402

# Зеркала Overpass: основное overpass-api.de часто занято («too busy») — идём по кругу.
MIRRORS = [
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass-api.de/api/interpreter",
]
# id границ регионов в OSM (relation, admin_level=4). Имена в OSM короткие: «Башкортостан».
RB_REL = 77677
REGIONS: dict[str, int] = {
    "РБ": RB_REL,
    "Челябинская обл.": 77687,
    "Оренбургская обл.": 77669,
    "Свердловская обл.": 79379,
    "Пермский край": 115135,
    "Татарстан": 79374,
    "Удмуртия": 115134,
}
AREA_OFFSET = 3600000000        # relation id → area id (правило Overpass)
PLACES = "^(city|town|village|hamlet|isolated_dwelling)$"
_TIMEOUT = 600


# ────────────────────────────── сеть ──────────────────────────────
def overpass(query: str, tag: str, cache: Path, retries: int = 4) -> dict:
    """Запрос к Overpass с кешем на диске и обходом зеркал. tag — имя файла кеша."""
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / f"{tag}.json"
    if path.exists() and path.stat().st_size > 0:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001 — битый кеш просто перекачаем
            pass
    last = ""
    for attempt in range(retries):
        for mirror in MIRRORS:
            try:
                data = urllib.parse.urlencode({"data": query}).encode()
                req = urllib.request.Request(mirror, data=data,
                                             headers={"User-Agent": "yuldash-villages-import/1.0"})
                with urllib.request.urlopen(req, timeout=_TIMEOUT) as r:  # noqa: S310 — домены OSM
                    body = r.read().decode("utf-8")
                js = json.loads(body)
                if "elements" not in js:
                    raise ValueError("в ответе нет elements")
                path.write_text(body, encoding="utf-8")
                return js
            except Exception as e:  # noqa: BLE001 — пробуем следующее зеркало
                last = f"{mirror}: {type(e).__name__}: {str(e)[:120]}"
                time.sleep(2)
        time.sleep(5 * (attempt + 1))
    raise RuntimeError(f"Overpass не ответил ({tag}): {last}")


# ────────────────────────────── геометрия ──────────────────────────────
def haversine(a_lat: float, a_lng: float, b_lat: float, b_lng: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = math.radians(b_lat - a_lat), math.radians(b_lng - a_lng)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


class Districts:
    """Контуры районов региона: точка (село) → имя района. Точка-в-полигоне с отсевом по bbox."""

    def __init__(self, elements: list):
        self.rows = []
        for e in elements:
            name = (e.get("tags") or {}).get("name")
            rings = [[(g["lat"], g["lon"]) for g in (m.get("geometry") or [])]
                     for m in e.get("members", []) if m.get("role") in ("outer", "")]
            rings = [r for r in rings if len(r) >= 2]
            if not name or not rings:
                continue
            lats = [p[0] for r in rings for p in r]
            lngs = [p[1] for r in rings for p in r]
            self.rows.append({"name": name, "rings": rings,
                              "bb": (min(lats), min(lngs), max(lats), max(lngs))})

    @staticmethod
    def _crossings(lat: float, lng: float, way: list) -> int:
        """Сколько раз луч вправо пересекает ЭТОТ кусок границы. Куски не замыкаем:
        граница района в OSM разрезана на десятки путей, замкнуть каждый — испортить счёт."""
        c = 0
        for i in range(len(way) - 1):
            y1, x1 = way[i]
            y2, x2 = way[i + 1]
            if (y1 > lat) != (y2 > lat):
                x = x1 + (lat - y1) * (x2 - x1) / ((y2 - y1) or 1e-12)
                if x > lng:
                    c += 1
        return c

    def find(self, lat: float, lng: float) -> str | None:
        """Точка внутри района, если пересечений со всеми кусками его границы — нечёт."""
        for row in self.rows:
            minlat, minlng, maxlat, maxlng = row["bb"]
            if not (minlat <= lat <= maxlat and minlng <= lng <= maxlng):
                continue
            if sum(self._crossings(lat, lng, way) for way in row["rings"]) % 2 == 1:
                return row["name"]
        return None


class Border:
    """Точки границы РБ в сетке 0.25° — «далеко ли село от Башкортостана» за микросекунды."""

    STEP = 0.25

    def __init__(self, points: list[tuple[float, float]]):
        self.grid: dict[tuple[int, int], list[tuple[float, float]]] = {}
        for lat, lng in points:
            self.grid.setdefault((int(lat / self.STEP), int(lng / self.STEP)), []).append((lat, lng))

    def km_to_border(self, lat: float, lng: float, cutoff_km: float) -> float:
        cells = int(cutoff_km / 111.0 / self.STEP) + 1
        gi, gj = int(lat / self.STEP), int(lng / self.STEP)
        best = float("inf")
        for i in range(gi - cells, gi + cells + 1):
            for j in range(gj - cells, gj + cells + 1):
                for plat, plng in self.grid.get((i, j), ()):
                    d = haversine(lat, lng, plat, plng)
                    if d < best:
                        best = d
        return best


# ────────────────────────────── шаги импорта ──────────────────────────────
def fetch_places(region: str, rel_id: int, cache: Path) -> list[dict]:
    """Все узлы-НП региона одним запросом."""
    q = (f'[out:json][timeout:{_TIMEOUT}];area({AREA_OFFSET + rel_id})->.r;'
         f'node["place"~"{PLACES}"](area.r);out body;')
    rows = parse_overpass_elements(overpass(q, f"regionnodes_{rel_id}", cache).get("elements", []))
    for r in rows:
        r["region"] = region
    return rows


def fetch_districts(rel_id: int, cache: Path) -> Districts:
    """Контуры районов региона одним запросом."""
    q = (f'[out:json][timeout:{_TIMEOUT}];area({AREA_OFFSET + rel_id})->.r;'
         f'rel["boundary"="administrative"]["admin_level"="6"](area.r);out geom;')
    return Districts(overpass(q, f"districtgeom_{rel_id}", cache).get("elements", []))


def fetch_border(cache: Path) -> Border:
    """Точки границы РБ из геометрии relation 77677."""
    js = overpass(f'[out:json][timeout:{_TIMEOUT}];rel({RB_REL});out geom;', "rb_border_geom", cache)
    pts = {(round(g["lat"], 4), round(g["lon"], 4))
           for e in js.get("elements", []) for m in e.get("members", [])
           for g in (m.get("geometry") or []) if g}
    return Border(sorted(pts))


def short_district(name: str) -> str:
    """«Иглинский район» → «Иглинский р-н», «городской округ Сибай» → «г.о. Сибай».
    Показываем под названием села, чтобы различать тёзок, — поэтому коротко."""
    n = " ".join((name or "").split())
    low = n.lower()
    for pref in ("городской округ город ", "городской округ ", "город "):
        if low.startswith(pref):
            return f"г.о. {n[len(pref):]}"
    for pref in ("муниципальный район ", "муниципальный округ ", "муниципальное образование "):
        if low.startswith(pref):
            n = n[len(pref):]
            low = n.lower()
            break
    for suf, tail in ((" муниципальный район", "р-н"), (" муниципальный округ", "окр."),
                      (" район", "р-н"), (" округ", "окр.")):
        if low.endswith(suf):
            return f"{n[: -len(suf)]} {tail}"
    return n


def dedup(rows: list[dict]) -> list[dict]:
    """Дедуп по (имя, регион, район). Порядок стабильный — чтобы diff в git был читаемым."""
    seen, out = set(), []
    for r in sorted(rows, key=lambda x: (x["name_ru"], x.get("region") or "", x.get("district") or "")):
        key = (r["name_ru"], r.get("region") or "", r.get("district") or "")
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def from_osm(cache: Path, border_km: float) -> tuple[list[dict], list[dict]]:
    """Полный импорт: РБ + приграничная полоса соседей. Возвращает (сёла РБ, сёла соседей)."""
    def say(m: str) -> None:
        print(m, file=sys.stderr, flush=True)

    say("1/3 граница Башкортостана…")
    border = fetch_border(cache)

    rb_rows: list[dict] = []
    nb_rows: list[dict] = []
    for i, (region, rel_id) in enumerate(REGIONS.items(), 1):
        say(f"2/3 [{i}/{len(REGIONS)}] {region}: сёла и районы…")
        places = fetch_places(region, rel_id, cache)
        districts = fetch_districts(rel_id, cache)
        for r in places:
            d = districts.find(r["lat"], r["lng"])
            r["district"] = short_district(d) if d else (r.get("district") or None)
        if region == "РБ":
            rb_rows.extend(places)
        else:
            near = [r for r in places if border.km_to_border(r["lat"], r["lng"], border_km) <= border_km]
            say(f"      в полосе {border_km:.0f} км: {len(near)} из {len(places)}")
            nb_rows.extend(near)

    say("3/3 дедуп…")
    return dedup(rb_rows), dedup(nb_rows)


def from_file(path: str) -> list[dict]:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    return parse_overpass_elements(raw.get("elements", raw if isinstance(raw, list) else []))


def write(rows: list[dict], path: Path, title: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    with_ba = sum(1 for r in rows if r.get("name_ba"))
    with_d = sum(1 for r in rows if r.get("district"))
    print(f"{title}: {len(rows)} → {path}  (с башкирским: {with_ba}, с районом: {with_d})",
          file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description="Импорт сельских НП РБ и приграничья из OSM")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--osm", action="store_true", help="полный импорт из OSM (РБ + соседи)")
    g.add_argument("--from-file", metavar="JSON", help="разобрать локальный дамп Overpass {elements:[…]}")
    ap.add_argument("--border-km", type=float, default=50.0, help="полоса у соседей (по умолч. 50 км)")
    ap.add_argument("--cache", default=".osm-cache", help="каталог кеша сырых ответов Overpass")
    ap.add_argument("--out", default=str(_VILLAGES_JSON), help="куда писать сёла РБ")
    ap.add_argument("--out-border", default=str(_VILLAGES_BORDER_JSON), help="куда писать сёла соседей")
    args = ap.parse_args()

    if args.from_file:
        write(dedup(from_file(args.from_file)), Path(args.out), "Сёла (из файла)")
        return 0
    rb, border = from_osm(Path(args.cache), args.border_km)
    write(rb, Path(args.out), "Сёла РБ")
    write(border, Path(args.out_border), "Сёла приграничья")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
