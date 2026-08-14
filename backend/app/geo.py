"""География (волна 2): справочник населённых пунктов РБ + соседние регионы.

Четыре kind'а (см. docs/business-logic-2026-07.md §4):
  city            — 21 город республиканского значения РБ;
  district_center — центры 54 муниципальных районов (сёла; где центр = город, он уже в city);
  village         — сельские НП: вся РБ (4482) + приграничная полоса соседних регионов
                    (2121, ~50 км от границы). Данные — app/data/villages_rb.json и
                    villages_border.json, заливает scripts/import_villages.py из OSM;
  neighbor        — приграничные города соседних регионов (популярный межгород).

Сиды идемпотентные — зовутся из lifespan (main.py): seed_settlements (город/райцентр/сосед по
name_ru) + seed_villages (деревни по (name_ru, region, district) — тёзки различаем районом).
Координаты городов, уже известных CITY_COORDS (services.py), совпадают с ними 1:1 —
geocode_city() и тесты видят те же значения. Координаты сёл — из OSM (ODbL: где показываем
деревни, нужна атрибуция «© OpenStreetMap»). name_ba — черновой башкирский (финал — за носителем).

Поиск по ~6600 строкам держим в оперативной памяти (_entry): справочник статичен, из БД
читаем один раз и переиспользуем, пока не изменилось количество строк (или не истёк TTL).
"""
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from sqlalchemy import func, text as sa_text
from sqlmodel import Session, select

from .logs import log
from .models import Settlement
from .services import haversine_km

# Датасеты деревень (наполняет scripts/import_villages.py из OSM; пустой [] → сид no-op).
_VILLAGES_JSON = Path(__file__).parent / "data" / "villages_rb.json"          # вся РБ
_VILLAGES_BORDER_JSON = Path(__file__).parent / "data" / "villages_border.json"  # приграничье соседей
# Город/райцентр → его муниципальный район (тоже из OSM). Баймак — райцентр ВНУТРИ Баймакского
# района, а Сибай — отдельный городской округ; на глаз не различить, а зоне «мой район» важно.
_CITY_DISTRICTS_JSON = Path(__file__).parent / "data" / "city_districts.json"
# Какие OSM place → «деревня» (плюс town: пгт/крупные сёла-райцентры уже в city/district_center — их отсеет дедуп).
_OSM_VILLAGE_PLACES = {"village", "hamlet", "town", "isolated_dwelling"}

# Радиус привязки точки заказа к ближайшему НП («город точки А/Б») для зон такси.
NEAREST_KM = 30.0
# «Город точки» — только крупные НП: деревня рядом не должна подменять город в зонах такси.
CITY_KINDS = ("city", "district_center", "neighbor")

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

_KIND_ORDER = {"city": 0, "district_center": 1, "neighbor": 2, "village": 3}
# Порядок подсказок: города РБ → райцентры → города соседей (Магнитогорск, Оренбург —
# частый межгород) → деревни. Деревень тысячи: будь они выше, ввод «Маг» показывал бы
# Магадеево вместо Магнитогорска. Внутри деревень РБ идёт раньше приграничья (см. _sort_key).


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
        invalidate_cache()


def _table_ready(session: Session) -> bool:
    try:
        session.exec(select(Settlement.id).limit(1)).first()
        return True
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до миграции) → сид молча пропускаем
        return False


# ─────────────────────────── Деревни (kind='village') ───────────────────────────
# Данные тянет scripts/import_villages.py из OpenStreetMap → app/data/villages_rb.json (вся РБ)
# и villages_border.json (приграничная полоса соседних регионов). Пустые файлы → сид no-op.

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


def _read_json_list(path: Path) -> list[dict]:
    """Читает список из JSON-файла. Нет файла / битый / не список → []."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return raw if isinstance(raw, list) else []
    except Exception:  # noqa: BLE001 — файла нет или битый → просто без этих данных
        return []


def _load_villages_data() -> list[dict]:
    """Деревни: РБ (villages_rb.json, region по умолчанию «РБ») + приграничье соседей
    (villages_border.json, у каждой строки свой region). Пусто → []."""
    rows = _read_json_list(_VILLAGES_JSON) + _read_json_list(_VILLAGES_BORDER_JSON)
    return rows


# Ключ advisory-замка Postgres: произвольное постоянное число, лишь бы своё.
_SEED_LOCK_KEY = 7281006


def _seed_lock(session: Session, take: bool) -> None:
    """Замок «сеет только один воркер». На проде пять воркеров gunicorn стартуют одновременно;
    без замка каждый увидит пустую таблицу и зальёт свои 6600 сёл — в подсказках всё задвоится
    (уникального индекса на (имя, регион, район) нет). На SQLite (тесты) — ничего не делаем."""
    try:
        if session.get_bind().dialect.name != "postgresql":
            return
        fn = "pg_advisory_lock" if take else "pg_advisory_unlock"
        session.execute(sa_text(f"SELECT {fn}(:k)"), {"k": _SEED_LOCK_KEY})
    except Exception as e:  # noqa: BLE001 — замок не взялся: сеем как раньше, дубли переживём
        log.warning(f"[SEED] advisory-замок не сработал ({'take' if take else 'release'}): {e}")


def seed_villages(session: Session) -> int:
    """Идемпотентный сид деревень (kind='village'). Ключ — (name_ru, region, district):
    тёзки в разных районах — разные точки. Пустой датасет → no-op. Возвращает число добавленных.

    Крупные НП (город/райцентр/сосед) из SETTLEMENTS_SEED деревней НЕ дублируем: OSM отдаёт
    place=town и для Сибая, и для Магнитогорска — их отсекаем по (name_ru, region)."""
    if not _table_ready(session):
        return 0
    data = _load_villages_data()
    if not data:
        return 0
    _seed_lock(session, take=True)
    try:
        return _seed_villages_locked(session, data)
    finally:
        _seed_lock(session, take=False)


def _seed_villages_locked(session: Session, data: list[dict]) -> int:
    existing: set[tuple] = set()
    big: set[tuple] = set()
    for s in session.exec(select(Settlement)).all():
        if s.kind == "village":
            existing.add((s.name_ru, s.region, s.district or ""))
        else:
            big.add((s.name_ru, s.region))
    pending: list[Settlement] = []
    for v in data:
        name = (v.get("name_ru") or "").strip()
        if not name or v.get("lat") is None or v.get("lng") is None:
            continue
        region = (v.get("region") or "").strip() or RB
        if (name, region) in big:
            continue                       # это город/райцентр/сосед — он уже в справочнике
        district = (v.get("district") or "").strip() or None
        key = (name, region, district or "")
        if key in existing:
            continue
        existing.add(key)
        pending.append(Settlement(
            name_ru=name, name_ba=(v.get("name_ba") or None), region=region, kind="village",
            district=district, lat=float(v["lat"]), lng=float(v["lng"]),
        ))
    # Пачками: первый деплой заливает ~6600 строк — по одной это минуты, пачками секунды.
    for i in range(0, len(pending), 1000):
        session.add_all(pending[i:i + 1000])
        session.commit()
    if pending:
        invalidate_cache()
    return len(pending)


# ─────────────────────── Снимок справочника в памяти (поиск) ───────────────────────
# Деревень тысячи, а справочник статичен (меняется только сидом на старте). Поэтому читаем
# его из БД один раз и держим готовый отсортированный список: подсказка на каждую букву
# больше не тянет 6600 строк из Postgres. Свежесть — по (кол-во строк, max id) + TTL.

# TTL — только страховка от правок «на месте» (админ поменял координаты/выключил НП):
# добавление и удаление строк ловится сигнатурой (кол-во, max id) на каждом запросе,
# поэтому пересобирать снимок чаще нет смысла — сборка 6600 строк стоит ~0,3 с.
_CACHE_TTL_S = 600.0
_cache: dict = {}   # bind → {"stamp", "sig", "rows", "exact", "with_district"}

# Свёртка для поиска: регистр, ё→е и башкирские буквы к русским соседям —
# чтобы «офо» находило «Өфө», а «березовка» — «Берёзовка».
_FOLD = str.maketrans({
    "ё": "е", "ә": "а", "ө": "о", "ү": "у", "һ": "х", "ҙ": "з", "ҫ": "с", "ң": "н", "ғ": "г", "ҡ": "к",
})


def fold(s: str) -> str:
    return (s or "").strip().casefold().translate(_FOLD)


def bare_name(text: str) -> str:
    """«Берёзовка (Иглинский р-н)» → «Берёзовка».

    Для поиска по ленте: приложение записывает выбранную деревню вместе с районом, а водитель
    мог набрать её руками, без района. Фильтр ищет подстроку — значит искать надо по голому
    имени, иначе поездка «Берёзовка» не найдётся по запросу «Берёзовка (Иглинский р-н)»."""
    t = (text or "").strip()
    if t.endswith(")") and "(" in t:
        head = t.rpartition("(")[0].strip()
        if head:
            return head
    return t


@dataclass(frozen=True, slots=True)
class SettlementRow:
    """Строка справочника в памяти. Поля — как у Settlement (settlement_payload берёт их же).
    Отдельный тип, а не ORM-объект: ORM-объект, переживший свою сессию, при коммите
    «протухает» (DetachedInstanceError) — в кеше это мина."""
    id: Optional[int]
    name_ru: str
    name_ba: Optional[str]
    region: str
    kind: str
    district: Optional[str]
    lat: float
    lng: float
    key_ru: str          # свёрнутое имя (поиск по префиксу)
    key_ba: str
    words: tuple         # свёрнутые начала слов («Верхние Киги» ← «киги»)


def _sort_key(r: SettlementRow) -> tuple:
    # Города → райцентры → соседи → деревни; деревни РБ раньше приграничья; дальше по алфавиту.
    return (_KIND_ORDER.get(r.kind, 9), 0 if r.region == RB else 1, r.name_ru)


def _to_row(id_, name_ru, name_ba, region, kind, district, lat, lng) -> SettlementRow:
    key_ru, key_ba = fold(name_ru), fold(name_ba or "")
    words = tuple(w for w in key_ru.replace("-", " ").split() if w)
    return SettlementRow(
        id=id_, name_ru=name_ru, name_ba=name_ba, region=region, kind=kind,
        district=district, lat=lat, lng=lng, key_ru=key_ru, key_ba=key_ba, words=words,
    )


def invalidate_cache() -> None:
    """Сбросить снимок (зовём после сида — чтобы новые НП были видны сразу)."""
    _cache.clear()


def _entry(session: Session) -> dict:
    """Снимок справочника: отсортированные строки + индекс точных имён. Кеш — по движку БД,
    свежесть — по (кол-во строк, max id) и TTL: сид добавил НП → снимок пересобирается."""
    key = id(session.get_bind())
    try:
        sig = tuple(session.exec(select(func.count(Settlement.id), func.max(Settlement.id))).one())
    except Exception:  # noqa: BLE001 — таблицы ещё нет → пустой справочник
        return {"rows": [], "exact": {}, "with_district": {}}
    hit = _cache.get(key)
    if hit and hit["sig"] == sig and (time.monotonic() - hit["stamp"]) < _CACHE_TTL_S:
        return hit
    # Тянем колонки, а не ORM-объекты: на 6600 строках это втрое дешевле, а больше и не нужно.
    rows = [_to_row(*r) for r in session.exec(
        select(Settlement.id, Settlement.name_ru, Settlement.name_ba, Settlement.region,
               Settlement.kind, Settlement.district, Settlement.lat, Settlement.lng)
        .where(Settlement.active == True)).all()]  # noqa: E712
    rows.sort(key=_sort_key)
    exact: dict[str, SettlementRow] = {}
    with_district: dict[tuple, SettlementRow] = {}
    for r in rows:                      # порядок важен: первым идёт город, потом деревня-тёзка
        exact.setdefault(r.key_ru, r)
        if r.key_ba:
            exact.setdefault(r.key_ba, r)
        if r.district:                  # «Берёзовка (Иглинский р-н)» — разные точки у тёзок
            dk = fold(r.district)
            with_district.setdefault((r.key_ru, dk), r)
            if r.key_ba:
                with_district.setdefault((r.key_ba, dk), r)
    entry = {"stamp": time.monotonic(), "sig": sig, "rows": rows,
             "exact": exact, "with_district": with_district}
    _cache[key] = entry
    return entry


def _snapshot(session: Session) -> list[SettlementRow]:
    """Активные НП, отсортированные для подсказок."""
    return _entry(session)["rows"]


def _active(session: Session) -> list[SettlementRow]:
    return _snapshot(session)


def search_settlements(session: Session, q: str = "", limit: int = 10) -> list[SettlementRow]:
    """Подсказки: сначала совпадения с начала имени (ru/ba), потом — с начала любого слова
    («киги» → «Верхние Киги»). Регистр, ё и башкирские буквы не важны (fold).
    Список уже отсортирован (города → … → деревни), поэтому берём первые limit."""
    limit = max(1, min(limit, 50))
    rows = _snapshot(session)
    needle = fold(q)
    if not needle:
        return rows[:limit]
    head, inside = [], []
    for s in rows:
        if s.key_ru.startswith(needle) or (s.key_ba and s.key_ba.startswith(needle)):
            head.append(s)
            if len(head) >= limit:
                return head[:limit]
        elif len(inside) < limit and any(w.startswith(needle) for w in s.words):
            inside.append(s)
    return (head + inside)[:limit]


def by_exact_name(session: Session, name: str) -> Optional[SettlementRow]:
    """Точное имя (ru или ba, без регистра) → НП. Для geocode_city().

    Понимает форму «Берёзовка (Иглинский р-н)» — так приложение записывает выбранную деревню,
    иначе четыре Берёзовки РБ неразличимы и поездка уехала бы за сто километров от нужной.
    Без района тёзки разрешаются по важности: сначала город, потом райцентр/сосед, потом деревня."""
    raw = (name or "").strip()
    if not raw:
        return None
    entry = _entry(session)
    if raw.endswith(")") and "(" in raw:
        head, _, tail = raw.rpartition("(")
        hit = entry["with_district"].get((fold(head), fold(tail[:-1])))
        if hit is not None:
            return hit
        raw = head.strip() or raw          # район не узнали — ищем хотя бы по имени
    return entry["exact"].get(fold(raw))   # индекс собран по важности: город раньше деревни


def nearest_settlement(session: Session, lat: float, lng: float,
                       max_km: float = NEAREST_KM,
                       kinds: tuple = CITY_KINDS) -> Optional[SettlementRow]:
    """Ближайший активный НП в радиусе max_km — «город» точки заказа для зон такси.

    По умолчанию ищем среди городов/райцентров/соседей: зона таксиста задаётся городом,
    и деревня в трёх километрах не должна подменять собой Уфу. kinds=() — искать среди всех."""
    best, best_km = None, None
    # Грубый отсев по «квадрату» вокруг точки: haversine на 8000 строк в цикле — дорого.
    dlat = max_km / 111.0
    dlng = max_km / max(10.0, 111.0 * abs(math.cos(math.radians(lat))))
    for s in _snapshot(session):
        if kinds and s.kind not in kinds:
            continue
        if abs(s.lat - lat) > dlat or abs(s.lng - lng) > dlng:
            continue
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


# ─────────────────────────── Зона работы: город / район / загород ───────────────────────────
# Одни правила на такси и курьера (раньше были две разные копии, и они разъезжались).
# База водителя: city — один НП, district — весь муниципальный район. Плюс два согласия:
# «загород» (вторая точка вне базы) и «соседние регионы» (выезд за пределы РБ).
# Как у Яндекс Про «Мой район»: в базовом режиме ОБЕ точки внутри зоны, выход — по тумблеру.

# Радиус привязки точки к селу: деревни редкие, 30 км мало для «в какой я район попал».
AREA_KM = 45.0


@dataclass(frozen=True, slots=True)
class Area:
    """«Где это» для точки или названия: сам НП (если узнали), его район, регион и
    ближайший крупный НП. Крупный нужен, чтобы посёлок в пяти километрах от Сибая
    считался «Сибаем» для водителя, у которого база — город: иначе подача с окраины
    выпадала бы из его зоны, и заказ не доставался никому."""
    settlement: Optional[SettlementRow]
    district: Optional[str]
    region: Optional[str]
    city: Optional[SettlementRow] = None

    @property
    def known(self) -> bool:
        return self.settlement is not None or self.district is not None


_UNKNOWN_AREA = Area(None, None, None)
# Насколько далеко село может быть от «своего» города, чтобы считаться его округой.
CITY_AROUND_KM = 20.0


def _city_districts() -> dict:
    """Карта «город → район» из data/city_districts.json (собрана по контурам OSM). Читаем один раз."""
    global _CITY_DISTRICTS_CACHE
    if _CITY_DISTRICTS_CACHE is None:
        try:
            raw = json.loads(_CITY_DISTRICTS_JSON.read_text(encoding="utf-8"))
            _CITY_DISTRICTS_CACHE = {k: v for k, v in raw.items() if v} if isinstance(raw, dict) else {}
        except Exception:  # noqa: BLE001 — нет файла → падаем на «г.о. Имя», как раньше
            _CITY_DISTRICTS_CACHE = {}
    return _CITY_DISTRICTS_CACHE


_CITY_DISTRICTS_CACHE: Optional[dict] = None


def _area_of_row(s: Optional[SettlementRow], city: Optional[SettlementRow] = None) -> Area:
    if s is None:
        return _UNKNOWN_AREA
    # У города своего района в справочнике нет — берём из карты OSM (Баймак → «Баймакский р-н»,
    # Сибай → «г.о. Сибай»). Нет в карте → считаем район отдельным округом по имени города.
    district = s.district or _city_districts().get(s.name_ru) or (
        f"г.о. {s.name_ru}" if s.kind in ("city", "neighbor") else None)
    big = city if city is not None else (s if s.kind in CITY_KINDS else None)
    return Area(s, district, s.region or None, big)


def area_at(session: Session, lat: Optional[float], lng: Optional[float]) -> Area:
    """Точка на карте → (НП, район, регион, ближайший город). Ищем среди ВСЕХ НП,
    включая деревни: для зоны важно, в каком районе человек стоит."""
    if lat is None or lng is None:
        return _UNKNOWN_AREA
    row = nearest_settlement(session, lat, lng, max_km=AREA_KM, kinds=())
    if row is None:
        return _UNKNOWN_AREA
    city = row if row.kind in CITY_KINDS else nearest_settlement(
        session, lat, lng, max_km=CITY_AROUND_KM)
    return _area_of_row(row, city)


def area_by_name(session: Session, name: str) -> Area:
    """Название («Сибай», «Берёзовка (Иглинский р-н)») → (НП, район, регион, город).
    Для курьера: у посылки хранится текст города, а не координаты."""
    row = by_exact_name(session, name)
    if row is None:
        return _UNKNOWN_AREA
    city = row if row.kind in CITY_KINDS else nearest_settlement(
        session, row.lat, row.lng, max_km=CITY_AROUND_KM)
    return _area_of_row(row, city)


def same_area(base_kind: str, base_city: str, base_district: str, area: Area) -> bool:
    """Точка внутри базовой зоны? Неизвестную точку НЕ режем (fail-open):
    в глуши справочник может не знать НП, и молчащий подбор хуже лишнего оффера."""
    if not area.known:
        return True
    if base_kind == "district":
        if not base_district:
            return True
        return fold(area.district or "") == fold(base_district)
    if not base_city:
        return True
    needle = fold(base_city)
    # Свой НП — или его округа: посёлок под Сибаем для сибайского водителя тоже «Сибай».
    for s in (area.settlement, area.city):
        if s is not None and (s.key_ru == needle or (s.key_ba and s.key_ba == needle)):
            return True
    return False


def _trip_km(a: Area, b: Area) -> Optional[float]:
    """Прямая между точками заказа, если оба НП известны (для «это ещё местная поездка?»)."""
    if a.settlement is None or b.settlement is None:
        return None
    return haversine_km(a.settlement.lat, a.settlement.lng, b.settlement.lat, b.settlement.lng)


def _direction_matches(session: Session, direction_id: int, b: Area) -> bool:
    """Заказ идёт в закреплённую сторону? Совпадение по самому НП или по его району
    (закрепил «Уфа» — заказ в уфимское село по пути тоже считаем своим)."""
    target = session.get(Settlement, direction_id)
    if target is None:
        return True                        # направление удалили из справочника — не режем подбор
    if b.settlement is not None and b.settlement.id == target.id:
        return True
    target_area = _area_of_row(_to_row(target.id, target.name_ru, target.name_ba, target.region,
                                       target.kind, target.district, target.lat, target.lng))
    return bool(b.district and target_area.district and fold(b.district) == fold(target_area.district))


def zone_allows(session: Session, *, zone: Optional[str], work_city: Optional[str],
                work_district: Optional[str], intercity: bool, regions: bool,
                direction_id: Optional[int], a: Area, b: Area,
                local_km: Optional[float] = None) -> bool:
    """Подходит ли заказ (точка А → точка Б) под зону работы. Общая для такси и курьера.

    По-человечески:
      • зона не выбрана → берём всё (как было до географии);
      • закреплено направление («еду на Уфу») → только заказы в ту сторону, остальные мимо;
      • подача не в моей зоне → чужой заказ, даже если я стою рядом;
      • обе точки дома (свой НП / свой район) или поездка короткая (соседнее село за рекой) →
        берём: Баймак → Сибай это 35 км, для человека это «по-местному», а не межгород;
      • вторая точка далеко за базой → нужен тумблер «выезд загород»;
      • другой регион → нужен ещё и тумблер «соседние регионы».
    """
    if not zone:
        return True
    base = "district" if zone == "district" else "city"
    a_in = same_area(base, work_city or "", work_district or "", a)
    b_in = same_area(base, work_city or "", work_district or "", b) if b.known else a_in
    # «Соседний регион» = поездка ПЕРЕСЕКАЕТ границу региона. Считаем по самому заказу, а не
    # «всё, что не РБ»: курьер из приграничного Магнитогорска возит у себя дома, и требовать
    # с него тумблер «соседние регионы» на местный заказ было бы глупо.
    other_region = bool(a.region and b.region and a.region != b.region)

    if direction_id is not None:
        # «По делам»: закрепил сторону — везу только туда (так же было до районов).
        if not intercity or (other_region and not regions):
            return False
        return _direction_matches(session, direction_id, b)
    if not a_in:
        return False
    km = _trip_km(a, b)
    near = km is not None and local_km is not None and km <= local_km
    if b_in or near:
        return not other_region or regions
    if not intercity:
        return False
    return not other_region or regions


def district_exists(session: Session, name: str) -> bool:
    """Есть ли такой район в справочнике. Нужно на входе зоны: район с опечаткой —
    это ноль заказов у человека и полное непонимание, почему тихо."""
    needle = fold(name)
    if not needle:
        return False
    return any(fold(r["district"]) == needle for r in districts_payload(session))


def districts_payload(session: Session) -> list[dict]:
    """Районы для пикера зоны: имя + регион + сколько НП внутри (человеку понятнее,
    что район не пустой). Сортировка: сначала РБ, потом соседи, внутри — по алфавиту."""
    counts: dict[tuple, int] = {}
    for s in _snapshot(session):
        area = _area_of_row(s)
        if not area.district:
            continue
        key = (area.district, s.region or RB)
        counts[key] = counts.get(key, 0) + 1
    rows = [{"district": d, "region": r, "settlements": n} for (d, r), n in counts.items()]
    rows.sort(key=lambda x: (0 if x["region"] == RB else 1, x["district"]))
    return rows


def popular_routes_payload(session: Session) -> list[dict]:
    """Пресеты маршрутов с полными карточками НП (для чипов в UI). Ненайденные пары пропускаем."""
    by_name = {s.name_ru: s for s in _active(session)}
    out = []
    for frm, to in POPULAR_ROUTES:
        a, b = by_name.get(frm), by_name.get(to)
        if a and b:
            out.append({"from": settlement_payload(a), "to": settlement_payload(b)})
    return out


def name_variants(session: Session, name: str) -> list[str]:
    """Все написания этого населённого пункта: как спросили + русское + башкирское.

    Зачем (аудит 2026-08-08, волна 92). Приложение двуязычное, и в поле «Откуда» оно
    подставляет имя НА ЯЗЫКЕ ЧЕЛОВЕКА: у башкироязычного водителя рейс уезжает в базу как
    «Баймаҡ», у русскоязычной пассажирки поиск идёт по «Баймак». Фильтр сравнивал строки
    как есть — и они не встречались. То есть двуязычие, ради которого проект и делается,
    разрезало базу пополам: две половины жителей одного района не видели поездки друг друга.

    Здесь мы разворачиваем запрос в оба написания по справочнику НП. Дёшево: справочник
    лежит в памяти, а в SQL уходит обычный OR по двум подстрокам — индекс не ломается.
    Незнакомое имя (человек набрал руками) возвращаем как есть: искать по нему всё равно надо.
    """
    raw = bare_name((name or "").strip())
    if not raw:
        return []
    out = [raw]
    row = by_exact_name(session, raw)
    if row is not None:
        for candidate in (row.name_ru, row.name_ba):
            candidate = (candidate or "").strip()
            # Сравниваем БУКВАЛЬНО, а не свёрнутым именем: нам нужны именно разные написания
            # («Баймак» и «Баймаҡ»), а свёртка их как раз приравнивает — и второй вариант
            # отбрасывался бы, ради которого всё и затевалось.
            if candidate and candidate not in out:
                out.append(candidate)
    return out
