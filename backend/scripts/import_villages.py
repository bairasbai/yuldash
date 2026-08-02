#!/usr/bin/env python3
"""Импорт деревень Башкортостана из OpenStreetMap → app/data/villages_rb.json.

Зачем: справочник НП (app/geo.py) держит 21 город + 54 райцентра + соседей. Этот скрипт
дозаливает ВСЕ сельские НП РБ (~4500) как kind='village' — чтобы человек из деревни выбирал
свою точку, а не ближайший райцентр.

Источник — OSM (лицензия ODbL: в приложении нужна атрибуция «© OpenStreetMap contributors»).
Разбор OSM-узлов — общий с бэкендом (app.geo.parse_overpass_elements), поэтому логика
покрыта тестами (tests/test_villages.py).

⚠️ Требует сетевого доступа к overpass-api.de. В закрытой среде (egress-политика 403) не
работает — тогда используйте `--from-file <overpass.json>` с заранее выгруженным ответом
Overpass (тот же формат: {"elements":[...]}), полученным там, где сеть открыта.

Запуск (из каталога backend/):
    python -m scripts.import_villages --overpass            # тянуть из OSM (по районам, с district)
    python -m scripts.import_villages --from-file dump.json # разобрать локальный дамп Overpass
    python -m scripts.import_villages --overpass --fast     # один запрос по всей РБ (district реже)

Идемпотентно по данным: перезаписывает villages_rb.json целиком (отсортированный, дедуп).
Сид в БД — отдельно, в lifespan (app.geo.seed_villages), при следующем деплое.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

# Разбор OSM — тот же, что в рантайме (единый источник правды, покрыт тестами).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.geo import parse_overpass_elements, _VILLAGES_JSON  # noqa: E402

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
RB_AREA_NAME = "Республика Башкортостан"   # admin_level=4
_TIMEOUT = 180


def _overpass(query: str) -> dict:
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": "yuldash-villages-import"})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as r:   # noqa: S310 — доверенный домен OSM
        return json.loads(r.read().decode("utf-8"))


def _districts() -> list[str]:
    """Имена районов РБ (admin_level=6) — чтобы тянуть деревни по одному району и знать district."""
    q = (f'[out:json][timeout:{_TIMEOUT}];'
         f'area["name"="{RB_AREA_NAME}"]["admin_level"="4"]->.rb;'
         f'relation["admin_level"="6"]["boundary"="administrative"](area.rb);out tags;')
    els = _overpass(q).get("elements", [])
    names = sorted({(e.get("tags") or {}).get("name", "") for e in els} - {""})
    return names


def _villages_in(area_name: str, level: str) -> list[dict]:
    q = (f'[out:json][timeout:{_TIMEOUT}];'
         f'area["name"="{area_name}"]["admin_level"="{level}"]->.a;'
         f'node["place"~"^(village|hamlet|town|isolated_dwelling)$"](area.a);out body;')
    return parse_overpass_elements(_overpass(q).get("elements", []))


def from_overpass(fast: bool) -> list[dict]:
    if fast:
        print("Один запрос по всей РБ…", file=sys.stderr)
        return _villages_in(RB_AREA_NAME, "4")
    out: list[dict] = []
    districts = _districts()
    print(f"Районов найдено: {len(districts)}", file=sys.stderr)
    for i, d in enumerate(districts, 1):
        try:
            rows = _villages_in(d, "6")
        except Exception as e:  # noqa: BLE001 — один упавший район не рушит импорт
            print(f"  [{i}/{len(districts)}] {d}: ошибка {e}", file=sys.stderr)
            continue
        for r in rows:
            r["district"] = r.get("district") or d   # район знаем из запроса
        out.extend(rows)
        print(f"  [{i}/{len(districts)}] {d}: {len(rows)}", file=sys.stderr)
        time.sleep(1.0)   # вежливо к Overpass (rate limit)
    return out


def from_file(path: str) -> list[dict]:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    return parse_overpass_elements(raw.get("elements", raw if isinstance(raw, list) else []))


def _dedup(rows: list[dict]) -> list[dict]:
    """Дедуп по (name_ru, district). Первый выигрывает (у него обычно есть координаты)."""
    seen, out = set(), []
    for r in sorted(rows, key=lambda x: (x["name_ru"], x.get("district") or "")):
        key = (r["name_ru"], r.get("district") or "")
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Импорт деревень РБ из OSM → villages_rb.json")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--overpass", action="store_true", help="тянуть из OSM (нужен доступ к overpass-api.de)")
    g.add_argument("--from-file", metavar="JSON", help="разобрать локальный дамп Overpass {elements:[...]}")
    ap.add_argument("--fast", action="store_true", help="с --overpass: один запрос по всей РБ (district реже)")
    ap.add_argument("--out", default=str(_VILLAGES_JSON), help="куда писать (по умолч. app/data/villages_rb.json)")
    args = ap.parse_args()

    rows = from_overpass(args.fast) if args.overpass else from_file(args.from_file)
    rows = _dedup(rows)
    Path(args.out).write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    with_ba = sum(1 for r in rows if r.get("name_ba"))
    with_d = sum(1 for r in rows if r.get("district"))
    print(f"Готово: {len(rows)} деревень → {args.out}  (с башкирским: {with_ba}, с районом: {with_d})",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
