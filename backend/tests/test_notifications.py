"""Центр уведомлений (F5): типы событий, бейдж непрочитанного, пометка прочитанным, приватность.

Уведомления пишутся в тех же местах, где шлётся push (services.push_notification).
Тесты фиксируют контракт `/notifications` ({unread, items}) и `/notifications/read`.
"""
from app.models import UserRole


# ----------------------------- helpers -----------------------------
def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _notes(client, u):
    r = client.get("/notifications", headers=u["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _types(feed):
    return {n["type"] for n in feed["items"]}


# ----------------------------- права / базовый контракт -----------------------------
def test_notifications_requires_auth(client):
    assert client.get("/notifications").status_code == 401
    assert client.post("/notifications/read", json={"all": True}).status_code == 401


def test_notifications_shape(client, user_factory):
    u = user_factory("EmptyNotif")
    feed = _notes(client, u)
    assert feed == {"unread": 0, "items": []}   # у нового юзера событий нет


# ----------------------------- события создают уведомления нужного типа -----------------------------
def test_booking_created_notifies_driver(client, user_factory):
    drv = user_factory("Bn Drv", role=UserRole.driver)
    pax = user_factory("Bn Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    feed = _notes(client, drv)
    assert feed["unread"] >= 1
    top = feed["items"][0]                       # непрочитанное сверху, новое первым
    assert top["type"] == "booking"
    assert top["ref_kind"] == "booking" and top["ref_id"] == booking["id"]
    assert top["title_ru"] == "Новая бронь" and top["title_ba"]   # обе языковые версии
    assert top["read"] is False


def test_confirm_notifies_passenger(client, user_factory):
    drv = user_factory("Cn Drv", role=UserRole.driver)
    pax = user_factory("Cn Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"])
    feed = _notes(client, pax)
    assert any(n["type"] == "booking" and n["title_ru"] == "Бронь подтверждена"
               and n["ref_id"] == booking["id"] for n in feed["items"])


def test_cancel_notifies_other_party(client, user_factory):
    drv = user_factory("XC Drv", role=UserRole.driver)
    pax = user_factory("XC Pax")
    ride = _publish(client, drv, seats=2)
    booking = _book(client, pax, ride["id"], seats=2)
    # пассажир отменяет → уведомление водителю
    client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"])
    feed = _notes(client, drv)
    assert any(n["type"] == "booking" and n["title_ru"] == "Бронь отменена" for n in feed["items"])


def test_driver_status_notifies_passenger(client, user_factory):
    drv = user_factory("Ds Drv", role=UserRole.driver)
    pax = user_factory("Ds Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    client.post(f"/bookings/{booking['id']}/driver-status", headers=drv["auth"], json={"status": "departed"})
    client.post(f"/bookings/{booking['id']}/driver-status", headers=drv["auth"], json={"status": "done"})
    feed = _notes(client, pax)
    titles = {n["title_ru"] for n in feed["items"] if n["type"] == "ride"}
    assert "Водитель выехал" in titles
    assert "Поездка завершена" in titles


def test_message_notifies_other_party(client, user_factory):
    drv = user_factory("Mn Drv", role=UserRole.driver)
    pax = user_factory("Mn Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    client.post(f"/bookings/{booking['id']}/messages", headers=pax["auth"], json={"text": "Я на месте"})
    feed = _notes(client, drv)
    msg = next(n for n in feed["items"] if n["type"] == "message")
    assert msg["ref_kind"] == "booking" and msg["ref_id"] == booking["id"]
    assert "Я на месте" in msg["body_ru"]


def test_request_respond_and_accept_notifications(client, user_factory):
    pax = user_factory("Rq Pax")
    drv = user_factory("Rq Drv", role=UserRole.driver)
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": "Учалы", "to_city": "Уфа", "seats": 1}).json()
    # водитель откликается → пассажиру уведомление ride, ref=request
    resp = client.post(f"/requests/{req['id']}/respond", headers=drv["auth"],
                       json={"price": 500, "comment": "Поеду"}).json()
    pf = _notes(client, pax)
    r_note = next(n for n in pf["items"] if n["type"] == "ride" and n["title_ru"] == "Отклик на заявку")
    assert r_note["ref_kind"] == "request" and r_note["ref_id"] == req["id"]
    # пассажир принимает → водителю уведомление ride, ref=booking
    acc = client.post(f"/responses/{resp['id']}/accept", headers=pax["auth"]).json()
    df = _notes(client, drv)
    a_note = next(n for n in df["items"] if n["type"] == "ride" and n["title_ru"] == "Заявку приняли")
    assert a_note["ref_kind"] == "booking" and a_note["ref_id"] == acc["booking_id"]


# ----------------------------- бейдж / порядок / пометка прочитанным -----------------------------
def test_unread_first_and_badge_then_mark(client, user_factory):
    drv = user_factory("Um Drv", role=UserRole.driver)
    pax = user_factory("Um Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])              # +1 booking водителю
    client.post(f"/bookings/{booking['id']}/messages", headers=pax["auth"], json={"text": "привет"})  # +1 message
    feed = _notes(client, drv)
    assert feed["unread"] == len(feed["items"]) >= 2      # всё пока непрочитано
    assert all(n["read"] is False for n in feed["items"])
    # пометить одно прочитанным
    first_id = feed["items"][0]["id"]
    r = client.post("/notifications/read", headers=drv["auth"], json={"id": first_id})
    assert r.status_code == 200 and r.json()["unread"] == feed["unread"] - 1
    after = _notes(client, drv)
    assert next(n for n in after["items"] if n["id"] == first_id)["read"] is True
    # непрочитанные идут выше прочитанного
    assert after["items"][0]["read"] is False
    assert after["items"][-1]["read"] is True
    # пометить все
    r2 = client.post("/notifications/read", headers=drv["auth"], json={"all": True})
    assert r2.status_code == 200 and r2.json()["unread"] == 0
    assert _notes(client, drv)["unread"] == 0


# ----------------------------- приватность: чужие уведомления недоступны -----------------------------
def test_notifications_are_private(client, user_factory):
    drv = user_factory("Pv Drv", role=UserRole.driver)
    pax = user_factory("Pv Pax")
    outsider = user_factory("Pv Out")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])              # уведомление уходит ТОЛЬКО водителю
    # посторонний не видит ничего
    assert _notes(client, outsider) == {"unread": 0, "items": []}
    # пассажир (не адресат брони-уведомления) не видит booking-уведомление водителя
    assert all(n["ref_id"] != booking["id"] or n["type"] != "booking" for n in _notes(client, pax)["items"])
    # посторонний не может «прочитать» чужое — его пометка не трогает чужой бейдж
    drv_before = _notes(client, drv)["unread"]
    client.post("/notifications/read", headers=outsider["auth"], json={"all": True})
    assert _notes(client, drv)["unread"] == drv_before
