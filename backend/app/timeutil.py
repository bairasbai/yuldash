"""Единое «сейчас» в UTC.

`datetime.utcnow()` объявлен deprecated в Python 3.12. Замена `datetime.now(timezone.utc)`
возвращает timezone-AWARE дату — а вся БД Юлдаша хранит НАИВНЫЕ даты, и сравнение
naive↔aware падает `TypeError`. Поэтому держим наивный UTC: поведение 1:1 со старым
`utcnow()`, но без deprecation. Полный переход на aware — отдельная миграция (код + данные).
"""
from datetime import datetime, timedelta, timezone
from typing import Optional


def utcnow() -> datetime:
    """Наивный (без tzinfo) текущий момент в UTC — прямая замена `datetime.utcnow()`."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def client_dt_to_utc(dt: Optional[datetime], naive_means: str = "local") -> Optional[datetime]:
    """Время, пришедшее ОТ КЛИЕНТА → наивный UTC (единое соглашение всей БД).

    Зачем (разбор №2, 2026-08-03): приложение слало время выезда попутки строкой без пояса
    («2026-08-05T10:00:00»), а сервер клал её в БД как есть — то есть трактовал уфимские 10:00
    как 10:00 UTC. Поездка «на 10:00» уезжала на 5 часов: висела в ленте до 17:00 по Уфе,
    напоминание «доехал?» приходило на пять часов позже. На экране время показывалось верно
    (клиент просто резал строку), поэтому ошибку не было видно ГЛАЗАМИ — только по последствиям.

    `naive_means`:
      * `"local"` — время БЕЗ пояса считаем местным башкирским (UTC+5). Так шлют старые версии
        приложения, и это чинит их БЕЗ обновления на телефоне — важно, апдейт доезжает не до всех.
      * `"utc"` — время без пояса считаем UTC. Для потоков, где клиент всегда шлёт пояс явно
        (такси-предзаказ) и наивное приходит только из тестов и служебных вызовов.

    Время С поясом трактуется точно, независимо от режима: новые версии приложения шлют
    `OffsetDateTime`, и никакой догадки уже не нужно.
    """
    if dt is None:
        return None
    if dt.tzinfo is not None:
        return dt.astimezone(timezone.utc).replace(tzinfo=None)
    if naive_means == "utc":
        return dt
    from .config import settings   # локальный импорт: config сам тянет timeutil, циклы не нужны
    return dt - timedelta(hours=settings.local_tz_offset_hours)


def local_date(dt: Optional[datetime]):
    """Календарный день по МЕСТНОМУ времени (Уфа = UTC + `local_tz_offset_hours`).

    Зачем отдельная функция. В базе всё лежит в UTC, а человек живёт по своему календарю,
    и «пятнадцатое» у него начинается на пять часов раньше, чем у сервера. Пока даты просто
    сравнивали через `.date()`, ранний рейс «15 августа, 4 утра» считался четырнадцатым:
    в ленте он не находился по нужному дню, а подписка «карауль на 15-е» его пропускала
    (аудит 2026-08-08, волна 79). Бьёт это по самому частому сельскому рейсу — выезду
    в район затемно, чтобы успеть к открытию учреждений в Уфе.
    """
    if dt is None:
        return None
    from .config import settings   # локальный импорт: config сам тянет timeutil
    return (dt + timedelta(hours=settings.local_tz_offset_hours)).date()
