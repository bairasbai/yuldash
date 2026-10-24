"""Общий барьер публикации рейса и каскада паузы одного водителя."""
import hashlib

from sqlalchemy import text
from sqlmodel import Session


def lock_driver_ride_publication(session: Session, driver_id: int) -> None:
    """Брать ДО Ride/Booking/User, после действий с собственным commit.

    Замок живёт до конца транзакции. Публикация либо видит уже записанную паузу,
    либо сохраняет рейс до снимка каскада, и тот его снимает. Одного замка User
    недостаточно: каскад выбирает рейсы раньше, чем доходит до остатка водителя.
    Платёж этот барьер не берёт: его порядок Payment → Ride → User.
    На SQLite, как и row-lock, этот межтранзакционный барьер недоступен.
    """
    if session.get_bind().dialect.name != "postgresql":
        return
    key = int.from_bytes(
        hashlib.sha256(f"yuldash-ride-publication\0{driver_id}".encode()).digest()[:8],
        "big", signed=True,
    )
    session.execute(text("SELECT pg_advisory_xact_lock(CAST(:key AS bigint))"), {"key": key})
