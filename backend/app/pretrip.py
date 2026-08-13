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
from datetime import date as date_type, datetime, timedelta
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .errors import herr
from .models import PreTripCheck
from .timeutil import utcnow
# `local_day` реэкспортируем: роутеры берут «местный день» отсюда, не дублируя логику.
# `last_online_at` нужен, чтобы отличить продолжающуюся смену от новой (волна 55).
from .workday import last_online_at as wd_last_online, local_day

# Текст отказа — двуязычный и объясняющий, ЧТО СДЕЛАТЬ (а не сухое «403 Forbidden»).
NEED_CHECK_RU = "Перед выходом на линию подтверди готовность: самочувствие, машина, без алкоголя."
NEED_CHECK_BA = "Линияға сығыр алдынан әҙерлекте раҫла: һаулыҡ, машина, эсемлекһеҙ."


def today_check(session: Session, driver_id: int, day: Optional[date_type] = None) -> Optional[PreTripCheck]:
    """Подтверждение за местный день. None → сегодня ещё не подтверждал."""
    day = day or local_day()
    return session.exec(
        select(PreTripCheck).where(PreTripCheck.driver_id == driver_id, PreTripCheck.day == day)
    ).first()


def _full(c: Optional[PreTripCheck]) -> bool:
    """Подтверждены ли все три пункта. Частичная галочка не считается."""
    return bool(c and c.health_ok and c.car_ok and c.no_alcohol)


def _still_this_shift(session: Session, c: PreTripCheck, driver_id: int,
                      now: Optional[datetime] = None) -> bool:
    """Действует ли подтверждение ПРЯМО СЕЙЧАС, то есть та же ли это смена.

    Отсчёт ведём от последнего события: подтвердил → выходил на линию → снова выходил. Пока
    перерыв меньше положенного отдыха, смена та же и подтверждение живо. Ушёл отдыхать — вышел
    новой сменой, подтверждай заново (закон и говорит «перед первым выходом на линию»).
    """
    now = now or utcnow()
    anchor = c.created_at
    last_online = wd_last_online(session, driver_id, now)
    if last_online is not None and last_online > anchor:
        anchor = last_online
    return (now - anchor) < timedelta(hours=settings.rest_hours)


def is_confirmed(session: Session, driver_id: int, day: Optional[date_type] = None) -> bool:
    """Подтверждена ли готовность для ТЕКУЩЕЙ смены.

    Считалось по календарным суткам, и это ломалось об обычную ночную работу с двух сторон
    (проверено запросом, аудит 2026-08-12, волна 55):

      • водитель вышел в 22:00 и подтвердил готовность — в 00:01 подтверждение «протухало»,
        и гейт снимал его с линии ПОСРЕДИ смены. Подтверждать «самочувствие» в три часа ночи,
        отработав пять часов, — это не проверка, а издевательство над правилом;
      • обратная сторона: подтверждение, сделанное в 00:10, действовало до 23:59 — можно было
        отработать смену, выспаться и выйти вечером на том же самом «сегодня подтвердил».

    Смена, а не сутки — та же единица, что у лимита часов (волна 54). `day` оставлен для
    вызовов, которые спрашивают про КОНКРЕТНЫЙ день (разбор ДТП, статистика): там по-прежнему
    ответ строго про эту дату.
    """
    if day is not None:
        return _full(today_check(session, driver_id, day))
    now = utcnow()
    today = local_day(now)
    # Смена могла начаться вчера вечером — смотрим оба дня, но берём только живое подтверждение.
    for d in (today, today - timedelta(days=1)):
        c = today_check(session, driver_id, d)
        if _full(c) and _still_this_shift(session, c, driver_id, now):
            return True
    return False


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
    """Гейт такси: не подтвердил готовность для этой смены → 403 с понятным текстом."""
    if not settings.pretrip_check_required:
        return
    if is_confirmed(session, driver_id):
        return
    raise herr(403, NEED_CHECK_RU, NEED_CHECK_BA)


def payload(session: Session, driver_id: int) -> dict:
    """Состояние для экрана водителя.

    `confirmed` считаем ровно тем же способом, что и гейт: экран, который показывает «готов»,
    пока сервер отвечает 403, — хуже отсутствия экрана. Тот же принцип, что у прогресса смены
    в кабинете (волна 54).
    """
    c = today_check(session, driver_id) or today_check(
        session, driver_id, local_day() - timedelta(days=1))
    return {
        "required": bool(settings.pretrip_check_required),
        "confirmed": is_confirmed(session, driver_id),
        "day": local_day().isoformat(),
        "confirmed_at": c.created_at.isoformat() if c else None,
        "note": (c.note if c else ""),
    }
