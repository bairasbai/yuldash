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


def confirm_boarding_undo(client, driver, passenger, booking_id: int):
    """Настоящие HTTP двух сторон: просьба сама не снимает посадку, отвечает её пассажир."""
    request = client.post(f"/bookings/{booking_id}/boarded/undo", headers=driver["auth"])
    if request.status_code != 200 or not request.json().get("boarding_undo_pending"):
        return request
    assert request.json()["status"] == "onboard"
    return client.post(f"/bookings/{booking_id}/boarded/undo/respond", headers=passenger["auth"], json={
        "request_id": request.json()["boarding_undo_request_id"], "confirm": True})
