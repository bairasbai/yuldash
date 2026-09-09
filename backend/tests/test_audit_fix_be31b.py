"""BE31b: старые очереди входят в общую ежедневную админскую сводку."""
from datetime import datetime, timedelta

from sqlmodel import Session, select

from app import digest, services, waiting_on_us
from app.config import settings
from app.db import engine
from app.models import Notification, SupportMessage, SupportSender, SupportTicket, SupportTicketStatus


def test_old_wait_cycle_is_in_next_daily_digest_once_without_second_user_reminder(
    client, user_factory, monkeypatch,
):
    person = user_factory("BE31b waiting person")
    first_run = datetime(2025, 4, 9, 16, 30)   # 21:30 по Уфе
    with Session(engine) as session:
        ticket = SupportTicket(
            user_id=person["id"], subject="BE31b", status=SupportTicketStatus.open,
            created_at=first_run - timedelta(days=4),
            updated_at=first_run - timedelta(days=3),
        )
        session.add(ticket)
        session.commit()
        session.refresh(ticket)
        ticket_id = ticket.id
        session.add(SupportMessage(
            ticket_id=ticket_id, sender=SupportSender.user, body="Жду ответа",
            created_at=first_run - timedelta(days=3),
        ))
        session.commit()

    immediate_summaries: list[str] = []
    monkeypatch.setattr(waiting_on_us, "utcnow", lambda: first_run)
    monkeypatch.setattr(
        services, "notify_admin_telegram",
        lambda text, **_kwargs: immediate_summaries.append(text),
    )
    with Session(engine) as session:
        assert waiting_on_us.remind_waiting_people(session)["support"] == 1
        assert waiting_on_us.remind_waiting_people(session)["support"] == 0
    assert len(immediate_summaries) == 1

    sent_daily: list[str] = []
    monkeypatch.setattr(settings, "daily_digest_enabled", True)
    monkeypatch.setattr(settings, "daily_digest_hour", 21)
    monkeypatch.setattr(digest, "_memo_sent_day", None)
    monkeypatch.setattr(
        digest, "notify_admin_telegram",
        lambda text, **_kwargs: sent_daily.append(text),
    )
    next_day = first_run + timedelta(days=1)
    monkeypatch.setattr(waiting_on_us, "utcnow", lambda: next_day)
    with Session(engine) as session:
        assert waiting_on_us.remind_waiting_people(session)["support"] == 0
        assert waiting_on_us.remind_waiting_people(session)["support"] == 0
    assert len(immediate_summaries) == 1

    with Session(engine) as session:
        assert digest.maybe_send_daily_digest(session, next_day) is True
        assert digest.maybe_send_daily_digest(session, next_day) is False
    monkeypatch.setattr(digest, "_memo_sent_day", None)  # второй процесс в тот же день
    with Session(engine) as session:
        assert digest.maybe_send_daily_digest(session, next_day) is False

    assert len(sent_daily) == 1
    assert "ждут ответа" in sent_daily[0]
    assert "поддерж" in sent_daily[0]
    with Session(engine) as session:
        reminders = session.exec(select(Notification).where(
            Notification.user_id == person["id"],
            Notification.ref_kind == "support",
            Notification.ref_id == ticket_id,
            Notification.title_ru == waiting_on_us.ЗАГОЛОВОК_RU,
        )).all()
        assert len(reminders) == 1
