"""BE30: зимняя тревога различает точные вид и номер поездки."""
from datetime import timedelta

from sqlalchemy.dialects import postgresql
from sqlmodel import Session, select

from app import winter_escalate
from app.db import engine
from app.models import Booking, InstantOrder, Ride, SosEvent
from app.timeutil import utcnow


def _record_delivery(monkeypatch) -> list[str]:
    """Не отправлять наружу; вернуть журнал попыток отправки."""
    from app import services
    from app.routers import safety

    calls: list[str] = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda *_a, **_k: calls.append("sms"))
    monkeypatch.setattr(services, "notify_admin_telegram",
                        lambda *_a, **_k: calls.append("admin"))
    monkeypatch.setattr(services, "sms_will_reach", lambda phones: len(phones or []))
    return calls


def test_order_10_does_not_suppress_order_1(client, user_factory, monkeypatch):
    """Старое order#…10 содержит order#…1 как подстроку, но это другая поездка."""
    person = user_factory("BE30 order substring")
    target_id = 9_200_301
    calls = _record_delivery(monkeypatch)

    with Session(engine) as session:
        session.add(InstantOrder(id=target_id, passenger_id=person["id"]))
        session.add(SosEvent(
            user_id=person["id"],
            category="other",
            note=f"Зимний протокол (order#{target_id}0): нет ответа",
        ))
        session.commit()

        result = winter_escalate.escalate_now(
            session,
            kind="order",
            obj_id=target_id,
            watch_user_id=person["id"],
            contact_phones=["+79990003001"],
        )

    assert result.get("already") is not True, result
    assert calls.count("sms") == 1, calls


def test_same_kind_and_id_is_idempotent(client, user_factory, monkeypatch):
    person = user_factory("BE30 exact duplicate")
    target_id = 9_200_302
    calls = _record_delivery(monkeypatch)

    with Session(engine) as session:
        session.add(InstantOrder(id=target_id, passenger_id=person["id"]))
        session.commit()
        first = winter_escalate.escalate_now(
            session, kind="order", obj_id=target_id, watch_user_id=person["id"],
            contact_phones=["+79990003002"],
        )
        second = winter_escalate.escalate_now(
            session, kind="order", obj_id=target_id, watch_user_id=person["id"],
            contact_phones=["+79990003002"],
        )

    assert first.get("already") is not True, first
    assert second.get("already") is True, second
    assert first["sos_event_id"] == second["sos_event_id"]
    assert calls.count("sms") == 1, calls


def test_same_identity_stays_idempotent_after_wait_text_changes(
    client, user_factory, monkeypatch,
):
    """Порог/формулировка могут измениться, но поездка не должна тревожить близких снова."""
    person = user_factory("BE30 old winter wording")
    target_id = 9_200_304
    calls = _record_delivery(monkeypatch)

    with Session(engine) as session:
        old = SosEvent(
            user_id=person["id"],
            category="other",
            note=(f"Зимний протокол (order#{target_id}): нет ответа "
                  "45 мин после старого вопроса"),
        )
        session.add(old)
        session.commit()
        session.refresh(old)

        result = winter_escalate.escalate_now(
            session, kind="order", obj_id=target_id, watch_user_id=person["id"],
            contact_phones=["+79990003004"],
        )

    assert result == {"state": "escalated", "already": True, "sos_event_id": old.id}
    assert calls == [], calls


def test_booking_order_and_parcel_do_not_share_dedup_key(
    client, user_factory, monkeypatch,
):
    person = user_factory("BE30 separate kinds")
    driver = user_factory("BE30 ride driver")
    target_id = 9_200_303
    calls = _record_delivery(monkeypatch)

    with Session(engine) as session:
        ride = Ride(
            id=9_210_303,
            driver_id=driver["id"],
            from_city="Уфа",
            to_city="Салават",
            depart_at=utcnow() + timedelta(hours=2),
        )
        session.add(ride)
        session.add(InstantOrder(id=target_id, passenger_id=person["id"]))
        session.commit()
        session.add(Booking(
            id=target_id,
            ride_id=ride.id,
            passenger_id=person["id"],
        ))
        session.commit()

        results = [
            winter_escalate.escalate_now(
                session, kind=kind, obj_id=target_id, watch_user_id=person["id"],
                contact_phones=["+79990003003"],
            )
            for kind in ("booking", "order", "parcel")
        ]
        events = session.exec(select(SosEvent).where(
            SosEvent.user_id == person["id"],
            SosEvent.note.like("Зимний протокол (%"),
        )).all()

    assert all(r.get("already") is not True for r in results), results
    assert len({r["sos_event_id"] for r in results}) == 3
    assert calls.count("sms") == 3, calls
    assert {e.note.split("(", 1)[1].split(")", 1)[0] for e in events} == {
        f"booking#{target_id}", f"order#{target_id}", f"parcel#{target_id}",
    }


def test_postgresql_serializes_winter_event_creation_by_existing_user_row():
    """SQLite не исполняет row lock; проверяем PostgreSQL SQL отдельно от production БД."""
    statement = winter_escalate._watch_user_lock_statement(123)
    sql = str(statement.compile(dialect=postgresql.dialect())).upper()

    assert "WHERE" in sql and "FOR UPDATE" in sql, sql
