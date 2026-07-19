"""F15: сезонные события Башкортостана — задел роста в пики (Сабантуй, Ураза/Курбан-байрам,
выпускные, 1 сентября, 9 Мая, Новый год).

Серверный конфиг (даты правятся ЗДЕСЬ, без пересборки клиента). Эндпоинт отдаёт события,
которые идут сейчас или начнутся в ближайшие ~3 недели — клиент рисует баннер на карте
«Скоро {событие} — едешь? Опубликуй поездку» и предлагает шаблон.

Даты: фиксированные праздники — по (месяц, день). Подвижные (Ураза/Курбан — лунный календарь)
заданы диапазоном; ОБНОВЛЯТЬ ЕЖЕГОДНО (пометка note). Логика вынесена в active_events(today, …)
— чистая, тестируется без привязки к реальной дате.
"""
from datetime import date, timedelta
from typing import Optional

from fastapi import APIRouter

from ..timeutil import utcnow

router = APIRouter(tags=["seasonal"])

# (месяц, день) начала/конца; emoji — для баннера; note_* — подпись/пометка.
SEASONAL_EVENTS = [
    {"code": "sabantuy",   "name_ru": "Сабантуй",      "name_ba": "Һабантуй",
     "start": (6, 1),   "end": (6, 30), "emoji": "🐎", "note_ru": "по районам",            "note_ba": "райондар буйынса"},
    {"code": "uraza",      "name_ru": "Ураза-байрам",  "name_ba": "Ураҙа байрам",
     "start": (3, 20),  "end": (3, 31), "emoji": "🌙", "note_ru": "дата подвижная",        "note_ba": "күсмә дата"},
    {"code": "kurban",     "name_ru": "Курбан-байрам", "name_ba": "Ҡорбан байрам",
     "start": (6, 6),   "end": (6, 9),  "emoji": "🌙", "note_ru": "дата подвижная",        "note_ba": "күсмә дата"},
    {"code": "graduation", "name_ru": "Выпускные",     "name_ba": "Сығарылыш кисәләре",
     "start": (6, 20),  "end": (6, 30), "emoji": "🎓", "note_ru": "",                       "note_ba": ""},
    {"code": "sep1",       "name_ru": "1 сентября",    "name_ba": "1 сентябрь",
     "start": (8, 28),  "end": (9, 3),  "emoji": "📚", "note_ru": "к началу учёбы",         "note_ba": "уҡыу башына"},
    {"code": "may9",       "name_ru": "9 Мая",         "name_ba": "9 Май",
     "start": (5, 7),   "end": (5, 10), "emoji": "🎖", "note_ru": "",                       "note_ba": ""},
    {"code": "newyear",    "name_ru": "Новый год",     "name_ba": "Яңы йыл",
     "start": (12, 28), "end": (1, 3),  "emoji": "🎄", "note_ru": "к родным на праздники",  "note_ba": "туғандарға"},
]

_LOOKAHEAD_DAYS = 21   # событие показываем, если идёт сейчас или начнётся в ближайшие 3 недели
_MAX_WINDOW = 90


def _occurrences(md_start, md_end, years):
    """Вхождения события (start_date, end_date) для указанных годов. Если конец «раньше»
    начала по (месяц, день) — событие переходит через год (напр. Новый год 28.12–03.01)."""
    for y in years:
        start = date(y, md_start[0], md_start[1])
        end_year = y if (md_end[0], md_end[1]) >= (md_start[0], md_start[1]) else y + 1
        yield start, date(end_year, md_end[0], md_end[1])


def active_events(today: date, window: int = _LOOKAHEAD_DAYS) -> list:
    """События, пересекающиеся с окном [today, today+window]. Чистая функция — тестируема."""
    horizon = today + timedelta(days=window)
    out = []
    for ev in SEASONAL_EVENTS:
        for start, end in _occurrences(ev["start"], ev["end"], (today.year - 1, today.year, today.year + 1)):
            if end >= today and start <= horizon:            # вхождение пересекается с окном
                out.append({
                    "code": ev["code"],
                    "name_ru": ev["name_ru"], "name_ba": ev["name_ba"],
                    "note_ru": ev["note_ru"], "note_ba": ev["note_ba"],
                    "emoji": ev["emoji"],
                    "starts_at": start.isoformat(), "ends_at": end.isoformat(),
                    "active": start <= today <= end,          # уже идёт (не «скоро»)
                })
                break
    out.sort(key=lambda e: e["starts_at"])
    return out


@router.get("/seasonal-events")
def seasonal_events(days: Optional[int] = None):
    """Актуальные сезонные события (публично — для баннера на карте). days — окно вперёд (по умолч. 21)."""
    window = _LOOKAHEAD_DAYS if not days else max(1, min(days, _MAX_WINDOW))
    return active_events(utcnow().date(), window)
