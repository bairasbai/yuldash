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

import json
import subprocess
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

import app.routers.safety as safety
from app import services, trip_wake
from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, TripShare, User, UserRole
from app.routers import family as family_router
from app.routers.bookings import PROBLEM_REPORT_WINDOW, notify_cancelled_in_transit
from app.routers.family import _family_step, notify_family_trip_status
from app.timeutil import utcnow
from app.visibility import cancelled_in_transit
from test_live_link import _FINISHED_KEYS, coord_like_values
from test_share_network_recovery import HARNESS, NODE, _page_script

pytestmark = pytest.mark.timeout(30)

PLATE = "М555ОР102"
MAMA = "+79170000777"
ONLY_SOS = "+79170000778"
SISTER = "+79170000779"


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
    # Повтор отмены (двойной тап, повтор после потери ответа) — второго SMS нет, второго пуша
    # пассажиру и второго сообщения дежурному — тоже.
    pushes_before, tg_before = len(pushes), len(tg)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "safety"}).status_code == 200
    assert len([t for p, t in sms if p == MAMA]) == 1
    assert len(pushes) == pushes_before and len(tg) == tg_before == 1


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
    with Session(engine) as s:
        phones = [s.get(User, pax["id"]).phone, s.get(User, driver["id"]).phone]
    assert all(phone and phone not in tg[-1] for phone in phones), "телефоны пассажира и водителя — не в Telegram"


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


def test_админ_снимает_поездку_с_пассажиром_в_машине(client, user_factory, monkeypatch):
    """Дежурный снимает поездку по звонку (`_ride_owned` пускает админа) — правда та же."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "админ", monkeypatch)
    _board(client, driver, bid)
    admin = user_factory("Отмена админ дежурный", role=UserRole.admin)
    sms.clear()
    pushes.clear()

    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200

    assert len([t for p, t in sms if p == MAMA and "отменили в пути" in t]) == 1, sms
    assert [p["title"] for p in pushes if p["user"] == pax["id"]] == ["Поездку отменили в пути"]
    assert len(tg) == 1 and f"#{bid}" in tg[0]
    assert client.get(f"/bookings/{bid}/role", headers=pax["auth"]).json()["cancelled_in_transit"] is True
    # Повтор (двойной тап) — ничего нового никому.
    seen = (len(sms), len(pushes), len(tg))
    assert client.post(f"/rides/{ride['id']}/cancel", headers=admin["auth"]).status_code == 200
    assert (len(sms), len(pushes), len(tg)) == seen


def test_госномер_после_отмены_в_пути_не_навсегда(client, user_factory, monkeypatch):
    """Номер машины нужен в те же часы, что и связь после поездки, — дальше это архив чужой машины."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "срок", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.cancelled_at = utcnow() - timedelta(hours=settings.chat_after_trip_hours, minutes=1)
        s.add(b)
        s.commit()

    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert details["cancelled_in_transit"] is True                  # что случилось — помним
    assert details["driver_plate"] == "" and details["driver_car_color"] == ""

    sos_sms = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sos_sms.append((list(phones), text)))
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: True)
    assert client.post("/sos", headers=pax["auth"], json={
        "category": "other", "note": "", "booking_id": bid}).status_code == 200
    assert sos_sms and all(PLATE not in text for _, text in sos_sms), sos_sms


# ── Гонка «Сел» и отмены (приёмка PR205) ──

def test_запоздавшая_отметка_посадки_после_отмены_в_пути_молчит(client, user_factory, monkeypatch):
    """«Сел» сохранился, и в ту же секунду водитель снял поездку: отмена успела сказать пассажиру и
    маме «отменили в пути». Запоздавшие «Водитель отметил посадку» и «сел в машину» после неё были
    бы неправдой. Отмену вклиниваем ровно туда, где была дыра: сразу после сохранения посадки."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "гонка", monkeypatch)
    real_poke = trip_wake.poke
    cancelled = []

    def cancel_right_after_boarding(booking_id):
        real_poke(booking_id)
        if booking_id != bid or cancelled:
            return
        cancelled.append(booking_id)
        with Session(engine) as s:
            b = s.get(Booking, bid)
            assert b.status == BookingStatus.onboard          # посадка уже в базе
            b.status, b.cancelled_at, b.cancelled_by = BookingStatus.cancelled, utcnow(), driver["id"]
            s.add(b)
            s.commit()
            s.refresh(b)
            notify_cancelled_in_transit(s, b, s.get(Ride, b.ride_id))

    monkeypatch.setattr(trip_wake, "poke", cancel_right_after_boarding)
    pushes.clear()
    r = client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled", r.text

    assert cancelled == [bid]
    to_mama = [t for p, t in sms if p == MAMA]
    assert len(to_mama) == 1 and "отменили в пути" in to_mama[0], f"маме после отмены ушло ещё: {to_mama}"
    assert [p["title"] for p in pushes if p["user"] == pax["id"]] == ["Поездку отменили в пути"]
    assert _state(client, token)["outcome"] == "cancelled_onboard"


def test_после_отмены_в_пути_близким_больше_ничего_не_уходит(client, user_factory, monkeypatch):
    """Строка шаринга «отменили в пути» — финал: ни запоздавшее «сел», ни «отметку посадки
    отменили», ни повтор самой отмены ничего не шлют и строку не переписывают."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "финал", monkeypatch)
    _board(client, driver, bid)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    sms.clear()
    with Session(engine) as s:
        passenger = s.get(User, pax["id"])
        for status in ("sat", "unsat", "arrived", "cancelled_onboard"):
            notify_family_trip_status(s, bid, passenger, status)
        rows = s.exec(select(TripShare).where(TripShare.booking_id == bid)).all()
    assert not sms, sms
    assert rows and {row.last_status for row in rows} == {"cancelled_onboard"}


def test_сел_по_отменённой_брони_не_уходит_а_снятие_отметки_уходит(client, user_factory, monkeypatch):
    """«Сел в машину» про отменённую бронь — неправда, даже если до посадки дело не дошло. А вот
    «отметку посадки отменили» шлём и по отменённой: иначе последним словом у мамы осталось бы «сел»."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "устар", monkeypatch)
    assert client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}).status_code == 200
    with Session(engine) as s:
        passenger = s.get(User, pax["id"])
        notify_family_trip_status(s, bid, passenger, "sat")
        assert not sms, f"«сел в машину» по отменённой брони: {sms}"
        for row in s.exec(select(TripShare).where(TripShare.booking_id == bid)).all():
            row.last_status = "sat"                   # маме уже ушло «сел» до отмены
            s.add(row)
        s.commit()
        notify_family_trip_status(s, bid, passenger, "unsat")
    assert len(sms) == 1 and "отметку посадки отменили" in sms[0][1], sms


@pytest.mark.parametrize("status", ["sat", "unsat", "arrived", "done", "cancelled_onboard"])
def test_отменили_в_пути_последнее_слово_лестницы(status):
    assert _family_step("cancelled_onboard", status) == ("cancelled_onboard", False)


# ── Значок на странице слежения (исполняем JS самой страницы, как test_share_network_recovery) ──

_ICON_JS = r"""
tests.cancelled_onboard_warns = async function() {
  const st = {status: 'finished', outcome: 'cancelled_onboard', passenger_first_name: 'Айгуль',
              phase_text: {ru: 'Поездку отменили в пути — позвони и узнай, всё ли в порядке', ba: 'Сәфәр юлда кире алынды'}};
  const p = page([{status: 200, body: live}, {status: 200, body: st}]);
  await p.flush(); await p.advance(5000);
  assert.equal(p.nodes['done-icon'].textContent, '⚠️');
  assert.equal(p.nodes.done.style.display, 'flex');
  assert.equal(p.cars, 0);
  assert.match(p.nodes.title.textContent, /отменили в пути/);
};
tests.cancelled_informs = async function() {
  const p = page([{status: 200, body: {status: 'finished', outcome: 'cancelled',
                                       phase_text: {ru: 'Поездка отменена', ba: 'Сәфәр кире алынды'}}}]);
  await p.flush();
  assert.equal(p.nodes['done-icon'].textContent, 'ℹ️');
};
tests.done_and_old_server_stay_green = async function() {
  for (const st of [{status: 'finished', outcome: 'done', phase_text: {ru: 'Поездка завершена ✅', ba: '✅'}},
                    {status: 'finished', phase_text: {ru: 'Поездка завершена ✅', ba: '✅'}}]) {
    const p = page([{status: 200, body: st}]);
    await p.flush();
    assert.equal(p.nodes['done-icon'].textContent, '✅');
  }
};
"""


@pytest.mark.skipif(NODE is None, reason="Для исполнения JS публичной страницы нужен Node.js")
@pytest.mark.parametrize("scenario", ["cancelled_onboard_warns", "cancelled_informs", "done_and_old_server_stay_green"])
def test_значок_страницы_слежения(scenario):
    assert HARNESS.count("tests[__SCENARIO__]()") == 1
    code = HARNESS.replace("tests[__SCENARIO__]()", _ICON_JS + "\ntests[__SCENARIO__]()")
    code = code.replace("__PAGE_SCRIPT__", json.dumps(_page_script())).replace("__SCENARIO__", json.dumps(scenario))
    result = subprocess.run([NODE, "-"], input=code, capture_output=True, text=True, timeout=10, encoding="utf-8")
    assert result.returncode == 0, f"{scenario}: {result.stderr.strip()}"


# ── Сбой шага SMS близким не роняет отмену (приёмка PR205, повторная) ──

def test_сбой_смс_близким_не_роняет_снятие_поездки(client, user_factory, monkeypatch):
    """Отмена уже сохранена — что бы ни случилось с SMS близким (база, исчезнувшая строка шаринга),
    водитель получает ответ, дежурный — весть, а кто ждал у дороги — «Поездка отменена». Раньше
    исключение на этом шаге давало водителю 500, остальным — тишину, а повтор отмены уже ничего не
    досылал: отмена сохранена, повтор ничего не делает."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(client, user_factory, "сбой", monkeypatch)
    _board(client, driver, bid)
    other = user_factory("Отмена сбой второй у дороги")
    bid2 = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    pushes.clear()

    def broken_family_step(*_a, **_kw):
        raise RuntimeError("база недоступна")

    monkeypatch.setattr(family_router, "notify_family_trip_status", broken_family_step)
    r = client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"])

    assert r.status_code == 200, r.text
    assert len(tg) == 1 and f"#{bid}" in tg[0], tg
    by_user = {}
    for p in pushes:
        by_user.setdefault(p["user"], []).append(p["title"])
    assert by_user.get(pax["id"]) == ["Поездку отменили в пути"], by_user
    assert by_user.get(other["id"]) == ["Поездка отменена"], by_user
    with Session(engine) as s:
        assert s.get(Booking, bid).status == BookingStatus.cancelled
        assert s.get(Booking, bid2).status == BookingStatus.cancelled


def test_ссылку_отозвали_сразу_после_отправки_статус_не_падает(client, user_factory, monkeypatch):
    """Строку шаринга удалили (отозвали ссылку сестры) сразу после того, как статус ушёл и
    сохранился. Перечитывание каждой строки по одной падало на удалённой — и с ним весь запрос:
    «Доехал», «Сел», отмена в пути (приёмка PR205). Теперь ответ — оставшиеся строки."""
    driver, pax, ride, bid, token, sms, tg, pushes = _trip(
        client, user_factory, "отзыв", monkeypatch, contacts=((MAMA, True), (SISTER, True)))
    _board(client, driver, bid)
    with Session(engine) as s:
        rows = s.exec(select(TripShare).where(TripShare.booking_id == bid).order_by(TripShare.id)).all()
        mama_id, sister_id = rows[0].id, rows[1].id
    sms.clear()
    sent, revoked = [], []
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: sent.append((phone, text)))

    def revoke_after_commit(_session):
        if sent and not revoked:          # статус ушёл и сохранён — пассажир отзывает ссылку сестры
            revoked.append(sister_id)
            with Session(engine) as other:
                other.delete(other.get(TripShare, sister_id))
                other.commit()

    with Session(engine) as s:
        event.listen(s, "after_commit", revoke_after_commit)
        result = notify_family_trip_status(s, bid, s.get(User, pax["id"]), "arrived")
        ids = [row.id for row in result]
    assert revoked == [sister_id]
    assert ids == [mama_id], ids
    assert len(sent) == 2 and all("доехал" in t for _, t in sent), sent
