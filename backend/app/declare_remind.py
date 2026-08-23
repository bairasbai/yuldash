"""💸 Водитель заплатил — и его отключали за то, что админ не нажал кнопку (волна 176).

Как устроена оплата комиссии. Перевод по СБП «на доверии»: водитель отправляет деньги
Александру и жмёт «Я оплатил». Долг уходит в `pending`, такси работает дальше — доверяем
слову. Александр видит перевод в банке и подтверждает. Если подтверждения нет три дня
(`DECLARE_TRUST_DAYS`), доверие кончается и такси закрывается.

Срок правильный: без него раз в неделю жать кнопку и не платить вовсе было бы выгодно
(этот угол закрыт аудитом 2026-08-07). Но у него оказался второй конец.

**Проба.** Водитель честно перевёл деньги в пятницу. Александр — один человек, четыре-пять
дней в неделю в командировках. Через три дня:

- такси у водителя закрыто (`declare_stale`);
- уведомлений он не получил ни одного — ни что срок идёт, ни что его отключили;
- в кабинете вместо правды написано «Оплати долг сервису, чтобы снова возить такси» —
  человеку, который уже заплатил;
- повторное «Я оплатил» не делает ничего: долг уже в `pending`, заявлять нечего.

Выхода нет ни одного. Честный водитель теряет рабочий день за чужое молчание и решает,
что его обманули, — а это ровно то доверие, на котором держится вся схема «на доверии».

Что делает этот модуль. За сутки до конца доверия предупреждает обе стороны: водителю —
что перевод пока не подтверждён и что делать, Александру — что заявки ждут и сколько
из них горят. Срок не двигается: лазейка не открывается, просто человек перестаёт узнавать
об отключении по факту отключения.

Живёт в общем ночном роботе (`taxi_worker.run_once`), рядом с эскалацией жалоб и SOS:
то, от чего зависит чужой заработок, не должно зависеть от того, открыл ли кто-то экран.
"""
from __future__ import annotations

import logging
from datetime import timedelta
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .debt import DECLARE_TRUST_DAYS
from .models import CommissionDebt, DebtStatus, User
from .timeutil import utcnow

log = logging.getLogger("yuldash")

#: За сколько часов до конца доверия предупреждаем. Сутки — человек успевает написать
#: в поддержку или переслать чек вечером, а не узнаёт об отключении утром за рулём.
WARN_BEFORE_HOURS = 24


def _горит(d: CommissionDebt, now) -> bool:
    """Доверие по этому долгу кончается меньше чем через сутки?"""
    заявлено = d.paid_declared_at or d.created_at
    if заявлено is None:
        return False
    конец = заявлено + timedelta(days=DECLARE_TRUST_DAYS)
    return now <= конец <= now + timedelta(hours=WARN_BEFORE_HOURS)


def remind_pending_declares(session: Session, dry_run: bool = False) -> list:
    """Предупредить водителей и Александра о заявках, которые вот-вот протухнут.

    Возврат: id водителей, которых предупредили (для лога и тестов).
    """
    now = utcnow()
    ожидают = session.exec(
        select(CommissionDebt).where(CommissionDebt.status == DebtStatus.pending)
    ).all()
    горящие = [d for d in ожидают if _горит(d, now)]
    if not горящие:
        return []

    по_водителям: dict = {}
    for d in горящие:
        по_водителям.setdefault(d.driver_id, []).append(d)

    предупреждены = []
    for driver_id, долги in по_водителям.items():
        if driver_id is None:
            continue
        # Второй раз про тот же долг не пишем: предупреждение раз в сутки — забота,
        # каждую ночь — спам, который перестают читать ровно к нужному моменту.
        if any(d.declare_reminded_at is not None for d in долги):
            continue
        предупреждены.append(driver_id)
        if dry_run:
            continue
        rub = sum(d.amount_kop for d in долги) // 100
        for d in долги:
            d.declare_reminded_at = now
            session.add(d)
        session.commit()
        try:
            from .services import push_notification
            push_notification(
                session, driver_id, "money",
                "Перевод пока не подтверждён", "Күсереү әле раҫланмаған",
                f"Оплату {rub} ₽ ещё не подтвердили. Если ты перевёл — напиши в поддержку "
                "и приложи чек, так быстрее. Иначе завтра такси закроется до подтверждения.",
                f"{rub} һумлыҡ түләү әле раҫланмаған. Күсергән булһаң — ярҙам хеҙмәтенә яҙ "
                "һәм чекты ебәр, шулай тиҙерәк. Юҡһа иртәгә такси раҫлауға тиклем ябыла.",
                ref_kind="debt", ref_id=долги[0].id,
            )
        except Exception as e:  # noqa: BLE001 — уведомление вторично, срок и так идёт
            log.warning(f"[declare_remind] push failed driver={driver_id}: {e}")

    if предупреждены and not dry_run:
        _напомнить_александру(session, по_водителям, len(ожидают))
    if предупреждены:
        log.info(f"[declare_remind] предупреждено водителей: {len(предупреждены)}")
    return предупреждены


def _напомнить_александру(session: Session, по_водителям: dict, всего: int) -> None:
    """Вторая сторона: заявки ждут человека, а человек в командировке.

    Пишем в телеграм — там Александра застать проще всего. Молчание телеграма не ломает
    ничего: водитель уже предупреждён, срок идёт своим ходом.
    """
    try:
        from .services import notify_admin_telegram
        имена = []
        for driver_id, долги in list(по_водителям.items())[:5]:
            u: Optional[User] = session.get(User, driver_id)
            rub = sum(d.amount_kop for d in долги) // 100
            имена.append(f"• {(u.name if u else '') or f'#{driver_id}'} — {rub} ₽")
        хвост = "\n".join(имена)
        notify_admin_telegram(
            f"⏳ Завтра закроется такси у {len(по_водителям)} водителей: перевод заявлен, "
            f"подтверждения нет.\n{хвост}\n\nВсего заявок ждёт подтверждения: {всего}.\n"
            f"Реквизиты для сверки: {settings.owner_sbp_phone}"
        )
    except Exception as e:  # noqa: BLE001
        log.warning(f"[declare_remind] admin telegram failed: {e}")
