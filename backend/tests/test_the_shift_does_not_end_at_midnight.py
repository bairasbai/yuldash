"""Смена водителя не обрывается полуночью.

Правило §8: восемь часов за рулём — потом отдых. Оно написано про усталость, а считалось
по календарным суткам. Водитель, работавший «впритык», обходил его, ни разу не нарушив:
7 часов 48 минут до полуночи (в лимит не упёрся → блока нет), в 00:01 счётчик с нуля — и ещё
восемь. Почти шестнадцать часов за рулём подряд, и формально всё в порядке.

Ровно та же слепота, из-за которой в проекте заводили недельный лимит: «каждый отдельный день
выглядит нормальным». Только здесь она внутри суток, и недельный лимит поймает это через
несколько дней, а ночная трасса Сибай–Уфа — сегодня (проверено запросом, аудит 2026-08-12,
волна 54).

Теперь смена продолжается, пока между работой не было настоящего отдыха. Поспал — начинаешь
с нуля, и это ровно то, ради чего правило и написано.
"""
from __future__ import annotations

from datetime import datetime, timedelta

from sqlmodel import Session

from app import workday as wd_mod
from app.config import settings
from app.db import engine
from app.models import TaxiWorkDay, UserRole

def _utc(local: datetime) -> datetime:
    """Местное время (Уфа) → наивный UTC, как хранится в базе."""
    return local - timedelta(hours=settings.local_tz_offset_hours)


def _worked_yesterday(session: Session, driver_id: int, hours: float, last_local: datetime,
                      hit_limit: bool = False) -> None:
    session.add(TaxiWorkDay(
        driver_id=driver_id, day=last_local.date(),
        seconds_online=int(hours * 3600),
        limit_reached_at=_utc(last_local) if hit_limit else None,
        last_heartbeat_at=_utc(last_local),
    ))
    session.commit()


def test_работал_впритык_до_полуночи_смена_продолжается(client, user_factory):
    """Главная история: вчера почти лимит, сегодня 00:30 — счётчик НЕ с нуля."""
    drv = user_factory("MidnightDrv", role=UserRole.driver)
    limit_h = settings.taxi_shift_limit_hours
    with Session(engine) as s:
        _worked_yesterday(s, drv["id"], limit_h - 0.2, datetime(2026, 8, 11, 23, 50))

        now = _utc(datetime(2026, 8, 12, 0, 30))
        wd = wd_mod.record_heartbeat(s, drv["id"], now)
        # Наработка вчерашнего вечера никуда не делась.
        assert wd_mod.shift_seconds(s, drv["id"], wd, now) >= int((limit_h - 0.2) * 3600)


def test_ещё_немного_за_рулём_и_отдых_наступает(client, user_factory):
    """Через двадцать минут работы он упирается в лимит — как и должен был вчера."""
    drv = user_factory("MidnightDrv2", role=UserRole.driver)
    limit_h = settings.taxi_shift_limit_hours
    with Session(engine) as s:
        _worked_yesterday(s, drv["id"], limit_h - 0.2, datetime(2026, 8, 11, 23, 50))

        start = _utc(datetime(2026, 8, 12, 0, 30))
        wd_mod.record_heartbeat(s, drv["id"], start)
        for minute in range(1, 25):        # пинги раз в минуту, как шлёт приложение
            wd = wd_mod.record_heartbeat(s, drv["id"], start + timedelta(minutes=minute))

        assert wd.limit_reached_at is not None
        assert wd_mod.blocking_workday(s, drv["id"], start + timedelta(minutes=30)) is not None


def test_после_отдыха_счётчик_начинается_с_нуля(client, user_factory):
    """Страховка от перестраховки: выспался — новая смена. Иначе правило превратилось бы
    в пожизненное наказание за вчерашний день."""
    drv = user_factory("MidnightDrv3", role=UserRole.driver)
    limit_h = settings.taxi_shift_limit_hours
    with Session(engine) as s:
        # Закончил вчера в 20:00, вышел сегодня в 10:00 — между ними полноценный отдых.
        _worked_yesterday(s, drv["id"], limit_h - 0.2, datetime(2026, 8, 11, 20, 0))

        now = _utc(datetime(2026, 8, 12, 10, 0))
        assert wd_mod.carried_over_seconds(s, drv["id"], now) == 0
        wd = wd_mod.record_heartbeat(s, drv["id"], now)
        assert wd.limit_reached_at is None
        assert wd_mod.shift_seconds(s, drv["id"], wd, now) < 60


def test_упёрся_в_лимит_блок_переживает_полночь(client, user_factory):
    """Контроль на старое поведение: кто дошёл до лимита, тот заблокирован и после полуночи."""
    drv = user_factory("MidnightDrv4", role=UserRole.driver)
    with Session(engine) as s:
        _worked_yesterday(s, drv["id"], settings.taxi_shift_limit_hours,
                          datetime(2026, 8, 11, 23, 50), hit_limit=True)
        assert wd_mod.blocking_workday(s, drv["id"], _utc(datetime(2026, 8, 12, 0, 30))) is not None


def test_кабинет_показывает_ту_же_цифру_что_и_лимит(client, user_factory):
    """Прогресс смены в кабинете обязан совпадать с тем, по чему считается блок. Иначе в полночь
    он визуально обнулится, а отдых придёт «неожиданно» — правило станет выглядеть случайным."""
    drv = user_factory("MidnightDrv5", role=UserRole.driver)
    limit_h = settings.taxi_shift_limit_hours
    with Session(engine) as s:
        _worked_yesterday(s, drv["id"], limit_h - 0.2, datetime(2026, 8, 11, 23, 50))

        now = _utc(datetime(2026, 8, 12, 0, 30))
        wd_mod.record_heartbeat(s, drv["id"], now)
        info = wd_mod.summary(s, drv["id"], now)
        assert info["seconds_online"] >= int((limit_h - 0.2) * 3600)
        assert info["remaining_sec"] <= int(0.2 * 3600)
