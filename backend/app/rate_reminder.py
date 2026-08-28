# -*- coding: utf-8 -*-
"""Напоминание оценить поездку (двуязычный push тем, кто ещё не оценил).

После завершения поездки часть людей забывает поставить оценку — а рейтинги это
доверие «между своими» (весь смысл продукта). Фоновая задача раз в N минут находит
завершённые поездки, по которым напоминание ещё НЕ слали, и шлёт «оцени поездку»
тем участникам, кто ещё не оценил. Один раз на поездку (флаг `rate_reminded`).

ТРИ СЕРВИСА, а не один (волна 197). Раньше задача читала только брони попутки: после
заказа такси и после доставки посылки не напоминали никому. Оценить поездку можно лишь
60 дней (`rating_service.RATING_WINDOW_DAYS`) — дальше звёзды потеряны навсегда. Для
таксиста это прямо деньги: с волны 194 его рабочий рейтинг, по которому matcher решает,
кому дать заказ, считается только по поездкам, где за рулём был он. Меньше оценок —
тоньше балл и дольше бейдж «Новичок» на карточке.

Запуск (systemd-таймер, см. deploy/yuldash-rate-reminder.*):
    python -m app.rate_reminder            # реально шлёт
    python -m app.rate_reminder --dry-run  # показать, кому бы ушло, ничего не меняя

Свойства: один процесс (без гонок между воркерами API), идемпотентно (флаг ставим
после отправки → повторный проход не спамит), безопасно (push мёртв без Firebase-ключа).
Уведомление идёт через единый push_notification → и в Центр уведомлений (двуязычно), и в FCM.
"""
import sys
from dataclasses import dataclass
from datetime import timedelta
from typing import Callable

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import Booking, BookingStatus, InstantOrder, InstantOrderStatus, ParcelDelivery, Rating, Ride
from .logs import log
from .services import push_notification
from .timeutil import utcnow

# Пол по возрасту поездки: бронь живёт от создания до поездки считанные дни → 30 дней
# покрывают любой честный цикл. Без пола скан шёл бы по ВСЕМ завершённым всех времён
# (рост навсегда), а поездку, закрытую задним числом через месяц, «напоминали» бы невпопад.
REMIND_MAX_AGE_DAYS = 30


@dataclass(frozen=True)
class Сервис:
    """Одна дверь напоминания: где искать поездки, кого спрашивать, какими словами.

    Вынесено в описание, а не в три копии цикла: у нас уже трижды расходились правила,
    написанные отдельно для попутки, такси и доставки (волны 185–188, 194–196). Здесь
    отличается ровно то, что обязано отличаться — таблица, стороны и текст.
    """
    ключ: str                       # ref_kind в Центре уведомлений
    заголовок: tuple                # (ru, ba)
    текст: tuple                    # (ru, ba)
    найти: Callable                 # session → список завершённых не-напомненных
    справочник: Callable            # session, объекты → что нужно ОДНИМ запросом на весь список
    стороны: Callable               # справочник, объект → (id одного, id второго)
    оценки: Callable                # session, объекты → множество (id поездки, кто оценил)


def _свежие(колонка):
    return колонка > utcnow() - timedelta(days=REMIND_MAX_AGE_DAYS)


# ------------------------------ попутка ------------------------------
def _брони(session: Session) -> list:
    return list(session.exec(
        select(Booking).where(
            Booking.status == BookingStatus.done,
            Booking.rate_reminded == False,   # noqa: E712 — SQL-сравнение, не Python is
            _свежие(Booking.created_at),
        )
    ).all())


def _рейсы(session: Session, items: list) -> dict:
    """Рейсы всех этих броней ОДНИМ запросом. Без этого шага проход делал поход в базу
    на каждую бронь, и стоимость росла вместе с числом поездок в городе за сутки —
    ровно то, что сторожит `tests/test_no_query_per_item.py`."""
    if not items:
        return {}
    return {
        r.id: r
        for r in session.exec(select(Ride).where(Ride.id.in_({b.ride_id for b in items}))).all()
    }


def _стороны_брони(рейсы: dict, b: Booking) -> tuple:
    ride = рейсы.get(b.ride_id)
    return (b.passenger_id, ride.driver_id if ride else None)


def _оценки_броней(session: Session, items: list) -> set:
    if not items:
        return set()
    return {
        (r.booking_id, r.rater_id)
        for r in session.exec(
            select(Rating).where(Rating.booking_id.in_([b.id for b in items]))
        ).all()
    }


# ------------------------------ такси ------------------------------
def _заказы(session: Session) -> list:
    return list(session.exec(
        select(InstantOrder).where(
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.rate_reminded == False,   # noqa: E712
            _свежие(InstantOrder.created_at),
        )
    ).all())


def _оценки_заказов(session: Session, items: list) -> set:
    if not items:
        return set()
    return {
        (r.order_id, r.rater_id)
        for r in session.exec(
            select(Rating).where(Rating.order_id.in_([o.id for o in items]))
        ).all()
    }


# ------------------------------ доставка ------------------------------
def _посылки(session: Session) -> list:
    return list(session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.status == "delivered",
            ParcelDelivery.rate_reminded == False,   # noqa: E712
            _свежие(ParcelDelivery.created_at),
        )
    ).all())


def _оценки_посылок(session: Session, items: list) -> set:
    if not items:
        return set()
    return {
        (r.parcel_id, r.rater_id)
        for r in session.exec(
            select(Rating).where(Rating.parcel_id.in_([p.id for p in items]))
        ).all()
    }


# Тексты разные намеренно: «попутчик», «водитель» и «курьер» — разные люди и разные услуги,
# а общее «оцени поездку» после доставки посылки читается как ошибка приложения.
# Черновой башкирский для такси и доставки — в docs/tasks.md на проверку Александру.
СЕРВИСЫ = (
    Сервис(
        ключ="booking",
        заголовок=("Оцени поездку", "Сәфәрҙе баһала"),
        текст=("Поставь оценку попутчику — это помогает доверию между своими.",
               "Юлдашыңа баһа ҡуй — был үҙ-ара ышанысҡа ярҙам итә."),
        найти=_брони,
        справочник=_рейсы,
        стороны=_стороны_брони,
        оценки=_оценки_броней,
    ),
    Сервис(
        ключ="order",
        заголовок=("Оцени поездку", "Сәфәрҙе баһала"),
        текст=("Как доехали? Пара секунд на звёзды — и другим будет проще выбрать.",
               "Ничек барып еттегеҙ? Йондоҙҙарға бер нисә секунд — башҡаларға һайлау еңелерәк булыр."),
        найти=_заказы,
        справочник=lambda session, items: None,      # обе стороны лежат в самом заказе
        стороны=lambda _спр, o: (o.passenger_id, o.driver_id),
        оценки=_оценки_заказов,
    ),
    Сервис(
        ключ="parcel",
        заголовок=("Оцени доставку", "Илтеүҙе баһала"),
        текст=("Посылка дошла. Поставь оценку — по ней люди выбирают, кому доверить своё.",
               "Бандероль килеп етте. Баһа ҡуй — кешеләр шуның буйынса үҙ әйберен кемгә ышанырға һайлай."),
        найти=_посылки,
        справочник=lambda session, items: None,      # обе стороны лежат в самой посылке
        стороны=lambda _спр, p: (p.sender_id, p.courier_id),
        оценки=_оценки_посылок,
    ),
)


def rate_reminder_once(session: Session, dry_run: bool = False) -> list[tuple[int, int]]:
    """Один проход по всем трём сервисам: по каждой завершённой не-напомненной поездке —
    напомнить не оценившим сторонам.
    Возвращает список (id поездки, id человека) — кому напомнили (или напомнилось бы при dry_run).
    """
    if not settings.rate_reminder_enabled:
        return []
    reminded: list[tuple[int, int]] = []
    for сервис in СЕРВИСЫ:
        try:
            reminded += _по_сервису(session, сервис, dry_run)
        except Exception as e:  # noqa: BLE001 — один сервис не должен валить остальные
            # Три сервиса в одном цикле — это необъявленная цепочка: сбой на попутке молча
            # отменял бы напоминания и по такси, и по доставке (тот же приём, что у воркера
            # такси, волна 198). Откат обязателен — иначе сорванная транзакция остаётся
            # на сессии и следующий сервис падает на ней же.
            try:
                session.rollback()
            except Exception:  # noqa: BLE001
                pass
            log.exception(f"[RATE-REMINDER] сервис {сервис.ключ} упал: {type(e).__name__}: {e}")
    return reminded


def _по_сервису(session: Session, сервис: Сервис, dry_run: bool) -> list:
    """Один сервис: найти завершённые поездки без напоминания и написать не оценившим."""
    reminded: list[tuple[int, int]] = []
    items = сервис.найти(session)
    if not items:
        return reminded
    # Уже поставленные оценки забираем ОДНИМ запросом на весь список, а не по одному
    # на каждого участника: за сутки таких поездок столько же, сколько поездок в городе.
    rated = сервис.оценки(session, items)
    справочник = сервис.справочник(session, items)
    for item in items:
        стороны = сервис.стороны(справочник, item)
        # Нет второй стороны — оценивать некого, молчим обоим. Так было и у попутки
        # (там проверяли `if ride`): заказ, который никто не взял, закрывается
        # без водителя, и «оцени поездку» человеку, который так и не уехал, — это насмешка.
        if any(uid is None for uid in стороны):
            стороны = ()
        for uid in стороны:
            if (item.id, uid) in rated:
                continue              # уже оценил эту поездку → не напоминаем
            if not dry_run:
                push_notification(
                    session, uid, "ride",
                    сервис.заголовок[0], сервис.заголовок[1],
                    сервис.текст[0], сервис.текст[1],
                    ref_kind=сервис.ключ, ref_id=item.id,
                )
            reminded.append((item.id, uid))
        # Помечаем ВСЕГДА (даже если оба уже оценили или вторая сторона пропала) →
        # не пере-сканируем каждый проход.
        if not dry_run:
            item.rate_reminded = True
            session.add(item)
            session.commit()
    return reminded


def main():
    dry = "--dry-run" in sys.argv
    mode = "СУХОЙ ПРОГОН (ничего не меняется)" if dry else "РЕАЛЬНАЯ рассылка"
    print(f"=== Напоминание оценить поездку · {mode} · {utcnow().isoformat()} ===")
    if not settings.rate_reminder_enabled:
        print("  выключено (rate_reminder_enabled=false) — пропуск")
        return
    with Session(engine) as session:
        reminded = rate_reminder_once(session, dry_run=dry)
    verb = "напомнилось бы" if dry else "напомнено"
    print(f"=== Итог: {verb} — {len(reminded)} ===")


if __name__ == "__main__":
    main()
