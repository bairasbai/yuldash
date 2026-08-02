# -*- coding: utf-8 -*-
"""Фоновый воркер такси: доводит заказы до конца, когда живой человек этого сделать не может.

Зачем (аудит 2026-07-26, три блокера одной работой):
1. **Предзаказ «на утро» не срабатывал сам.** Активация была ленивой — только когда пассажир
   ВРУЧНУЮ откроет экран «Мои предзаказы». Бабушка заказала машину на 6:00 к автобусу в Уфу —
   утром поиск даже не запускался. Теперь активирует воркер.
2. **Заказ мог зависнуть навсегда.** У водителя сел телефон на трассе Сибай–Уфа → заказ вечно
   «в пути»: отменить его мог только водитель, фонового закрытия не было, у админа кнопки нет.
   Пассажир при этом НЕ мог заказать другое такси (сервер возвращал ему мёртвый заказ).
3. **«Рядом никого» = мгновенная смерть заказа.** В райцентре ночью на линии 2-3 водителя, оба
   заняты — пассажир получал отказ за 2 секунды и уходил к конкуренту. Теперь он может нажать
   «подождать», а воркер спокойно перезапускает поиск, пока не найдёт или не выйдет время.

Плюс четвёртая, техническая: протухшие офферы двигались только «лениво» (когда кто-нибудь
опросит заказ) — при закрытом приложении подбор стоял.

Запуск (systemd-таймер, см. deploy/yuldash-taxi-worker.*):
    python -m app.taxi_worker            # реальный прогон
    python -m app.taxi_worker --dry-run  # показать, что сделал бы, ничего не меняя

Свойства: один процесс (без гонок между воркерами API), идемпотентно (каждый шаг проверяет
статус под свежим чтением), безопасно (ошибка одной задачи не валит остальные).
"""
import sys
from datetime import timedelta

from sqlmodel import Session, select

from . import instant_service as isv
from .config import settings
from .db import engine
from .logs import log
from .models import InstantOrder, InstantOrderStatus as S
from .services import send_push
from .timeutil import utcnow

# Статусы, из которых заказ уже никуда не уедет сам (терминальные).
_TERMINAL = (S.done, S.cancelled, S.expired)
# Живые статусы, где заказ ждёт действий человека — именно они могут «зависнуть».
_ALIVE = (S.searching, S.offered, S.accepted, S.arriving, S.onboard)


def _last_move_at(o: InstantOrder):
    """Момент последнего осмысленного перехода заказа — от него считаем «завис»."""
    return (o.onboard_at or o.arriving_at or o.accepted_at or o.offered_at
            or o.searching_at or o.created_at)


# ----------------------------- 1. Предзаказы -----------------------------
def activate_due_scheduled(session: Session, dry_run: bool = False) -> list:
    """scheduled с наступившим временем → запускаем поиск. Возврат: id активированных."""
    now = utcnow()
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status == S.scheduled,
            InstantOrder.scheduled_at != None,          # noqa: E711 — SQL IS NOT NULL
            InstantOrder.scheduled_at <= now,
        ).limit(200)
    ).all()
    done = []
    for o in rows:
        if dry_run:
            done.append(o.id)
            continue
        try:
            isv.activate_scheduled(session, o)
            done.append(o.id)
        except Exception as e:  # noqa: BLE001 — один заказ не должен ронять прогон
            log.warning(f"[TAXI-WORKER] активация предзаказа #{o.id}: {type(e).__name__}: {e}")
    return done


# ----------------------------- 2. Зависшие заказы -----------------------------
def close_stuck_orders(session: Session, dry_run: bool = False) -> list:
    """Заказы в живых статусах без движения дольше order_stuck_hours → закрываем системно.

    Пассажир и водитель получают честный пуш: «заказ закрыт автоматически». Деньги не двигаем
    (Модель А — платформа их не касается), штрафов не ставим: виноватых тут нет, связь пропала."""
    cutoff = utcnow() - timedelta(hours=settings.order_stuck_hours)
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status.in_(_ALIVE),
            InstantOrder.created_at <= cutoff,          # грубый предфильтр по индексу
        ).limit(500)
    ).all()
    closed = []
    for o in rows:
        if _last_move_at(o) > cutoff:
            continue                                    # двигался недавно — не трогаем
        if dry_run:
            closed.append(o.id)
            continue
        try:
            fresh = session.exec(
                select(InstantOrder).where(InstantOrder.id == o.id).with_for_update()
            ).first()
            if not fresh or fresh.status in _TERMINAL or _last_move_at(fresh) > cutoff:
                continue                                # успели закрыть/сдвинуть параллельно
            # onboard/accepted → «отменён системой»; ещё не найден водитель → «истёк».
            fresh.status = S.cancelled if fresh.driver_id else S.expired
            fresh.cancel_by = "system"
            fresh.cancel_reason = "stuck_timeout"
            fresh.cancelled_at = utcnow()
            fresh.current_offer_driver_id = None
            fresh.offer_expires_at = None
            fresh.wait_until = None
            session.add(fresh)
            session.commit()
            closed.append(fresh.id)
            for uid in {fresh.passenger_id, fresh.driver_id} - {None}:
                try:
                    send_push(session, uid, "Заказ закрыт · Заказ ябылды",
                              "Долго не было связи — заказ закрыли автоматически. "
                              "Если поездка состоялась, договоритесь напрямую. "
                              "· Оҙаҡ бәйләнеш булманы — заказ автоматик ябылды.")
                except Exception:  # noqa: BLE001 — пуш вторичен
                    pass
        except Exception as e:  # noqa: BLE001
            log.warning(f"[TAXI-WORKER] закрытие зависшего #{o.id}: {type(e).__name__}: {e}")
    return closed


# ----------------------------- 3. Очередь «рядом никого» -----------------------------
def retry_waiting_orders(session: Session, dry_run: bool = False) -> list:
    """Пассажир нажал «подождать» (wait_until в будущем) → тихо перезапускаем поиск.

    notify=False: пассажир УЖЕ знает, что машин нет — повторять ему «рядом никого» каждые
    две минуты значит выпросить выключение уведомлений. Нашли водителя → обычный оффер, и
    пассажир получит нормальный пуш о найденной машине."""
    now = utcnow()
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.wait_until != None,            # noqa: E711
            InstantOrder.wait_until > now,
            InstantOrder.status.in_((S.expired, S.searching)),
        ).limit(200)
    ).all()
    retried = []
    gap = timedelta(minutes=settings.order_retry_every_min)
    for o in rows:
        # Не чаще order_retry_every_min: между попытками должен пройти интервал.
        if o.searching_at and (now - o.searching_at) < gap:
            continue
        if dry_run:
            retried.append(o.id)
            continue
        try:
            fresh = isv.start_matching(session, o, notify=False)
            session.execute(
                InstantOrder.__table__.update()
                .where(InstantOrder.__table__.c.id == o.id)
                .values(retry_count=(o.retry_count or 0) + 1)
            )
            session.commit()
            retried.append(o.id)
            if fresh is not None and fresh.status == S.offered:
                try:
                    send_push(session, fresh.passenger_id, "Водитель нашёлся 🚕 · Водитель табылды",
                              "Мы нашли машину по твоему заказу. · Заказың буйынса машина таптыҡ.")
                except Exception:  # noqa: BLE001
                    pass
        except Exception as e:  # noqa: BLE001
            log.warning(f"[TAXI-WORKER] перезапуск поиска #{o.id}: {type(e).__name__}: {e}")
    return retried


def finish_expired_waits(session: Session, dry_run: bool = False) -> list:
    """Время ожидания вышло, машина так и не нашлась → честно закрываем и говорим об этом."""
    now = utcnow()
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.wait_until != None,            # noqa: E711
            InstantOrder.wait_until <= now,
            InstantOrder.status.in_((S.expired, S.searching)),
        ).limit(200)
    ).all()
    finished = []
    for o in rows:
        if dry_run:
            finished.append(o.id)
            continue
        try:
            fresh = session.exec(
                select(InstantOrder).where(InstantOrder.id == o.id).with_for_update()
            ).first()
            if not fresh or fresh.wait_until is None or fresh.status not in (S.expired, S.searching):
                continue
            fresh.wait_until = None
            fresh.status = S.expired
            fresh.expired_at = fresh.expired_at or utcnow()
            session.add(fresh)
            session.commit()
            finished.append(fresh.id)
            try:
                send_push(session, fresh.passenger_id, "Машину не нашли · Машина табылманы",
                          "Свободных водителей рядом так и не появилось. Попробуй ещё раз "
                          "или оставь заявку попутчикам. · Тағы ҡабатлап ҡара йәки ғариза ҡалдыр.")
            except Exception:  # noqa: BLE001
                pass
        except Exception as e:  # noqa: BLE001
            log.warning(f"[TAXI-WORKER] закрытие ожидания #{o.id}: {type(e).__name__}: {e}")
    return finished


# ----------------------------- 4. Протухшие офферы -----------------------------
def advance_stale_offers(session: Session, dry_run: bool = False) -> list:
    """Оффер висит без ответа дольше срока → двигаем подбор к следующему водителю.

    Раньше это делалось только «лениво» (при опросе заказа клиентом): пассажир закрыл
    приложение — и подбор замирал до его возвращения."""
    now = utcnow()
    stale_before = now - timedelta(minutes=settings.order_offer_stale_min)
    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.status == S.offered,
            InstantOrder.offer_expires_at != None,      # noqa: E711
            InstantOrder.offer_expires_at < now,
        ).limit(200)
    ).all()
    moved = []
    for o in rows:
        if o.offered_at and o.offered_at > stale_before:
            continue                                    # только что предложили — пусть подумает
        if dry_run:
            moved.append(o.id)
            continue
        try:
            isv.advance_after_no_accept(session, o, notify=False)
            moved.append(o.id)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[TAXI-WORKER] сдвиг оффера #{o.id}: {type(e).__name__}: {e}")
    return moved


def run_once(session: Session, dry_run: bool = False) -> dict:
    """Один полный прогон всех задач. Возврат — сводка для лога/тестов."""
    return {
        "scheduled_activated": activate_due_scheduled(session, dry_run),
        "stuck_closed": close_stuck_orders(session, dry_run),
        "waits_retried": retry_waiting_orders(session, dry_run),
        "waits_finished": finish_expired_waits(session, dry_run),
        "offers_advanced": advance_stale_offers(session, dry_run),
    }


def main():
    dry = "--dry-run" in sys.argv
    mode = "СУХОЙ ПРОГОН (ничего не меняется)" if dry else "РЕАЛЬНЫЙ прогон"
    print(f"=== Воркер такси · {mode} · {utcnow().isoformat()} ===")
    if not settings.taxi_worker_enabled:
        print("  выключен (taxi_worker_enabled=false) — пропуск")
        return
    with Session(engine) as session:
        res = run_once(session, dry_run=dry)
    labels = {
        "scheduled_activated": "предзаказов запущено",
        "stuck_closed": "зависших закрыто",
        "waits_retried": "поисков перезапущено",
        "waits_finished": "ожиданий завершено",
        "offers_advanced": "офферов сдвинуто",
    }
    for key, label in labels.items():
        print(f"  {label}: {len(res[key])}")
    print("=== Готово ===")


if __name__ == "__main__":
    main()
