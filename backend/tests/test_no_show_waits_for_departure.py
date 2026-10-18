"""«Не пришёл» — не раньше чем через 15 минут после выезда (решение Александра 18.10.2026).

Что было. Водитель мог отметить неявку в любой момент — хоть за неделю до поездки — одним касанием
рядом с «Сел», и отменить это было нельзя. Промахнулся — бронь человека, который стоит у дороги,
снята, место ушло в продажу. У BlaBlaCar водитель ждёт 15 минут после времени выезда.

Что стало (сервер — источник правды о сроке, клиенты своих 15 минут не держат):
* `POST /bookings/{id}/no-show` раньше `depart_at + NO_SHOW_WAIT` — 409 с кодом `no_show_too_early`,
  сколькими секундами ещё ждать (`no_show_wait_sec`) и моментом `no_show_at`; бронь не тронута;
* ровно в этот момент и позже — 200; проверка повторяется под замком, по свежей строке поездки;
* уже отмеченная неявка при повторе отвечает тем же итогом, даже если выезд потом перенесли;
* `GET /driver/bookings` отдаёт `no_show_at` — клиент прячет кнопку до него и показывает «с 14:35».

Остальные тесты неявки ездят с поездкой «выехал давно» (`test_flows.waited_out`), а не ослабляют
проверку.
"""
from datetime import datetime, timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole
from app.routers.bookings import NO_SHOW_WAIT, no_show_available_at, no_show_wait_left
from app.timeutil import utcnow

WAIT_SEC = int(NO_SHOW_WAIT.total_seconds())


def _trip(client, user_factory, tag, depart_in):
    """Поездка с выездом через `depart_in` (минус — уже уехал), подтверждённая бронь."""
    drv = user_factory(f"NSW Driver {tag}", role=UserRole.driver)
    pax = user_factory(f"NSW Passenger {tag}")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "comment": f"nsw-{tag}",
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": 3, "price": 500})
    assert ride.status_code == 200, ride.text
    ride = ride.json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid


def _no_show(client, drv, bid):
    return client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])


def _depart_at(ride_id: int) -> datetime:
    with Session(engine) as s:
        return s.get(Ride, ride_id).depart_at


def _untouched(bid: int, ride_id: int, seats_before: int):
    """Отказ ничего не поменял: бронь живая, неявки нет, место не вернулось."""
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.status == BookingStatus.confirmed and b.no_show is False
        assert b.cancel_reason is None and b.cancelled_by is None and b.cancelled_at is None
        assert s.get(Ride, ride_id).seats_left == seats_before


# ── Рано: 409 с кодом, оставшимся временем и моментом ──

def test_a_week_before_the_ride_the_tap_is_refused_and_nothing_changes(client, user_factory):
    drv, _pax, ride, bid = _trip(client, user_factory, "week", timedelta(days=7))
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = _no_show(client, drv, bid)
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "no_show_too_early"
    assert detail["ru"].strip() and detail["ba"].strip() and detail["ba"] != detail["ru"]
    # Остаток — до выезда и ещё 15 минут; секунды, а не время: часы телефона врут.
    week = 7 * 86_400
    assert week + WAIT_SEC - 60 <= detail["no_show_wait_sec"] <= week + WAIT_SEC
    _untouched(bid, ride["id"], seats_before)


def test_ten_minutes_before_departure_is_still_too_early(client, user_factory):
    """Самый частый промах: водитель у подъезда за 10 минут до выезда, пассажира ещё нет."""
    drv, _pax, ride, bid = _trip(client, user_factory, "ten", timedelta(minutes=10))
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    assert 10 * 60 + WAIT_SEC - 30 <= r.json()["detail"]["no_show_wait_sec"] <= 10 * 60 + WAIT_SEC
    _untouched(bid, ride["id"], seats_before)


def test_a_minute_after_departure_the_wait_is_not_over(client, user_factory):
    drv, _pax, ride, bid = _trip(client, user_factory, "just", timedelta(minutes=-1))
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    assert WAIT_SEC - 60 - 30 <= r.json()["detail"]["no_show_wait_sec"] <= WAIT_SEC - 60
    _untouched(bid, ride["id"], seats_before)


def test_the_refusal_carries_the_moment_the_button_opens(client, user_factory):
    """`no_show_at` в отказе — тот же момент, что в строке брони водителя: выезд + ожидание."""
    drv, _pax, ride, bid = _trip(client, user_factory, "moment", timedelta(hours=3))
    expected = _depart_at(ride["id"]) + NO_SHOW_WAIT
    r = _no_show(client, drv, bid)
    assert datetime.fromisoformat(r.json()["detail"]["no_show_at"]) == expected
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    assert datetime.fromisoformat(row["no_show_at"]) == expected


# ── Ровно в срок и позже: проходит ──

def test_after_the_wait_the_no_show_goes_through(client, user_factory):
    drv, _pax, ride, bid = _trip(client, user_factory, "late", -(NO_SHOW_WAIT + timedelta(minutes=1)))
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    r = _no_show(client, drv, bid)
    assert r.status_code == 200 and r.json()["status"] == "cancelled" and r.json()["no_show"] is True, r.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.no_show is True and b.cancel_reason == "no_show" and b.cancelled_by == drv["id"]
        assert s.get(Ride, ride["id"]).seats_left == seats_before + 1


def test_the_boundary_is_exact_to_the_second(client, user_factory, monkeypatch):
    """За секунду до срока — отказ, ровно в срок — проходит. Часы сервера подставлены."""
    import app.routers.bookings as bookings_mod

    drv, _pax, ride, bid = _trip(client, user_factory, "edge", timedelta(hours=1))
    opens = _depart_at(ride["id"]) + NO_SHOW_WAIT

    monkeypatch.setattr(bookings_mod, "utcnow", lambda: opens - timedelta(seconds=1))
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    assert r.json()["detail"]["no_show_wait_sec"] == 1

    # Меньше секунды — тоже «ещё рано»: «0 секунд осталось» значит ровно «можно».
    monkeypatch.setattr(bookings_mod, "utcnow", lambda: opens - timedelta(milliseconds=500))
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and r.json()["detail"]["no_show_wait_sec"] == 1, r.text

    monkeypatch.setattr(bookings_mod, "utcnow", lambda: opens)
    r = _no_show(client, drv, bid)
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text


def test_the_wait_is_one_rule_for_the_check_the_row_and_the_refusal(client, user_factory):
    """Один срок на три места: проверка, строка брони и отказ не могут разойтись."""
    _drv, _pax, ride, _bid = _trip(client, user_factory, "rule", timedelta(minutes=30))
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        assert no_show_available_at(r) == r.depart_at + NO_SHOW_WAIT
        assert no_show_wait_left(r, now=r.depart_at + NO_SHOW_WAIT) == 0
        assert no_show_wait_left(r, now=r.depart_at + NO_SHOW_WAIT - timedelta(seconds=90)) == 90
        assert no_show_wait_left(r, now=r.depart_at + NO_SHOW_WAIT + timedelta(days=1)) == 0
        r.depart_at = None
        assert no_show_available_at(r) is None and no_show_wait_left(r) == 0, "нет выезда — проверять нечем"


# ── Повтор и гонки ──

def test_repeat_after_success_gives_the_same_answer_even_if_the_ride_moved(client, user_factory):
    drv, _pax, ride, bid = _trip(client, user_factory, "repeat", -(NO_SHOW_WAIT + timedelta(minutes=1)))
    first = _no_show(client, drv, bid)
    assert first.status_code == 200, first.text
    with Session(engine) as s:
        seats_after_first = s.get(Ride, ride["id"]).seats_left
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() + timedelta(days=3)    # выезд перенесли уже после отметки
        s.add(r)
        s.commit()
    again = _no_show(client, drv, bid)
    assert again.status_code == 200, f"красная ошибка после успеха: {again.status_code} {again.text[:160]}"
    assert again.json()["status"] == "cancelled" and again.json()["no_show"] is True
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).seats_left == seats_after_first, "место вернулось дважды"


def test_a_ride_moved_later_between_the_check_and_the_lock_is_refused(client, user_factory, monkeypatch):
    """Первая проверка прошла (выезд был давно), а пока водитель жал, выезд перенесли на вечер:
    перепроверка под замком смотрит в свежую строку поездки и отказывает, бронь не трогает."""
    import app.routers.bookings as bookings_mod

    drv, _pax, ride, bid = _trip(client, user_factory, "moved", -(NO_SHOW_WAIT + timedelta(minutes=1)))
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    original = bookings_mod.booking_and_ride_for_user
    fired = {"yes": False}

    def moved_in_between(*a, **kw):
        result = original(*a, **kw)
        if not fired["yes"]:
            fired["yes"] = True
            with Session(engine) as other:
                r = other.get(Ride, ride["id"])
                r.depart_at = utcnow() + timedelta(hours=6)
                other.add(r)
                other.commit()
        return result

    monkeypatch.setattr(bookings_mod, "booking_and_ride_for_user", moved_in_between)
    r = _no_show(client, drv, bid)
    assert fired["yes"]
    assert r.status_code == 409 and r.json()["detail"]["code"] == "no_show_too_early", r.text
    _untouched(bid, ride["id"], seats_before)


# ── Остальные двери не изменились ──

def test_a_pending_booking_keeps_its_own_refusal(client, user_factory):
    """Неподтверждённой брони — прежний отказ про подтверждение, а не «рано»: причина названа точно."""
    drv = user_factory("NSW Driver pending", role=UserRole.driver)
    pax = user_factory("NSW Passenger pending")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "comment": "nsw-pending",
        "depart_at": (utcnow() + timedelta(days=2)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    r = _no_show(client, drv, bid)
    assert r.status_code == 409 and "code" not in r.json()["detail"], r.text


def test_only_the_driver_of_the_ride_and_before_the_clock(client, user_factory):
    """Чужому и пассажиру — 403 раньше любых разговоров про срок: про время поездки им знать нечего."""
    drv, pax, _ride, bid = _trip(client, user_factory, "who", timedelta(days=1))
    assert _no_show(client, pax, bid).status_code == 403
    stranger = user_factory("NSW Stranger", role=UserRole.driver)
    assert _no_show(client, stranger, bid).status_code in (403, 404)
    assert _no_show(client, drv, bid).status_code == 409
