"""Посадку отмечает ВОДИТЕЛЬ, а не пассажир (решение Александра 17.10.2026).

Что было. Пассажир сам жал «Я сел» — за 30 минут до выезда, без всякой проверки. Нажать можно
было из дома: бронь становилась «в пути», и водитель уже не мог отметить «Не пришёл» (решение
14.10). А нажатое по ошибке было не вернуть.

Что стало:
* «Сел» жмёт водитель на карточке подтверждённого пассажира (`driver-status` «boarded»);
* промахнулся карточкой — «Отменить» в первые 5 минут (`/boarded/undo`), бронь снова подтверждена;
* старое приложение со своим «Я сел» получает 409 «Посадку теперь отмечает водитель»;
* всё, что раньше запускал «Я сел», запускает отметка водителя: «в пути», толчок каналу
  координат, уведомление пассажиру, SMS близким, запрет отмены пассажиром и неявки водителем;
* водитель забыл отметить — поездка закрывается его «Завершить», как раньше;
* «Завершить» пассажира — только после «Сел» водителя, иначе 409 (решение лида по PR #174):
  завершив подтверждённую бронь сам, пассажир закрывал водителю «Не пришёл»;
* приёмка PR174: «Завершить» — ещё и после окна «Отменить» водителя (запись условная), «Доехал» —
  только «в пути»; близкие узнают об отмене отметки (не больше трёх SMS на посадку); оба пуша —
  urgent; отказ отмены «в пути» ведёт к разбору; «Сообщить о проблеме» после поездки — 6 часов.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Notification, Ride, TripShare, UserRole
from app.routers.bookings import NO_SHOW_WAIT
from app.timeutil import utcnow


def _trip(client, user_factory, tag, *, depart_in=timedelta(minutes=10), confirm=True):
    drv = user_factory(f"DMB Driver {tag}", role=UserRole.driver)
    pax = user_factory(f"DMB Passenger {tag}")
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "comment": f"dmb-{tag}",
        "depart_at": (utcnow() + depart_in).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    if confirm:
        assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, bid


def _board(client, who, bid):
    return client.post(f"/bookings/{bid}/driver-status", headers=who["auth"], json={"status": "boarded"})


def _undo(client, who, bid):
    return client.post(f"/bookings/{bid}/boarded/undo", headers=who["auth"])


def _booking(bid) -> Booking:
    with Session(engine) as s:
        return s.get(Booking, bid)


def _notes(user_id: int, title: str) -> int:
    with Session(engine) as s:
        return len(s.exec(select(Notification).where(
            Notification.user_id == user_id, Notification.title_ru == title)).all())


def _age_mark(bid, minutes: int):
    """Отметка «Сел» была `minutes` минут назад (через API время не отмотать)."""
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.onboard_at = utcnow() - timedelta(minutes=minutes)
        s.add(b)
        s.commit()


# ── Водитель отмечает «Сел» ──

def test_driver_marks_boarding_and_booking_goes_onboard(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "mark")
    r = _board(client, drv, bid)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "onboard"
    assert 290 <= body["boarding_undo_sec"] <= 300, body
    b = _booking(bid)
    assert b.status == BookingStatus.onboard and b.onboard_at is not None
    # Обе стороны видят «в пути»; отменить отметку может только водитель — пассажиру 0.
    drv_state = client.get(f"/bookings/{bid}/role", headers=drv["auth"]).json()
    pax_state = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert drv_state["status"] == pax_state["status"] == "onboard"
    assert drv_state["boarding_undo_sec"] > 0 and pax_state["boarding_undo_sec"] == 0
    # Вторая сторона брони — для «Сообщить о проблеме» пассажира, который ждёт отметки.
    assert pax_state["peer_id"] == drv["id"] and drv_state["peer_id"] == pax["id"]
    # Карточка в кабинете водителя знает, сколько ещё можно отменить — и после перезапуска.
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    assert row["status"] == "onboard" and row["boarding_undo_sec"] > 0


def test_mark_is_idempotent_and_notifies_the_passenger_once(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "twice")
    assert _board(client, drv, bid).status_code == 200
    first_mark = _booking(bid).onboard_at
    again = _board(client, drv, bid)
    assert again.status_code == 200 and again.json()["status"] == "onboard", again.text
    assert _booking(bid).onboard_at == first_mark, "повтор не должен сдвигать окно отмены"
    assert _notes(pax["id"], "Водитель отметил посадку") == 1, "повторный тап — второй пуш"


def test_wrong_driver_and_passenger_cannot_mark(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "who")
    stranger = user_factory("DMB Stranger driver", role=UserRole.driver)
    assert _board(client, stranger, bid).status_code in (403, 404)
    r = _board(client, pax, bid)
    assert r.status_code == 403, r.text
    assert _booking(bid).status == BookingStatus.confirmed


def test_only_confirmed_booking_can_be_marked(client, user_factory):
    drv, _pax, _ride, bid = _trip(client, user_factory, "pending", confirm=False)
    r = _board(client, drv, bid)
    assert r.status_code == 409, r.text
    assert r.json()["detail"]["ru"].strip() and r.json()["detail"]["ba"].strip()
    assert _booking(bid).status == BookingStatus.pending


def test_mark_outside_the_window_is_refused(client, user_factory):
    """Окно то же, что было у «Я сел»: не раньше чем за 30 минут до выезда."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "early", depart_in=timedelta(hours=3))
    r = _board(client, drv, bid)
    assert r.status_code == 409, r.text
    assert "30 минут" in r.json()["detail"]["ru"]
    assert _booking(bid).status == BookingStatus.confirmed
    # Ровно на границе окна (за 25 минут) — уже можно.
    drv2, _pax2, _ride2, bid2 = _trip(client, user_factory, "edge", depart_in=timedelta(minutes=25))
    assert _board(client, drv2, bid2).status_code == 200


# ── Старое приложение с «Я сел» ──

def test_passenger_i_sat_from_an_old_app_gets_409(client, user_factory):
    _drv, pax, _ride, bid = _trip(client, user_factory, "oldapp")
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "sat"})
    assert r.status_code == 409, r.text
    detail = r.json()["detail"]
    assert detail["ru"] == "Посадку теперь отмечает водитель"
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    assert detail["code"] == "driver_marks_boarding"
    assert _booking(bid).status == BookingStatus.confirmed, "пассажир сам себя не посадил"


# ── «Отменить» — промахнулся карточкой ──

def test_undo_within_five_minutes_returns_booking_to_confirmed(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "undo")
    assert _board(client, drv, bid).status_code == 200
    _age_mark(bid, 4)
    r = _undo(client, drv, bid)
    assert r.status_code == 200, r.text
    assert r.json() == {"ok": True, "status": "confirmed", "boarding_undo_sec": 0}
    b = _booking(bid)
    assert b.status == BookingStatus.confirmed and b.onboard_at is None
    # Пассажиру снимают тревогу тем же каналом, что сообщили о посадке.
    assert _notes(pax["id"], "Отметку посадки отменили") == 1
    # Повтор после успешной отмены — тот же итог, не ошибка.
    assert _undo(client, drv, bid).json()["status"] == "confirmed"
    # Бронь снова обычная: пассажир может отменить, а водитель — отметить «Сел» ещё раз.
    assert _board(client, drv, bid).json()["status"] == "onboard"


def test_undo_after_the_window_is_refused(client, user_factory):
    drv, _pax, _ride, bid = _trip(client, user_factory, "late")
    assert _board(client, drv, bid).status_code == 200
    _age_mark(bid, 6)
    r = _undo(client, drv, bid)
    assert r.status_code == 409, r.text
    assert "5 минут" in r.json()["detail"]["ru"] and "сообщи о проблеме" in r.json()["detail"]["ru"]
    assert r.json()["detail"]["ba"].strip()
    assert _booking(bid).status == BookingStatus.onboard
    row = next(x for x in client.get("/driver/bookings", headers=drv["auth"]).json() if x["booking_id"] == bid)
    assert row["boarding_undo_sec"] == 0


def test_only_the_driver_of_the_ride_can_undo(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "undowho")
    assert _board(client, drv, bid).status_code == 200
    stranger = user_factory("DMB Undo stranger", role=UserRole.driver)
    assert _undo(client, stranger, bid).status_code in (403, 404)
    assert _undo(client, pax, bid).status_code == 403
    assert _booking(bid).status == BookingStatus.onboard


def test_legacy_passenger_boarding_cannot_be_undone(client, user_factory):
    """«В пути» от прежнего «Я сел» (до 17.10, без onboard_at) — не отметка водителя."""
    drv, _pax, _ride, bid = _trip(client, user_factory, "legacy")
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.onboard
        s.add(b)
        s.commit()
    assert _undo(client, drv, bid).status_code == 409
    assert _booking(bid).status == BookingStatus.onboard


def test_undo_of_a_finished_trip_is_refused(client, user_factory):
    drv, _pax, _ride, bid = _trip(client, user_factory, "undodone")
    assert _board(client, drv, bid).status_code == 200
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    assert _undo(client, drv, bid).status_code == 409
    assert _booking(bid).status == BookingStatus.done


# ── Всё, что запускал «Я сел», запускает отметка водителя ──

def test_onboard_blocks_passenger_cancel_and_driver_no_show(client, user_factory):
    drv, pax, ride, bid = _trip(client, user_factory, "locks")
    assert _board(client, drv, bid).status_code == 200
    with Session(engine) as s:
        seats_before = s.get(Ride, ride["id"]).seats_left
    cancel = client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "plans_changed"})
    assert cancel.status_code == 409 and cancel.json()["detail"]["code"] == "onboard_no_cancel", cancel.text
    # Отказ видит прежде всего тот, кто НЕ садился, — и ведём его к разбору (приёмка PR174).
    assert cancel.json()["detail"]["ru"] == "Водитель отметил посадку. Не садился — сообщи о проблеме."
    assert cancel.json()["detail"]["ba"].strip() and cancel.json()["detail"]["ba"] != cancel.json()["detail"]["ru"]
    no_show = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert no_show.status_code == 409 and no_show.json()["detail"]["code"] == "passenger_onboard", no_show.text
    # В отказе — путь вперёд: «Отменить» в первые минуты или разбор; и сколько минут осталось —
    # карточка водителя показывает «Отменить» с верным отсчётом, а не сразу разбор.
    assert "Отменить" in no_show.json()["detail"]["ru"]
    assert 0 < no_show.json()["detail"]["boarding_undo_sec"] <= 300
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.onboard
        assert s.get(Ride, ride["id"]).seats_left == seats_before


def test_undo_reopens_the_no_show_path(client, user_factory):
    """Промахнулся карточкой у того, кто так и не пришёл: «Отменить» → «Не пришёл» работает."""
    # Выехал давно: «Не пришёл» — не раньше чем через NO_SHOW_WAIT после выезда (решение 18.10.2026).
    drv, _pax, _ride, bid = _trip(client, user_factory, "undonoshow", depart_in=-(NO_SHOW_WAIT + timedelta(minutes=1)))
    assert _board(client, drv, bid).status_code == 200
    assert _undo(client, drv, bid).status_code == 200
    r = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text


def test_mark_and_undo_poke_the_live_channel(client, user_factory, monkeypatch):
    import app.routers.bookings as bookings_mod

    pokes: list[int] = []
    monkeypatch.setattr(bookings_mod.trip_wake, "poke", lambda booking_id: pokes.append(booking_id))
    drv, _pax, _ride, bid = _trip(client, user_factory, "poke")
    assert _board(client, drv, bid).status_code == 200
    assert pokes == [bid], "«Сел» не толкнул канал координат"
    assert _board(client, drv, bid).status_code == 200
    assert pokes == [bid], "повторный тап толкать не должен — ничего не сменилось"
    assert _undo(client, drv, bid).status_code == 200
    assert pokes == [bid, bid], "«Отменить» не толкнул канал координат"


def test_mark_tells_the_passenger_and_the_family(client, user_factory, monkeypatch):
    import app.routers.family as fam

    sent: list[tuple[str, str]] = []
    monkeypatch.setattr(fam, "send_text", lambda phone, text: sent.append((phone, text)))
    drv, pax, _ride, bid = _trip(client, user_factory, "family")
    contact = client.post("/trusted-contacts", headers=pax["auth"], json={
        "name": "Мама", "relation": "мать", "phone": "+79990001717", "notify_by_default": True})
    assert contact.status_code == 200, contact.text
    assert client.post(f"/bookings/{bid}/share", headers=pax["auth"],
                       json={"contact_id": contact.json()["id"]}).status_code == 200
    sent.clear()

    assert _board(client, drv, bid).status_code == 200
    assert _notes(pax["id"], "Водитель отметил посадку") == 1
    texts = [t for p, t in sent if p == "+79990001717"]
    assert texts and "сел в машину" in texts[0], sent
    with Session(engine) as s:
        shares = s.exec(select(TripShare).where(TripShare.booking_id == bid)).all()
        assert [x.last_status for x in shares] == ["sat"]
    # Повтор отметки — без второго SMS (статус у близкого не сменился).
    assert _board(client, drv, bid).status_code == 200
    assert len([t for p, t in sent if p == "+79990001717"]) == 1


def test_undo_tells_the_family_once_and_cycles_burn_no_sms(client, user_factory, monkeypatch):
    """Близкий получил «сел в машину» — узнаёт и об отмене. Но цикл «Сел» / «Отменить» не жжёт
    SMS: на всю посадку не больше двух (сел → отменили), дальше — тишина. Повторное «сел» после
    отмены не шлём: иначе «Сел → Отменить → Сел → Отменить» оставлял у близкого ложное «сел в
    машину» (повторная приёмка PR174) — «ещё не в машине» ошибка безопаснее."""
    import app.routers.family as fam

    sent: list[tuple[str, str]] = []
    monkeypatch.setattr(fam, "send_text", lambda phone, text: sent.append((phone, text)))
    drv, pax, _ride, bid = _trip(client, user_factory, "familyundo")
    phone = "+79990001719"
    contact = client.post("/trusted-contacts", headers=pax["auth"], json={
        "name": "Мама", "relation": "мать", "phone": phone, "notify_by_default": True})
    assert contact.status_code == 200, contact.text
    assert client.post(f"/bookings/{bid}/share", headers=pax["auth"],
                       json={"contact_id": contact.json()["id"]}).status_code == 200
    sent.clear()

    def texts() -> list[str]:
        return [t for p, t in sent if p == phone]

    def share_status() -> str:
        with Session(engine) as s:
            return s.exec(select(TripShare).where(TripShare.booking_id == bid)).one().last_status

    assert _board(client, drv, bid).status_code == 200
    assert len(texts()) == 1 and "сел в машину" in texts()[0]
    assert _undo(client, drv, bid).status_code == 200
    assert len(texts()) == 2 and "отметку посадки отменили" in texts()[1], texts()
    assert share_status() == "unsat", "строка шаринга осталась «сел» после отмены"
    assert _board(client, drv, bid).status_code == 200
    assert len(texts()) == 2, f"второе «сел» после отмены ушло близкому: {texts()}"
    for _ in range(3):
        assert _undo(client, drv, bid).status_code == 200
        assert _board(client, drv, bid).status_code == 200
    assert _undo(client, drv, bid).status_code == 200
    # Цикл закончился отменой: последнее, что знает близкий, — «отметку посадки отменили».
    assert len(texts()) == 2 and "отметку посадки отменили" in texts()[-1], f"цикл жжёт SMS: {texts()}"
    assert share_status() == "unsat_quiet"


def test_boarding_pushes_ring_in_quiet_hours(client, user_factory, monkeypatch):
    """«Водитель отметил посадку» и «Отметку посадки отменили» — urgent, как «Выехал / Подъезжаю»:
    ранний рейс в 5 утра попадает в тихие часы, а действовать надо сейчас (приёмка PR174)."""
    import app.routers.bookings as bookings_mod

    calls: list[tuple[str, bool]] = []
    real = bookings_mod.push_notification

    def spy(*args, **kwargs):
        calls.append((args[3], bool(kwargs.get("urgent"))))
        return real(*args, **kwargs)

    monkeypatch.setattr(bookings_mod, "push_notification", spy)
    drv, _pax, _ride, bid = _trip(client, user_factory, "urgent")
    assert _board(client, drv, bid).status_code == 200
    assert _undo(client, drv, bid).status_code == 200
    assert ("Водитель отметил посадку", True) in calls, calls
    assert ("Отметку посадки отменили", True) in calls, calls


def test_passenger_can_report_a_problem_for_six_hours_after_the_trip(client, user_factory):
    """После завершения «Сообщить о проблеме» у пассажира ещё 6 часов от позднего из «выезд» и
    «Сел» (приёмка PR174): водитель отметил не того и закрыл поездку — разбор не потерян.
    Водителю окна нет; квитанция отдаёт вторую сторону для разбора."""
    drv, pax, ride, bid = _trip(client, user_factory, "report")
    assert _board(client, drv, bid).status_code == 200
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    six_hours = 6 * 3600
    pax_left = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["problem_report_sec"]
    assert six_hours - 60 < pax_left <= six_hours + 10 * 60 + 5, pax_left   # выезд через 10 минут
    assert client.get(f"/bookings/{bid}/role", headers=drv["auth"]).json()["problem_report_sec"] == 0
    pax_receipt = client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()
    drv_receipt = client.get(f"/trips/{bid}/receipt", headers=drv["auth"]).json()
    assert pax_receipt["counterparty_id"] == drv["id"] and pax_receipt["problem_report_sec"] > 0
    assert drv_receipt["counterparty_id"] == pax["id"] and drv_receipt["problem_report_sec"] == 0
    # Окно вышло: и выезд, и посадка, и закрытие поездки — семь часов назад.
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(hours=7)
        b = s.get(Booking, bid)
        b.onboard_at = utcnow() - timedelta(hours=7)
        b.done_at = utcnow() - timedelta(hours=7)
        s.add(r)
        s.add(b)
        s.commit()
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["problem_report_sec"] == 0
    assert client.get(f"/trips/{bid}/receipt", headers=pax["auth"]).json()["problem_report_sec"] == 0


def test_long_trip_keeps_the_report_window_from_its_completion(client, user_factory):
    """Долгая поездка: выезд и посадка восемь часов назад, водитель закрыл только сейчас. Окно
    «Сообщить о проблеме» считается и от закрытия (повторная приёмка PR174) — иначе его бы уже
    не было, хотя поездка только что кончилась."""
    drv, pax, ride, bid = _trip(client, user_factory, "longtrip")
    assert _board(client, drv, bid).status_code == 200
    with Session(engine) as s:
        r = s.get(Ride, ride["id"])
        r.depart_at = utcnow() - timedelta(hours=8)
        b = s.get(Booking, bid)
        b.onboard_at = utcnow() - timedelta(hours=8)
        s.add(r)
        s.add(b)
        s.commit()
    assert client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                       json={"status": "done"}).status_code == 200
    assert _booking(bid).done_at is not None
    left = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["problem_report_sec"]
    assert 6 * 3600 - 60 < left <= 6 * 3600, left
    # Тем же правилом — «Завершил» пассажира и «Завершить рейс» водителя ставят время закрытия.
    drv2, pax2, ride2, bid2 = _trip(client, user_factory, "longtrip2")
    assert _board(client, drv2, bid2).status_code == 200
    _age_mark(bid2, 6)
    assert client.post(f"/bookings/{bid2}/trip-status", headers=pax2["auth"], json={"status": "done"}).status_code == 200
    assert _booking(bid2).done_at is not None
    drv3, _pax3, ride3, bid3 = _trip(client, user_factory, "longtrip3")
    assert client.post(f"/rides/{ride3['id']}/complete", headers=drv3["auth"]).status_code == 200
    assert _booking(bid3).done_at is not None


def test_forgotten_mark_still_closes_with_drivers_finish(client, user_factory):
    """Водитель забыл отметить «Сел» — его «Завершить» закрывает поездку, как раньше."""
    drv, pax, _ride, bid = _trip(client, user_factory, "forgot")
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "done"})
    assert r.status_code == 200, r.text
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["status"] == "done"


# ── «Завершить» пассажира — только после отметки водителя ──

def _departed(ride_id: int, hours_ago: int = 2):
    """Время выезда прошло (через API поездку в прошлом не создать)."""
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=hours_ago)
        s.add(r)
        s.commit()


def test_passenger_cannot_finish_before_the_driver_marks_boarding(client, user_factory):
    """Та же неявка другой дверью: пассажир не пришёл, а после времени выезда сам «завершил»
    подтверждённую бронь прямым запросом — и «Не пришёл» водителю уже не поставить
    (решение лида по PR #174, 17.10.2026). Завершает пассажир только после «Сел» водителя."""
    drv, pax, ride, bid = _trip(client, user_factory, "paxdone")
    _departed(ride["id"])
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 409, f"пассажир закрыл бронь без посадки: {r.text}"
    detail = r.json()["detail"]
    assert detail["ru"] == "Поездку завершит водитель после посадки"
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    assert _booking(bid).status == BookingStatus.confirmed
    # Дверь к неявке открыта: водитель по-прежнему может отметить «Не пришёл».
    no_show = client.post(f"/bookings/{bid}/no-show", headers=drv["auth"])
    assert no_show.status_code == 200 and no_show.json()["status"] == "cancelled", no_show.text


def test_passenger_cannot_finish_an_unconfirmed_booking_either(client, user_factory):
    _drv, pax, ride, bid = _trip(client, user_factory, "paxdonepending", confirm=False)
    _departed(ride["id"])
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 409, r.text
    assert _booking(bid).status == BookingStatus.pending


def test_passenger_cannot_finish_inside_the_undo_window(client, user_factory):
    """«Завершил» в первую же секунду после «Сел» отнимал у водителя «Отменить»: промах карточкой
    становился необратимым (приёмка PR174). Пока окно открыто — понятный отказ, водитель отменяет."""
    drv, pax, _ride, bid = _trip(client, user_factory, "paxdoneearly")
    assert _board(client, drv, bid).status_code == 200
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 409, f"пассажир закрыл поездку, пока водитель мог отменить отметку: {r.text}"
    detail = r.json()["detail"]
    assert detail["code"] == "boarding_undo_open" and 0 < detail["boarding_undo_sec"] <= 300, detail
    assert "через 5 мин" in detail["ru"] and detail["ba"].strip() and detail["ba"] != detail["ru"]
    assert _booking(bid).status == BookingStatus.onboard
    assert _undo(client, drv, bid).json()["status"] == "confirmed", "окно отмены у водителя съели"


def test_driver_undo_landing_mid_finish_is_not_overwritten(client, user_factory, monkeypatch):
    """Граница окна: «Отменить» водителя пришло между проверкой и записью «Завершил». Запись
    условная (только из «в пути») — отмена не затирается завершением."""
    import app.routers.family as fam
    from sqlalchemy import update
    from sqlalchemy.orm import object_session

    drv, pax, _ride, bid = _trip(client, user_factory, "paxdonerace")
    assert _board(client, drv, bid).status_code == 200

    def undo_lands_now(booking, now=None):
        object_session(booking).execute(update(Booking).where(Booking.id == booking.id).values(
            status=BookingStatus.confirmed, onboard_at=None))
        return 0

    monkeypatch.setattr(fam, "boarding_undo_left", undo_lands_now)
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 409, r.text
    assert _booking(bid).status == BookingStatus.confirmed, "завершение затёрло отмену посадки"


def test_arrived_only_from_the_car(client, user_factory):
    """«Доехал» до отметки водителя слал близким «доехал до места» за человека, который не садился."""
    drv, pax, _ride, bid = _trip(client, user_factory, "arrived")
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "arrived"})
    assert r.status_code == 409 and r.json()["detail"]["ba"].strip(), r.text
    assert _board(client, drv, bid).status_code == 200
    # Окно «Отменить» водителя — как у «Завершил»: иначе близкий получал «доехал до места», а
    # отмена отметки оставляла строку шаринга «доехал» (повторная приёмка PR174).
    early = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "arrived"})
    assert early.status_code == 409, early.text
    detail = early.json()["detail"]
    assert detail["code"] == "boarding_undo_open" and "«Доехал» можно отметить через 5 мин" in detail["ru"], detail
    assert detail["ba"].strip() and detail["ba"] != detail["ru"]
    _age_mark(bid, 6)
    ok = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "arrived"})
    assert ok.status_code == 200, ok.text


def test_passenger_finishes_after_the_driver_marks_boarding(client, user_factory):
    drv, pax, _ride, bid = _trip(client, user_factory, "paxdoneok")
    assert _board(client, drv, bid).status_code == 200
    _age_mark(bid, 6)   # окно «Отменить» водителя прошло
    r = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 200, r.text
    assert _booking(bid).status == BookingStatus.done
    # Повтор по закрытой брони — тот же итог, не ошибка (очередь без сети шлёт повторно).
    again = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert again.status_code == 200, again.text


@pytest.mark.parametrize("status", ["departed", "arriving"])
def test_other_driver_phases_still_work(client, user_factory, status):
    """«Выехал / Подъезжаю» не задеты новой отметкой: фаза пишется, бронь остаётся подтверждённой."""
    drv, pax, _ride, bid = _trip(client, user_factory, f"phase-{status}")
    r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": status})
    assert r.status_code == 200 and r.json()["driver_phase"] == status, r.text
    state = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert state["status"] == "confirmed" and state["driver_phase"] == status
