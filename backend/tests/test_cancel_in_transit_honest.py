"""Отмена, когда пассажир уже в машине, — честная (решение Александра 23.10.2026).

Водителю отмена «в пути» оставлена (решение 14.10: «сломалась машина, высадил за поведение»).
Но после неё человек, возможно, стоит на трассе, а система говорила неправду:

* страница слежения у близких показывала «Поездка завершена ✅»;
* близкие ничего не получали, хотя о посадке им уходило SMS;
* пассажиру — пуш «посмотри другие поездки», экран закрывался, «Сообщить о проблеме» не было;
* в SOS близким пропадал госномер — ровно тогда, когда он нужнее всего;
* «Снять поездку» отменяла и тех, кто в машине, без замков — «Сел» в ту же секунду расходился с отменой.

Признак один на всех (`visibility.cancelled_in_transit`): отменена, посадка отмечена и не снята
(`onboard_at`), отменил не пассажир (ему «в пути» отмена закрыта). Координат после отмены ссылка
не отдаёт по-прежнему — где высадили, скажет сам человек (SOS, «Застрял на трассе»).
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.routers.safety as safety
from app import services
from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, UserRole
from app.routers import family as family_router
from app.routers.bookings import PROBLEM_REPORT_WINDOW
from app.timeutil import utcnow
from app.visibility import cancelled_in_transit
from test_live_link import _FINISHED_KEYS, coord_like_values

pytestmark = pytest.mark.timeout(30)

PLATE = "М555ОР102"
MAMA = "+79170000777"
ONLY_SOS = "+79170000778"


def _trip(client, user_factory, tag: str, monkeypatch, *, contacts=((MAMA, True),)):
    """Водитель с машиной, пассажир с близкими и ссылкой слежения; бронь подтверждена."""
    sms, tg, pushes = [], [], []
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: sms.append((phone, text)))
    monkeypatch.setattr(services, "notify_admin_telegram", lambda text, reply_markup=None: tg.append(text) or True)
    real_push = services.push_notification

    def spy_push(session, user_id, kind, title_ru, title_ba, body_ru, body_ba, **kw):
        pushes.append({"user": user_id, "title": title_ru, "title_ba": title_ba, "body": body_ru,
                       "body_ba": body_ba, "urgent": kw.get("urgent", False)})
        return real_push(session, user_id, kind, title_ru, title_ba, body_ru, body_ba, **kw)

    import app.routers.bookings as bookings_router
    import app.routers.rides as rides_router
    monkeypatch.setattr(bookings_router, "push_notification", spy_push)
    monkeypatch.setattr(rides_router, "push_notification", spy_push)

    driver = user_factory(f"Отмена {tag} водитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=driver["id"], car_make="Лада", car_model="Веста",
                            car_color="серая", car_plate=PLATE))
        s.commit()
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    pax = user_factory(f"Айгуль {tag}")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    tokens = []
    for phone, notify in contacts:
        cid = client.post("/trusted-contacts", headers=pax["auth"], json={
            "name": "Мама", "relation": "мама", "phone": phone, "notify_by_default": notify}).json()["id"]
        r = client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid})
        assert r.status_code == 200, r.text
        tokens.append(r.json()["token"])
    sms.clear()
    return driver, pax, ride, bid, tokens[0] if tokens else None, sms, tg, pushes


def _board(client, driver, bid):
    r = client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"})
    assert r.status_code == 200, r.text


def _state(client, token):
    r = client.get(f"/t/{token}/state.json")
    assert r.status_code == 200, r.text
    return r.json()


# ── Отмена брони водителем, когда пассажир в машине ──

def test_близкие_видят_отмену_в_пути_а_не_завершена(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "ссылка", monkeypatch)
    _board(client, driver, bid)
    assert _state(client, token)["status"] == "onboard"

    r = client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text

    body = _state(client, token)
    assert body["status"] == "finished"                 # страница любой версии перестаёт опрашивать
    assert body["outcome"] == "cancelled_onboard"
    assert "отменили в пути" in body["phase_text"]["ru"] and "позвони" in body["phase_text"]["ru"]
    assert "✅" not in body["phase_text"]["ru"] and body["phase_text"]["ba"] and "✅" not in body["phase_text"]["ba"]
    assert body["car"] is None and coord_like_values(body) == []   # где высадили — не дело ссылки
    assert set(body) == _FINISHED_KEYS
    page = client.get(f"/t/{token}").text
    assert 'id="done-icon"' in page and "cancelled_onboard" in page


def test_близкому_одно_sms_а_только_sos_не_тревожим(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(
        client, user_factory, "смс", monkeypatch, contacts=((MAMA, True), (ONLY_SOS, False)))
    _board(client, driver, bid)
    sms.clear()

    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "safety"}).status_code == 200

    to_mama = [t for p, t in sms if p == MAMA]
    assert len(to_mama) == 1, sms
    assert "отменили в пути" in to_mama[0] and "Позвони" in to_mama[0] and "Айгуль" in to_mama[0]
    assert not [t for p, t in sms if p == ONLY_SOS], "контакт «Только SOS» получил статус поездки"
    # Повтор отмены (двойной тап, повтор после потери ответа) — второго SMS нет.
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "safety"}).status_code == 200
    assert len([t for p, t in sms if p == MAMA]) == 1


def test_пассажиру_срочный_пуш_с_путём_к_помощи_дежурному_весть(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "пуш", monkeypatch)
    _board(client, driver, bid)
    pushes.clear()

    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"],
                       json={"reason": "passenger_late"}).status_code == 200

    mine = [p for p in pushes if p["user"] == pax["id"]]
    assert len(mine) == 1, pushes
    push = mine[0]
    assert push["urgent"] is True
    assert "в пути" in push["title"] and "SOS" in push["body"] and "Застрял на трассе" in push["body"]
    assert push["title_ba"] and push["body_ba"] and push["body_ba"] != push["body"]
    assert "другие поездки" not in push["body"]
    assert "опоздал" not in push["body"].lower(), "причина «до посадки» в пути — неправда"
    assert tg and f"#{bid}" in tg[-1] and "в пути" in tg[-1]
    assert MAMA not in tg[-1] and "52." not in tg[-1], "дежурному — без телефонов и координат"


def test_приложение_знает_об_отмене_в_пути_госномер_остаётся_телефон_нет(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "клиент", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200

    role = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert role["status"] == "cancelled" and role["cancelled_in_transit"] is True
    # Поспорить с такой отменой — его право: «Сообщить о проблеме» открыта от момента отмены.
    assert 0 < role["problem_report_sec"] <= int(PROBLEM_REPORT_WINDOW.total_seconds())

    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert details["cancelled_in_transit"] is True
    assert details["driver_plate"] == PLATE and details["driver_car_color"] == "серая"
    assert details["driver_phone"] == "" and details["contact_unlocked"] is False

    mine = client.get("/bookings/mine", headers=pax["auth"]).json()
    row = next(b for b in mine if b["id"] == bid)
    assert row["cancelled_in_transit"] is True


def test_sos_после_отмены_в_пути_даёт_близким_госномер(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "sos", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200

    sos_sms = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sos_sms.append((list(phones), text)))
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: True)
    r = client.post("/sos", headers=pax["auth"], json={"category": "other", "note": "", "booking_id": bid})
    assert r.status_code == 200, r.text
    to_family = [t for phones, t in sos_sms if MAMA in phones]
    assert to_family and PLATE in to_family[0], f"госномер не дошёл до близких: {sos_sms}"


# ── Что НЕ считается отменой в пути ──

def test_отмена_до_посадки_честно_отменена_без_тревоги(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "до", monkeypatch)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200

    body = _state(client, token)
    assert body["status"] == "finished" and body["outcome"] == "cancelled"
    assert body["phase_text"]["ru"] == "Поездка отменена" and "✅" not in body["phase_text"]["ru"]
    assert not sms and not tg
    role = client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()
    assert role["cancelled_in_transit"] is False and role["problem_report_sec"] == 0
    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert details["driver_plate"] == "", "отменённая до посадки бронь госномер не открывает (16.10)"


def test_снятая_отметка_посадки_не_отмена_в_пути(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "снял", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/boarded/undo", headers=driver["auth"]).status_code == 200
    sms.clear()
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    assert _state(client, token)["outcome"] == "cancelled"
    assert not [t for p, t in sms if "отменили в пути" in t]


def test_завершённая_поездка_по_прежнему_завершена(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "готово", monkeypatch)
    _board(client, driver, bid)
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    body = _state(client, token)
    assert body["outcome"] == "done" and "завершена ✅" in body["phase_text"]["ru"]


def test_признак_один_на_всех_и_не_ловит_пассажира():
    """Пассажиру «в пути» отмена закрыта, но старая строка с его отменой — не «высадили»."""
    now = utcnow()
    b = Booking(id=1, ride_id=1, passenger_id=7, seats=1, price=1, status=BookingStatus.cancelled,
                onboard_at=now, cancelled_by=9)
    assert cancelled_in_transit(b) is True
    b.cancelled_by = 7
    assert cancelled_in_transit(b) is False
    b.cancelled_by, b.onboard_at = 9, None
    assert cancelled_in_transit(b) is False
    b.onboard_at, b.status = now, BookingStatus.done
    assert cancelled_in_transit(b) is False


# ── «Снять поездку», когда в машине люди ──

def test_снять_поездку_с_пассажиром_в_машине(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "снять", monkeypatch)
    _board(client, driver, bid)
    other = user_factory("Отмена снять второй")
    bid2 = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    sms.clear()
    pushes.clear()

    r = client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"])
    assert r.status_code == 200, r.text

    assert _state(client, token)["outcome"] == "cancelled_onboard"
    assert len([t for p, t in sms if p == MAMA and "отменили в пути" in t]) == 1
    by_user = {p["user"]: p for p in pushes}
    assert "в пути" in by_user[pax["id"]]["title"] and by_user[pax["id"]]["urgent"] is True
    # Кто ещё ждал у дороги — прежний текст: ему правда пора искать другую поездку.
    assert "другие поездки" in by_user[other["id"]]["body"]
    with Session(engine) as s:
        assert cancelled_in_transit(s.get(Booking, bid)) is True
        assert cancelled_in_transit(s.get(Booking, bid2)) is False


def test_кабинет_знает_поездку_каждой_брони_для_предупреждения(client, user_factory, monkeypatch):
    """«Снять поездку» в кабинете предупреждает «В машине пассажиры: N» — для этого брони
    водителя должны знать свою поездку (раньше в /driver/bookings был только маршрут текстом:
    две поездки Сибай → Уфа были неразличимы)."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "кабинет", monkeypatch)
    _board(client, driver, bid)
    rows = client.get("/driver/bookings", headers=driver["auth"]).json()
    row = next(r for r in rows if r["booking_id"] == bid)
    assert row["ride_id"] == ride["id"] and row["status"] == "onboard"


def test_окно_разбора_считается_от_отмены(client, user_factory, monkeypatch):
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "окно", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.cancelled_at = utcnow() - PROBLEM_REPORT_WINDOW - timedelta(minutes=1)
        s.add(b)
        s.commit()
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["problem_report_sec"] == 0
