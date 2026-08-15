# -*- coding: utf-8 -*-
"""Напоминание оценить поездку (двуязычный push тем, кто ещё не оценил).

После завершения поездки часть людей забывает поставить оценку — а рейтинги это
доверие «между своими» (весь смысл продукта). Фоновая задача раз в N минут находит
завершённые брони, по которым напоминание ещё НЕ слали, и шлёт «оцените поездку»
тем участникам, кто ещё не оценил. Один раз на бронь (флаг Booking.rate_reminded).

Запуск (systemd-таймер, см. deploy/yuldash-rate-reminder.*):
    python -m app.rate_reminder            # реально шлёт
    python -m app.rate_reminder --dry-run  # показать, кому бы ушло, ничего не меняя

Свойства: один процесс (без гонок между воркерами API), идемпотентно (флаг ставим
после отправки → повторный проход не спамит), безопасно (push мёртв без Firebase-ключа).
Уведомление идёт через единый push_notification → и в Центр уведомлений (двуязычно), и в FCM.
"""
import sys
from datetime import timedelta

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import Booking, BookingStatus, Rating, Ride
from .services import push_notification
from .timeutil import utcnow

# Пол по возрасту брони: у Booking нет done_at, а бронь живёт от создания до поездки считанные
# дни → 30 дней покрывают любой честный цикл. Без пола скан шёл бы по ВСЕМ done всех времён
# (рост навсегда), а бронь, закрытую задним числом через месяц, «напоминали» бы невпопад.
REMIND_MAX_AGE_DAYS = 30


def rate_reminder_once(session: Session, dry_run: bool = False) -> list[tuple[int, int]]:
    """Один проход: по каждой завершённой не-напомненной броне — напомнить не оценившим сторонам.
    Возвращает список (booking_id, user_id) — кому напомнили (или напомнилось бы при dry_run)."""
    if not settings.rate_reminder_enabled:
        return []
    reminded: list[tuple[int, int]] = []
    done = session.exec(
        select(Booking).where(
            Booking.status == BookingStatus.done,
            Booking.rate_reminded == False,   # noqa: E712 — SQL-сравнение, не Python is
            Booking.created_at > utcnow() - timedelta(days=REMIND_MAX_AGE_DAYS),
        )
    ).all()
    if not done:
        return []
    # Поездки и уже поставленные оценки забираем ДВУМЯ запросами на весь список, а не по два
    # на каждую бронь. Раньше: поездка (session.get) плюс проверка оценки на каждого из двоих —
    # то есть три похода в базу на бронь. За сутки таких броней столько же, сколько поездок в
    # городе, и с ростом это растёт линейно.
    rides = {
        r.id: r for r in session.exec(select(Ride).where(Ride.id.in_({b.ride_id for b in done}))).all()
    }
    rated: set[tuple[int, int]] = {
        (r.booking_id, r.rater_id)
        for r in session.exec(select(Rating).where(Rating.booking_id.in_([b.id for b in done]))).all()
    }
    for b in done:
        ride = rides.get(b.ride_id)
        if ride:
            for uid in (b.passenger_id, ride.driver_id):
                if (b.id, uid) in rated:
                    continue                  # уже оценил эту поездку → не напоминаем
                if not dry_run:
                    push_notification(
                        session, uid, "ride",
                        "Оцени поездку", "Сәфәрҙе баһала",
                        "Поставь оценку попутчику — это помогает доверию между своими.",
                        "Юлдашыңа баһа ҡуй — был үҙ-ара ышанысҡа ярҙам итә.",
                        ref_kind="booking", ref_id=b.id,
                    )
                reminded.append((b.id, uid))
        # Помечаем ВСЕГДА (даже если оба уже оценили или поездка пропала) → не пере-сканируем каждый проход.
        if not dry_run:
            b.rate_reminded = True
            session.add(b)
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
