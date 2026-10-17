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
from threading import Barrier

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


def _post_rate(booking_id: int, auth: dict, stars: int):
    # Своя TestClient на запрос (как test_otp_phone_concurrency.py): общий клиент гоняет
    # запросы одного транспорта по очереди, а нам нужны ДВА настоящих HTTP-вызова разом.
    request = TestClient(app, raise_server_exceptions=False)
    try:
        return request.post(f"/bookings/{booking_id}/rate", headers=auth, json={"stars": stars})
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
    # Победила одна из двух одновременных звёзд (какая именно — гонка, не фиксируем какая),
    # но ровно одна, и она реально одна из отправленных.
    assert rows[0].stars in (3, 5)
