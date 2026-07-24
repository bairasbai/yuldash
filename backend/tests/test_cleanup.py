# -*- coding: utf-8 -*-
"""Ретеншен-чистка (app.cleanup): убирает эфемерное, бережёт важное.
Ключевое — бронь С рейтингом НЕ удаляется (репутацию не теряем), аккаунт цел."""
from datetime import timedelta

from sqlmodel import Session, select

from app import cleanup
from app.db import engine
from app.models import (
    Booking, BookingStatus, Message, OtpCode, Rating, RefreshToken, Ride, RideStatus, User,
)
from app.timeutil import utcnow


def _old(days):
    return utcnow() - timedelta(days=days)


def test_cleanup_purges_ephemeral_keeps_important(client, user_factory):
    uid = user_factory(name="Ретеншен")["id"]
    with Session(engine) as s:
        ride = Ride(driver_id=uid, from_city="A", to_city="B", depart_at=utcnow(),
                    status=RideStatus.active, created_at=_old(200))
        s.add(ride); s.commit(); s.refresh(ride)
        b_keep = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        b_del = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        s.add(b_keep); s.add(b_del); s.commit(); s.refresh(b_keep); s.refresh(b_del)
        s.add(Rating(booking_id=b_keep.id, rater_id=uid, ratee_id=uid, stars=5, created_at=_old(200)))
        old_msg = Message(booking_id=b_del.id, sender_id=uid, text="старое", created_at=_old(40))
        new_msg = Message(booking_id=b_keep.id, sender_id=uid, text="свежее", created_at=utcnow())
        s.add(old_msg); s.add(new_msg)
        s.add(OtpCode(phone="+79990001122", code="123456", created_at=_old(5), expires_at=_old(5)))
        s.add(RefreshToken(user_id=uid, token_hash="cleanup-dead", revoked=True,
                           expires_at=_old(5), created_at=_old(5)))
        s.commit()
        keep_b, del_b, old_m, new_m = b_keep.id, b_del.id, old_msg.id, new_msg.id

    cleanup.main()   # реальная чистка

    with Session(engine) as s:
        assert s.get(Message, old_m) is None                                   # старое сообщение вычищено
        assert s.get(Message, new_m) is not None                                # свежее осталось
        assert s.get(Booking, keep_b) is not None                               # бронь С рейтингом сохранена
        assert s.get(Booking, del_b) is None                                    # бронь без рейтинга удалена
        assert s.exec(select(RefreshToken).where(RefreshToken.token_hash == "cleanup-dead")).first() is None
        assert s.exec(select(OtpCode).where(OtpCode.phone == "+79990001122")).first() is None
        assert s.get(User, uid) is not None                                     # АККАУНТ ЦЕЛ
        assert s.exec(select(Rating).where(Rating.booking_id == keep_b)).first() is not None  # рейтинг цел


def test_cleanup_dry_run_deletes_nothing(client, user_factory):
    uid = user_factory(name="Сухой")["id"]
    with Session(engine) as s:
        # Реальная бронь (FK на Postgres обязателен — SQLite его игнорировал бы) + старое сообщение на ней.
        ride = Ride(driver_id=uid, from_city="A", to_city="B", depart_at=utcnow(),
                    status=RideStatus.active, created_at=_old(200))
        s.add(ride); s.commit(); s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=uid, status=BookingStatus.done, created_at=_old(200))
        s.add(b); s.commit(); s.refresh(b)
        m = Message(booking_id=b.id, sender_id=uid, text="dry-старое", created_at=_old(40))
        s.add(m); s.commit()
        mid = m.id
    try:
        cleanup.DRY = True     # сухой прогон: только считает, ничего не удаляет
        cleanup.main()
    finally:
        cleanup.DRY = False
    with Session(engine) as s:
        assert s.get(Message, mid) is not None   # сухой прогон ничего не удалил


def test_cleanup_media_sweeps_old_keeps_fresh(client):
    """Медиа-чистка через storage: старое фото удаляется (диск/S3), свежее — остаётся."""
    import os
    import time as _t

    from app.services import CHAT_DIR
    os.makedirs(CHAT_DIR, exist_ok=True)
    old_path = os.path.join(CHAT_DIR, "cleanup_old.jpg")
    fresh_path = os.path.join(CHAT_DIR, "cleanup_fresh.jpg")
    with open(old_path, "wb") as f:
        f.write(b"x" * 16)
    with open(fresh_path, "wb") as f:
        f.write(b"y" * 16)
    old_ts = _t.time() - (cleanup.MEDIA_DAYS + 5) * 86400
    os.utime(old_path, (old_ts, old_ts))                 # состарить mtime
    cleanup.main()
    assert not os.path.exists(old_path)                  # старое медиа вычищено
    assert os.path.exists(fresh_path)                    # свежее осталось
    os.remove(fresh_path)


def test_cleanup_batched_delete_removes_all(client, monkeypatch):
    """Батчинг: даже при маленьком чанке удаляются ВСЕ подходящие строки (несколько итераций)."""
    from app.models import OtpCode
    with Session(engine) as s:
        for i in range(5):
            s.add(OtpCode(phone=f"+7000000{i:04d}", code="000000", created_at=_old(5), expires_at=_old(5)))
        s.commit()
    monkeypatch.setattr(cleanup, "_BATCH", 2)            # форсируем несколько чанков (5 строк по 2)
    cleanup.main()
    with Session(engine) as s:
        left = s.exec(select(OtpCode).where(OtpCode.phone.like("+7000000%"))).all()
    assert left == []                                    # все старые OTP удалены, несмотря на чанки
