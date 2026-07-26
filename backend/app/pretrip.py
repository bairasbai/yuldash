# -*- coding: utf-8 -*-
"""Предрейсовое подтверждение таксиста (580-ФЗ, честный минимум).

Закон требует предрейсовый медосмотр и контроль исправности машины. Медцентра у платформы нет
и не будет — но и «ничего» неправильный ответ: до аудита 2026-07-26 слова «осмотр» в коде не
встречалось ни разу. Компромисс, который реально работает в райцентре: **один раз в день, перед
первым выходом на линию, водитель явно подтверждает три вещи** — самочувствие, исправность
машины, отсутствие алкоголя и влияющих на реакцию лекарств.

Это САМОДЕКЛАРАЦИЯ, и так она и называется в интерфейсе — мы не притворяемся медосмотром.
Ценность в двух вещах:
 • человек делает осознанное действие вместо «да ладно, доеду» — это работает даже без врача;
 • остаётся запись: при разборе ДТП видно, что водитель заявил в этот день (`PreTripCheck`).

Гейт стоит там же, где остальные такси-гейты (presence/offer/accept): попутка не затрагивается —
она не такси и предрейсового контроля не требует. Активный заказ не рубим: подтверждение нужно
для НОВОЙ работы, а того, кого уже везут, водитель довезёт.

День — МЕСТНЫЙ (UTC + local_tz_offset_hours), как в `workday.py`: смена таксиста мыслится
местными сутками, а не UTC-полуночью посреди рабочего вечера.
"""
from datetime import date as date_type
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .errors import herr
from .models import PreTripCheck
from .workday import local_day   # реэкспорт: роутеры берут «местный день» отсюда, не дублируя логику

# Текст отказа — двуязычный и объясняющий, ЧТО СДЕЛАТЬ (а не сухое «403 Forbidden»).
NEED_CHECK_RU = "Перед выходом на линию подтверди готовность: самочувствие, машина, без алкоголя."
NEED_CHECK_BA = "Линияға сығыр алдынан әҙерлекте раҫла: һаулыҡ, машина, эсемлекһеҙ."


def today_check(session: Session, driver_id: int, day: Optional[date_type] = None) -> Optional[PreTripCheck]:
    """Подтверждение за местный день. None → сегодня ещё не подтверждал."""
    day = day or local_day()
    return session.exec(
        select(PreTripCheck).where(PreTripCheck.driver_id == driver_id, PreTripCheck.day == day)
    ).first()


def is_confirmed(session: Session, driver_id: int, day: Optional[date_type] = None) -> bool:
    """Подтверждено ли всё три пункта за сегодня. Частичная галочка не считается."""
    c = today_check(session, driver_id, day)
    return bool(c and c.health_ok and c.car_ok and c.no_alcohol)


def confirm(session: Session, driver_id: int, health_ok: bool, car_ok: bool,
            no_alcohol: bool, note: str = "") -> PreTripCheck:
    """Записать подтверждение за сегодня.

    Все три пункта обязательны: «частично готов» — это не готов, и подписывать за человека
    мы не будем. Поэтому строка существует только в состоянии «подтверждено полностью»;
    повтор в тот же день просто обновляет заметку (UNIQUE driver_id+day, дублей нет).
    """
    if not (health_ok and car_ok and no_alcohol):
        raise herr(
            400,
            "Нужно подтвердить все три пункта — иначе на линию нельзя",
            "Өс пункттың өсөһөн дә раҫларға кәрәк — юғиһә линияға сыға алмайһың",
        )
    day = local_day()
    c = today_check(session, driver_id, day)
    if c is None:
        c = PreTripCheck(driver_id=driver_id, day=day)
    c.health_ok = True
    c.car_ok = True
    c.no_alcohol = True
    c.note = (note or "").strip()[:300]
    session.add(c)
    session.commit()
    session.refresh(c)
    return c


def guard_pretrip(session: Session, driver_id: int) -> None:
    """Гейт такси: не подтвердил сегодня → 403 с понятным текстом (не сухой отказ)."""
    if not settings.pretrip_check_required:
        return
    if is_confirmed(session, driver_id):
        return
    raise herr(403, NEED_CHECK_RU, NEED_CHECK_BA)


def payload(session: Session, driver_id: int) -> dict:
    """Состояние на сегодня для экрана водителя."""
    c = today_check(session, driver_id)
    return {
        "required": bool(settings.pretrip_check_required),
        "confirmed": bool(c and c.health_ok and c.car_ok and c.no_alcohol),
        "day": local_day().isoformat(),
        "confirmed_at": c.created_at.isoformat() if c else None,
        "note": (c.note if c else ""),
    }
