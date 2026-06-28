"""Единое «сейчас» в UTC.

`datetime.utcnow()` объявлен deprecated в Python 3.12. Замена `datetime.now(timezone.utc)`
возвращает timezone-AWARE дату — а вся БД Юлдаша хранит НАИВНЫЕ даты, и сравнение
naive↔aware падает `TypeError`. Поэтому держим наивный UTC: поведение 1:1 со старым
`utcnow()`, но без deprecation. Полный переход на aware — отдельная миграция (код + данные).
"""
from datetime import datetime, timezone


def utcnow() -> datetime:
    """Наивный (без tzinfo) текущий момент в UTC — прямая замена `datetime.utcnow()`."""
    return datetime.now(timezone.utc).replace(tzinfo=None)
