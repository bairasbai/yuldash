"""leaf-3.2 — PG-гонка: двойная отправка оценки не создаёт два `Rating` на одну поездку.

Запускать ТОЛЬКО на изолированном PostgreSQL (`DATABASE_URL=postgresql://...` перед pytest) —
на SQLite тест сам себя пропускает: гонка ДВУХ настоящих одновременных транзакций там не
воспроизводима (один файл БД, нет второго честного подключения с собственным backend-процессом).

Сценарий: пассажир открыл экран оценки в двух вкладках (или нажал «Отправить» второй раз после
того, как первый ответ завис) — оба запроса настоящей гонкой бьют в `POST /bookings/{id}/rate`
одновременно. До фикса `rating_service.apply_rating` проверял «есть ли уже оценка» и вставлял
новую строку НЕ одной операцией: оба запроса читали «оценки нет» раньше, чем любой из них
коммитил, и каждый вставлял СВОЙ `Rating` — 200 OK на обеих вкладках, а в базе задвоенная оценка.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier, Event

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlmodel import Session, select

from app import rating_service
from app.db import engine
from app.main import app
from app.models import Rating, UserRole
from app.timeutil import utcnow
from boarding_helpers import settle_boarding

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")


def _done_booking(client, user_factory):
    """Завершённая поездка (попутка), готовая к оценке — тот же путь, что реальный человек."""
    drv = user_factory("ГонкаВодитель", role=UserRole.driver)
    pax = user_factory("ГонкаПассажир")
    body = {"from_city": "Баймак", "to_city": "Сибай",
            "depart_at": (utcnow() - timedelta(minutes=1)).replace(microsecond=0).isoformat() + "+00:00",
            "seats_total": 3, "price": 300}
    ride = client.post("/rides", headers=drv["auth"], json=body)
    assert ride.status_code == 200, ride.text
    ride = ride.json()
    booking = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert booking.status_code == 200, booking.text
    booking = booking.json()
    # Посадку отмечает водитель (Q4, PR174): без его «Сел» пассажир поездку не завершит.
    confirmed = client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"])
    assert confirmed.status_code == 200, confirmed.text
    boarded = client.post(f"/bookings/{booking['id']}/driver-status", headers=drv["auth"], json={"status": "boarded"})
    assert boarded.status_code == 200, boarded.text
    settle_boarding(booking["id"])   # пассажир завершает после окна «Отменить» водителя (PR174)
    done = client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert done.status_code == 200, done.text
    return pax, drv, booking["id"]


def _post_rate(booking_id: int, auth: dict, stars: int, from_notification: bool = False):
    # Своя TestClient на запрос (как test_otp_phone_concurrency.py): общий клиент гоняет
    # запросы одного транспорта по очереди, а нам нужны ДВА настоящих HTTP-вызова разом.
    request = TestClient(app, raise_server_exceptions=False)
    try:
        return request.post(f"/bookings/{booking_id}/rate", headers=auth,
                            json={"stars": stars, "from_notification": from_notification})
    finally:
        request.close()


def test_two_simultaneous_tabs_do_not_double_the_rating(client, user_factory, monkeypatch):
    pax, drv, bid = _done_booking(client, user_factory)
    ready = Barrier(2)
    original = rating_service._lock_rating_slot
    pids: list[int] = []

    def lock_after_both_requests_arrive(session, rater_id, booking_id, order_id):
        # Доказываем, что это ДВА разных backend-соединения (настоящая гонка, не один поток
        # по очереди) — тем же способом, что test_refresh_postgres_concurrency.py.
        pids.append(session.execute(text("SELECT pg_backend_pid()")).scalar_one())
        ready.wait(timeout=10)   # обе транзакции доходят до проверки «есть ли оценка» ВМЕСТЕ
        return original(session, rater_id, booking_id, order_id)

    monkeypatch.setattr(rating_service, "_lock_rating_slot", lock_after_both_requests_arrive)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(_post_rate, bid, pax["auth"], stars) for stars in (3, 5)]
        results = [f.result(timeout=20) for f in futures]

    assert len(set(pids)) == 2, "обе попытки обязаны идти с РАЗНЫХ соединений — иначе это не гонка"
    statuses = [r.status_code for r in results]
    assert statuses == [200, 200], f"неожиданный отказ в гонке: {statuses} {[r.text for r in results]}"

    with Session(engine) as session:
        rows = session.exec(
            select(Rating).where(Rating.booking_id == bid, Rating.rater_id == pax["id"])
        ).all()
    assert len(rows) == 1, (
        f"двойная отправка создала {len(rows)} оценки вместо одной (звёзды: {[r.stars for r in rows]}) — "
        "водителю задвоился голос одного пассажира в среднем рейтинге"
    )
    assert rows[0].stars in (3, 5)


@pytest.mark.parametrize("first_stars", [3, 5])
def test_notification_race_keeps_manual_rating_in_either_lock_order(client, user_factory, monkeypatch, first_stars):
    passenger, _, booking = _done_booking(client, user_factory)
    first_has_lock, let_first_finish, second_arrived = Event(), Event(), Event()
    original = rating_service._lock_rating_slot
    connections = []

    def ordered_acquire(session, rater_id, booking_id, order_id):
        connections.append(session.execute(text("SELECT pg_backend_pid()")).scalar_one())
        if len(connections) == 1:
            original(session, rater_id, booking_id, order_id)
            first_has_lock.set()
            assert let_first_finish.wait(10)
        else:
            second_arrived.set()
            original(session, rater_id, booking_id, order_id)

    monkeypatch.setattr(rating_service, "_lock_rating_slot", ordered_acquire)
    second_stars = 8 - first_stars
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(_post_rate, booking, passenger["auth"], first_stars, first_stars == 5)
        try:
            assert first_has_lock.wait(10)
            second = pool.submit(_post_rate, booking, passenger["auth"], second_stars, second_stars == 5)
            assert second_arrived.wait(10)
        finally:
            let_first_finish.set()
        results = {first_stars: first.result(timeout=10), second_stars: second.result(timeout=10)}

    assert len(set(connections)) == 2
    assert all(response.status_code == 200 for response in results.values())
    assert results[5].json()["kept"] is (first_stars == 3)
    assert results[5].json()["my_stars"] == (3 if first_stars == 3 else 5)
    with Session(engine) as session:
        rating = session.exec(select(Rating).where(
            Rating.booking_id == booking, Rating.rater_id == passenger["id"],
        )).one()
        assert rating.stars == 3
