# -*- coding: utf-8 -*-
"""Авто-подбор водителя для «помощь»-заявок (пассажир БЕЗ приложения).

Обычную заявку пассажир закрывает сам в приложении — выбирает отклик. Но заявку
от человека без приложения (создана по звонку / за пожилого / голосом) некому принять
в приложении: раньше это делал админ вручную в Telegram. Здесь система делает это САМА —
спустя короткую паузу (дать водителям откликнуться) берёт ЛУЧШИЙ отклик (рейтинг водителя,
затем цена) и принимает его как приложение. Админу уходит уведомление-факт, не просьба.

Запуск (systemd-таймер раз в минуту, см. deploy/yuldash-automatch.*):
    python -m app.automatch            # реальный подбор
    python -m app.automatch --dry-run  # показать, что подобралось бы, ничего не меняя

Свойства: один процесс (без гонок между воркерами API), идемпотентно (приём заявки
атомарен, повторно её уже не взять), безопасно (только active-заявки пассажиров без
устройства — у кого есть приложение, тот выбирает сам, их не трогаем)."""
import sys
from datetime import timedelta

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import DeviceToken, RequestResponse, RideRequest
from .safety_logic import may_ride_together
from .services import notify_admin_telegram, user_rating
from .timeutil import utcnow


def _best_response(session: Session, responses: list[RequestResponse]) -> RequestResponse:
    """Лучший отклик: выше рейтинг водителя → ниже цена (0/неуказанная — в конец) → раньше откликнулся.

    Цену берём ту, что НА СТОЛЕ (после торга), а не первое предложение водителя: иначе сравнивали
    бы числа, от которых стороны уже отошли. Здесь это почти всегда одно и то же (авто-подбор
    работает только для пассажиров без приложения, а они торговаться не могут), но сравнение
    должно опираться на ту же цену, по которой создастся поездка."""
    from .routers.requests import price_on_table   # локальный импорт — без цикла на старте

    def rank(r: RequestResponse):
        avg, _ = user_rating(session, r.driver_id)
        p = price_on_table(r)
        price_rank = p if p > 0 else float("inf")
        return (-avg, price_rank, r.id)
    return sorted(responses, key=rank)[0]


def automatch_once(session: Session, dry_run: bool = False) -> list[tuple[int, int]]:
    """Один проход: по каждой созревшей «помощь»-заявке принять лучший отклик.
    Возвращает список (request_id, response_id) — что подобрано (или подобралось бы при dry_run)."""
    if not settings.automatch_enabled:
        return []
    from .routers.requests import accept_request_response   # локальный импорт — без цикла на старте
    cutoff = utcnow() - timedelta(seconds=settings.automatch_grace_sec)
    matched: list[tuple[int, int]] = []
    active = session.exec(select(RideRequest).where(RideRequest.status == "active")).all()
    for req in active:
        # Только пассажир БЕЗ приложения: сам принять не может → это «помощь». Есть приложение → выбирает сам.
        has_device = session.exec(
            select(DeviceToken).where(DeviceToken.user_id == req.passenger_id)
        ).first() is not None
        if has_device:
            continue
        offers = session.exec(select(RequestResponse).where(
            RequestResponse.request_id == req.id, RequestResponse.status == "offered")).all()
        # Отстранённого и заблокированного отсеиваем ЗДЕСЬ, а не полагаемся на отказ при приёме:
        # иначе один негодный отклик уводил бы всю заявку в except ниже, и человек без приложения
        # остался бы без машины при живых честных откликах (аудит 2026-08-12, волна 46).
        offers = [o for o in offers if may_ride_together(session, req.passenger_id, o.driver_id)]
        if not offers:
            continue
        # Пауза: дать другим водителям откликнуться, чтобы выбрать лучшего, а не первого попавшегося.
        if min(o.created_at for o in offers) > cutoff:
            continue
        best = _best_response(session, offers)
        if dry_run:
            matched.append((req.id, best.id))
            continue
        try:
            accept_request_response(session, best)
        except Exception as e:  # заявка уже закрыта/ошибка — пропускаем одну, не роняем весь проход
            print(f"  заявка #{req.id}: пропуск ({type(e).__name__}: {e})")
            continue
        matched.append((req.id, best.id))
        notify_admin_telegram(
            f"🤖 Авто-подбор по заявке #{req.id}\n"
            f"{req.from_city} → {req.to_city}\n"
            f"Принят отклик #{best.id}. При желании свяжись с пассажиром и водителем."
        )
    return matched


def main():
    dry = "--dry-run" in sys.argv
    mode = "СУХОЙ ПРОГОН (ничего не меняется)" if dry else "РЕАЛЬНЫЙ подбор"
    print(f"=== Авто-подбор Юлдаш · {mode} · {utcnow().isoformat()} ===")
    if not settings.automatch_enabled:
        print("  выключено (automatch_enabled=false) — пропуск")
        return
    with Session(engine) as session:
        matched = automatch_once(session, dry_run=dry)
    verb = "подобралось бы" if dry else "подобрано"
    print(f"=== Итог: {verb} заявок — {len(matched)} ===")
    for req_id, resp_id in matched:
        print(f"  заявка #{req_id} → отклик #{resp_id}")


if __name__ == "__main__":
    main()
