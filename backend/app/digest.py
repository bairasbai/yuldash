"""Дневная сводка админу (Александру) в Telegram (B9b-3).

Без внешнего cron — прагматично: первый HTTP-запрос ПОСЛЕ daily_digest_hour местного
времени (Уфа, UTC+local_tz_offset_hours) запускает отправку в фоновом потоке.
«Сегодня уже отправлено» держим в двух слоях:
  1) память процесса (_memo_sent_day) — не дёргать БД на каждом запросе;
  2) строка-замок DailyDigestLog с UNIQUE(day) — гонку воркеров gunicorn решает БД
     (у второго воркера insert падает → сводку он не шлёт).

Выключается конфигом: DAILY_DIGEST_ENABLED=false. Час — DAILY_DIGEST_HOUR (местный).
Приватность: в сводке только агрегаты-счётчики, ни телефонов, ни имён, ни координат.
"""
import threading
from datetime import date, datetime, time as time_type, timedelta
from typing import Optional

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from .config import settings
from .db import engine
from .logs import log
from .models import (
    Booking, CommissionDebt, DailyDigestLog, InstantOrder, InstantOrderStatus,
    Report, Ride, TaxiWorkDay, User,
)
from .services import notify_admin_telegram
from .timeutil import utcnow

_memo_sent_day: Optional[date] = None   # per-process: сегодня уже отправлено/проверено


def _local_now(now: Optional[datetime] = None) -> datetime:
    """Местное время (Уфа = UTC + local_tz_offset_hours). Naive-UTC + сдвиг — как в workday."""
    return (now or utcnow()) + timedelta(hours=settings.local_tz_offset_hours)


def _day_window_utc(day: date) -> tuple[datetime, datetime]:
    """Границы МЕСТНОГО дня в UTC (created_at в БД — UTC)."""
    start = datetime.combine(day, time_type.min) - timedelta(hours=settings.local_tz_offset_hours)
    return start, start + timedelta(days=1)


def _count(session: Session, model, column, start: datetime, end: datetime, *extra) -> int:
    q = select(func.count(model.id)).where(column >= start, column < end, *extra)
    return int(session.exec(q).one() or 0)


def build_digest(session: Session, day: date) -> str:
    """Собрать текст сводки за местный день. Только агрегаты (счётчики за день)."""
    start, end = _day_window_utc(day)
    rides = _count(session, Ride, Ride.created_at, start, end)
    bookings = _count(session, Booking, Booking.created_at, start, end)
    orders = _count(session, InstantOrder, InstantOrder.created_at, start, end)
    orders_done = _count(session, InstantOrder, InstantOrder.done_at, start, end,
                         InstantOrder.status == InstantOrderStatus.done)
    orders_cancelled = _count(session, InstantOrder, InstantOrder.cancelled_at, start, end)
    new_users = _count(session, User, User.created_at, start, end)
    # «Водители на линии»: сколько РАЗНЫХ таксистов реально выходили на линию за день
    # (у TaxiWorkDay строка на (driver, day) появляется на первом presence-heartbeat).
    drivers_online = int(session.exec(
        select(func.count(TaxiWorkDay.id)).where(
            TaxiWorkDay.day == day, TaxiWorkDay.seconds_online > 0)
    ).one() or 0)
    fee_kop = int(session.exec(
        select(func.coalesce(func.sum(CommissionDebt.amount_kop), 0)).where(
            CommissionDebt.created_at >= start, CommissionDebt.created_at < end)
    ).one() or 0)
    reports = _count(session, Report, Report.created_at, start, end)
    return (
        f"📊 Юлдаш за {day.strftime('%d.%m.%Y')}:\n"
        f"поездок попутки {rides} (брони {bookings}),\n"
        f"такси-заказов {orders} (done {orders_done}, отмен {orders_cancelled}),\n"
        f"новых пользователей {new_users},\n"
        f"водителей на линии {drivers_online},\n"
        f"выручка-комиссия ~{fee_kop // 100} ₽,\n"
        f"жалоб новых {reports}"
    )


def daily_digest(session: Session, day: Optional[date] = None) -> str:
    """Собрать сводку за день и отправить админу в Telegram. Возвращает текст (для тестов)."""
    day = day or _local_now().date()
    text = build_digest(session, day)
    notify_admin_telegram(text)
    return text


def maybe_send_daily_digest(session: Session, now: Optional[datetime] = None) -> bool:
    """Гейт «раз в день после N часов»: True = сводка отправлена этим вызовом.
    Идемпотентно и безопасно к гонкам (UNIQUE(day) в БД — арбитр между воркерами)."""
    global _memo_sent_day
    if not settings.daily_digest_enabled:
        return False
    local = _local_now(now)
    if local.hour < settings.daily_digest_hour:
        return False
    day = local.date()
    if _memo_sent_day == day:
        return False
    if session.exec(select(DailyDigestLog).where(DailyDigestLog.day == day)).first():
        _memo_sent_day = day          # другой воркер уже отправил — запоминаем и молчим
        return False
    try:
        session.add(DailyDigestLog(day=day))
        session.commit()
    except IntegrityError:            # параллельный воркер выиграл гонку insert'а
        session.rollback()
        _memo_sent_day = day
        return False
    _memo_sent_day = day
    daily_digest(session, day)
    return True


def _run_check() -> None:
    """Фоновый поток: своя сессия БД, ошибки не роняют ничего (сводка — не критичный путь)."""
    try:
        with Session(engine) as s:
            maybe_send_daily_digest(s)
    except Exception as e:  # noqa: BLE001 — сводка никогда не должна ломать запросы
        log.warning(f"[DIGEST] error: {e}")
        from .observability import capture
        capture(e)   # H2: фоновый поток иначе тихо глох бы без алерта


class DailyDigestMiddleware:
    """Чистый ASGI-слой: на КАЖДЫЙ http-запрос — только дешёвая проверка часа и памяти
    процесса (без БД). Порог пройден и сегодня ещё не слали → отправка в daemon-потоке,
    сам запрос не ждёт ни БД, ни Telegram."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and settings.daily_digest_enabled:
            local = _local_now()
            if local.hour >= settings.daily_digest_hour and _memo_sent_day != local.date():
                threading.Thread(target=_run_check, daemon=True).start()
        await self.app(scope, receive, send)
