"""«Не пришёл» нельзя отметить пассажиру, который уже «в пути» (решение 14.10.2026).

С волны 1 посадка реально переводит бронь в onboard (`test_boarding_is_recorded.py`). А неявка
по-прежнему принималась и для onboard: одним тапом перечёркивалась отметка «человек в машине» —
бронь отменялась, места возвращались в продажу, неявка ложилась в Надёжность пассажира.

С 17.10.2026 посадку отмечает сам водитель («Сел»), но правило то же: «в пути» неявкой не снять.
Промахнулся карточкой — «Отменить» в первые минуты (`test_driver_marks_boarding.py`), позже —
разбор: водитель сообщает о проблеме (`POST /incidents` по брони, тип passenger_no_show),
пассажир объясняется, решает админ.
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Incident, Ride, UserRole
from app.routers.bookings import NO_SHOW_WAIT
from app.timeutil import utcnow

# Выехал давно: «Не пришёл» доступна не раньше чем через NO_SHOW_WAIT после выезда (решение 18.10.2026).
# Тесты, которые проходят через неявку по подтверждённой брони, ездят с такой поездкой.
WAITED_OUT = -(NO_SHOW_WAIT + timedelta(minutes=1))


def _boarded(client, user_factory, tag, depart_in=timedelta(minutes=10)):
    drv = user_factory(f"NSO Driver {tag}", role=UserRole.driver)
    pax = user_factory(f"NSO Passenger {tag}")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()
    assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, b["id"]


def _board(client, drv, bid):
    """Водитель отмечает «Сел» (решение 17.10.2026)."""
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "boarded"})
    assert r.status_code == 200, r.text


def test_no_show_refused_for_onboard_booking(client, user_factory):
    drv, pax, ride, bid = _boarded(client, user_factory, "a")
    _board(client, drv, bid)
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        seats_before = s.get(Ride, ride["id"]).seats_left

    r = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    # Понятный отказ на двух языках: что случилось и что делать («Отменить» или сообщить о проблеме).
    assert "в машине" in detail["ru"] and "Отменить" in detail["ru"] and "сообщи о проблеме" in detail["ru"]
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    # Машинная причина: клиент по ней сразу меняет «Не пришёл» на «Сообщить о проблеме».
    assert detail["code"] == "passenger_onboard"

    with Session(engine) as s:
        b = s.get(Booking, bid)
        # Ничего не тронуто: бронь в пути, неявки нет, места не вернулись в продажу.
        assert b.status == BookingStatus.onboard
        assert b.no_show is False and b.cancel_reason is None and b.cancelled_by is None
        assert s.get(Ride, ride["id"]).seats_left == seats_before


def test_driver_opens_dispute_for_admin_instead(client, user_factory):
    """Путь вместо неявки — существующий двусторонний разбор по брони («Сообщить о проблеме»):
    пассажира зовут объясниться, решает админ. Вторую сторону клиент берёт из /driver/bookings."""
    drv, pax, _ride, bid = _boarded(client, user_factory, "b")
    _board(client, drv, bid)
    rows = client.get("/driver/bookings", headers=drv["auth"]).json()
    row = next(r for r in rows if r["booking_id"] == bid)
    assert row["status"] == "onboard" and row["passenger_id"] == pax["id"]

    r = client.post("/incidents", headers=drv["auth"], json={
        "respondent_id": row["passenger_id"], "type": "passenger_no_show", "booking_id": bid,
        "description": "Отметил «Сел» по ошибке, в машину он не садился"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        inc = s.get(Incident, r.json()["id"])
        assert inc.booking_id == bid and inc.type == "passenger_no_show"
        assert inc.respondent_id == pax["id"] and inc.reporter_role == "driver"
        assert inc.status == "awaiting_response"
        # Сама бронь не тронута: разбор решает админ, а не кнопка.
        assert s.get(Booking, bid).status == BookingStatus.onboard


def test_confirmed_booking_still_takes_no_show(client, user_factory):
    """Обычный случай не сломан: человек не пришёл, посадку не отмечали — неявка проходит."""
    drv, _pax, ride, bid = _boarded(client, user_factory, "c", depart_in=WAITED_OUT)
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    with Session(engine) as s:
        assert s.get(Booking, bid).no_show is True
        assert s.get(Ride, ride["id"]).seats_left == seats_before + 1


def test_boarding_between_check_and_lock_is_refused_too(client, user_factory, monkeypatch):
    """Посадку отметили (второй телефон водителя, старая очередь), пока водитель жал «Не пришёл»:
    перепроверка под замком тоже отказывает, а не отменяет бронь человека, который уже в машине."""
    import app.routers.bookings as bookings_mod

    # Срок ожидания прошёл: иначе первая же проверка вернула бы «рано», и гонка под замком не проверялась.
    drv, _pax, ride, bid = _boarded(client, user_factory, "d", depart_in=WAITED_OUT)
    original = bookings_mod.booking_and_ride_for_user
    fired = {"yes": False}

    def sat_in_between(*a, **kw):
        result = original(*a, **kw)
        if not fired["yes"]:
            fired["yes"] = True
            with Session(engine) as other:
                b = other.get(Booking, bid)
                b.status = BookingStatus.onboard
                other.add(b)
                other.commit()
        return result

    monkeypatch.setattr(bookings_mod, "booking_and_ride_for_user", sat_in_between)
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert fired["yes"]
    assert r.status_code == 409, r.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.status == BookingStatus.onboard and b.no_show is False
        assert s.get(Ride, ride["id"]).seats_left == seats_before


def test_admin_confirmed_no_show_counts_against_reliability(client, user_factory):
    """Разбор вместо кнопки должен иметь те же последствия: бронь «в пути» закрывается как done,
    но если админ признал, что пассажир не садился, поездка ему не засчитывается, а бьёт по
    Надёжности. Раньше ветка «выполнена» стояла первой — и одна отметка посадки снимала всё (ревью Opus)."""
    from app.safety_logic import reliability_for

    drv, pax, _ride, bid = _boarded(client, user_factory, "e")
    admin = user_factory("NSO Admin", role=UserRole.admin)
    _board(client, drv, bid)
    inc = client.post("/incidents", headers=drv["auth"], json={
        "respondent_id": pax["id"], "type": "passenger_no_show", "booking_id": bid,
        "description": "В машину не садился"}).json()
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.done
        assert reliability_for(s, pax["id"]) == 100          # без решения админа — не наказываем
    r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                    json={"resolution": "warning", "fault": "respondent", "note": "Не садился"})
    assert r.status_code == 200 and r.json()["status"] == "resolved", r.text
    with Session(engine) as s:
        assert reliability_for(s, pax["id"]) < 100


def test_driver_cannot_cancel_onboard_booking_as_no_show(client, user_factory):
    """Та же неявка другой дверью — отмена брони «в пути» с причиной «не пришёл» — тоже 409."""
    drv, pax, ride, bid = _boarded(client, user_factory, "f")
    _board(client, drv, bid)
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = client.post(f"/bookings/{bid}/cancel", headers=drv["auth"], json={"reason": "no_show"})
    assert r.status_code == 409 and r.json()["detail"]["code"] == "passenger_onboard", r.text
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        assert s.get(Ride, ride["id"]).seats_left == seats_before