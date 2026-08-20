"""F15: сезонные события Башкортостана — АВТО-обновление дат каждый год (без правок кода).

Задел роста в пики: Сабантуй, Ураза/Курбан-байрам, выпускные, 1 сентября, 9 Мая, Новый год,
День Республики Башкортостан, дни городов (Уфа, Стерлитамак, Салават, Сибай, …). Эндпоинт
отдаёт события, которые идут сейчас или начнутся в ближайшие ~3 недели — клиент рисует баннер
на карте «Скоро {событие} — едешь? Опубликуй поездку» и предлагает шаблон.

── Почему «на автомате» ─────────────────────────────────────────────────────────────────────
Даты НЕ хардкодятся как (месяц, день) на конкретный год — они ВЫЧИСЛЯЮТСЯ для нужного года:

  • fixed        — фиксированное число (9 Мая, 1 сентября, День Республики 11 октября…).
  • nth_weekday  — N-я суббота/воскресенье месяца (Сабантуй — 2-я суббота июня; дни городов —
                   как правило выходной; День металлурга — 3-е воскресенье июля).
  • hijri        — мусульманские праздники по лунному календарю (Ураза/Курбан). Считаются через
                   табличный исламский календарь → григорианская дата пересчитывается КАЖДЫЙ год
                   автоматически (Ураза сдвигается ~на 11 дней раньше ежегодно). Точность ±1 день
                   к решению муфтията (окончательную дату объявляют по наблюдению луны).

Итог: код не надо трогать из года в год — даты пересобираются сами. Когда выходят ОФИЦИАЛЬНЫЕ
данные (напр. точная дата дня города или сдвиг выходного), их можно «прибить» через
SEASONAL_OVERRIDES — ручной приоритет над авто-расчётом на конкретный год.

Логика вынесена в active_events(today, …) — чистая, тестируется без привязки к реальной дате.
"""
import calendar
from datetime import date, timedelta
from typing import Optional

from fastapi import APIRouter

from ..timeutil import local_date, utcnow

router = APIRouter(tags=["seasonal"])

_LOOKAHEAD_DAYS = 21   # событие показываем, если идёт сейчас или начнётся в ближайшие 3 недели
_MAX_WINDOW = 90

# Дни недели как в date.weekday(): Пн=0 … Сб=5, Вс=6.
_SAT, _SUN = 5, 6


# ─────────────────────────────────── Календарная математика ───────────────────────────────────
# Все конвертации — целочисленные (Julian Day Number), без внешних библиотек. Проверено на
# официальных датах Ураза/Курбан 2025–2027 (совпадение ±1 день).

def _greg_to_jdn(y: int, m: int, d: int) -> int:
    a = (14 - m) // 12
    yy = y + 4800 - a
    mm = m + 12 * a - 3
    return d + (153 * mm + 2) // 5 + 365 * yy + yy // 4 - yy // 100 + yy // 400 - 32045


def _jdn_to_greg(j: int) -> date:
    a = j + 32044
    b = (4 * a + 3) // 146097
    c = a - (146097 * b) // 4
    dd = (4 * c + 3) // 1461
    e = c - (1461 * dd) // 4
    mm = (5 * e + 2) // 153
    day = e - (153 * mm + 2) // 5 + 1
    month = mm + 3 - 12 * (mm // 10)
    year = 100 * b + dd - 4800 + mm // 10
    return date(year, month, day)


def _hijri_to_jdn(y: int, m: int, d: int) -> int:
    """Табличный исламский календарь (leap-годы по правилу 11/30). Эпоха 1948439 — выверена."""
    return d + (29 * (m - 1) + m // 2) + (y - 1) * 354 + (3 + 11 * y) // 30 + 1948439


def _nth_weekday(year: int, month: int, weekday: int, nth: int) -> date:
    """N-й weekday месяца. nth=1..5 — с начала, nth=-1 — последний в месяце."""
    if nth < 0:
        last = calendar.monthrange(year, month)[1]
        d = date(year, month, last)
        return d - timedelta(days=(d.weekday() - weekday) % 7)
    first = date(year, month, 1)
    day = 1 + (weekday - first.weekday()) % 7 + (nth - 1) * 7
    last = calendar.monthrange(year, month)[1]
    if day > last:              # 5-й вторник, которого нет → берём последний (4-й)
        day -= 7
    return date(year, month, day)


def _hijri_anchors(today: date, hmonth: int, hday: int) -> list:
    """Григорианские даты мусульманского (hmonth, hday) для лет вокруг today (± ~2 года)."""
    est = (today.year - 622) * 33 // 32       # грубая оценка хиджры для текущего года
    out = []
    for hy in range(est - 1, est + 3):
        out.append(_jdn_to_greg(_hijri_to_jdn(hy, hmonth, hday)))
    return out


# ─────────────────────────────────── Каталог событий ───────────────────────────────────
# kind: "fixed" (month, day) | "nth_weekday" (month, weekday, nth) | "hijri" (hmonth, hday).
# before/after — сколько дней до/после «главного дня» считаем праздничным окном (для баннера
#                «скоро» добавляется ещё _LOOKAHEAD_DAYS сверху). Новый год через before=4 от 1 янв
#                автоматически уходит в 28 декабря — арифметика дат сама переносит через год.
# category: fed (федеральный) | rb (республиканский) | religious | school | city.
# note_* — короткая пометка под баннером. Названия/пометки на башкирском — ЧЕРНОВИК (проверка носителем).
SEASONAL_EVENTS = [
    # ── Федеральные и общие ──
    {"code": "newyear", "name_ru": "Новый год", "name_ba": "Яңы йыл", "emoji": "🎄",
     "category": "fed", "kind": "fixed", "month": 1, "day": 1, "before": 4, "after": 2,
     "note_ru": "к родным на праздники", "note_ba": "туғандарға ҡунаҡҡа"},
    {"code": "def_day", "name_ru": "День защитника Отечества", "name_ba": "Ватанды һаҡлаусы көнө",
     "emoji": "🎖", "category": "fed", "kind": "fixed", "month": 2, "day": 23, "before": 1, "after": 0,
     "note_ru": "", "note_ba": ""},
    {"code": "mar8", "name_ru": "8 Марта", "name_ba": "8 Март", "emoji": "🌷",
     "category": "fed", "kind": "fixed", "month": 3, "day": 8, "before": 1, "after": 0,
     "note_ru": "к близким", "note_ba": "яҡындарға"},
    {"code": "may_labor", "name_ru": "Праздник Весны и Труда", "name_ba": "Яҙ һәм Хеҙмәт байрамы",
     "emoji": "🌱", "category": "fed", "kind": "fixed", "month": 5, "day": 1, "before": 1, "after": 2,
     "note_ru": "майские", "note_ba": "май байрамдары"},
    {"code": "may9", "name_ru": "День Победы", "name_ba": "Еңеү көнө", "emoji": "🎖",
     "category": "fed", "kind": "fixed", "month": 5, "day": 9, "before": 2, "after": 1,
     "note_ru": "", "note_ba": ""},
    {"code": "ruday", "name_ru": "День России", "name_ba": "Рәсәй көнө", "emoji": "🇷🇺",
     "category": "fed", "kind": "fixed", "month": 6, "day": 12, "before": 1, "after": 1,
     "note_ru": "", "note_ba": ""},
    {"code": "sep1", "name_ru": "День знаний", "name_ba": "Белем көнө", "emoji": "📚",
     "category": "school", "kind": "fixed", "month": 9, "day": 1, "before": 4, "after": 2,
     "note_ru": "к началу учёбы", "note_ba": "уҡыу башына"},
    {"code": "unity", "name_ru": "День народного единства", "name_ba": "Халыҡтар берҙәмлеге көнө",
     "emoji": "🤝", "category": "fed", "kind": "fixed", "month": 11, "day": 4, "before": 1, "after": 1,
     "note_ru": "", "note_ba": ""},

    # ── Республиканские / башкирские ──
    {"code": "rbday", "name_ru": "День Республики Башкортостан", "name_ba": "Башҡортостан Республикаһы көнө",
     "emoji": "🏛", "category": "rb", "kind": "fixed", "month": 10, "day": 11, "before": 2, "after": 1,
     "note_ru": "", "note_ba": ""},
    {"code": "sabantuy", "name_ru": "Сабантуй", "name_ba": "Һабантуй", "emoji": "🐎",
     "category": "rb", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 2, "before": 7, "after": 21,
     "note_ru": "по районам", "note_ba": "райондар буйынса"},
    {"code": "graduation", "name_ru": "Выпускные", "name_ba": "Сығарылыш кисәләре", "emoji": "🎓",
     "category": "school", "kind": "fixed", "month": 6, "day": 23, "before": 3, "after": 5,
     "note_ru": "", "note_ba": ""},

    # ── Мусульманские (лунный календарь → авто-пересчёт каждый год) ──
    {"code": "uraza", "name_ru": "Ураза-байрам", "name_ba": "Ураҙа байрам", "emoji": "🌙",
     "category": "religious", "kind": "hijri", "hmonth": 10, "hday": 1, "before": 2, "after": 1,
     "note_ru": "дата по лунному календарю", "note_ba": "ай календары буйынса"},
    {"code": "kurban", "name_ru": "Курбан-байрам", "name_ba": "Ҡорбан байрам", "emoji": "🌙",
     "category": "religious", "kind": "hijri", "hmonth": 12, "hday": 10, "before": 2, "after": 2,
     "note_ru": "дата по лунному календарю", "note_ba": "ай календары буйынса"},

    # ── Дни городов (даты — лучшая оценка по устойчивому паттерну; уточняются ежегодно,
    #    при выходе официальной даты — прибить через SEASONAL_OVERRIDES) ──
    {"code": "ufa_day", "name_ru": "День города Уфы", "name_ba": "Өфө ҡалаһы көнө", "emoji": "🏙",
     "category": "city", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 2, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "sterlitamak_day", "name_ru": "День города Стерлитамака", "name_ba": "Стәрлетамаҡ ҡалаһы көнө",
     "emoji": "🏙", "category": "city", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 2, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "salavat_day", "name_ru": "День города Салавата", "name_ba": "Салауат ҡалаһы көнө", "emoji": "🏙",
     "category": "city", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 2, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "neftekamsk_day", "name_ru": "День города Нефтекамска", "name_ba": "Нефтекама ҡалаһы көнө",
     "emoji": "🏙", "category": "city", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 2, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "oktyabrsky_day", "name_ru": "День города Октябрьского", "name_ba": "Октябрьский ҡалаһы көнө",
     "emoji": "🏙", "category": "city", "kind": "nth_weekday", "month": 6, "weekday": _SAT, "nth": 1, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "sibay_day", "name_ru": "День города Сибая", "name_ba": "Сибай ҡалаһы көнө", "emoji": "🏙",
     "category": "city", "kind": "nth_weekday", "month": 8, "weekday": _SAT, "nth": -1, "before": 3, "after": 1,
     "note_ru": "дата уточняется", "note_ba": "датаһы аныҡлана"},
    {"code": "magnitogorsk_day", "name_ru": "День города Магнитогорска", "name_ba": "Магнитогорск ҡалаһы көнө",
     "emoji": "🏙", "category": "city", "kind": "nth_weekday", "month": 7, "weekday": _SUN, "nth": 3, "before": 3, "after": 1,
     "note_ru": "у границы РБ", "note_ba": "РБ сигендә"},
]

# Ручной приоритет: когда вышла ОФИЦИАЛЬНАЯ дата — прибиваем её на конкретный год.
# Формат: code -> { год_главного_дня: (start_iso, end_iso) }. Перебивает авто-расчёт.
# Пример: "ufa_day": {2026: ("2026-06-13", "2026-06-14")}
SEASONAL_OVERRIDES: dict = {}


def _anchor_dates(ev: dict, today: date) -> list:
    """Кандидаты «главного дня» события для лет вокруг today."""
    kind = ev["kind"]
    if kind == "fixed":
        return [date(y, ev["month"], ev["day"]) for y in (today.year - 1, today.year, today.year + 1)]
    if kind == "nth_weekday":
        return [_nth_weekday(y, ev["month"], ev["weekday"], ev["nth"])
                for y in (today.year - 1, today.year, today.year + 1)]
    if kind == "hijri":
        return _hijri_anchors(today, ev["hmonth"], ev["hday"])
    return []


def _occurrence(ev: dict, anchor: date):
    """(start, end) праздничного окна вокруг anchor, с учётом ручного override на год anchor."""
    ov = SEASONAL_OVERRIDES.get(ev["code"], {}).get(anchor.year)
    if ov:
        return date.fromisoformat(ov[0]), date.fromisoformat(ov[1])
    start = anchor - timedelta(days=ev.get("before", 0))
    end = anchor + timedelta(days=ev.get("after", 0))
    return start, end


def active_events(today: date, window: int = _LOOKAHEAD_DAYS) -> list:
    """События, пересекающиеся с окном [today, today+window]. Чистая функция — тестируема.

    Для каждого события берём ближайшее вхождение, которое ещё не кончилось и начинается
    не позже горизонта. active=True — праздник уже идёт (не «скоро»)."""
    horizon = today + timedelta(days=window)
    out = []
    for ev in SEASONAL_EVENTS:
        best = None
        for anchor in _anchor_dates(ev, today):
            start, end = _occurrence(ev, anchor)
            if end >= today and start <= horizon:              # вхождение пересекается с окном
                if best is None or start < best[0]:            # берём самое раннее подходящее
                    best = (start, end, anchor)
        if best:
            start, end, anchor = best
            out.append({
                "code": ev["code"],
                "name_ru": ev["name_ru"], "name_ba": ev["name_ba"],
                "note_ru": ev["note_ru"], "note_ba": ev["note_ba"],
                "emoji": ev["emoji"], "category": ev["category"],
                "anchor": anchor.isoformat(),
                "starts_at": start.isoformat(), "ends_at": end.isoformat(),
                "active": start <= today <= end,               # уже идёт (не «скоро»)
            })
    out.sort(key=lambda e: (e["starts_at"], e["code"]))
    return out


@router.get("/seasonal-events")
def seasonal_events(days: Optional[int] = None):
    """Актуальные сезонные события (публично — для баннера на карте). days — окно вперёд (по умолч. 21).

    Ответ — {"items": [...]} (единый контракт списков клиента: `optJSONArray("items")`)."""
    window = _LOOKAHEAD_DAYS if not days else max(1, min(days, _MAX_WINDOW))
    return {"items": active_events(local_date(utcnow()), window)}
