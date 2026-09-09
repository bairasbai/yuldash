"""BE31: поддержка напоминает по последнему сообщению и текущему циклу ожидания."""
from datetime import timedelta

from sqlmodel import Session, select

from app import services, waiting_on_us
from app.db import engine
from app.models import (
    Notification,
    SupportMessage,
    SupportSender,
    SupportTicket,
    SupportTicketStatus,
)
from app.timeutil import utcnow


def _ticket(user_id: int, messages: list[tuple[SupportSender, int]]) -> int:
    """Создать открытый диалог; число — сколько дней назад написано сообщение."""
    now = utcnow()
    with Session(engine) as session:
        ticket = SupportTicket(
            user_id=user_id,
            subject="BE31",
            status=SupportTicketStatus.open,
            created_at=now - timedelta(days=max(days for _sender, days in messages)),
            updated_at=now - timedelta(days=messages[-1][1]),
        )
        session.add(ticket)
        session.commit()
        session.refresh(ticket)
        for index, (sender, days) in enumerate(messages):
            session.add(SupportMessage(
                ticket_id=ticket.id,
                sender=sender,
                body=f"BE31 message {index}",
                created_at=now - timedelta(days=days),
            ))
        session.commit()
        return ticket.id


def _reminders(user_id: int, ticket_id: int) -> list[Notification]:
    with Session(engine) as session:
        return list(session.exec(select(Notification).where(
            Notification.user_id == user_id,
            Notification.ref_kind == "support",
            Notification.ref_id == ticket_id,
            Notification.title_ru == waiting_on_us.ЗАГОЛОВОК_RU,
        ).order_by(Notification.created_at.asc(), Notification.id.asc())).all())


def test_queue_uses_the_last_message_not_any_old_admin_reply(client, user_factory):
    waiting_user = user_factory("BE31 user waits")
    admin_waits = _ticket(waiting_user["id"], [
        (SupportSender.user, 6),
        (SupportSender.admin, 5),
        (SupportSender.user, 3),
    ])
    answered_user = user_factory("BE31 admin answered")
    user_does_not_wait = _ticket(answered_user["id"], [
        (SupportSender.admin, 6),
        (SupportSender.user, 5),
        (SupportSender.admin, 3),
    ])

    with Session(engine) as session:
        found = {ticket.id for ticket in waiting_on_us._ждут_ответа_тикеты(
            session, utcnow() - timedelta(days=2),
        )}
        # Не оставляем старые контрольные тикеты следующим тестам этого же session-набора.
        for ticket_id in (admin_waits, user_does_not_wait):
            ticket = session.get(SupportTicket, ticket_id)
            ticket.status = SupportTicketStatus.closed
            session.add(ticket)
        session.commit()

    assert admin_waits in found
    assert user_does_not_wait not in found


def test_dedup_and_admin_summary_happen_once_per_current_wait_cycle(
    client, user_factory, monkeypatch,
):
    person = user_factory("BE31 two wait cycles")
    ticket_id = _ticket(person["id"], [(SupportSender.user, 12)])
    admin_summaries: list[str] = []
    monkeypatch.setattr(
        services, "notify_admin_telegram",
        lambda text, **_kwargs: admin_summaries.append(text),
    )

    with Session(engine) as session:
        first = waiting_on_us.remind_waiting_people(session)
        second_same_cycle = waiting_on_us.remind_waiting_people(session)
    assert first["support"] == 1
    assert second_same_cycle["support"] == 0
    assert len(_reminders(person["id"], ticket_id)) == 1

    # Первое напоминание было в старом цикле. Затем поддержка ответила, а человек задал
    # новый вопрос и снова ждёт больше двух дней.
    now = utcnow()
    with Session(engine) as session:
        old_reminder = _reminders(person["id"], ticket_id)[0]
        stored = session.get(Notification, old_reminder.id)
        stored.created_at = now - timedelta(days=10)
        session.add(stored)
        session.add(SupportMessage(
            ticket_id=ticket_id,
            sender=SupportSender.admin,
            body="Старый ответ поддержки",
            created_at=now - timedelta(days=5),
        ))
        session.add(SupportMessage(
            ticket_id=ticket_id,
            sender=SupportSender.user,
            body="Новый вопрос",
            created_at=now - timedelta(days=3),
        ))
        ticket = session.get(SupportTicket, ticket_id)
        ticket.status = SupportTicketStatus.open
        ticket.updated_at = now - timedelta(days=3)
        session.add(ticket)
        session.commit()

    with Session(engine) as session:
        new_cycle = waiting_on_us.remind_waiting_people(session)
        repeated_new_cycle = waiting_on_us.remind_waiting_people(session)

    assert new_cycle["support"] == 1
    assert repeated_new_cycle["support"] == 0
    assert len(_reminders(person["id"], ticket_id)) == 2
    assert len(admin_summaries) == 2, (
        "новый цикл ожидания должен снова дать одну сводку админу, а повторный минутный "
        "запуск worker не должен слать её заново"
    )
