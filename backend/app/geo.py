"""География (волна 2): справочник населённых пунктов РБ + соседние регионы.

Четыре kind'а (см. docs/business-logic-2026-07.md §4):
  city            — 21 город республиканского значения РБ;
  district_center — центры 54 муниципальных районов (сёла; где центр = город, он уже в city);
  village         — ВСЕ сельские НП РБ (~4500) из OSM; данные в app/data/villages_rb.json
                    (в репо пустой — фича готова, ждёт данных; заливает scripts/import_villages.py);
  neighbor        — приграничные города соседних регионов (популярный межгород).

Сиды идемпотентные — зовутся из lifespan (main.py): seed_settlements (город/райцентр/сосед по
name_ru) + seed_villages (деревни по (name_ru, district) — тёзки в разных районах различаем).
Координаты городов, уже известных CITY_COORDS (services.py), совпадают с ними 1:1 —
geocode_city() и тесты видят те же значения. Координаты сёл — прикидка по открытым данным
(~0.01°), спорные сверяет Александр. name_ba — черновой башкирский (финал — за носителем).
"""
import json
from pathlib import Path
from typing import Optional

from sqlmodel import Session, select

from .models import Settlement
from .services import haversine_km

# Датасет деревень РБ (заполняется скриптом scripts/import_villages.py из OSM; в репо — пустой []).
_VILLAGES_JSON = Path(__file__).parent / "data" / "villages_rb.json"
# Какие OSM place → «деревня» (плюс town: пгт/крупные сёла-райцентры уже в city/district_center — их отсеет дедуп).
_OSM_VILLAGE_PLACES = {"village", "hamlet", "town", "isolated_dwelling"}

# Радиус привязки точки заказа к ближайшему НП («город точки А/Б») для зон такси.
NEAREST_KM = 30.0

RB = "РБ"

# (name_ru, name_ba|None, region, kind, lat, lng)
SETTLEMENTS_SEED: list[tuple] = [
    # --- 21 город республиканского значения РБ ---
    ("Уфа", "Өфө", RB, "city", 54.735, 55.958),
    ("Стерлитамак", "Стәрлетамаҡ", RB, "city", 53.630, 55.950),
    ("Салават", "Салауат", RB, "city", 53.361, 55.925),
    ("Нефтекамск", "Нефтекама", RB, "city", 56.088, 54.248),
    ("Октябрьский", "Октябрьский", RB, "city", 54.481, 53.471),
    ("Белорецк", "Белорет", RB, "city", 53.968, 58.410),
    ("Туймазы", "Туймазы", RB, "city", 54.600, 53.700),
    ("Ишимбай", "Ишембай", RB, "city", 53.454, 56.044),
    ("Кумертау", "Күмертау", RB, "city", 52.757, 55.797),
    ("Мелеуз", "Мәләүез", RB, "city", 52.959, 55.928),
    ("Бирск", "Бөрө", RB, "city", 55.416, 55.535),
    ("Белебей", "Бәләбәй", RB, "city", 54.100, 54.110),
    ("Благовещенск", None, RB, "city", 55.035, 55.981),
    ("Дюртюли", "Дүртөйлө", RB, "city", 55.485, 54.852),
    ("Учалы", "Учалы", RB, "city", 54.304, 59.430),
    ("Сибай", "Сибай", RB, "city", 52.716, 58.664),
    ("Баймак", "Баймаҡ", RB, "city", 52.591, 58.317),
    ("Давлеканово", "Дәүләкән", RB, "city", 54.220, 55.031),
    ("Янаул", "Яңауыл", RB, "city", 56.265, 54.930),
    ("Агидель", "Ағиҙел", RB, "city", 55.900, 53.934),
    ("Межгорье", None, RB, "city", 54.240, 58.028),
    # --- Центры 54 районов РБ (только сёла/пгт: где центр = город выше — не дублируем:
    # Баймакский, Белебеевский, Белорецкий, Бирский, Благовещенский, Давлекановский,
    # Дюртюлинский, Ишимбайский, Мелеузовский, Стерлитамакский, Туймазинский, Уфимский,
    # Учалинский, Янаульский районы) ---
    ("Аскарово", "Асҡар", RB, "district_center", 53.332, 58.526),        # Абзелиловский
    ("Раевский", "Рай", RB, "district_center", 54.066, 54.913),          # Альшеевский
    ("Архангельское", None, RB, "district_center", 54.409, 56.783),      # Архангельский
    ("Аскино", "Асҡын", RB, "district_center", 56.091, 56.585),          # Аскинский
    ("Толбазы", "Толбаҙы", RB, "district_center", 54.004, 55.886),       # Аургазинский
    ("Бакалы", "Баҡалы", RB, "district_center", 55.176, 53.802),         # Бакалинский
    ("Старобалтачево", "Иҫке Балтас", RB, "district_center", 55.997, 55.919),  # Балтачевский
    ("Новобелокатай", "Яңы Балаҡатай", RB, "district_center", 55.710, 58.960),  # Белокатайский (ba: ba.wikipedia)
    ("Бижбуляк", "Бишбүләк", RB, "district_center", 53.697, 54.262),     # Бижбулякский
    ("Языково", None, RB, "district_center", 54.699, 54.903),            # Благоварский
    ("Буздяк", "Буздяҡ", RB, "district_center", 54.573, 54.521),         # Буздякский
    ("Бураево", "Борай", RB, "district_center", 55.849, 55.406),         # Бураевский
    ("Старосубхангулово", "Иҫке Собханғол", RB, "district_center", 53.103, 57.430),  # Бурзянский
    ("Красноусольский", "Ҡыҙыл Усолка", RB, "district_center", 53.893, 56.476),      # Гафурийский
    ("Месягутово", "Мәсәғүт", RB, "district_center", 55.536, 58.250),    # Дуванский
    ("Ермекеево", "Йәрмәкәй", RB, "district_center", 54.076, 53.671),    # Ермекеевский
    ("Исянгулово", "Иҫәнғол", RB, "district_center", 52.190, 56.583),    # Зианчуринский
    ("Зилаир", "Йылайыр", RB, "district_center", 52.231, 57.449),        # Зилаирский
    ("Иглино", None, RB, "district_center", 54.830, 56.419),             # Иглинский
    ("Верхнеяркеево", "Үрге Йәркәй", RB, "district_center", 55.450, 54.307),  # Илишевский
    ("Калтасы", "Ҡалтасы", RB, "district_center", 55.970, 54.809),       # Калтасинский
    ("Караидель", "Ҡариҙел", RB, "district_center", 55.841, 56.896),     # Караидельский
    ("Кармаскалы", "Ҡармаскалы", RB, "district_center", 54.363, 56.159), # Кармаскалинский
    ("Верхние Киги", "Үрге Ҡыйғы", RB, "district_center", 55.400, 58.600),  # Кигинский
    ("Николо-Берёзовка", None, RB, "district_center", 56.120, 54.170),   # Краснокамский
    ("Мраково", "Мораҡ", RB, "district_center", 52.718, 56.629),         # Кугарчинский
    ("Кушнаренково", None, RB, "district_center", 55.112, 55.342),       # Кушнаренковский
    ("Ермолаево", "Йырмалай", RB, "district_center", 52.707, 55.810),    # Куюргазинский
    ("Большеустьикинское", "Оло Устикин", RB, "district_center", 55.941, 58.271),  # Мечетлинский
    ("Мишкино", "Мишкә", RB, "district_center", 55.535, 55.959),         # Мишкинский
    ("Киргиз-Мияки", "Ҡырғыҙ-Мейәкә", RB, "district_center", 53.632, 54.798),  # Миякинский
    ("Красная Горка", None, RB, "district_center", 55.192, 56.669),      # Нуримановский
    ("Малояз", "Малаяҙ", RB, "district_center", 55.183, 58.166),         # Салаватский
    ("Стерлибашево", "Стәрлебаш", RB, "district_center", 53.440, 55.259),  # Стерлибашевский
    ("Верхние Татышлы", "Үрге Тәтешле", RB, "district_center", 56.284, 55.857),  # Татышлинский
    ("Фёдоровка", None, RB, "district_center", 53.178, 55.188),          # Фёдоровский
    ("Акъяр", "Аҡъяр", RB, "district_center", 51.872, 58.236),           # Хайбуллинский
    ("Чекмагуш", "Саҡмағош", RB, "district_center", 55.135, 54.650),     # Чекмагушевский
    ("Чишмы", "Шишмә", RB, "district_center", 54.593, 55.376),           # Чишминский
    ("Шаран", "Шаран", RB, "district_center", 54.819, 53.993),           # Шаранский
    # --- Соседние регионы (популярный межгород) ---
    ("Магнитогорск", "Магнит", "Челябинская обл.", "neighbor", 53.412, 58.984),
    ("Верхнеуральск", None, "Челябинская обл.", "neighbor", 53.876, 59.216),
    ("Миасс", None, "Челябинская обл.", "neighbor", 55.046, 60.108),
    ("Челябинск", "Силәбе", "Челябинская обл.", "neighbor", 55.160, 61.403),
    ("Орск", None, "Оренбургская обл.", "neighbor", 51.229, 58.475),
    ("Гай", None, "Оренбургская обл.", "neighbor", 51.465, 58.442),
    ("Новотроицк", None, "Оренбургская обл.", "neighbor", 51.196, 58.299),
    ("Кувандык", None, "Оренбургская обл.", "neighbor", 51.478, 57.355),
    ("Оренбург", "Ырымбур", "Оренбургская обл.", "neighbor", 51.769, 55.097),
    ("Бугульма", "Бөгөлмә", "Татарстан", "neighbor", 54.536, 52.797),
    ("Азнакаево", None, "Татарстан", "neighbor", 54.860, 53.075),
    ("Альметьевск", "Әлмәт", "Татарстан", "neighbor", 54.901, 52.297),
    ("Казань", "Ҡазан", "Татарстан", "neighbor", 55.796, 49.108),
    ("Ижевск", None, "Удмуртия", "neighbor", 56.853, 53.211),
    ("Сарапул", None, "Удмуртия", "neighbor", 56.462, 53.804),
    ("Чайковский", None, "Пермский край", "neighbor", 56.768, 54.147),
    ("Пермь", None, "Пермский край", "neighbor", 58.010, 56.229),
    ("Екатеринбург", None, "Свердловская обл.", "neighbor", 56.838, 60.597),
]

# Пресеты популярных маршрутов (§4): пары name_ru. Цены/частоту не хардкодим — только пары.
POPULAR_ROUTES: list[tuple[str, str]] = [
    ("Сибай", "Магнитогорск"),
    ("Акъяр", "Орск"),
    ("Акъяр", "Гай"),
    ("Учалы", "Магнитогорск"),
    ("Октябрьский", "Бугульма"),
    ("Нефтекамск", "Ижевск"),
    ("Янаул", "Чайковский"),
    ("Уфа", "Екатеринбург"),
    ("Сибай", "Уфа"),
    ("Баймак", "Уфа"),
]

_KIND_ORDER = {"city": 0, "district_center": 1, "village": 2, "neighbor": 3}
# village — деревни РБ идут в поиске ПОСЛЕ городов/райцентров (не мешаются наверху),
# но перед городами соседних регионов (для попутки по РБ они ближе). Данные — app/data/villages_rb.json.


def seed_settlements(session: Session) -> None:
    """Идемпотентный сид: добавляет отсутствующие (по name_ru) + БЭКФИЛЛ пустого башкирского имени.

    - Новый НП (нет в БД) → вставляем.
    - Существующий НП с ПУСТЫМ name_ba, а в сиде имя есть → заполняем (гэп двуязычия).
      Заполненное имя НЕ трогаем — правки координат/названий в БД не затираются.
    Так добавление имён в SEED само доезжает до прода без отдельной миграции."""
    if not _table_ready(session):
        return
    rows = {s.name_ru: s for s in session.exec(select(Settlement)).all()}
    changed = False
    for name_ru, name_ba, region, kind, lat, lng in SETTLEMENTS_SEED:
        s = rows.get(name_ru)
        if s is None:
            session.add(Settlement(name_ru=name_ru, name_ba=name_ba, region=region, kind=kind, lat=lat, lng=lng))
            changed = True
        elif name_ba and not (s.name_ba or "").strip():
            s.name_ba = name_ba   # бэкфилл только пустого — заполненное (в т.ч. правки админа) не трогаем
            session.add(s)
            changed = True
    if changed:
        session.commit()


def _table_ready(session: Session) -> bool:
    try:
        session.exec(select(Settlement.id).limit(1)).first()
        return True
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до миграции) → сид молча пропускаем
        return False


# ─────────────────────────── Деревни РБ (kind='village') ───────────────────────────
# Данные тянет scripts/import_villages.py из OpenStreetMap → app/data/villages_rb.json.
# В репозитории файл пустой ([]) — фича «готова, ждёт данных»: seed_villages на пустом = no-op.

def parse_overpass_elements(elements: list) -> list[dict]:
    """OSM Overpass elements → [{name_ru, name_ba, district, lat, lng}]. Чистая (тестируется).

    Берём только узлы-места (place ∈ village/hamlet/town/...) с русским именем и координатами.
    district — из тегов is_in:* / addr:district, если OSM их дал (иначе None; скрипт дозаполняет)."""
    out = []
    for e in elements:
        if e.get("type") != "node":
            continue
        tags = e.get("tags") or {}
        if tags.get("place") not in _OSM_VILLAGE_PLACES:
            continue
        name = (tags.get("name") or "").strip()
        lat, lng = e.get("lat"), e.get("lon")
        if not name or lat is None or lng is None:
            continue
        district = (tags.get("is_in:district") or tags.get("addr:district")
                    or tags.get("is_in:county") or "").strip() or None
        out.append({
            "name_ru": name,
            "name_ba": (tags.get("name:ba") or "").strip() or None,
            "district": district,
            "lat": round(float(lat), 5), "lng": round(float(lng), 5),
        })
    return out


def _load_villages_data() -> list[dict]:
    """Читает app/data/villages_rb.json (список деревень). Нет файла / битый / пустой → []."""
    try:
        raw = json.loads(_VILLAGES_JSON.read_text(encoding="utf-8"))
        return raw if isinstance(raw, list) else []
    except Exception:  # noqa: BLE001 — файла нет или битый → просто без деревень
        return []


def seed_villages(session: Session) -> int:
    """Идемпотентный сид деревень (kind='village'). Ключ — (name_ru, district) для тёзок.
    Пустой датасет → no-op. Возвращает число добавленных (для лога)."""
    if not _table_ready(session):
        return 0
    data = _load_villages_data()
    if not data:
        return 0
    existing = {(s.name_ru, s.district or "")
                for s in session.exec(select(Settlement).where(Settlement.kind == "village")).all()}
    added = 0
    for v in data:
        name = (v.get("name_ru") or "").strip()
        if not name or v.get("lat") is None or v.get("lng") is None:
            continue
        district = (v.get("district") or "").strip() or None
        key = (name, district or "")
        if key in existing:
            continue
        session.add(Settlement(
            name_ru=name, name_ba=(v.get("name_ba") or None), region=RB, kind="village",
            district=district, lat=float(v["lat"]), lng=float(v["lng"]),
        ))
        existing.add(key)
        added += 1
    if added:
        session.commit()
    return added


def _active(session: Session) -> list[Settlement]:
    return session.exec(select(Settlement).where(Settlement.active == True)).all()  # noqa: E712


def search_settlements(session: Session, q: str = "", limit: int = 10) -> list[Settlement]:
    """Префиксный поиск по name_ru И name_ba, регистронезависимый, только активные.
    Фильтруем в Python: SQL lower() в SQLite не знает кириллицу, а строк здесь ~95."""
    limit = max(1, min(limit, 50))
    rows = _active(session)
    needle = q.strip().casefold()
    if needle:
        rows = [s for s in rows if s.name_ru.casefold().startswith(needle)
                or (s.name_ba or "").casefold().startswith(needle)]
    rows.sort(key=lambda s: (_KIND_ORDER.get(s.kind, 9), s.name_ru))
    return rows[:limit]


def by_exact_name(session: Session, name: str) -> Optional[Settlement]:
    """Точное имя (ru или ba, без регистра) → НП. Для geocode_city()."""
    needle = name.strip().casefold()
    if not needle:
        return None
    for s in _active(session):
        if s.name_ru.casefold() == needle or (s.name_ba or "").casefold() == needle:
            return s
    return None


def nearest_settlement(session: Session, lat: float, lng: float,
                       max_km: float = NEAREST_KM) -> Optional[Settlement]:
    """Ближайший активный НП в радиусе max_km — «город» точки заказа для зон такси."""
    best, best_km = None, None
    for s in _active(session):
        km = haversine_km(lat, lng, s.lat, s.lng)
        if best_km is None or km < best_km:
            best, best_km = s, km
    if best is not None and best_km is not None and best_km <= max_km:
        return best
    return None


def settlement_payload(s: Settlement) -> dict:
    return {
        "id": s.id, "name_ru": s.name_ru, "name_ba": s.name_ba,
        "region": s.region, "kind": s.kind, "district": s.district, "lat": s.lat, "lng": s.lng,
    }


def popular_routes_payload(session: Session) -> list[dict]:
    """Пресеты маршрутов с полными карточками НП (для чипов в UI). Ненайденные пары пропускаем."""
    by_name = {s.name_ru: s for s in _active(session)}
    out = []
    for frm, to in POPULAR_ROUTES:
        a, b = by_name.get(frm), by_name.get(to)
        if a and b:
            out.append({"from": settlement_payload(a), "to": settlement_payload(b)})
    return out
