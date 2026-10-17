"""Посадку отмечает водитель (Q4, PR174), а пассажир завершает поездку только после окна, в которое
водитель может отменить свою отметку «Сел» (5 минут, `bookings.BOARDING_UNDO_WINDOW`).

Тестам, которым нужна состоявшаяся поездка «как в жизни» — водитель отметил посадку, пассажир
потом завершил, — отметка ставится «давно»: через API время не отмотать. Только для тестов,
идущих в одном процессе с приложением (TestClient, общая база); живой сервер так не подвинуть."""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking
from app.routers.bookings import BOARDING_UNDO_WINDOW
from app.timeutil import utcnow


def settle_boarding(booking_id: int) -> None:
    """Окно «Отменить» у водителя закрыто: «Сел» отмечен больше 5 минут назад."""
    with Session(engine) as s:
        b = s.get(Booking, booking_id)
        b.onboard_at = utcnow() - BOARDING_UNDO_WINDOW - timedelta(seconds=1)
        s.add(b)
        s.commit()


def board(client, driver, booking_id: int, *, settled: bool = True) -> None:
    """Водитель отмечает «Сел»; `settled` — и окно его отмены уже прошло."""
    r = client.post(f"/bookings/{booking_id}/driver-status", headers=driver["auth"], json={"status": "boarded"})
    assert r.status_code == 200, r.text
    if settled:
        settle_boarding(booking_id)
