"""Строка, взятая «под локом», должна читаться ЗАНОВО — иначе лок сторожит пустоту.

По всему бэкенду один и тот же приём: сначала объект берут обычным чтением (`session.get`),
потом ту же строку читают ещё раз `with_for_update()` и перепроверяют статус — «первая отмена
уже могла отработать», «два параллельных accept не пройдут оба». Приём верный, но SQLAlchemy
при повторной загрузке УЖЕ ЗАГРУЖЕННОГО объекта не перезаписывает его поля: возвращается тот же
объект со старыми значениями. Лок берётся, а проверка смотрит в прошлое — и на Postgres хуже,
чем на SQLite: ожидание на локе ГАРАНТИРУЕТ, что данные прочитаны до чужого коммита.

Настоящего Postgres в окружении нет, поэтому гонка воспроизводится честно, но детерминированно:
между первым чтением и локом другая СЕССИЯ коммитит изменение — ровно то, что в проде делает
параллельный запрос. Если под локом видно новое значение, защита работает.

Что ломалось (аудит 2026-08-07):
  * отмена брони возвращала места из устаревшего seats_left → мест в поездке становилось больше,
    чем в машине (овербукинг);
  * отмена затирала отметку неявки, поставленную водителем секундой раньше;
  * два принятия одной заявки создавали ДВЕ поездки — за пассажиром приезжали два водителя.
"""
import uuid

from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideRequest, User, UserRole
from app.security import make_token

_n = {"i": 0}
_LIVE = (BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)


def mk_user(name="Кеше", role=UserRole.passenger):
    _n["i"] += 1
    i = _n["i"]
    with Session(engine) as s:
        u = User(phone=f"real-lock-{i}", name=name, verified=True, role=role,
                 telegram_id=f"lock{i}")
        s.add(u)
        s.commit()
        s.refresh(u)
        return {"id": u.id, "token": make_token(u.id),
                "auth": {"Authorization": f"Bearer {make_token(u.id)}"}}


def _city():
    return "Город" + uuid.uuid4().hex[:8]


def _publish(client, drv, seats=3):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": _city(), "to_city": _city(), "depart_at": "2030-01-01T10:00:00",
        "seats_total": seats, "price": 300,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _seats_are_consistent(ride_id: int) -> tuple:
    """(свободно, занято живыми бронями, всего) — сумма первых двух обязана равняться третьему."""
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        busy = sum(b.seats for b in s.exec(
            select(Booking).where(Booking.ride_id == ride_id, Booking.status.in_(_LIVE))).all())
        return ride.seats_left, busy, ride.seats_total


def _race_once(monkeypatch, module, name, effect):
    """Подменяем функцию, которая вызывается МЕЖДУ первым чтением и локом: она один раз
    выполняет `effect` (коммит «параллельного запроса» в отдельной сессии) и работает дальше."""
    original = getattr(module, name)
    fired = {"yes": False}

    def wrapper(*a, **kw):
        result = original(*a, **kw)
        if not fired["yes"]:
            fired["yes"] = True
            with Session(engine) as other:
                effect(other)
                other.commit()
        return result

    monkeypatch.setattr(module, name, wrapper)


# --------------------------- 1. Отмена брони и места ---------------------------

def test_cancel_returns_seats_by_fresh_count(client, monkeypatch):
    """Пока пассажир отменяет бронь, последнее место занимает другой — мест не должно стать больше."""
    import app.routers.bookings as bookings_mod

    drv = mk_user("ЛокВод", role=UserRole.driver)
    a, b, c = mk_user("ЛокA"), mk_user("ЛокB"), mk_user("ЛокC")
    ride = _publish(client, drv, seats=3)
    booking_a = _book(client, a, ride["id"])       # свободно 2
    _book(client, b, ride["id"])                   # свободно 1

    def третий_занимает_последнее_место(other: Session):
        r = other.get(Ride, ride["id"])
        r.seats_left -= 1                          # свободно 0
        other.add(Booking(ride_id=ride["id"], passenger_id=c["id"], seats=1, price=300,
                          status=BookingStatus.pending))
        other.add(r)

    _race_once(monkeypatch, bookings_mod, "booking_and_ride_for_user",
               третий_занимает_последнее_место)
    assert client.post(f"/bookings/{booking_a['id']}/cancel", headers=a["auth"]).status_code == 200

    free, busy, total = _seats_are_consistent(ride["id"])
    assert free + busy == total, f"мест {free} свободно + {busy} занято ≠ {total} в машине"


# --------------------------- 2. Отмена и отметка неявки ---------------------------

def test_cancel_does_not_overwrite_fresh_no_show(client, monkeypatch):
    """Водитель отметил неявку — пассажир в ту же секунду жмёт «Отменить». Отметка обязана уцелеть."""
    import app.routers.bookings as bookings_mod

    drv = mk_user("НеявкаВод", role=UserRole.driver)
    pax = mk_user("НеявкаПас")
    ride = _publish(client, drv, seats=3)
    booking = _book(client, pax, ride["id"])
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"]).status_code == 200

    def водитель_отметил_неявку(other: Session):
        bk = other.get(Booking, booking["id"])
        bk.status = BookingStatus.cancelled
        bk.no_show = True
        bk.cancel_reason = "no_show"
        bk.cancelled_by = drv["id"]
        r = other.get(Ride, ride["id"])
        r.seats_left = min(r.seats_total, r.seats_left + bk.seats)
        other.add(bk)
        other.add(r)

    _race_once(monkeypatch, bookings_mod, "booking_and_ride_for_user", водитель_отметил_неявку)
    assert client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"]).status_code == 200

    with Session(engine) as s:
        bk = s.get(Booking, booking["id"])
        assert bk.no_show is True, "отметка неявки затёрта отменой, читавшей устаревшую строку"
        assert bk.cancel_reason == "no_show"
        assert bk.cancelled_by == drv["id"]
    free, busy, total = _seats_are_consistent(ride["id"])
    assert free + busy == total


def test_no_show_returns_seats_by_fresh_count(client, monkeypatch):
    """Зеркало первого случая для неявки: пока водитель её отмечает, место занимает другой."""
    import app.routers.bookings as bookings_mod

    drv = mk_user("НшВод", role=UserRole.driver)
    a, b, c = mk_user("НшA"), mk_user("НшB"), mk_user("НшC")
    ride = _publish(client, drv, seats=3)
    booking_a = _book(client, a, ride["id"])
    assert client.post(f"/bookings/{booking_a['id']}/confirm", headers=drv["auth"]).status_code == 200
    _book(client, b, ride["id"])

    def третий_занимает_последнее_место(other: Session):
        r = other.get(Ride, ride["id"])
        r.seats_left -= 1
        other.add(Booking(ride_id=ride["id"], passenger_id=c["id"], seats=1, price=300,
                          status=BookingStatus.pending))
        other.add(r)

    _race_once(monkeypatch, bookings_mod, "booking_and_ride_for_user",
               третий_занимает_последнее_место)
    assert client.post(f"/bookings/{booking_a['id']}/no-show", headers=drv["auth"]).status_code == 200

    free, busy, total = _seats_are_consistent(ride["id"])
    assert free + busy == total, f"мест {free} свободно + {busy} занято ≠ {total} в машине"


# --------------------------- 3. Два принятия одной заявки ---------------------------

def test_second_accept_of_closed_request_is_rejected(client, monkeypatch):
    """Пассажир принимает отклик, а заявку в этот момент уже закрыл параллельный accept
    (второе окно, автоподбор, кнопка админа в Telegram). Второй поездки быть не должно."""
    import app.routers.requests as requests_mod

    pax = mk_user("ЗаявПас")
    d1 = mk_user("ЗаявВод1", role=UserRole.driver)
    d2 = mk_user("ЗаявВод2", role=UserRole.driver)
    frm, to = _city(), _city()
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": frm, "to_city": to, "seats": 1}).json()
    r1 = client.post(f"/requests/{req['id']}/respond", headers=d1["auth"], json={"price": 300})
    r2 = client.post(f"/requests/{req['id']}/respond", headers=d2["auth"], json={"price": 400})
    assert r1.status_code == 200 and r2.status_code == 200

    def первый_отклик_уже_принят(other: Session):
        rq = other.get(RideRequest, req["id"])
        rq.status = "matched"                      # параллельный accept опередил
        other.add(rq)

    _race_once(monkeypatch, requests_mod, "_bargain_role", первый_отклик_уже_принят)
    resp = client.post(f"/responses/{r2.json()['id']}/accept", headers=pax["auth"])
    assert resp.status_code == 400, resp.text      # «Заявка уже закрыта»

    with Session(engine) as s:
        rides = s.exec(select(Ride).where(Ride.from_city == frm, Ride.to_city == to)).all()
        assert len(rides) == 0, "на закрытую заявку создалась вторая поездка — два водителя за пассажиром"
