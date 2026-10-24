"""Отмена в пути и «Сел» в одну секунду — на настоящих замках PostgreSQL (приёмка PR205).

Водитель жмёт «Сел» и тут же «Снять поездку» (или дежурный снимает её по звонку). Запросы идут
одновременно, и раньше маме могло прийти «отменили в пути», а следом — запоздавшее «сел в машину».
Порядок держат замок строк шаринга (`family.notify_family_trip_status`) и перепроверка брони после
сохранения посадки (`bookings._mark_boarded`). На SQLite замков нет — эти проверки только для
Postgres; детерминированный вариант той же гонки — в `test_cancel_in_transit_honest.py`.
"""
from __future__ import annotations

import threading
import time
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import event
from sqlmodel import Session, select

from app import services
from app.db import engine
from app.main import app
from app.models import Booking, BookingStatus, DriverProfile, TripShare, UserRole
from app.routers import family as family_router
from app.timeutil import utcnow

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="гонка на замках — только PostgreSQL"),
    pytest.mark.timeout(60),
]

MAMA = "+79170000991"
SISTER = "+79170000992"


@pytest.fixture
def spies(monkeypatch):
    """SMS маме, сообщения дежурному и пуши — с замком: их пишут параллельные запросы."""
    lock = threading.Lock()
    caught = {"sms": [], "tg": [], "pushes": [], "lock": lock}

    def sms(phone, text):
        with lock:
            caught["sms"].append((phone, text))

    def tg(text, reply_markup=None):
        with lock:
            caught["tg"].append(text)
        return True

    real_push = services.push_notification

    def push(session, user_id, kind, title_ru, title_ba, body_ru, body_ba, **kw):
        with lock:
            caught["pushes"].append((user_id, title_ru))
        return real_push(session, user_id, kind, title_ru, title_ba, body_ru, body_ba, **kw)

    import app.routers.bookings as bookings_router
    import app.routers.rides as rides_router
    monkeypatch.setattr(family_router, "send_text", sms)
    monkeypatch.setattr(services, "notify_admin_telegram", tg)
    monkeypatch.setattr(bookings_router, "push_notification", push)
    monkeypatch.setattr(rides_router, "push_notification", push)
    return caught


def _trip(client, user_factory, tag):
    driver = user_factory(f"Гонка {tag} водитель", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=driver["id"], car_make="Лада", car_model="Веста",
                            car_color="серая", car_plate="А111АА102"))
        s.commit()
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00",
        "seats_total": 3, "price": 500}).json()
    pax = user_factory(f"Гонка {tag} пассажир")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    cid = client.post("/trusted-contacts", headers=pax["auth"], json={
        "name": "Мама", "relation": "мама", "phone": MAMA, "notify_by_default": True}).json()["id"]
    assert client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid}).status_code == 200
    return driver, pax, ride, bid


def _together(*calls):
    """Все запросы стартуют в один момент: барьер держит потоки до последнего."""
    out = [None] * len(calls)
    barrier = threading.Barrier(len(calls))

    def run(i, fn):
        barrier.wait(timeout=10)
        out[i] = fn()

    threads = [threading.Thread(target=run, args=(i, fn)) for i, fn in enumerate(calls)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    return out


def _clear(spies):
    for key in ("sms", "tg", "pushes"):
        spies[key].clear()


@pytest.mark.parametrize("attempt", range(8))
def test_снять_поездку_и_сел_в_одну_секунду(client, user_factory, spies, attempt):
    driver, pax, ride, bid = _trip(client, user_factory, f"сел{attempt}")
    _clear(spies)
    cancel, board = _together(
        lambda: client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"]),
        lambda: client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"}),
    )
    assert cancel.status_code == 200, cancel.text
    assert board.status_code in (200, 409), board.text
    with Session(engine) as s:
        b = s.get(Booking, bid)
        assert b.status == BookingStatus.cancelled
        in_transit = b.onboard_at is not None
    titles = [t for u, t in spies["pushes"] if u == pax["id"]]
    to_mama = [t for p, t in spies["sms"] if p == MAMA]
    if in_transit:          # «Сел» успел раньше отмены — значит, отмена в пути
        assert titles.count("Поездку отменили в пути") == 1 and "Поездка отменена" not in titles, titles
        assert len(spies["tg"]) == 1, spies["tg"]
        assert to_mama and "отменили в пути" in to_mama[-1], f"последним маме ушло не «отменили»: {to_mama}"
        assert len([t for t in to_mama if "отменили в пути" in t]) == 1
    else:                   # отмена раньше «Сел» — обычная отмена, посадки не было
        assert "Поездка отменена" in titles and "Поездку отменили в пути" not in titles, titles
        assert not spies["tg"] and not to_mama, (spies["tg"], to_mama)


@pytest.mark.parametrize("attempt", range(4))
def test_снять_поездку_и_отменить_бронь_в_пути_разом(client, user_factory, spies, attempt):
    """Две отмены одной брони в пути: пассажиру, маме и дежурному — по одному сообщению."""
    driver, pax, ride, bid = _trip(client, user_factory, f"две{attempt}")
    assert client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"],
                       json={"status": "boarded"}).status_code == 200
    _clear(spies)
    r1, r2 = _together(
        lambda: client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"]),
        lambda: client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": "other"}),
    )
    assert r1.status_code == 200 and r2.status_code == 200, (r1.text, r2.text)
    titles = [t for u, t in spies["pushes"] if u == pax["id"]]
    assert titles == ["Поездку отменили в пути"], titles
    assert len([t for p, t in spies["sms"] if p == MAMA and "отменили в пути" in t]) == 1, spies["sms"]
    assert len(spies["tg"]) == 1, spies["tg"]


def test_медленный_шлюз_смс_не_переставляет_сообщения_маме(client, user_factory, spies, monkeypatch):
    """Шлюз SMS отвечает медленно: «сел в машину» ещё в пути, а водитель уже снял поездку. Без
    замка строк шаринга «отменили в пути» обгоняло «сел» — и последним словом у мамы было «сел»."""
    driver, pax, ride, bid = _trip(client, user_factory, "шлюз")
    _clear(spies)
    sending_sat = threading.Event()

    def slow_gateway(phone, text):
        if "сел в машину" in text:
            sending_sat.set()
            time.sleep(0.5)
        with spies["lock"]:
            spies["sms"].append((phone, text))

    monkeypatch.setattr(family_router, "send_text", slow_gateway)
    out = {}
    board = threading.Thread(target=lambda: out.update(r=client.post(
        f"/bookings/{bid}/driver-status", headers=driver["auth"], json={"status": "boarded"})))
    board.start()
    assert sending_sat.wait(10), "«сел в машину» так и не начали отправлять"
    cancel = client.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"])
    board.join(30)
    assert out["r"].status_code == 200 and cancel.status_code == 200, (out["r"].text, cancel.text)
    to_mama = [t for p, t in spies["sms"] if p == MAMA]
    assert len(to_mama) == 2 and "сел в машину" in to_mama[0] and "отменили в пути" in to_mama[1], to_mama


def test_двойное_доехал_при_медленном_шлюзе_одно_смс(client, user_factory, spies, monkeypatch):
    """«Доехал» нажали дважды, пока шлюз отвечает на первое. Замки поездки и брони статус
    отпускает до отправки SMS, и второй запрос доходит до строк шаринга, пока первое «доехал» ещё
    не сохранено. Без замка строк он читал старое «сел» — и мама получала «доехал» дважды за счёт
    платформы."""
    driver, pax, ride, bid = _trip(client, user_factory, "доехал")
    assert client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"],
                       json={"status": "boarded"}).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.onboard_at = utcnow() - timedelta(minutes=10)          # окно «Отменить» водителя прошло
        s.add(b)
        s.commit()
    _clear(spies)
    sending = threading.Event()

    def slow_gateway(phone, text):
        if "доехал" in text:
            sending.set()
            time.sleep(0.5)
        with spies["lock"]:
            spies["sms"].append((phone, text))

    monkeypatch.setattr(family_router, "send_text", slow_gateway)
    arrived = {"status": "arrived"}
    out = {}
    first = threading.Thread(target=lambda: out.update(r=client.post(
        f"/bookings/{bid}/trip-status", headers=pax["auth"], json=arrived)))
    first.start()
    assert sending.wait(10), "«доехал» так и не начали отправлять"
    second = client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json=arrived)
    first.join(30)
    assert out["r"].status_code == 200 and second.status_code == 200, (out["r"].text, second.text)
    assert len([t for p, t in spies["sms"] if p == MAMA and "доехал" in t]) == 1, spies["sms"]


def test_снять_поездку_пока_отзывают_ссылку_сестры(client, user_factory, spies, monkeypatch):
    """Пока отмена шлёт SMS близким (замок строк шаринга взят), пассажир отзывает ссылку сестры:
    отзыв ждёт замка и удаляет строку сразу после commit отмены. Раньше перечитывание удалённой
    строки давало водителю 500, дежурному — ничего, а второму пассажиру у дороги — тишину."""
    driver, pax, ride, bid = _trip(client, user_factory, "отзыв")
    cid = client.post("/trusted-contacts", headers=pax["auth"], json={
        "name": "Сестра", "relation": "сестра", "phone": SISTER, "notify_by_default": True}).json()["id"]
    assert client.post(f"/bookings/{bid}/share", headers=pax["auth"], json={"contact_id": cid}).status_code == 200
    with Session(engine) as s:
        sister_share = s.exec(select(TripShare.id).where(TripShare.booking_id == bid, TripShare.contact_id == cid)).one()
    assert client.post(f"/bookings/{bid}/driver-status", headers=driver["auth"],
                       json={"status": "boarded"}).status_code == 200
    other = user_factory("Гонка отзыв второй у дороги")
    bid2 = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"]).status_code == 200
    _clear(spies)
    api = TestClient(app, raise_server_exceptions=False)     # 500 — ответом, а не исключением теста
    state = {"thread": None, "tid": None, "revoke": None}

    def slow_gateway(phone, text):
        with spies["lock"]:
            spies["sms"].append((phone, text))
        if "отменили в пути" in text and state["thread"] is None:
            state["tid"] = threading.get_ident()
            revoke = threading.Thread(target=lambda: state.update(revoke=api.delete(
                f"/bookings/{bid}/share/{sister_share}", headers=pax["auth"])))
            state["thread"] = revoke
            revoke.start()
            time.sleep(0.5)                                    # отзыв упирается в замок строки

    def after_commit(_session):
        if state["thread"] and threading.get_ident() == state["tid"]:
            revoke, state["thread"] = state["thread"], False
            revoke.join(10)                                    # замок отпущен — строка удалена

    monkeypatch.setattr(family_router, "send_text", slow_gateway)
    event.listen(Session, "after_commit", after_commit)
    try:
        cancel = api.post(f"/rides/{ride['id']}/cancel", headers=driver["auth"])
    finally:
        event.remove(Session, "after_commit", after_commit)
    assert state["thread"] is False and state["revoke"].status_code == 200, state
    assert cancel.status_code == 200, cancel.text
    assert len(spies["tg"]) == 1, spies["tg"]
    titles = {}
    for user_id, title in spies["pushes"]:
        titles.setdefault(user_id, []).append(title)
    assert titles.get(pax["id"]) == ["Поездку отменили в пути"], titles
    assert titles.get(other["id"]) == ["Поездка отменена"], titles
