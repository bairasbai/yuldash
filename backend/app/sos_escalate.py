"""Повторный сигнал по SOS, который никто не принял.

Зачем (аудит 2026-08-08, волна 82). Красная кнопка отправляет три вещи: SMS близким,
сообщение админу в Telegram и — ночью — SMS админу. Дальше сигнал ждёт, пока дежурный
его откроет и нажмёт «принял». Если этого не произошло, не происходило НИЧЕГО: сигнал
оставался в списке со статусом «открыт» и молчал.

Замысел довести дело до конца в проекте был. В настройках лежало `sos_escalate_after_min`
(«не принят за N минут → повторный сигнал»), а у события — поле `escalated_at` с подписью
«когда ушёл повторный сигнал». Настройка была, поле было, кода не было ни строчки. Со
стороны всё выглядело так, будто эскалация работает, — и это опаснее, чем её честное
отсутствие: на неё можно понадеяться.

Человеческая цена простая. Ночь, трасса, человек нажал SOS. Дежурный спит, Telegram
не прочитан. Через десять минут не происходит ничего — ни повтора, ни звука. Утром
в списке будет запись «открыт», и никто не сможет сказать, почему на неё не ответили.

Что делает эта задача: находит непринятые сигналы старше порога, шлёт повтор (Telegram
всегда, SMS админам — если включено) и ставит `escalated_at`, чтобы повтор был ровно один.
Второй раз человека будить нечем — но и молчать без следа больше нельзя.
"""
from __future__ import annotations

import sys
from datetime import timedelta

from sqlmodel import Session, select

from .config import settings
from .db import engine
from .logs import log
from .models import SosEvent, User
from .services import notify_admin_telegram, send_text
from .timeutil import utcnow


def _admin_phones() -> list[str]:
    return [p.strip() for p in (settings.admin_phones or "").split(",") if p.strip()]


def escalate_unhandled(session: Session, dry_run: bool = False) -> list[int]:
    """Непринятые SOS старше порога → повторный сигнал. Возврат: id событий."""
    if settings.sos_escalate_after_min <= 0:
        return []
    edge = utcnow() - timedelta(minutes=settings.sos_escalate_after_min)
    rows = session.exec(
        select(SosEvent).where(
            SosEvent.status == "open",
            SosEvent.escalated_at.is_(None),      # type: ignore[union-attr]
            SosEvent.created_at <= edge,
        )
    ).all()
    done: list[int] = []
    for event in rows:
        if dry_run:
            done.append(event.id)
            continue
        user = session.get(User, event.user_id)
        waiting_min = int((utcnow() - event.created_at).total_seconds() // 60)
        text = (
            f"⏰ SOS НЕ ПРИНЯТ {waiting_min} мин (Юлдаш)\n"
            f"Сигнал #{event.id}\n"
            f"От: {(user.name if user else None) or '—'}\n"
            f"Тел: {(user.phone if user else None) or '—'}\n"
            f"Категория: {event.category}\n"
            f"Детали: {event.note or '—'}\n"
            f"→ Открой админку и нажми «принял»"
        )
        try:
            notify_admin_telegram(text)
        except Exception as e:  # noqa: BLE001 — один сбой не должен рвать прогон
            log.warning(f"[SOS-ESCALATE] telegram #{event.id}: {type(e).__name__}: {e}")
        if settings.sos_sms_to_admin:
            for phone in _admin_phones():
                try:
                    send_text(phone, f"SOS Юлдаш не принят {waiting_min} мин. Сигнал #{event.id}. Открой админку.")
                except Exception as e:  # noqa: BLE001
                    log.warning(f"[SOS-ESCALATE] sms #{event.id}: {type(e).__name__}: {e}")
        # Метку ставим ПОСЛЕ отправки: упали на отправке — повторим в следующий прогон,
        # а не «отчитаемся» о сигнале, которого никто не получил.
        event.escalated_at = utcnow()
        session.add(event)
        session.commit()
        done.append(event.id)
        log.info(f"[SOS-ESCALATE] повтор по сигналу #{event.id} (ждал {waiting_min} мин)")
    return done


def main():
    dry = "--dry-run" in sys.argv
    with Session(engine) as session:
        ids = escalate_unhandled(session, dry)
    print(f"Повторных сигналов SOS: {len(ids)}{' (сухой прогон)' if dry else ''}")


if __name__ == "__main__":
    main()
