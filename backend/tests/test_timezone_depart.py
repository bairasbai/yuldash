# -*- coding: utf-8 -*-
"""Время выезда живёт в UTC (разбор №2, 2026-08-03).

Баг был тихим и оттого долгим: приложение слало «2026-08-05T10:00:00» без пояса, сервер клал
строку в БД как есть, вся остальная база — в UTC. Уфимские 10:00 становились 10:00 UTC, то есть
на 5 часов позже реальности. Поездка висела в ленте до 17:00 по Уфе, «доехал?» приходило
на пять часов позже, а на экране время выглядело верным — клиент резал строку и не переводил пояс.
Две ошибки компенсировали друг друга ровно там, куда смотрит человек.

Правило теперь одно: время С поясом трактуем точно, время БЕЗ пояса считаем местным
башкирским. Второе — не «на всякий случай», а поддержка старых версий приложения: обновление
доезжает не до всех телефонов, и до тех пор их время обязано пониматься правильно.
"""
from datetime import datetime, timedelta, timezone

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Ride, RideRequest
from app.timeutil import client_dt_to_utc, utcnow


def _ride(rid: int) -> Ride:
    with Session(engine) as s:
        return s.get(Ride, rid)


def _create_ride(client, auth, depart_iso: str):
    return client.post("/rides", headers=auth, json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart_iso,
        "price": 300, "seats_total": 3,
    })


# ----------------------------- сам перевод -----------------------------

def test_aware_time_is_converted_exactly():
    """С поясом догадка не нужна: 10:00+05:00 — это 05:00 UTC, и точка."""
    got = client_dt_to_utc(datetime(2026, 8, 5, 10, 0, tzinfo=timezone(timedelta(hours=5))))
    assert got == datetime(2026, 8, 5, 5, 0)
    assert got.tzinfo is None, "в БД всё наивное — иначе сравнения с utcnow() падают TypeError"


def test_naive_time_is_read_as_local():
    """Старое приложение шлёт голые 10:00 — это уфимские 10:00, то есть 05:00 UTC."""
    got = client_dt_to_utc(datetime(2026, 8, 5, 10, 0))
    assert got == datetime(2026, 8, 5, 10, 0) - timedelta(hours=settings.local_tz_offset_hours)


def test_naive_can_stay_utc_where_the_client_always_sends_offset():
    """Такси-предзаказ всегда слал пояс — там наивное время осталось UTC, и это осознанно."""
    assert client_dt_to_utc(datetime(2026, 8, 5, 10, 0), naive_means="utc") == datetime(2026, 8, 5, 10, 0)


def test_none_stays_none():
    assert client_dt_to_utc(None) is None


# --------------------------- сквозь эндпоинты ---------------------------

def test_ride_created_with_offset_is_stored_in_utc(client, user_factory):
    driver = user_factory(name="ЧасовойВодитель", role=__import__("app.models", fromlist=["UserRole"]).UserRole.driver)
    when = (utcnow() + timedelta(days=1)).replace(microsecond=0, second=0)
    local = (when + timedelta(hours=settings.local_tz_offset_hours)).strftime("%Y-%m-%dT%H:%M:%S")
    r = _create_ride(client, driver["auth"], f"{local}+05:00")
    assert r.status_code == 200, r.text
    assert _ride(r.json()["id"]).depart_at == when


def test_old_client_without_offset_is_fixed_server_side(client, user_factory):
    """Главная причина, почему наивное = местное: телефон обновится не завтра, а ездят уже сегодня."""
    from app.models import UserRole
    driver = user_factory(name="СтарыйКлиент", role=UserRole.driver)
    when = (utcnow() + timedelta(days=1)).replace(microsecond=0, second=0)
    local = (when + timedelta(hours=settings.local_tz_offset_hours)).strftime("%Y-%m-%dT%H:%M:%S")
    r = _create_ride(client, driver["auth"], local)      # без пояса, как шлёт старая версия
    assert r.status_code == 200, r.text
    assert _ride(r.json()["id"]).depart_at == when


def test_ride_leaves_the_feed_on_time(client, user_factory):
    """Ради чего всё: поездка, которая уже ушла 3 часа назад, не должна висеть в ленте.
    До починки она держалась там лишние 5 часов — люди звонили водителю, который давно уехал."""
    from app.models import UserRole
    driver = user_factory(name="УехалДавно", role=UserRole.driver)
    # Поездку в прошлом заводим НАПРЯМУЮ в базе: публиковать такую через API теперь нельзя
    # (проверка ввода 2026-08-05 — водитель опубликовал бы рейс, которого сразу не видно).
    # Здесь нас интересует не публикация, а окно выдачи: уехавшее не должно висеть в ленте.
    from sqlmodel import Session

    from app.db import engine
    from app.models import Ride, RideStatus
    with Session(engine) as s:
        ride = Ride(
            driver_id=driver["id"], from_city="Баймак", to_city="Сибай",
            depart_at=utcnow() - timedelta(hours=3),
            seats_total=3, seats_left=3, price=300, status=RideStatus.active,
        )
        s.add(ride)
        s.commit()
        s.refresh(ride)
        rid = ride.id
    feed = client.get("/rides", headers=driver["auth"]).json()
    ids = {item["id"] for item in (feed if isinstance(feed, list) else feed.get("items", []))}
    assert rid not in ids, "поездка трёхчасовой давности обязана уйти из выдачи (окно — 2 часа)"


def test_request_desired_at_is_stored_in_utc(client, user_factory):
    passenger = user_factory(name="ЧасовойПассажир")
    when = (utcnow() + timedelta(days=1)).replace(microsecond=0, second=0)
    local = (when + timedelta(hours=settings.local_tz_offset_hours)).strftime("%Y-%m-%dT%H:%M:%S")
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Акъяр", "to_city": "Сибай", "desired_at": local, "seats": 1,
    })
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        req = s.exec(select(RideRequest).where(RideRequest.id == r.json()["id"])).first()
    assert req.desired_at == when
