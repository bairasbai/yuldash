"""Reconcile a bounded batch of known SMS.ru receipts without submitting messages."""

from argparse import ArgumentParser
from datetime import timedelta

from sqlalchemy import or_, update
from sqlmodel import Session, select

from .db import engine
from .models import OtpSendAttempt
from .smsru import PENDING_STATUSES, lookup_statuses
from .timeutil import utcnow


def reconcile_receipts(session: Session, *, limit: int = 100) -> int:
    if not 1 <= limit <= 100:
        raise ValueError("SMS.ru status batch must contain up to 100 messages")
    attempts = session.exec(select(
        OtpSendAttempt.id, OtpSendAttempt.sms_ru_message_id,
        OtpSendAttempt.sms_ru_status_code, OtpSendAttempt.updated_at,
    ).where(
        OtpSendAttempt.sms_ru_message_id.is_not(None), OtpSendAttempt.state.in_(("sent", "unknown")),
        or_(OtpSendAttempt.sms_ru_status_code.is_(None), OtpSendAttempt.sms_ru_status_code.in_(PENDING_STATUSES)),
        OtpSendAttempt.updated_at <= utcnow() - timedelta(minutes=1),
    ).order_by(OtpSendAttempt.updated_at, OtpSendAttempt.id).limit(limit)).all()
    session.commit()
    codes = lookup_statuses([message_id for _, message_id, _, _ in attempts])
    now = utcnow()
    confirmed = 0
    for attempt_id, message_id, old_status, version in attempts:
        status = codes.get(message_id)
        fields = {"updated_at": now}
        observed = status is not None and (old_status is None or status >= old_status)
        if observed:
            fields.update(state="sent", sms_ru_status_code=status)
        changed = session.execute(update(OtpSendAttempt).where(
            OtpSendAttempt.id == attempt_id, OtpSendAttempt.sms_ru_message_id == message_id,
            OtpSendAttempt.sms_ru_status_code == old_status, OtpSendAttempt.updated_at == version,
        ).values(**fields))
        if observed:
            confirmed += changed.rowcount
    session.commit()
    return confirmed


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, choices=range(1, 101), metavar="1..100", default=100)
    args = parser.parse_args()
    with Session(engine) as session:
        confirmed = reconcile_receipts(session, limit=args.limit)
    print(f"SMS.ru receipts confirmed: {confirmed}")


if __name__ == "__main__":
    main()
