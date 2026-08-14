"""Пауза не отбирает у человека способ защититься.

Все предыдущие волны спрашивали «где наказание НЕ работает». Здесь вопрос обратный: **где оно
работает ЛИШНЕГО**. Пауза «Справедливости» — это «новых дел не начинаем», а не «остался без
голоса»: под разбором человек всё ещё едет в чужой машине, и с ним может случиться что угодно.

Проверено запросами (аудит 2026-08-14, волна 63). Открыто и должно быть открыто: SOS, чат
по идущей поездке, завершение поездки, ответ на обвинение в свой адрес, обращение в поддержку,
Центр справедливости, оценка после поездки, анонимная жалоба.

А вот **двусторонний разбор был закрыт целиком** — и это перекос. Человек едет последнюю
разрешённую поездку (завершать её мы намеренно не мешаем), водитель ведёт себя опасно —
сообщить нельзя, 403. При этом анонимная жалоба отстранённому была открыта всегда: то есть
«защита от мести» через этот канал и так не работала, а терялся именно разбор, где обе стороны
слышимы и решает человек.

Теперь пауза мешает только жалобе БЕЗ КОНТЕКСТА: «в воздух» на кого угодно — нельзя (это и был
бы канал мести), про свою поездку — можно. От абьюза защищает то же, что и раньше: лимит
обращений в час, обязательная привязка к общей поездке и запрет жалобы на себя.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import (Booking, BookingStatus, Incident, Ride, RideStatus, SafetyProfile,
                        UserRole)
from app.timeutil import utcnow


def _suspend(user_id: int) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=7)))
        s.commit()


def _live_trip(session: Session, pax_id: int, drv_id: int) -> int:
    """Поездка ИДЁТ: бронь подтверждена, время выезда прошло."""
    ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Уфа",
                depart_at=utcnow() - timedelta(minutes=30), seats_total=3, seats_left=2,
                price=500, status=RideStatus.active)
    session.add(ride)
    session.commit()
    session.refresh(ride)
    b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=500,
                status=BookingStatus.confirmed, boarding_code="123456")
    session.add(b)
    session.commit()
    session.refresh(b)
    return b.id


def _suspended_passenger_on_the_road(client, user_factory, tag):
    pax = user_factory(f"{tag}Pax", role=UserRole.passenger)
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    with Session(engine) as s:
        bid = _live_trip(s, pax["id"], drv["id"])
    _suspend(pax["id"])
    return pax, drv, bid


def test_на_паузе_можно_сообщить_об_опасном_водителе(client, user_factory):
    """Главная история волны: он в машине, ему страшно, и он под разбором."""
    pax, drv, bid = _suspended_passenger_on_the_road(client, user_factory, "Defence")

    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "booking_id": bid,
        "type": "unsafe", "description": "водитель вёл себя опасно",
    })
    assert r.status_code == 200
    assert r.json()["type"] == "unsafe"


def test_жалоба_в_воздух_на_паузе_по_прежнему_закрыта(client, user_factory):
    """Граница: без привязки к поездке разбор стал бы каналом мести."""
    pax = user_factory("DefenceRevengePax", role=UserRole.passenger)
    other = user_factory("DefenceRevengeOther", role=UserRole.passenger)
    _suspend(pax["id"])

    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": other["id"],
        "type": "harassment", "description": "просто хочу навредить",
    })
    assert r.status_code == 403


def test_sos_на_паузе_работает(client, user_factory):
    pax, _drv, bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceSos")
    r = client.post("/sos", headers=pax["auth"], json={
        "category": "other", "note": "нужна помощь", "booking_id": bid})
    assert r.status_code == 200


def test_на_паузе_можно_написать_водителю_и_завершить_поездку(client, user_factory):
    """Пауза не должна бросать человека на полдороге."""
    pax, _drv, bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceTrip")

    assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                       json={"text": "я у подъезда"}).status_code == 200
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"],
                       json={"status": "done"}).status_code == 200


def test_на_паузе_можно_ответить_на_обвинение(client, user_factory):
    """Иначе разбор односторонний: обвинили и слушать не стали."""
    pax, drv, bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceAnswer")
    with Session(engine) as s:
        inc = Incident(reporter_id=drv["id"], respondent_id=pax["id"], booking_id=bid,
                       type="rude", description="пассажир хамил", status="awaiting_response")
        s.add(inc)
        s.commit()
        s.refresh(inc)
        iid = inc.id

    r = client.post(f"/incidents/{iid}/respond", headers=pax["auth"],
                    json={"statement": "всё было не так, вот моя версия"})
    assert r.status_code == 200


def test_на_паузе_можно_написать_в_поддержку_и_увидеть_свой_разбор(client, user_factory):
    pax, _drv, _bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceSupport")

    assert client.post("/support/tickets", headers=pax["auth"], json={
        "subject": "не согласен с решением", "body": "объясните, за что"}).status_code == 200
    assert client.get("/me/standing", headers=pax["auth"]).status_code == 200


def test_на_паузе_можно_оценить_поездку(client, user_factory):
    """Оценка — это тоже голос: молчаливое «пять звёзд» за опасную поездку никому не нужно."""
    pax, _drv, bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceRate")
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"],
                       json={"status": "done"}).status_code == 200

    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                       json={"stars": 1, "text": ""}).status_code == 200


def test_новых_дел_на_паузе_по_прежнему_не_начинаем(client, user_factory):
    """Контроль обратной стороны: разрешив защиту, наказание не отменяем."""
    pax, drv, _bid = _suspended_passenger_on_the_road(client, user_factory, "DefenceStill")
    from app.config import settings
    depart = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(
        microsecond=0).isoformat()
    fresh = client.post("/rides", headers=drv["auth"], json={
        "from_city": "DefenceA", "to_city": "DefenceB", "seats_total": 3, "price": 500,
        "depart_at": depart,
    })
    assert fresh.status_code == 200

    assert client.post("/bookings", headers=pax["auth"], json={
        "ride_id": fresh.json()["id"], "seats": 1}).status_code == 403
    assert client.post("/requests", headers=pax["auth"], json={
        "from_city": "DefenceA", "to_city": "DefenceB", "seats": 1}).status_code == 403
