"""Два ответа Александра 24.10.2026 на вопросы приёмки отмены в пути.

1. **Предел удержания удаления.** Открытый разбор по брони, которую отменили в пути, держит удаление
   аккаунта водителя — но не дольше 30 дней от отмены. Дальше разбор удаление не держит (спор и улики
   второй стороны и так переживают удаление, обезличенными), а дежурный всё равно получает весть.
   Минимум 6 ч после отмены — как был.
2. **Boost на паузе.** Пауза аккаунта по решению разбора снимает его будущие поездки — и раньше остаток
   оплаченного поднятия на них сгорал. Теперь он ложится водителю же на счёт тем же путём, что при
   «Снять поездку», и срок остатка считается от конца паузы: во время паузы поездку не опубликовать.
   Никому другому остаток не достаётся.
"""
from __future__ import annotations

import re
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import account, services
from app.db import engine
from app.models import Booking, Incident, Ride, RideStatus, SafetyProfile, User, UserRole
from app.routers import family as family_router
from app.timeutil import utcnow
from app.visibility import PROBLEM_REPORT_WINDOW

from test_api import _ride

# Живые и поднятые поездки этих тестов не остаются в общей ленте (CI PR212).
from feed_hygiene import rides_do_not_leak_into_the_feed  # noqa: F401

pytestmark = pytest.mark.timeout(60)


@pytest.fixture
def tg(monkeypatch):
    caught = []
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: None)
    monkeypatch.setattr(services, "notify_admin_telegram", lambda text, reply_markup=None: caught.append(text) or True)
    monkeypatch.setattr(account, "_run_in_background", lambda fn: fn())
    account._duty_pinged.clear()
    return caught


# ═══════════════════════ 1. Предел 30 дней ═══════════════════════

def _in_transit_with_review(client, user_factory, tag, *, days_ago):
    """Водитель, пассажир в машине, водитель снял бронь; пассажир открыл спор. Отмена — `days_ago` дней назад."""
    driver = user_factory(f"Предел {tag} водитель", role=UserRole.driver)
    pax = user_factory(f"Предел {tag} пассажир")
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "price": 500, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00"}).json()
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    assert client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"}).status_code == 200
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    when = utcnow() - timedelta(days=days_ago)
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.onboard_at, b.cancelled_at = when - timedelta(hours=1), when
        s.add(b)
        s.add(Incident(booking_id=bid, reporter_id=pax["id"], respondent_id=driver["id"], type="unsafe",
                       reporter_role="passenger"))
        s.commit()
    return driver, pax, ride, bid


def test_разбор_держит_удаление_до_30_дней_и_называет_крайний_срок(client, user_factory, tg):
    driver, pax, ride, bid = _in_transit_with_review(client, user_factory, "10дней", days_ago=10)
    r = client.post("/me/delete", headers=driver["auth"])
    assert r.status_code == 409, r.text
    d = r.json()["detail"]
    assert d["code"] == "transit_cancel_review_open"
    assert d["ru"].startswith("Удалить аккаунт можно будет, когда закроют разбор, и не позже"), d["ru"]
    assert "30 дней" in d["ru"] and "30 көн" in d["ba"] and "иң һуңы" in d["ba"]
    with Session(engine) as s:
        cap = s.get(Booking, bid).cancelled_at + timedelta(days=30)
    assert d["delete_no_later_than"] == cap.isoformat()
    assert re.search(r"\d{2}\.\d{2} \d{2}:\d{2}", d["ru"]), "крайний срок — числом"
    pings = [t for t in tg if t.startswith("🗑")]
    assert len(pings) == 1 and f"#{bid}" in pings[0] and "не дольше 30 дней" in pings[0], tg


def test_после_30_дней_открытый_разбор_не_держит_а_дежурный_узнаёт(client, user_factory, tg):
    driver, pax, ride, bid = _in_transit_with_review(client, user_factory, "31день", days_ago=31)
    r = client.post("/me/delete", headers=driver["auth"])
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(User, driver["id"]) is None, "аккаунт не удалён"
        inc = s.exec(select(Incident).where(Incident.reporter_id == pax["id"])).first()
        assert inc is not None and inc.respondent_id is None, "спор пассажира пережил удаление обезличенным"
        names_phones = [s.get(User, pax["id"]).phone, "Предел"]
    pings = [t for t in tg if t.startswith("🗑")]
    assert len(pings) == 1 and f"#{bid}" in pings[0] and "удаляет данные" in pings[0], tg
    assert not any(x in pings[0] for x in names_phones)


def test_старый_разбор_не_держит_а_свежий_держит(client, user_factory, tg):
    """Две отмены в пути у одного водителя: разбор по старой (за пределом) не держит, по свежей — держит."""
    driver, pax, ride, old_bid = _in_transit_with_review(client, user_factory, "две", days_ago=40)
    pax2 = user_factory("Предел две пассажир-2")
    ride2 = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Уфа", "to_city": "Сибай", "price": 500, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(minutes=20)).isoformat() + "+00:00"}).json()
    bid2 = client.post("/bookings", headers=pax2["auth"], json={"ride_id": ride2["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    assert client.post(f"/bookings/{bid2}/driver-status", headers=driver["auth"], json={"status": "boarded"}).status_code == 200
    assert client.post(f"/bookings/{bid2}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid2)
        b.cancelled_at = utcnow() - timedelta(days=2)
        s.add(b)
        s.add(Incident(booking_id=bid2, reporter_id=pax2["id"], respondent_id=driver["id"], type="rude",
                       reporter_role="passenger"))
        s.commit()
    r = client.post("/me/delete", headers=driver["auth"])
    assert r.status_code == 409 and r.json()["detail"]["code"] == "transit_cancel_review_open"
    held = [t for t in tg if "просит удалить" in t]
    assert held and re.search(rf"#{bid2}\b", held[0]) and not re.search(rf"#{old_bid}\b", held[0])
    assert not [t for t in tg if "удаляет данные" in t], "удаление отказано — «удаляет данные» неправда"
    # Свежий разбор закрыли — удаление проходит, и только теперь дежурный узнаёт про старый открытый разбор.
    with Session(engine) as s:
        for inc in s.exec(select(Incident).where(Incident.booking_id == bid2)).all():
            inc.status = "resolved"
            s.add(inc)
        s.commit()
    assert client.post("/me/delete", headers=driver["auth"]).status_code == 200
    past = [t for t in tg if "удаляет данные" in t]
    assert past and re.search(rf"#{old_bid}\b", past[0]) and "факт разбора" in past[0]


def test_не_позже_считается_по_всем_отменам_в_пути(client, user_factory, tg):
    """Спор по старой отмене (предел через минуту) и свежая отмена без разбора: «не позже» — по свежей,
    по ней жалобу ещё могут подать (итоговая приёмка 24.10)."""
    driver, pax, ride, old_bid = _in_transit_with_review(client, user_factory, "непозже", days_ago=30)
    with Session(engine) as s:   # предел старой — через минуту
        b = s.get(Booking, old_bid)
        b.cancelled_at = utcnow() - timedelta(days=30) + timedelta(minutes=1)
        s.add(b)
        s.commit()
    pax2 = user_factory("Предел непозже пассажир-2")
    ride2 = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Уфа", "to_city": "Сибай", "price": 500, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(minutes=20)).isoformat() + "+00:00"}).json()
    bid2 = client.post("/bookings", headers=pax2["auth"], json={"ride_id": ride2["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    assert client.post(f"/bookings/{bid2}/driver-status", headers=driver["auth"], json={"status": "boarded"}).status_code == 200
    assert client.post(f"/bookings/{bid2}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    r = client.post("/me/delete", headers=driver["auth"])
    assert r.status_code == 409 and r.json()["detail"]["code"] == "transit_cancel_review_open"
    with Session(engine) as s:
        fresh_cap = s.get(Booking, bid2).cancelled_at + timedelta(days=30)
    assert r.json()["detail"]["delete_no_later_than"] == fresh_cap.isoformat()


def test_минимум_шесть_часов_остаётся(client, user_factory, tg):
    driver, pax, ride, bid = _in_transit_with_review(client, user_factory, "6ч", days_ago=0)
    with Session(engine) as s:   # разбор закрыт, но окно 6 ч ещё идёт
        for inc in s.exec(select(Incident).where(Incident.booking_id == bid)).all():
            inc.status = "closed"
            s.add(inc)
        s.commit()
    r = client.post("/me/delete", headers=driver["auth"])
    assert r.status_code == 409 and r.json()["detail"]["code"] == "transit_cancel_hold"
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.cancelled_at = utcnow() - PROBLEM_REPORT_WINDOW - timedelta(minutes=1)
        s.add(b)
        s.commit()
    assert client.post("/me/delete", headers=driver["auth"]).status_code == 200


# ═══════════════════════ 2. Boost на паузе ═══════════════════════

def _pause_via_review(client, admin, reporter, driver, bid, days=30, resolution="suspend"):
    """Настоящий путь, как в test_pause_reaches_tomorrows_ride: жалоба → разбор админа → пауза."""
    inc = client.post("/incidents", headers=reporter["auth"], json={
        "booking_id": bid, "type": "unsafe", "text": "гнал по встречке", "respondent_id": driver["id"]})
    assert inc.status_code == 200, inc.text
    body = {"resolution": resolution, "fault": "respondent", "note": "опасное вождение"}
    if days:
        body["suspend_days"] = days
    r = client.post(f"/admin/incidents/{inc.json()['id']}/resolve", headers=admin["auth"], json=body)
    assert r.status_code == 200, r.text


def _boost(ride_id, hours):
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.boosted_until = utcnow() + timedelta(hours=hours)
        s.add(r)
        s.commit()


def _credit(uid):
    with Session(engine) as s:
        u = s.get(User, uid)
        return u.boost_credit_sec, u.boost_credit_until


def _set_credit(uid, sec, until):
    with Session(engine) as s:
        u = s.get(User, uid)
        u.boost_credit_sec, u.boost_credit_until = sec, until
        s.add(u)
        s.commit()


@pytest.fixture
def разбор(client, user_factory):
    водитель = user_factory("БустПауза водитель", role=UserRole.driver)
    жалобщик = user_factory("БустПауза жалобщик")
    админ = user_factory("БустПауза админ", role=UserRole.admin)
    прошлый = _ride(client, водитель, comment="поездка с жалобой")
    bid = client.post("/bookings", headers=жалобщик["auth"], json={"ride_id": прошлый, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return водитель, жалобщик, админ, bid


def test_пауза_снимает_поднятую_поездку_остаток_водителю_до_конца_паузы_и_ещё_30_дней(client, user_factory, разбор, tg):
    водитель, жалобщик, админ, bid = разбор
    поднятая = _ride(client, водитель, comment="поднятая, без броней")
    _boost(поднятая, 6)
    _set_credit(водитель["id"], 3600, utcnow() + timedelta(days=3))   # живой прежний остаток — 1 ч

    _pause_via_review(client, админ, жалобщик, водитель, bid, days=30)

    with Session(engine) as s:
        ride = s.get(Ride, поднятая)
        prof = s.exec(select(SafetyProfile).where(SafetyProfile.user_id == водитель["id"])).first()
    assert ride.status == RideStatus.cancelled, "поездка без броней осталась живой после паузы"
    assert ride.boosted_until is None
    sec, until = _credit(водитель["id"])
    assert abs(sec - 7 * 3600) < 10, f"остаток 6 ч + прежний 1 ч, а на счету {sec} с"
    assert until >= prof.suspended_until + timedelta(days=30) - timedelta(seconds=5), \
        "остаток сгорит раньше, чем кончится пауза и пройдут 30 дней"
    assert _credit(админ["id"])[0] == 0 and _credit(жалобщик["id"])[0] == 0, "остаток достался не водителю"


def test_после_паузы_новая_поездка_выходит_поднятой(client, user_factory, разбор, tg):
    водитель, жалобщик, админ, bid = разбор
    поднятая = _ride(client, водитель, comment="поднятая до паузы")
    _boost(поднятая, 5)
    _pause_via_review(client, админ, жалобщик, водитель, bid, days=3)
    assert abs(_credit(водитель["id"])[0] - 5 * 3600) < 10
    with Session(engine) as s:   # пауза прошла
        prof = s.exec(select(SafetyProfile).where(SafetyProfile.user_id == водитель["id"])).first()
        prof.suspended_until = utcnow() - timedelta(minutes=1)
        s.add(prof)
        s.commit()
    new_id = _ride(client, водитель, comment="после паузы")
    with Session(engine) as s:
        new = s.get(Ride, new_id)
    assert new.boosted_until is not None and new.boosted_until > utcnow() + timedelta(hours=4, minutes=50)
    assert _credit(водитель["id"])[0] == 0


def test_бан_остаток_живёт_до_конца_бана_и_ещё_30_дней(client, user_factory, разбор, tg):
    водитель, жалобщик, админ, bid = разбор
    поднятая = _ride(client, водитель, comment="поднятая перед баном")
    _boost(поднятая, 2)
    _pause_via_review(client, админ, жалобщик, водитель, bid, days=0, resolution="ban")
    sec, until = _credit(водитель["id"])
    assert abs(sec - 2 * 3600) < 10 and until > utcnow() + timedelta(days=3650 + 29)


def test_идущая_поднятая_поездка_и_пауза_без_поднятия(client, user_factory, разбор, tg):
    водитель, жалобщик, админ, bid = разбор
    в_пути = _ride(client, водитель, comment="поднятая, уже в пути")
    _boost(в_пути, 4)
    with Session(engine) as s:
        r = s.get(Ride, в_пути)
        r.depart_at = utcnow() - timedelta(minutes=30)
        s.add(r)
        s.commit()
    until = utcnow() + timedelta(days=4)
    _set_credit(водитель["id"], 2 * 3600, until)
    _pause_via_review(client, админ, жалобщик, водитель, bid, days=7)
    with Session(engine) as s:
        r = s.get(Ride, в_пути)
    assert r.status == RideStatus.active and r.boosted_until is not None, "идущую поездку не рвём, поднятие на ней"
    assert _credit(водитель["id"]) == (2 * 3600, until), "без снятых поднятых поездок счёт не трогаем"


def test_срок_остатка_не_укорачивается_обычной_отменой_после_паузы():
    """Остаток, отложенный до конца паузы, не укорачивает следующая обычная отмена."""
    now = utcnow()
    driver = User(phone="+79990000002", name="Водитель")
    first = Ride(driver_id=1, from_city="А", to_city="Б", price=1, seats_total=1, seats_left=1,
                 depart_at=now + timedelta(hours=5), boosted_until=now + timedelta(hours=3))
    services.stash_boost_credit(driver, first, now, usable_from=now + timedelta(days=20))
    long_until = driver.boost_credit_until
    assert long_until >= now + timedelta(days=50) - timedelta(seconds=1)
    second = Ride(driver_id=1, from_city="А", to_city="Б", price=1, seats_total=1, seats_left=1,
                  depart_at=now + timedelta(hours=5), boosted_until=now + timedelta(hours=1))
    services.stash_boost_credit(driver, second, now)
    assert driver.boost_credit_sec == 4 * 3600
    assert driver.boost_credit_until == long_until
