"""Отмена в пути: четыре решения Александра 24.10.2026.

Пассажир уже в машине, водитель (или дежурный по звонку) снимает поездку. Раньше:

1. оценить водителя было нельзя — оценка только у завершённой: водитель уходил от плохого отзыва,
   отменив поездку перед прибытием;
2. водитель мог тут же удалить аккаунт — вместе с бронью, ссылкой близких и привязкой SOS, пока
   пассажир ещё стоит на трассе или только собирается «Сообщить о проблеме»;
3. взрослый, который отвечает за поездку несовершеннолетнего (`minor_guardian_phone`), ничего не узнавал;
4. дежурный снимал поднятую (Boost) поездку — остаток поднятия уезжал на счёт ДЕЖУРНОГО, а не водителя.

Последнюю точку машины близким после отмены — не делаем (решение «не делаем», задача не про это).
"""
from __future__ import annotations

import logging
import re
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import account, services
from app.config import settings
from app.db import engine
from app.models import (Booking, BookingStatus, DriverProfile, FamilySmsLog, Incident, Rating, Report, Ride,
                        SafetyProfile, SosEvent, TripShare, User, UserRole)
from app.rating_service import RATING_WINDOW_DAYS
from app.routers import family as family_router
from app.security import normalize_phone
from app.timeutil import utcnow
from app.visibility import PROBLEM_REPORT_WINDOW, cancelled_in_transit

pytestmark = pytest.mark.timeout(60)

PLATE = "К777КК102"
GUARD = "8 (917) 000-12-34"           # как ввёл подросток: со скобками и восьмёркой
GUARD_NORM = "+79170001234"
MAMA = "+79170000777"


@pytest.fixture
def spies(monkeypatch):
    """SMS (тот же шлюз, что у близких), сообщения дежурному и пуш «рейтинг просел»."""
    caught = {"sms": [], "tg": [], "advice": []}
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: caught["sms"].append((phone, text)))
    monkeypatch.setattr(services, "notify_admin_telegram",
                        lambda text, reply_markup=None: caught["tg"].append(text) or True)
    from app import quality
    monkeypatch.setattr(quality, "maybe_low_rating_advice", lambda s, uid, avg, now=None: caught["advice"].append(uid))
    # Весть дежурному об удалении — сразу, без фонового потока; троттл — с чистого листа.
    monkeypatch.setattr(account, "_run_in_background", lambda fn: fn())
    account._duty_pinged.clear()
    return caught


def _trip(client, user_factory, tag, *, minor=False, guardian=GUARD, contacts=(), pax_name=None, lang=""):
    """Водитель с машиной и пассажир; бронь подтверждена. Посадку отмечает тест (`_board`)."""
    driver = user_factory(f"Решения {tag} водитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=driver["id"], car_make="Лада", car_model="Веста",
                            car_color="серая", car_plate=PLATE))
        s.commit()
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    pax = user_factory(pax_name or f"Айгуль {tag}")
    if lang:
        with Session(engine) as s:
            u = s.get(User, pax["id"])
            u.language = lang
            s.add(u)
            s.commit()
    body = {"ride_id": ride["id"], "seats": 1}
    if minor:
        body.update(minor_passenger=True, minor_guardian_name="Гульнара", minor_guardian_phone=guardian)
    r = client.post("/bookings", headers=pax["auth"], json=body)
    assert r.status_code == 200, r.text
    bid = r.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    for phone, notify in contacts:
        cid = client.post("/trusted-contacts", headers=pax["auth"], json={
            "name": "Мама", "relation": "мама", "phone": phone, "notify_by_default": notify}).json()["id"]
        assert client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid}).status_code == 200
    return driver, pax, ride, bid


def _board(client, driver, bid):
    r = client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"})
    assert r.status_code == 200, r.text


def _cancel_in_transit(client, driver, bid):
    _board(client, driver, bid)
    r = client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text


def _details(client, who, bid):
    r = client.get(f"/bookings/{bid}/details", headers=who["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _edit_booking(bid, **fields):
    with Session(engine) as s:
        b = s.get(Booking, bid)
        for k, v in fields.items():
            setattr(b, k, v)
        s.add(b)
        s.commit()


def _edit_ride(ride_id, **fields):
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        for k, v in fields.items():
            setattr(r, k, v)
        s.add(r)
        s.commit()


# ═══════════════════════ 1. Оценка водителя после отмены в пути ═══════════════════════

def test_пассажир_оценивает_водителя_после_отмены_в_пути(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "оценка")
    _cancel_in_transit(client, driver, bid)

    d = _details(client, pax, bid)
    assert d["cancelled_in_transit"] is True and d["can_rate"] is True and d["my_stars"] == 0

    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 2, "tags": "rude"})
    assert r.status_code == 200, r.text
    assert r.json()["ratee_id"] == driver["id"]
    d = _details(client, pax, bid)
    assert d["my_stars"] == 2 and d["can_rate"] is True, "повтор обновляет оценку, как у завершённой"
    with Session(engine) as s:
        assert s.exec(select(DriverProfile).where(DriverProfile.user_id == driver["id"])).first().rating == 2.0, \
            "оценка после отмены в пути — в водительский балл, как у завершённой"
    # Водитель, только что высадивший человека, не получает тут же пуш «рейтинг просел» — по нему понятно, кто.
    assert spies["advice"] == []


def test_дежурный_снял_поездку_пассажир_тоже_оценивает(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "оценка-админ")
    _board(client, driver, bid)
    admin = user_factory("Решения дежурный", role=UserRole.admin)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    assert _details(client, pax, bid)["can_rate"] is True
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5}).status_code == 200


def test_чужой_и_второй_пассажир_не_оценивают(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "чужой")
    other = user_factory("Решения чужой")
    pax2 = user_factory("Решения второй пассажир")
    b2 = client.post("/bookings", headers=pax2["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{b2}/confirm", headers=driver["auth"]).status_code == 200
    _cancel_in_transit(client, driver, bid)

    assert client.post(f"/bookings/{bid}/rate", headers=other["auth"], json={"stars": 1}).status_code == 403
    assert client.post(f"/bookings/{bid}/rate", headers=pax2["auth"], json={"stars": 1}).status_code == 403, \
        "второй пассажир той же поездки оценивает по чужой брони"
    with Session(engine) as s:
        assert not s.exec(select(Rating).where(Rating.booking_id == bid)).all()


def test_водитель_после_своей_отмены_в_пути_не_оценивает(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "водитель")
    _cancel_in_transit(client, driver, bid)
    assert client.post(f"/bookings/{bid}/rate", headers=driver["auth"], json={"stars": 1}).status_code == 409
    d = _details(client, driver, bid)
    assert d["can_rate"] is False and d["my_stars"] == 0


def test_обычная_отмена_снятая_посадка_и_неявка_оценку_не_открывают(client, user_factory, spies):
    # До посадки.
    driver, pax, ride, bid = _trip(client, user_factory, "до-посадки")
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    assert _details(client, pax, bid)["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code == 409
    # Водитель снял отметку «Сел», потом отменил: `onboard_at` пуст — это не отмена в пути.
    driver, pax, ride, bid = _trip(client, user_factory, "снял-посадку")
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/boarded/undo", headers=driver["auth"]).status_code == 200
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    assert _details(client, pax, bid)["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code == 409
    # Неявка.
    driver, pax, ride, bid = _trip(client, user_factory, "неявка")
    _edit_booking(bid, status=BookingStatus.cancelled, no_show=True, cancel_reason="no_show",
                  cancelled_by=driver["id"], cancelled_at=utcnow())
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code == 409
    # Живая поездка — тоже нет.
    driver, pax, ride, bid = _trip(client, user_factory, "в-пути")
    _board(client, driver, bid)
    assert _details(client, pax, bid)["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5}).status_code == 409


def test_срок_оценки_как_у_завершённой(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "срок")
    _cancel_in_transit(client, driver, bid)
    _edit_ride(ride["id"], depart_at=utcnow() - timedelta(days=RATING_WINDOW_DAYS + 1))
    assert _details(client, pax, bid)["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code == 409


def test_пауза_аккаунта_как_у_завершённой(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "пауза")
    _cancel_in_transit(client, driver, bid)
    _edit_ride(ride["id"], depart_at=utcnow() - timedelta(days=3))
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=pax["id"], suspended_until=utcnow() + timedelta(days=7)))
        s.commit()
    assert _details(client, pax, bid)["can_rate"] is False
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 1}).status_code == 403


def test_детали_не_отдают_чужую_оценку(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "чужая-оценка")
    _cancel_in_transit(client, driver, bid)
    with Session(engine) as s:   # оценка водителя по этой брони (как будто до решения) — чужая для пассажира
        s.add(Rating(booking_id=bid, rater_id=driver["id"], ratee_id=pax["id"], stars=1))
        s.commit()
    d = _details(client, pax, bid)
    assert d["my_stars"] == 0 and d["can_rate"] is True


# ═══════════════════════ 2. Удаление аккаунта водителя после отмены в пути ═══════════════════════

def _delete(client, who):
    return client.post("/me/delete", headers=who["auth"])


def _alive(uid) -> bool:
    with Session(engine) as s:
        return s.get(User, uid) is not None


def test_сразу_после_отмены_в_пути_удаление_водителя_ждёт_шесть_часов(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "удаление", contacts=((MAMA, True),))
    _cancel_in_transit(client, driver, bid)
    with Session(engine) as s:
        s.add(SosEvent(user_id=pax["id"], booking_id=bid, category="other", status="handled"))
        s.commit()

    r = _delete(client, driver)
    assert r.status_code == 409, r.text
    d = r.json()["detail"]
    assert d["code"] == "transit_cancel_hold" and d["delete_after"]
    assert d["ru"] and d["ba"] and d["ru"] != d["ba"]
    assert d["ru"].startswith("Удалить аккаунт можно будет не раньше") and "Уфы" in d["ru"], \
        "срок — первым: на Android отказ — всплывающая строка, хвост обрезается"
    assert d["ba"].startswith("Аккаунтты юйыу — иң иртә")
    with Session(engine) as s:
        pax_user = s.get(User, pax["id"])
        assert pax_user.phone not in d["ru"] + d["ba"] and "Айгуль" not in d["ru"] + d["ba"]
    without_time = re.sub(r"\d{2}\.\d{2} \d{2}:\d{2}", "", d["ru"]).replace("6 часов", "")
    assert not any(ch.isdigit() for ch in without_time), "в отказе нет цифр, кроме срока"
    # Ничего не удалено: аккаунт, бронь, ссылка близких и SOS на месте.
    assert _alive(driver["id"])
    with Session(engine) as s:
        assert s.get(Booking, bid) is not None
        assert s.exec(select(TripShare).where(TripShare.booking_id == bid)).first() is not None
        assert s.exec(select(SosEvent).where(SosEvent.booking_id == bid)).first() is not None
    assert spies["tg"] == [] or all("удалить" not in t for t in spies["tg"]), "окно 6 ч — не повод тревожить дежурного"

    # Прошло 6 ч, разбора нет — удаление проходит.
    _edit_booking(bid, cancelled_at=utcnow() - PROBLEM_REPORT_WINDOW - timedelta(minutes=1))
    assert _delete(client, driver).status_code == 200
    assert not _alive(driver["id"])


@pytest.mark.parametrize("kind", ["incident", "report", "sos", "sos_without_booking", "roadside"])
def test_открытый_разбор_держит_удаление_до_закрытия(client, user_factory, spies, kind):
    driver, pax, ride, bid = _trip(client, user_factory, f"разбор-{kind}")
    _cancel_in_transit(client, driver, bid)
    with Session(engine) as s:
        if kind == "incident":
            row = Incident(booking_id=bid, reporter_id=pax["id"], respondent_id=driver["id"], type="unsafe",
                           reporter_role="passenger", status="awaiting_response")
        elif kind == "report":
            row = Report(reporter_id=pax["id"], target_user_id=driver["id"], booking_id=bid, category="other")
        elif kind == "sos":
            row = SosEvent(user_id=pax["id"], booking_id=bid, category="threat")
        elif kind == "sos_without_booking":   # экран SOS открыли без брони — сразу после отмены
            row = SosEvent(user_id=pax["id"], booking_id=None, category="threat", created_at=utcnow())
        else:
            r = client.post(f"/bookings/{bid}/stuck", headers=pax["auth"], json={})
            assert r.status_code == 200, r.text
            row = None
        if row is not None:
            s.add(row)
            s.commit()
    # И после 6 ч — разбор открыт, ждём (посадка — раньше отмены, как в жизни).
    _edit_booking(bid, cancelled_at=utcnow() - PROBLEM_REPORT_WINDOW - timedelta(hours=1),
                  onboard_at=utcnow() - PROBLEM_REPORT_WINDOW - timedelta(hours=2))
    if kind == "sos_without_booking":
        with Session(engine) as s:   # SOS — в окне 6 ч после отмены
            ev = s.exec(select(SosEvent).where(SosEvent.user_id == pax["id"], SosEvent.booking_id.is_(None))).first()
            ev.created_at = utcnow() - PROBLEM_REPORT_WINDOW
            s.add(ev)
            s.commit()
    r = _delete(client, driver)
    assert r.status_code == 409, r.text
    d = r.json()["detail"]
    assert d["code"] == "transit_cancel_review_open" and d["ru"] and d["ba"]
    assert _alive(driver["id"])
    # Дежурному — номер брони, без имён и телефонов; второй отказ подряд — без повтора.
    assert len(spies["tg"]) >= 1 and f"#{bid}" in spies["tg"][-1]
    with Session(engine) as s:
        names_phones = [s.get(User, pax["id"]).phone, s.get(User, driver["id"]).phone, "Айгуль", "водитель"]
    assert not any(x in spies["tg"][-1] for x in names_phones)
    pinged = len(spies["tg"])
    assert _delete(client, driver).status_code == 409
    assert len(spies["tg"]) == pinged, "дежурного тревожим не на каждое нажатие"

    # Разбор закрыли — удаление проходит.
    with Session(engine) as s:
        if kind == "incident":
            for inc in s.exec(select(Incident).where(Incident.booking_id == bid)).all():
                inc.status = "resolved"
                s.add(inc)
        elif kind == "report":
            for rep in s.exec(select(Report).where(Report.booking_id == bid)).all():
                rep.status = "rejected"
                s.add(rep)
        else:
            for ev in s.exec(select(SosEvent).where(SosEvent.user_id == pax["id"])).all():
                ev.status = "handled"
                s.add(ev)
        s.commit()
    assert _delete(client, driver).status_code == 200
    assert not _alive(driver["id"])


def test_sos_без_брони_нажатый_ещё_в_машине_держит(client, user_factory, spies):
    """SOS нажали в машине (экран SOS без брони), а потом водитель высадил — это разбор этой поездки."""
    driver, pax, ride, bid = _trip(client, user_factory, "сос-в-машине")
    _board(client, driver, bid)
    with Session(engine) as s:
        s.add(SosEvent(user_id=pax["id"], booking_id=None, category="threat", created_at=utcnow()))
        s.commit()
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    _edit_booking(bid, cancelled_at=utcnow() + timedelta(seconds=1) - PROBLEM_REPORT_WINDOW - timedelta(hours=1),
                  onboard_at=utcnow() - PROBLEM_REPORT_WINDOW - timedelta(hours=2))
    with Session(engine) as s:   # SOS — между посадкой и отменой
        ev = s.exec(select(SosEvent).where(SosEvent.user_id == pax["id"], SosEvent.booking_id.is_(None))).first()
        ev.created_at = utcnow() - PROBLEM_REPORT_WINDOW - timedelta(minutes=90)
        s.add(ev)
        s.commit()
    r = _delete(client, driver)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "transit_cancel_review_open", r.text
    assert r.json()["detail"]["ru"].startswith("Удалить аккаунт можно будет, когда закроют разбор")


def test_дежурному_номер_именно_той_брони_где_разбор(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "две-брони")
    pax2 = user_factory("Решения второй в машине")
    bid2 = client.post("/bookings", headers=pax2["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    _board(client, driver, bid)
    _board(client, driver, bid2)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"]).status_code == 200
    with Session(engine) as s:   # спор — только по второй брони
        s.add(Incident(booking_id=bid2, reporter_id=pax2["id"], respondent_id=driver["id"], type="unsafe",
                       reporter_role="passenger"))
        s.commit()
    assert _delete(client, driver).json()["detail"]["code"] == "transit_cancel_review_open"
    ping = spies["tg"][-1]
    assert re.search(rf"#{bid2}\b", ping) and not re.search(rf"#{bid}\b", ping), ping


def test_закрытый_спор_и_старый_sos_без_брони_не_держат(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "закрыто")
    _cancel_in_transit(client, driver, bid)
    _edit_booking(bid, cancelled_at=utcnow() - PROBLEM_REPORT_WINDOW - timedelta(hours=2))
    with Session(engine) as s:
        s.add(Incident(booking_id=bid, reporter_id=pax["id"], respondent_id=driver["id"], type="rude",
                       reporter_role="passenger", status="closed"))
        s.add(Report(reporter_id=pax["id"], target_user_id=driver["id"], booking_id=bid, status="resolved"))
        # SOS без брони, но через сутки — уже не про эту поездку.
        s.add(SosEvent(user_id=pax["id"], booking_id=None, created_at=utcnow()))
        s.commit()
    assert _delete(client, driver).status_code == 200


def test_разбор_по_чужой_брони_и_обычная_отмена_не_держат(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "чужой-разбор")
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    other_driver, other_pax, other_ride, other_bid = _trip(client, user_factory, "чужой-разбор-2")
    _cancel_in_transit(client, other_driver, other_bid)
    with Session(engine) as s:   # открытый спор — по брони ЧУЖОЙ поездки
        s.add(Incident(booking_id=other_bid, reporter_id=other_pax["id"], respondent_id=other_driver["id"],
                       type="unsafe", reporter_role="passenger"))
        s.add(Incident(booking_id=bid, reporter_id=pax["id"], respondent_id=driver["id"],
                       type="driver_no_show", reporter_role="passenger"))   # по обычной отмене — не отмена в пути
        s.commit()
    assert _delete(client, driver).status_code == 200


def test_пассажир_и_дежурный_удаляются_свободно(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "свободно")
    _board(client, driver, bid)
    admin = user_factory("Решения дежурный-2", role=UserRole.admin)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    r = _delete(client, driver)
    assert r.status_code == 409 and r.json()["detail"]["code"] == "transit_cancel_hold", "водителя — держит"
    assert _delete(client, admin).status_code == 200, "дежурный, который снял поездку, не водитель"
    # Пассажир уходит свободно — это его данные; его бронь уходит вместе с ним, и держать водителя
    # больше нечем (разбирать некому).
    assert _delete(client, pax).status_code == 200, "пассажира отмена в пути не держит"
    assert _delete(client, driver).status_code == 200


def test_без_момента_отмены_не_держим(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "без-момента")
    _cancel_in_transit(client, driver, bid)
    _edit_booking(bid, cancelled_at=None)
    assert _delete(client, driver).status_code == 200


def test_правило_в_базе_и_в_коде_одно(client, user_factory):
    """SQL-фильтр `account.transit_cancel_hold` и `visibility.cancelled_in_transit` считают одинаково."""
    driver = user_factory("Решения правило водитель", role=UserRole.driver)
    pax = user_factory("Решения правило пассажир")
    now = utcnow()
    with Session(engine) as s:
        ride = Ride(driver_id=driver["id"], from_city="Сибай", to_city="Уфа", price=500, seats_total=3,
                    seats_left=3, depart_at=now)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        rows = []
        for status in (BookingStatus.cancelled, BookingStatus.done, BookingStatus.confirmed):
            for onboard in (None, now):
                for by in (None, pax["id"], driver["id"], 999_999):
                    b = Booking(ride_id=ride.id, passenger_id=pax["id"], status=status, onboard_at=onboard,
                                cancelled_by=by, cancelled_at=now)
                    s.add(b)
                    rows.append(b)
        s.commit()
        for b in rows:
            s.refresh(b)
        expected = {b.id for b in rows if cancelled_in_transit(b)}
        hold = account.transit_cancel_hold(s, [ride.id])
        assert expected and hold is not None
        assert set(hold.booking_ids) == expected


# ═══════════════════════ 3. SMS взрослому за несовершеннолетнего ═══════════════════════

def _guard_sms(spies):
    return [(p, t) for p, t in spies["sms"] if normalize_phone(p) == GUARD_NORM]


def _guard_log(pax_id) -> int:
    with Session(engine) as s:
        return len(s.exec(select(FamilySmsLog).where(FamilySmsLog.user_id == pax_id,
                                                     FamilySmsLog.kind == "guardian")).all())


def test_отменили_в_пути_несовершеннолетнего_взрослому_одно_sms(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "взрослый", minor=True)
    _cancel_in_transit(client, driver, bid)

    sms = _guard_sms(spies)
    assert len(sms) == 1, spies["sms"]
    phone, text = sms[0]
    assert phone == GUARD_NORM, "SMS — на нормализованный номер"
    assert "отменили в пути" in text and "Айгуль" in text and "Позвони" in text
    with Session(engine) as s:
        pax_phone = s.get(User, pax["id"]).phone
    for leak in (pax_phone, PLATE, "Сибай", "Уфа", "Лада"):
        assert leak not in text, f"лишнее в SMS взрослому: {leak}"
    assert len(text) <= 134, "две части SMS, не три"
    assert _guard_log(pax["id"]) == 1
    # Повтор отмены — второго SMS и второй записи расхода нет.
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    assert len(_guard_sms(spies)) == 1 and _guard_log(pax["id"]) == 1


def test_sms_взрослому_по_башкирски_и_длинное_имя(client, user_factory, spies):
    long_name = "Гульшат-Айгуль Мухаметзяновна Кильдиярова"
    driver, pax, ride, bid = _trip(client, user_factory, "ба", minor=True, pax_name=long_name, lang="ba")
    _cancel_in_transit(client, driver, bid)
    (phone, text), = _guard_sms(spies)
    assert "юлда кире алынды" in text and "отменили" not in text
    assert long_name not in text and "…" in text, "имя обрезано"
    assert len(text) <= 134
    for lang in ("ru", "ba"):
        assert len(family_router.guardian_sms_text(User(name=long_name), lang)) <= 134


def test_заглушка_имени_взрослому_не_пишем(client, user_factory, spies):
    assert "пассажир" in family_router.guardian_sms_text(User(name="Пользователь"), "ru")
    assert "Telegram" not in family_router.guardian_sms_text(User(name="Telegram"), "ru")


@pytest.mark.parametrize("case", ["no_phone", "not_minor", "garbage", "short", "own_phone"])
def test_sms_взрослому_не_уходит(client, user_factory, spies, case):
    driver, pax, ride, bid = _trip(client, user_factory, f"нет-{case}", minor=(case != "not_minor"))
    if case == "no_phone":
        _edit_booking(bid, minor_guardian_phone="")
    elif case == "not_minor":   # номер подставлен руками в базе — не подросток, SMS нет
        _edit_booking(bid, minor_guardian_phone=GUARD)
    elif case == "garbage":
        _edit_booking(bid, minor_guardian_phone="мама")
    elif case == "short":
        _edit_booking(bid, minor_guardian_phone="12345")
    else:
        with Session(engine) as s:
            u = s.get(User, pax["id"])
            u.phone = GUARD_NORM
            s.add(u)
            s.commit()
    _cancel_in_transit(client, driver, bid)
    assert _guard_sms(spies) == [] and not [t for _p, t in spies["sms"] if "взрослым" in t]
    assert _guard_log(pax["id"]) == 0


def test_обычная_отмена_взрослому_не_пишет(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "обычная", minor=True)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    assert _guard_sms(spies) == []


def test_мама_и_взрослый_один_номер(client, user_factory, spies):
    # Мама в доверенных получает «отменили в пути» — второе SMS ей же как взрослому не идёт.
    driver, pax, ride, bid = _trip(client, user_factory, "мама", minor=True, contacts=((GUARD_NORM, True),))
    _cancel_in_transit(client, driver, bid)
    about_cancel = [t for _p, t in _guard_sms(spies) if "отменили в пути" in t]
    assert len(about_cancel) == 1 and "взрослым" not in about_cancel[0], spies["sms"]
    assert _guard_log(pax["id"]) == 0
    # «Только SOS» статусов не получает — а как взрослому за поездку SMS ей уходит.
    spies["sms"].clear()
    driver, pax, ride, bid = _trip(client, user_factory, "мама-сос", minor=True, contacts=((GUARD_NORM, False),))
    _cancel_in_transit(client, driver, bid)
    about_cancel = [t for _p, t in _guard_sms(spies) if "отменили в пути" in t]
    assert len(about_cancel) == 1 and "взрослым" in about_cancel[0], spies["sms"]


def test_потолок_sms_общий(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "потолок", minor=True)
    with Session(engine) as s:
        for _ in range(services.FAMILY_SMS_PER_DAY):
            s.add(FamilySmsLog(user_id=pax["id"], kind="status"))
        s.commit()
    _cancel_in_transit(client, driver, bid)
    assert _guard_sms(spies) == [] and _guard_log(pax["id"]) == 0


def test_снять_поездку_водителем_и_дежурным_тоже_пишет_взрослому(client, user_factory, spies):
    driver, pax, ride, bid = _trip(client, user_factory, "снять", minor=True)
    _board(client, driver, bid)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"]).status_code == 200
    assert len(_guard_sms(spies)) == 1
    spies["sms"].clear()
    driver, pax, ride, bid = _trip(client, user_factory, "снять-админ", minor=True)
    _board(client, driver, bid)
    admin = user_factory("Решения дежурный-3", role=UserRole.admin)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    assert len(_guard_sms(spies)) == 1


def test_сбой_sms_взрослому_не_роняет_отмену(client, user_factory, spies, monkeypatch):
    def boom(phone, text):
        raise RuntimeError("шлюз упал")
    monkeypatch.setattr(family_router, "send_text", boom)
    driver, pax, ride, bid = _trip(client, user_factory, "сбой", minor=True)
    _board(client, driver, bid)
    r = client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert any(f"#{bid}" in t for t in spies["tg"]), "дежурный узнал об отмене в пути"


def test_на_проде_без_sms_текст_не_в_логе(monkeypatch, caplog):
    """Шлюз тот же, что у близких: мок на проде пишет в лог только «тело скрыто»."""
    assert family_router.send_text is services.send_text
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "sms_provider", "mock")
    text = family_router.guardian_sms_text(User(name="Айгуль"), "ru")
    with caplog.at_level(logging.INFO):
        assert services.send_text(GUARD_NORM, text) is False
    assert "отменили в пути" not in caplog.text and GUARD_NORM not in caplog.text


# ═══════════════════════ 4. Boost при «Снять поездку» дежурным ═══════════════════════

def _boosted_ride(client, driver, hours=6):
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймаҡ", "to_city": "Сибай", "price": 300, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(hours=5)).isoformat()}).json()
    if hours:
        _edit_ride(ride["id"], boosted_until=utcnow() + timedelta(hours=hours))
    return ride


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


def test_дежурный_снял_поднятую_поездку_остаток_водителю(client, user_factory):
    driver = user_factory("Буст водитель", role=UserRole.driver)
    admin = user_factory("Буст дежурный", role=UserRole.admin)
    ride = _boosted_ride(client, driver, hours=6)
    _set_credit(driver["id"], 2 * 3600, utcnow() + timedelta(days=5))       # живой прежний остаток
    admin_until = utcnow() + timedelta(days=10)
    _set_credit(admin["id"], 3600, admin_until)                              # свой остаток у дежурного

    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200

    sec, until = _credit(driver["id"])
    assert abs(sec - 8 * 3600) < 10, f"водителю: прежние 2 ч + остаток 6 ч, а не {sec} с"
    assert until > utcnow() + timedelta(days=29)
    a_sec, a_until = _credit(admin["id"])
    assert a_sec == 3600 and a_until == admin_until, "у дежурного его остаток не меняется"
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).boosted_until is None


def test_просроченный_остаток_водителя_не_воскресает(client, user_factory):
    driver = user_factory("Буст водитель-2", role=UserRole.driver)
    admin = user_factory("Буст дежурный-2", role=UserRole.admin)
    ride = _boosted_ride(client, driver, hours=3)
    _set_credit(driver["id"], 10 * 3600, utcnow() - timedelta(days=1))
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    assert abs(_credit(driver["id"])[0] - 3 * 3600) < 10


def test_без_поднятия_строку_водителя_не_трогаем(client, user_factory):
    driver = user_factory("Буст водитель-3", role=UserRole.driver)
    admin = user_factory("Буст дежурный-3", role=UserRole.admin)
    ride = _boosted_ride(client, driver, hours=0)
    until = utcnow() + timedelta(days=4)
    _set_credit(driver["id"], 5 * 3600, until)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    assert _credit(driver["id"]) == (5 * 3600, until)
    assert _credit(admin["id"])[0] == 0


def test_сам_водитель_снял_как_раньше(client, user_factory):
    driver = user_factory("Буст водитель-4", role=UserRole.driver)
    ride = _boosted_ride(client, driver, hours=6)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"]).status_code == 200
    assert abs(_credit(driver["id"])[0] - 6 * 3600) < 10
