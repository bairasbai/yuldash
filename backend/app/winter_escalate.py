"""❄️ Зимний протокол: зов близких, когда человек молчит.

Что тут не так было (аудит 2026-08-08, волна 114).

Протокол устроен так: через какое-то время после начала пути приложение спрашивает
«ты доехал(а)?». Не ответил полчаса — тем, кого человек сам выбрал, уходит SMS
«позвони, проверь».

Второй шаг — «прошло полчаса, зовём близких» — выполнялся ТОЛЬКО когда приложение ещё раз
спрашивало сервер. А спрашивает оно, пока экран открыт. Получалось наоборот:

* телефон разрядился, машина в кювете, человек без сознания — приложение молчит,
  и близким не уходит НИЧЕГО. Никогда;
* человек цел, доехал, снова открыл приложение — приложение спрашивает сервер, срок вышел,
  и маме улетает тревога «не отметился, позвони».

То есть тревога приходила ровно у того, у кого всё хорошо. Проверено пробой: 45 минут
молчания и прогон всех фоновых задач — ноль SMS; один запрос из приложения — SMS уходит.

Поэтому шаг эскалации переехал сюда, к ночному роботу (`taxi_worker.run_once`), рядом
с эскалацией непринятого SOS (волна 82). Решение то же самое и по той же причине: то, от чего
зависит безопасность человека, не может зависеть от того, открыт ли у него экран.

Сам зов близких (`escalate_now`) остался ОДИН на всех: его зовёт и ручка из приложения,
и робот. Иначе через месяц эти два пути разойдутся текстами и правилами.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from sqlmodel import Session, select

from .models import (
    Booking,
    BookingStatus,
    InstantOrder,
    ParcelDelivery,
    SosEvent,
    TripShare,
    TrustedContact,
    User,
)
from .timeutil import utcnow

log = logging.getLogger("yuldash")

#: Сколько ждём ответа «доехал?», прежде чем позвать близких.
ESCALATE_AFTER_MIN = 30

#: Статусы, при которых спрашивать и звать уже некого — путь закончился.
_CLOSED_BOOKING = (BookingStatus.done, BookingStatus.cancelled)
_CLOSED_ORDER = ("done", "cancelled", "expired")
_CLOSED_PARCEL = ("delivered", "canceled", "returned")


def _phones_by_share(session: Session, *, booking_id=None, order_id=None) -> list[str]:
    """Телефоны тех, кому человек РАСШАРИЛ эту поездку.

    Расшарил — значит сам сказал «меня ждут вот эти люди». Брать всех доверенных подряд
    нельзя: контакт заводят и для других поводов, а тревожить без спроса мы не вправе.
    """
    q = select(TripShare)
    q = q.where(TripShare.booking_id == booking_id) if booking_id else q.where(TripShare.order_id == order_id)
    contact_ids = [sh.contact_id for sh in session.exec(q).all() if sh.contact_id]
    if not contact_ids:
        return []
    return [c.phone for c in session.exec(
        select(TrustedContact).where(TrustedContact.id.in_(contact_ids))
    ).all() if c.phone]


def _own_contacts(session: Session, user_id: int) -> list[str]:
    """Свои доверенные контакты — для курьера: он едет один, и ссылки слежения у него нет."""
    if not user_id:
        return []
    return [c.phone for c in session.exec(
        select(TrustedContact).where(TrustedContact.user_id == user_id)
    ).all() if c.phone]


def escalate_now(
    session: Session,
    *,
    kind: str,
    obj_id: int,
    watch_user_id: int,
    contact_phones: list,
    also_notify_user_id=None,
    background=None,
) -> dict:
    """Позвать близких. ОДНА точка: зовут и ручка из приложения, и ночной робот.

    `background` — способ отправки. Из веб-запроса передают FastAPI-BackgroundTasks, чтобы
    не держать человека на экране, пока уходят SMS. Робот передаёт None: он и так фоновый,
    ему ждать нечего.
    """
    from .routers.safety import _send_sos_sms          # локально: safety тянет пол-проекта
    from .services import notify_admin_telegram, push_notification

    if not contact_phones:
        # Звать некого: человек не добавил доверенных или не расшарил поездку.
        # Придумывать за него, кому звонить, мы не вправе.
        return {"state": "no_share"}

    # Повторно не эскалируем: иначе каждый следующий заход шлёт SMS заново — это и флуд,
    # и расход, и лишняя паника у того, кто уже поехал проверять.
    already = session.exec(select(SosEvent).where(
        SosEvent.user_id == watch_user_id,
        SosEvent.category == "other",
        SosEvent.note.like(f"%{kind}#{obj_id}%"),
    ).limit(1)).first()
    if already:
        return {"state": "escalated", "already": True, "sos_event_id": already.id}

    event = SosEvent(
        user_id=watch_user_id,
        booking_id=obj_id if kind == "booking" else None,
        order_id=obj_id if kind == "order" else None,
        category="other",
        note=(f"Зимний протокол ({kind}#{obj_id}): нет ответа "
              f"{ESCALATE_AFTER_MIN} мин после проверки «доехал?»"),
    )
    session.add(event)
    session.commit()
    session.refresh(event)

    who_row = session.get(User, watch_user_id)
    who = (who_row.name or who_row.phone) if who_row else "человек"
    sms_text = f"Юлдаш: {who} не отметил(а), что доехал(а). Позвони, проверь, всё ли хорошо."
    admin_text = (f"❄️ Зимний протокол: нет ответа (Юлдаш)\n{kind} #{obj_id}\n"
                  f"Контактов уведомлено: {len(contact_phones)}")
    if background is not None:
        background.add_task(_send_sos_sms, contact_phones, sms_text)
        background.add_task(notify_admin_telegram, admin_text)
    else:
        _send_sos_sms(contact_phones, sms_text)
        notify_admin_telegram(admin_text)

    if also_notify_user_id:
        push_notification(
            session, also_notify_user_id, "system",
            "Курьер не выходит на связь", "Курьер бәйләнешкә сыҡмай",
            "Мы не получили от него подтверждения и уже предупредили его близких.",
            "Беҙ унан раҫлау алманыҡ һәм яҡындарына хәбәр иттек.",
            ref_kind=kind, ref_id=obj_id,
        )
    log.info(f"[WINTER] {kind}={obj_id} escalated contacts={len(contact_phones)}")
    return {"state": "escalated", "sos_event_id": event.id,
            "contacts_notified": len(contact_phones)}


def escalate_silent(session: Session) -> int:
    """Пройти по всем, кого спросили «доехал?» и кто молчит дольше срока.

    Зовётся ночным роботом. Именно здесь протокол перестаёт зависеть от того, открыт ли
    у человека экран: раньше молчание телефона означало «тревоги не будет», а должно
    означать ровно обратное.
    """
    порог = utcnow() - timedelta(minutes=ESCALATE_AFTER_MIN)
    позвали = 0

    def _спросили_и_молчит(model):
        return session.exec(select(model).where(
            model.winter_check_sent_at != None,      # noqa: E711 — спрашивали
            model.winter_check_sent_at < порог,      # и ждём дольше срока
            model.winter_check_ack_at == None,       # noqa: E711 — ответа так и нет
        )).all()

    for b in _спросили_и_молчит(Booking):
        if b.status in _CLOSED_BOOKING:
            continue
        r = escalate_now(session, kind="booking", obj_id=b.id, watch_user_id=b.passenger_id,
                         contact_phones=_phones_by_share(session, booking_id=b.id))
        позвали += 1 if r.get("state") == "escalated" and not r.get("already") else 0

    for o in _спросили_и_молчит(InstantOrder):
        status = o.status.value if hasattr(o.status, "value") else o.status
        if status in _CLOSED_ORDER:
            continue
        r = escalate_now(session, kind="order", obj_id=o.id, watch_user_id=o.passenger_id,
                         contact_phones=_phones_by_share(session, order_id=o.id))
        позвали += 1 if r.get("state") == "escalated" and not r.get("already") else 0

    for p in _спросили_и_молчит(ParcelDelivery):
        if p.status in _CLOSED_PARCEL or not p.courier_id:
            continue
        r = escalate_now(session, kind="parcel", obj_id=p.id, watch_user_id=p.courier_id,
                         contact_phones=_own_contacts(session, p.courier_id),
                         also_notify_user_id=p.sender_id)
        позвали += 1 if r.get("state") == "escalated" and not r.get("already") else 0

    if позвали:
        log.info(f"[WINTER] робот позвал близких: {позвали}")
    return позвали
