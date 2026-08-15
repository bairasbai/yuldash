# -*- coding: utf-8 -*-
"""Контроль сроков документов таксиста: напоминаем заранее, снимаем допуск после срока.

Зачем (аудит 2026-07-26, юридический блокер):
Проверка документов была РАЗОВОЙ. Одобрили заявку в июле — человек считался годным вечно:
в декабре он возил с просроченным ОСАГО и без техосмотра, а Юлдаш называл его «проверенным
водителем». Для пассажира это прямой риск (при ДТП без ОСАГО платить некому), для платформы —
ответственность по 580-ФЗ.

Что делает эта задача (раз в сутки, systemd-таймер):
1. За `docs_warn_days` и ещё раз за `docs_warn_again_days` до истечения любого документа —
   тёплое двуязычное напоминание. Дедуп по `docs_warned_at`: не чаще раза в сутки.
2. Срок прошёл → `docs_expired = True`: допуск к ТАКСИ снят (см. `taxi.is_approved_taxi_driver`),
   водитель снимается с линии (`DriverProfile.online = False`), приходит честное объяснение.
   **ПОПУТКА продолжает работать** — она не требует разрешения на такси, и лишать человека
   попуток за просроченную диагностическую карту было бы несправедливо.
3. Документ обновили (дата снова в будущем) → `docs_expired = False` автоматически, без админа.
   Человек не должен ждать, пока кто-то вручную вернёт ему работу.
4. Одобренные без заполненных сроков (одобрены до появления этих полей) — мягкая просьба
   дозаполнить раз в `docs_remind_missing_days`. Допуск при этом НЕ снимаем: мы сами не
   спросили даты, наказывать за это человека нечестно.

Активный заказ не трогаем: снятие допуска блокирует новые (presence/offer/accept), а того,
кого уже везут, водитель довезёт.

Запуск (см. deploy/yuldash-doc-check.*):
    python -m app.doc_check            # реальный прогон
    python -m app.doc_check --dry-run  # показать, что сделал бы, ничего не меняя
"""
import sys
from datetime import date as date_type, timedelta

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .logs import log
from .models import (
    DriverProfile, TaxiApplication, TaxiApplicationStatus,
)
from .timeutil import utcnow

# Человеческие названия документов для текста напоминания (RU/BA).
_DOC_NAMES = {
    "osago_until": ("ОСАГО", "ОСАГО"),
    "permit_until": ("разрешение на такси", "такси рөхсәте"),
    "inspection_until": ("диагностическая карта", "диагностика картаһы"),
}


def _push(session: Session, user_id: int, title_ru: str, title_ba: str, body_ru: str, body_ba: str) -> None:
    """Уведомление водителю. Вторично — падение пуша не должно валить обход документов."""
    try:
        from .services import push_notification
        push_notification(session, user_id, "docs", title_ru, title_ba, body_ru, body_ba)
    except Exception as e:  # noqa: BLE001
        log.warning(f"[doc_check] push failed user={user_id}: {e}")


def _dates(app: TaxiApplication) -> dict:
    """Заполненные сроки заявки: {поле: дата}. Пустые не участвуют ни в чём."""
    out = {}
    for field in _DOC_NAMES:
        v = getattr(app, field, None)
        if isinstance(v, date_type):
            out[field] = v
    return out


def _plural_days(n: int) -> str:
    """«3 дня» / «14 дней» — иначе текст читается как машинный перевод."""
    if 11 <= n % 100 <= 14:
        return f"{n} дней"
    last = n % 10
    if last == 1:
        return f"{n} день"
    if last in (2, 3, 4):
        return f"{n} дня"
    return f"{n} дней"


def _warned_today(app: TaxiApplication, now) -> bool:
    return bool(app.docs_warned_at and (now - app.docs_warned_at) < timedelta(hours=20))


def expire_overdue(session: Session, dry_run: bool = False) -> list:
    """Срок прошёл → снять допуск к такси и с линии. Возврат: id заявок."""
    today = utcnow().date()
    apps = session.exec(
        select(TaxiApplication).where(
            TaxiApplication.status == TaxiApplicationStatus.approved,
            TaxiApplication.docs_expired == False,      # noqa: E712 — SQL IS FALSE
        )
    ).all()
    touched = []
    for app in apps:
        overdue = {f: d for f, d in _dates(app).items() if d < today}
        if not overdue:
            continue
        touched.append(app.id)
        if dry_run:
            continue
        app.docs_expired = True
        session.add(app)
        # Снимаем с линии: иначе он остаётся «онлайн» и получает офферы, которые тут же
        # отбиваются гейтом — выглядит как поломка приложения, а не как честный запрет.
        dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == app.user_id)).first()
        if dp and dp.online:
            # Через общую дверь: она же убирает координаты из Redis. Раньше здесь стоял
            # голый флаг, и точка снятого за документы водителя лежала в GEO вечно.
            from .instant_service import driver_go_offline
            driver_go_offline(session, dp)
        session.commit()
        names_ru = ", ".join(_DOC_NAMES[f][0] for f in overdue)
        names_ba = ", ".join(_DOC_NAMES[f][1] for f in overdue)
        _push(
            session, app.user_id,
            "Такси на паузе: документы просрочены", "Такси паузала: документтар ваҡыты үткән",
            f"Истёк срок: {names_ru}. Обнови документ в профиле — допуск вернётся сам, сразу. "
            f"Попутки работают как обычно.",
            f"Ваҡыты үткән: {names_ba}. Документты профилдә яңырт — рөхсәт үҙе, шунда уҡ ҡайта. "
            f"Юлдаш сәфәрҙәре ғәҙәттәгесә эшләй.",
        )
    return touched


def restore_renewed(session: Session, dry_run: bool = False) -> list:
    """Документы обновлены (все даты снова в будущем) → вернуть допуск. Возврат: id заявок."""
    today = utcnow().date()
    apps = session.exec(
        select(TaxiApplication).where(
            TaxiApplication.status == TaxiApplicationStatus.approved,
            TaxiApplication.docs_expired == True,       # noqa: E712 — SQL IS TRUE
        )
    ).all()
    touched = []
    for app in apps:
        dates = _dates(app)
        # Ни одной просроченной даты. Пустые поля не блокируют возврат: их отсутствие
        # само по себе допуск не снимало (см. модуль-docstring, п.4).
        if any(d < today for d in dates.values()):
            continue
        touched.append(app.id)
        if dry_run:
            continue
        app.docs_expired = False
        app.docs_warned_at = None      # цикл напоминаний начинается заново
        session.add(app)
        session.commit()
        _push(
            session, app.user_id,
            "Документы приняты — можно на линию", "Документтар ҡабул ителде — линияға сығырға була",
            "Спасибо, что обновил. Такси снова доступно.",
            "Яңыртҡаның өсөн рәхмәт. Такси яңынан асыҡ.",
        )
    return touched


def warn_soon(session: Session, dry_run: bool = False) -> list:
    """Скоро истекает (за docs_warn_days и ещё раз за docs_warn_again_days) → напомнить."""
    today = utcnow().date()
    now = utcnow()
    apps = session.exec(
        select(TaxiApplication).where(
            TaxiApplication.status == TaxiApplicationStatus.approved,
            TaxiApplication.docs_expired == False,      # noqa: E712
        )
    ).all()
    touched = []
    for app in apps:
        if _warned_today(app, now):
            continue
        soon = {}
        for field, d in _dates(app).items():
            left = (d - today).days
            if 0 <= left <= settings.docs_warn_days:
                soon[field] = left
        if not soon:
            continue
        touched.append(app.id)
        if dry_run:
            continue
        app.docs_warned_at = now
        session.add(app)
        session.commit()
        field, left = min(soon.items(), key=lambda kv: kv[1])   # пишем про самый срочный
        ru, ba = _DOC_NAMES[field]
        when_ru = "сегодня последний день" if left == 0 else f"осталось {_plural_days(left)}"
        when_ba = "бөгөн һуңғы көн" if left == 0 else f"{left} көн ҡалды"
        _push(
            session, app.user_id,
            "Скоро истекает документ", "Документ ваҡыты бөтә",
            f"{ru.capitalize()}: {when_ru}. Обнови заранее — иначе такси встанет на паузу, "
            f"а заказы уйдут другим.",
            f"{ba.capitalize()}: {when_ba}. Алдан яңырт — юғиһә такси паузаға китә, "
            f"заказдар башҡаларға китәсәк.",
        )
    return touched


def remind_missing(session: Session, dry_run: bool = False) -> list:
    """Одобрен, но сроки не заполнены → мягко попросить. Допуск НЕ снимаем."""
    now = utcnow()
    apps = session.exec(
        select(TaxiApplication).where(
            TaxiApplication.status == TaxiApplicationStatus.approved,
            TaxiApplication.docs_expired == False,      # noqa: E712
        )
    ).all()
    touched = []
    for app in apps:
        if _dates(app):
            continue          # хоть одна дата есть — человек в курсе, не дёргаем
        if app.docs_warned_at and (now - app.docs_warned_at) < timedelta(days=settings.docs_remind_missing_days):
            continue
        touched.append(app.id)
        if dry_run:
            continue
        app.docs_warned_at = now
        session.add(app)
        session.commit()
        _push(
            session, app.user_id,
            "Укажи сроки документов", "Документтар ваҡытын күрһәт",
            "Добавь даты ОСАГО, разрешения и диагностической карты в профиле — "
            "мы напомним заранее, чтобы такси не встало неожиданно.",
            "Профилдә ОСАГО, рөхсәт һәм диагностика картаһы ваҡытын өҫтә — "
            "такси көтмәгәндә туҡтамаһын өсөн алдан иҫкә төшөрөрбөҙ.",
        )
    return touched


def run_once(session: Session, dry_run: bool = False) -> dict:
    """Один полный обход. Порядок важен: сперва вернуть допуск обновившимся, потом снимать."""
    result = {}
    for name, fn in (
        ("restored", restore_renewed),
        ("expired", expire_overdue),
        ("warned", warn_soon),
        ("reminded", remind_missing),
    ):
        try:
            result[name] = fn(session, dry_run)
        except Exception as e:  # noqa: BLE001 — одна задача не должна валить остальные
            log.exception(f"[doc_check] {name} failed: {e}")
            result[name] = []
    return result


def main() -> None:
    dry = "--dry-run" in sys.argv
    with Session(engine) as session:
        res = run_once(session, dry_run=dry)
    total = sum(len(v) for v in res.values())
    prefix = "[doc_check][dry-run]" if dry else "[doc_check]"
    if total:
        log.info(f"{prefix} " + ", ".join(f"{k}={len(v)}" for k, v in res.items() if v))


if __name__ == "__main__":
    main()
