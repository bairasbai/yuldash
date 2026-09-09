"""⏳ Человек ждёт нашего ответа — и не знает, ждут ли его вообще (волна 178).

Четыре очереди упираются в одного человека — Александра: заявка в таксисты, заявка в курьеры,
документы водителя на проверку и обращение в поддержку. Каждая устроена одинаково: при
создании уходит ОДНО сообщение ему в телеграм, дальше тишина.

**Проба.** Человек написал в поддержку «забыл телефон в машине, помогите связаться». Через
неделю: писем ему — ноль, тикет всё так же открыт, напоминаний никому не ушло. Второй случай:
собрал ИНН, разрешение, ОСАГО, подал заявку в таксисты — через месяц ни одного письма
и статус «на рассмотрении» без единого слова о сроке.

Оба раза дело не в злом умысле, а в арифметике: Александр один, четыре-пять дней в неделю
в командировках, на приложение у него пять-десять минут в день. Одно пропущенное сообщение
в телеграме — и человек ждёт вечно. Для того, кто потерял вещь или хочет выйти на работу,
это выглядит одинаково: сервису всё равно.

Что делает модуль. Каждую ночь смотрит все четыре очереди и:

- человеку, который ждёт дольше `PROMISE_DAYS`, пишет один раз: «мы получили, помним,
  отвечаем» — молчание страшнее задержки;
- Александру шлёт одну сводку: что где висит и сколько ждёт самое старое дело.

Отметку «уже писали» держим не новой колонкой, а самим уведомлением: у него есть `ref_kind`,
`ref_id` и время. Для поддержки время отделяет новый вопрос после ответа от старого цикла
ожидания. Меньше кода — меньше мест, где рассинхронизируется правда.

Живёт в общем ночном роботе (`taxi_worker.run_once`), рядом с эскалацией жалоб и напоминанием
о переводах: то, что зависит от чужой занятости, не должно зависеть ещё и от того, открыл ли
кто-то экран.
"""
from __future__ import annotations

import logging
from datetime import timedelta
from typing import Optional

from sqlmodel import Session, select

from .models import (
    CourierApplication, DriverProfile, Notification, SupportMessage, SupportSender,
    SupportTicket, SupportTicketStatus, TaxiApplication, TaxiApplicationStatus, User,
)
from .timeutil import utcnow

log = logging.getLogger("yuldash")

#: Через сколько дней ожидания пишем человеку. Двое суток — столько живёт обещание «ответим
#: на днях»: меньше — дёргаем зря по делам, которые Александр и так закроет вечером; больше —
#: человек уже успел решить, что его не читают.
PROMISE_DAYS = 2

#: Сколько имён показываем Александру в сводке, чтобы сообщение оставалось читаемым.
В_СВОДКЕ = 5


def _уже_писали(session: Session, user_id: int, ref_kind: str, ref_id: Optional[int],
                 *, цикл_с=None) -> bool:
    """Про это дело (для поддержки — в текущем цикле ожидания) уже напоминали?"""
    q = select(Notification).where(
        Notification.user_id == user_id,
        Notification.ref_kind == ref_kind,
        Notification.ref_id == ref_id,
        Notification.title_ru == ЗАГОЛОВОК_RU,
    )
    if цикл_с is not None:
        q = q.where(Notification.created_at >= цикл_с)
    return session.exec(q).first() is not None


ЗАГОЛОВОК_RU = "Мы получили и помним"
ЗАГОЛОВОК_BA = "Алдыҡ һәм иҫтә тотабыҙ"


def _написать(session: Session, user_id: int, ref_kind: str, ref_id: Optional[int],
              что_ждёт_ru: str, что_ждёт_ba: str) -> None:
    """Одно письмо: не «скоро», а «мы тебя видим». Обещать точный срок нечестно."""
    from .services import push_notification
    push_notification(
        session, user_id, "system",
        ЗАГОЛОВОК_RU, ЗАГОЛОВОК_BA,
        f"{что_ждёт_ru} Мы не забыли — ответим, как только дойдёт очередь. "
        "Если что-то срочное, напиши в поддержку.",
        f"{что_ждёт_ba} Онотманыҡ — сират еткәс тә яуап бирәбеҙ. "
        "Ашығыс булһа, ярҙам хеҙмәтенә яҙ.",
        ref_kind=ref_kind, ref_id=ref_id,
    )


def _последнее_сообщение(session: Session, ticket_id: int):
    return session.exec(
        select(SupportMessage).where(SupportMessage.ticket_id == ticket_id)
        .order_by(SupportMessage.created_at.desc(), SupportMessage.id.desc())
    ).first()


def _ждут_ответа_тикеты(session: Session, порог) -> list:
    """Обращения, где последнее слово за нами: человек написал, админ не ответил."""
    открытые = session.exec(
        select(SupportTicket).where(
            SupportTicket.status == SupportTicketStatus.open,
            SupportTicket.updated_at <= порог,
        )
    ).all()
    ждут = []
    for t in открытые:
        последнее = _последнее_сообщение(session, t.id)
        # Реальный тикет всегда имеет первое сообщение. Пустой старый тикет всё равно
        # оставляем в очереди: это повреждённое, но не решённое обращение.
        if последнее is None:
            ждут.append(t)
            continue
        sender = последнее.sender.value if hasattr(последнее.sender, "value") else последнее.sender
        # Возраст текущего хода хранится в SupportTicket.updated_at: обе ручки сообщений
        # обновляют его вместе с добавлением сообщения. Sender берём из самого последнего.
        if sender == SupportSender.user.value:
            ждут.append(t)
    return ждут


def _очереди(session: Session, порог) -> list:
    """Один источник состава очередей для reminder и ежедневной админской сводки."""
    return [
        ("support", _ждут_ответа_тикеты(session, порог), "user_id", "created_at",
         "Ты написал в поддержку, и ответа пока нет.",
         "Һин ярҙам хеҙмәтенә яҙғайның, яуап әле юҡ."),
        ("taxi_app", session.exec(select(TaxiApplication).where(
            TaxiApplication.status == TaxiApplicationStatus.pending,
            TaxiApplication.created_at <= порог)).all(), "user_id", "created_at",
         "Твоя заявка в таксисты на проверке.",
         "Һинең таксистҡа заявкаң тикшереүҙә."),
        ("courier_app", session.exec(select(CourierApplication).where(
            CourierApplication.status == "pending",
            CourierApplication.created_at <= порог)).all(), "user_id", "created_at",
         "Твоя заявка в курьеры на проверке.",
         "Һинең курьерға заявкаң тикшереүҙә."),
        ("driver_docs", session.exec(select(DriverProfile).where(
            DriverProfile.docs_status == "pending")).all(), "user_id", None,
         "Твои документы на проверке.",
         "Һинең документтарың тикшереүҙә."),
    ]


def waiting_digest_line(session: Session, now=None) -> str:
    """Текущий старый хвост очередей — только агрегаты, без имён и личных данных."""
    now = now or utcnow()
    порог = now - timedelta(days=PROMISE_DAYS)
    counts = {"support": 0, "taxi_app": 0, "courier_app": 0, "driver_docs": 0}
    самое_старое = None
    for вид, дела, поле_человека, поле_даты, _ru, _ba in _очереди(session, порог):
        for дело in дела:
            if getattr(дело, поле_человека, None) is None:
                continue
            когда = дело.updated_at if вид == "support" else (
                getattr(дело, поле_даты, None) if поле_даты else None
            )
            if поле_даты and (когда is None or когда > порог):
                continue
            counts[вид] += 1
            if когда is not None and (самое_старое is None or когда < самое_старое):
                самое_старое = когда

    имена = {
        "support": "поддержка",
        "taxi_app": "заявки в таксисты",
        "courier_app": "заявки в курьеры",
        "driver_docs": "документы водителей",
    }
    части = [f"{имена[вид]} {число}" for вид, число in counts.items() if число]
    if not части:
        return f"ждут ответа дольше {PROMISE_DAYS} дн.: 0"
    хвост = ""
    if самое_старое is not None:
        хвост = f" (самое старое {max(0, (now - самое_старое).days)} дн.)"
    return f"ждут ответа дольше {PROMISE_DAYS} дн.: " + ", ".join(части) + хвост


def remind_waiting_people(session: Session, dry_run: bool = False) -> dict:
    """Написать тем, кто ждёт нас дольше обещанного, и дать Александру сводку.

    Возврат: сколько человек предупредили в каждой очереди (для лога и тестов).
    """
    now = utcnow()
    порог = now - timedelta(days=PROMISE_DAYS)
    итог = {"support": 0, "taxi_app": 0, "courier_app": 0, "driver_docs": 0}
    самое_старое = None

    for вид, дела, поле_человека, поле_даты, текст_ru, текст_ba in _очереди(session, порог):
        for дело in дела:
            user_id = getattr(дело, поле_человека, None)
            if user_id is None:
                continue
            if вид == "support":
                последнее = _последнее_сообщение(session, дело.id)
                когда = дело.updated_at
                цикл_с = последнее.created_at if последнее is not None else дело.created_at
            else:
                когда = getattr(дело, поле_даты, None) if поле_даты else None
                цикл_с = None
            if поле_даты and (когда is None or когда > порог):
                continue
            if когда is not None and (самое_старое is None or когда < самое_старое):
                самое_старое = когда
            # У обычных заявок один жизненный цикл. У поддержки после ответа админа человек
            # может задать новый вопрос в том же тикете: старое напоминание этот цикл не гасит.
            if _уже_писали(session, user_id, вид, дело.id, цикл_с=цикл_с):
                continue
            итог[вид] += 1
            if dry_run:
                continue
            try:
                _написать(session, user_id, вид, дело.id, текст_ru, текст_ba)
            except Exception as e:  # noqa: BLE001 — письмо вторично, очередь важнее
                log.warning(f"[waiting_on_us] push failed {вид}={дело.id}: {e}")

    всего = sum(итог.values())
    if всего and not dry_run:
        _сводка_александру(session, итог, самое_старое, now)
    if всего:
        log.info(f"[waiting_on_us] предупреждено людей: {итог}")
    return итог


def _сводка_александру(session: Session, итог: dict, самое_старое, now) -> None:
    """Одно сообщение вместо четырёх: что висит и сколько ждёт самое старое дело."""
    try:
        from .services import notify_admin_telegram
        имена = {
            "support": "обращения в поддержку",
            "taxi_app": "заявки в таксисты",
            "courier_app": "заявки в курьеры",
            "driver_docs": "документы водителей",
        }
        строки = [f"• {имена[к]}: {v}" for к, v in итог.items() if v]
        хвост = ""
        if самое_старое is not None:
            дней = max(0, (now - самое_старое).days)
            хвост = f"\nСамое старое дело ждёт {дней} дн."
        notify_admin_telegram(
            "⏳ Люди ждут ответа дольше двух дней. Им написали, что мы помним.\n"
            + "\n".join(строки) + хвост
        )
    except Exception as e:  # noqa: BLE001
        log.warning(f"[waiting_on_us] admin telegram failed: {e}")


def _имя(session: Session, user_id: int) -> str:
    u: Optional[User] = session.get(User, user_id)
    return (u.name if u else "") or f"#{user_id}"
