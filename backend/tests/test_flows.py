"""Регресс-тесты пользовательских сценариев Юлдаша по доменам.

Тонкий smoke проверял «что-то работает». Эти тесты фиксируют КОНТРАКТЫ
(формы ответов, права доступа, бизнес-правила), чтобы рефакторинги их не сломали.
Лимитер в тестах выключен (conftest).
"""
import base64
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Ride
from app.models import UserRole
from app.timeutil import utcnow


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


def just_left() -> str:
    """Время выезда «только что» (UTC с явным поясом).

    Завершить поездку можно лишь у НАЧАВШЕЙСЯ — обе двери к этому переходу закрыты планкой
    (аудит 2026-08-07: цикл «опубликовал на 2030 год → забронировал вторым аккаунтом →
    завершил» рисовал бейдж «N поездок» без единого метра пути). Поэтому тесты, которые
    доводят поездку до конца, ездят «прямо сейчас», как в жизни, а не в 2030 году."""
    return (utcnow() - timedelta(minutes=1)).replace(microsecond=0).isoformat() + "+00:00"


def _trip(client, user_factory, depart_at: str | None = None):
    """Готовая поездка: водитель, поездка, пассажир, бронь. Возвращает (drv, pax, ride, booking).

    `depart_at` по умолчанию будущий (так тесты про бронь/отмену ближе к жизни). Тестам,
    которые ЗАВЕРШАЮТ поездку, нужен `just_left()`."""
    drv = user_factory("Drv", role=UserRole.driver)
    pax = user_factory("Pax")
    ride = _publish(client, drv, **({"depart_at": depart_at} if depart_at else {}))
    booking = _book(client, pax, ride["id"])
    return drv, pax, ride, booking


# ----------------------------- поездки -----------------------------
def test_ride_out_shape(client, user_factory):
    drv = user_factory("RideDrv", role=UserRole.driver)
    _publish(client, drv, frm="Темясово", to="Уфа", price=1400)
    rides = client.get("/rides", params={"from_city": "Темясово"}).json()
    assert rides and {"from_city", "driver_name", "driver_rating", "driver_verified", "seats_left"} <= set(rides[0])


def test_ride_filters(client, user_factory):
    # gender="female": отметку «только женщины» ставит женщина за рулём (аудит 2026-08-08).
    drv = user_factory("FiltDrv", role=UserRole.driver, gender="female")
    _publish(client, drv, frm="Акъяр", to="Сибай", women_only=True)
    assert all(r["women_only"] for r in client.get("/rides", params={"from_city": "Акъяр", "women_only": True}).json())


def test_price_hint(client, user_factory):
    drv = user_factory("PHDrv", role=UserRole.driver)
    _publish(client, drv, frm="Зилаир", to="Уфа", price=900)
    body = client.get("/rides/price_hint", params={"from_city": "Зилаир", "to_city": "Уфа"}).json()
    # Обратная совместимость: старые поля на месте.
    assert body["count"] >= 1 and body["avg"] > 0


def test_price_hint_fuel_estimate_known_cities(client):
    """Справедливая цена: для известных городов (справочник БашРТ) считаются
    distance_km и fuel_estimate_kop = км × расход × цена бензина."""
    from app.config import settings
    from app.services import geocode_city, haversine_km

    body = client.get("/rides/price_hint", params={"from_city": "Уфа", "to_city": "Сибай"}).json()
    assert body["distance_km"] is not None and body["distance_km"] > 0
    assert body["fuel_estimate_kop"] is not None and body["fuel_estimate_kop"] > 0

    # Сверяем с формулой контракта напрямую.
    f, t = geocode_city("Уфа"), geocode_city("Сибай")
    dist = round(haversine_km(f[0], f[1], t[0], t[1]), 1)
    expected_kop = round(dist * (settings.fuel_consumption_l_per_100km / 100.0) * settings.fuel_price_rub_per_liter * 100)
    assert body["distance_km"] == dist
    assert body["fuel_estimate_kop"] == expected_kop


def test_price_hint_fuel_null_unknown_city(client):
    """Нет координат хотя бы одного конца → distance_km / fuel_estimate_kop = null (без краша)."""
    body = client.get("/rides/price_hint", params={"from_city": "Уфа", "to_city": "ГородКоторогоНет"}).json()
    assert body["distance_km"] is None
    assert body["fuel_estimate_kop"] is None


def test_rides_near_distance(client, user_factory):
    drv = user_factory("NearDrv", role=UserRole.driver)
    _publish(client, drv, frm="Сибай", to="Уфа")
    # координаты Уфы — дистанция до Сибая должна посчитаться
    body = client.get("/rides/near", params={"from_city": "Сибай", "lat": 54.735, "lng": 55.958}).json()
    assert body["count"] >= 1
    assert any(it.get("distance_km") is not None for it in body["items"])


def test_requests_near_requires_auth(client, user_factory):
    pax = user_factory("ReqNearPax")
    client.post("/requests", headers=pax["auth"], json={"from_city": "Баймак", "to_city": "Сибай", "comment": "без телефона"})
    assert client.get("/requests/near").status_code == 401
    body = client.get("/requests/near", headers=pax["auth"]).json()
    assert body["count"] >= 1
    assert all("phone" not in item for item in body["items"])


def test_get_ride_404(client):
    assert client.get("/rides/99999999").status_code == 404


def test_public_ride_outputs_hide_pickup_until_booking_confirmed(client, user_factory):
    drv = user_factory("PrivacyDrv", role=UserRole.driver)
    ride = _publish(
        client,
        drv,
        frm="ПриватГрад",
        to="Уфа",
        pickup="Подъезд 3, дом 12",
        pickup_lat=52.12345,
        pickup_lng=58.54321,
    )

    one = client.get(f"/rides/{ride['id']}").json()
    assert one["pickup"] == ""
    assert one["pickup_lat"] is None
    assert one["pickup_lng"] is None

    rows = client.get("/rides", params={"from_city": "ПриватГрад"}).json()
    public_row = next(r for r in rows if r["id"] == ride["id"])
    assert public_row["pickup"] == ""
    assert public_row["pickup_lat"] is None
    assert public_row["pickup_lng"] is None

    near = client.get("/rides/near", params={"from_city": "ПриватГрад", "lat": 52.1, "lng": 58.5}).json()
    near_row = next(r for r in near["items"] if r["id"] == ride["id"])
    assert near_row["pickup"] == ""
    assert near_row["pickup_lat"] is None
    assert near_row["pickup_lng"] is None


def test_rides_pagination(client, user_factory):
    drv = user_factory("PgDrv", role=UserRole.driver)
    city = "ПагинГрад"
    # Пять РАЗНЫХ поездок: одинаковые схлопываются как двойной тап (2026-08-06), и страниц
    # бы не получилось. В жизни водитель и публикует разное — время у каждого рейса своё.
    for i in range(5):
        _publish(client, drv, frm=city, to="Сибай", comment=f"рейс {i}")
    all_rides = client.get("/rides", params={"from_city": city}).json()
    assert len(all_rides) == 5                       # дефолт (без limit) — все, как было
    page = client.get("/rides", params={"from_city": city, "limit": 2, "offset": 0}).json()
    assert len(page) == 2
    page2 = client.get("/rides", params={"from_city": city, "limit": 2, "offset": 4}).json()
    assert len(page2) == 1                            # хвост страницы


# ----------------------------- заявки + матчинг -----------------------------
def test_request_create_and_match(client, user_factory):
    drv = user_factory("MDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Учалы", to="Магнитогорск")
    pax = user_factory("MPax")
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Учалы", "to_city": "Магнитогорск", "seats": 1}).json()
    assert req["status"] == "active"
    mine = client.get("/requests/mine", headers=pax["auth"]).json()
    assert any(x["id"] == req["id"] for x in mine)
    matches = client.get("/match/rides", headers=pax["auth"], params={"request_id": req["id"]}).json()
    assert any(m["id"] == ride["id"] for m in matches)


def test_match_other_user_forbidden(client, user_factory):
    pax = user_factory("OwnPax")
    req = client.post("/requests", headers=pax["auth"], json={"from_city": "Сибай", "to_city": "Уфа"}).json()
    outsider = user_factory("Nosy")
    assert client.get("/match/rides", headers=outsider["auth"], params={"request_id": req["id"]}).status_code == 403


# ----------------------------- брони -----------------------------
def test_cannot_book_own_ride(client, user_factory):
    drv = user_factory("SelfDrv", role=UserRole.driver)
    ride = _publish(client, drv)
    assert client.post("/bookings", headers=drv["auth"], json={"ride_id": ride["id"], "seats": 1}).status_code == 400


def test_confirm_only_by_driver(client, user_factory, monkeypatch):
    drv, pax, ride, booking = _trip(client, user_factory)
    pushed = []
    # RC: бронь теперь уведомляет через services.push_notification (F5) — перехватываем send_push внутри него.
    monkeypatch.setattr("app.services.send_push", lambda s, uid, title, body, **kw: pushed.append((uid, title)))
    # пассажир не может подтвердить
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=pax["auth"]).status_code == 403
    # водитель — может
    r = client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "confirmed"
    # F2: пассажиру ушёл push «Бронь подтверждена» (открывает телефон/точку сбора)
    assert (pax["id"], "Бронь подтверждена") in pushed
    # идемпотентный повтор — без второго пуша
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"]).status_code == 200
    assert len([p for p in pushed if p[1] == "Бронь подтверждена"]) == 1


def test_booking_details_unlock_after_confirm(client, user_factory):
    drv = user_factory("DetailDrv", role=UserRole.driver)
    pax = user_factory("DetailPax")
    ride = _publish(
        client,
        drv,
        frm="Темясово",
        to="Уфа",
        price=1400,
        pickup="Автовокзал",
        pickup_lat=52.972,
        pickup_lng=58.160,
    )
    booking = _book(client, pax, ride["id"])

    pending = client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"])
    assert pending.status_code == 200
    assert pending.json()["contact_unlocked"] is False
    assert pending.json()["driver_phone"] == ""
    assert pending.json()["pickup"] == ""
    assert pending.json()["pickup_lat"] is None
    assert pending.json()["from_lat"] is not None
    assert pending.json()["to_lat"] is not None

    client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"])
    confirmed = client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()
    assert confirmed["contact_unlocked"] is True
    assert confirmed["driver_phone"]
    assert confirmed["pickup"] == "Автовокзал"
    assert confirmed["pickup_lat"] == 52.972
    assert confirmed["from_lat"] == pending.json()["from_lat"]
    assert confirmed["to_lat"] == pending.json()["to_lat"]


def test_booking_details_backfills_route_coords_for_old_rides(client, user_factory):
    drv = user_factory("OldGeoDrv", role=UserRole.driver)
    pax = user_factory("OldGeoPax")
    ride = _publish(client, drv, frm="Темясово", to="Уфа", price=1400)
    with Session(engine) as session:
        db_ride = session.get(Ride, ride["id"])
        db_ride.from_lat = None
        db_ride.from_lng = None
        db_ride.to_lat = None
        db_ride.to_lng = None
        session.add(db_ride)
        session.commit()
    booking = _book(client, pax, ride["id"])

    details = client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"])
    assert details.status_code == 200
    body = details.json()
    assert body["from_lat"] is not None
    assert body["from_lng"] is not None
    assert body["to_lat"] is not None
    assert body["to_lng"] is not None

    with Session(engine) as session:
        db_ride = session.get(Ride, ride["id"])
        assert db_ride.from_lat is not None
        assert db_ride.to_lat is not None


def test_booking_details_forbidden_for_outsider(client, user_factory):
    _drv, _pax, _ride, booking = _trip(client, user_factory)
    outsider = user_factory("DetailOutsider")
    assert client.get(f"/bookings/{booking['id']}/details", headers=outsider["auth"]).status_code == 403


def test_cancel_returns_seats(client, user_factory):
    drv = user_factory("CancDrv", role=UserRole.driver)
    ride = _publish(client, drv, seats=2)
    pax = user_factory("CancPax")
    b = _book(client, pax, ride["id"], seats=2)
    assert client.get(f"/rides/{ride['id']}").json()["seats_left"] == 0
    r = client.post(f"/bookings/{b['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert client.get(f"/rides/{ride['id']}").json()["seats_left"] == 2


def test_no_duplicate_booking(client, user_factory):
    drv = user_factory("DupDrv", role=UserRole.driver)
    ride = _publish(client, drv, seats=3)
    pax = user_factory("DupPax")
    b1 = _book(client, pax, ride["id"], seats=1)
    b2 = _book(client, pax, ride["id"], seats=1)   # повтор / двойной тап «Поехать»
    assert b1["id"] == b2["id"]                      # идемпотентно — та же бронь
    assert client.get(f"/rides/{ride['id']}").json()["seats_left"] == 2  # место списано один раз, не два


def test_finish_trip_closes_booking(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory, depart_at=just_left())
    r = client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "done"})
    assert r.status_code == 200
    mine = client.get("/bookings/mine", headers=pax["auth"]).json()
    me = next(b for b in mine if b["id"] == booking["id"])
    assert me["status"] == "done"   # «Завершить» реально закрыло бронь на сервере


def test_blocked_driver_hidden_from_search(client, user_factory):
    drv = user_factory("BlkDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Кага", to="Уфа")
    pax = user_factory("BlkPax")
    # до блокировки — поездка видна пассажиру
    assert any(r["id"] == ride["id"] for r in client.get("/rides", headers=pax["auth"], params={"from_city": "Кага"}).json())
    # пассажир блокирует водителя
    assert client.post("/blocks", headers=pax["auth"], json={"blocked_user_id": ride["driver_id"]}).status_code == 200
    # теперь скрыта из поиска для него
    assert all(r["id"] != ride["id"] for r in client.get("/rides", headers=pax["auth"], params={"from_city": "Кага"}).json())
    # аноним (без токена) по-прежнему видит — фильтр только для залогиненного
    assert any(r["id"] == ride["id"] for r in client.get("/rides", params={"from_city": "Кага"}).json())


def test_referral_flow(client, user_factory):
    a = user_factory("RefA")
    b = user_factory("RefB")
    code = client.get("/referral/me", headers=a["auth"]).json()["code"]
    assert len(code) == 6
    # b вводит код a → оба получают по 1 бонусу
    assert client.post("/referral/redeem", headers=b["auth"], json={"code": code}).json()["credits"] == 1
    a_me = client.get("/referral/me", headers=a["auth"]).json()
    assert a_me["credits"] == 1 and a_me["invited"] == 1
    assert client.get("/referral/me", headers=b["auth"]).json()["redeemed"] is True
    # нельзя дважды и нельзя свой код
    assert client.post("/referral/redeem", headers=b["auth"], json={"code": code}).status_code == 400
    assert client.post("/referral/redeem", headers=a["auth"], json={"code": code}).status_code == 400


def test_driver_referral_bonus(client, user_factory):
    """Узел F19/B8: базовый реферал (+1 обоим по коду) работает и не сломан. А ДОП.
    водительский бонус начисляет СТРОГАЯ версия B8 — за реально ЗАВЕРШЁННЫЕ поездки
    приглашённого с ≥3 разными пассажирами (см. test_antifraud), а НЕ за факт публикации
    рейса. Публикация сама по себе доп. бонус не даёт (защита от накрутки пустыми рейсами)."""
    inviter = user_factory("InviteDrvA")
    invited = user_factory("InviteDrvB")
    code = client.get("/referral/me", headers=inviter["auth"]).json()["code"]
    # обычный реферал: invited вводит код → оба +1 (базовый бонус, как и раньше — не сломан)
    assert client.post("/referral/redeem", headers=invited["auth"], json={"code": code}).json()["credits"] == 1
    assert client.get("/referral/me", headers=inviter["auth"]).json()["credits"] == 1
    # invited публикует рейс — доп. водительский бонус НЕ начисляется на публикацию
    # (узел B8: бонус только за завершённые поездки с разными пассажирами).
    _publish(client, invited, frm="Баймак", to="Уфа")
    assert client.get("/referral/me", headers=inviter["auth"]).json()["credits"] == 1


def test_driver_referral_no_inviter_no_bonus(client, user_factory):
    """Не приглашённый водитель публикует рейс → водительский бонус никому не начисляется
    (обычный поток публикации не ломается, никаких побочных начислений)."""
    solo = user_factory("SoloDrv")
    before = client.get("/referral/me", headers=solo["auth"]).json()["credits"]
    _publish(client, solo, frm="Баймак", to="Магнитогорск")
    assert client.get("/referral/me", headers=solo["auth"]).json()["credits"] == before


def test_boost_free_consumes_credit(client, user_factory):
    drv = user_factory("BoostDrv", role=UserRole.driver)
    other = user_factory("BoostRef")
    code = client.get("/referral/me", headers=drv["auth"]).json()["code"]
    client.post("/referral/redeem", headers=other["auth"], json={"code": code})  # drv +1 бонус
    assert client.get("/referral/me", headers=drv["auth"]).json()["credits"] == 1
    ride = _publish(client, drv)
    assert client.post("/boost/free", headers=drv["auth"], json={"ride_id": ride["id"]}).json()["credits"] == 0
    # без бонусов — отказ; чужую поездку — нельзя
    assert client.post("/boost/free", headers=drv["auth"], json={"ride_id": ride["id"]}).status_code == 400
    assert client.post("/boost/free", headers=other["auth"], json={"ride_id": ride["id"]}).status_code == 403


def test_driver_status_and_role(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    assert client.get(f"/bookings/{booking['id']}/role", headers=drv["auth"]).json()["role"] == "driver"
    assert client.get(f"/bookings/{booking['id']}/role", headers=pax["auth"]).json()["role"] == "passenger"
    assert client.post(f"/bookings/{booking['id']}/driver-status", headers=drv["auth"], json={"status": "departed"}).status_code == 200
    assert client.post(f"/bookings/{booking['id']}/driver-status", headers=pax["auth"], json={"status": "departed"}).status_code == 403
    assert client.post(f"/bookings/{booking['id']}/driver-status", headers=drv["auth"], json={"status": "xxx"}).status_code == 400


def test_booking_lists(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    mine = client.get("/bookings/mine", headers=pax["auth"]).json()
    assert any(b["id"] == booking["id"] for b in mine)
    assert any(b["booking_id"] == booking["id"] for b in client.get("/driver/bookings", headers=drv["auth"]).json())
    # Джойн сводки поездки (экран «Мои поездки» рисует реальные карточки, а не заглушку).
    me = next(b for b in mine if b["id"] == booking["id"])
    assert me["from_city"] == "Баймак" and me["to_city"] == "Сибай"
    assert me["driver_name"] == "Drv"
    assert me["ride_id"] == ride["id"]
    assert me["status"] in ("pending", "confirmed", "onboard", "done", "cancelled")


# ----------------------------- чат + инбокс -----------------------------
def test_chat_conversations_notifications(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    bid = booking["id"]
    assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "Я на месте"}).status_code == 200
    msgs = client.get(f"/bookings/{bid}/messages", headers=drv["auth"]).json()
    assert msgs[-1]["text"] == "Я на месте"
    convs = client.get("/conversations", headers=drv["auth"]).json()
    assert any(c["booking_id"] == bid for c in convs)
    # Центр уведомлений: типизированная лента {unread, items}; входящее сообщение → уведомление type=message.
    notes = client.get("/notifications", headers=drv["auth"]).json()
    assert notes["unread"] >= 1
    assert any(n["type"] == "message" for n in notes["items"])


# ----------------------------- рейтинги -----------------------------
def test_two_way_rating(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory, depart_at=just_left())
    bid = booking["id"]
    # Оценить можно только ЗАВЕРШЁННУЮ поездку (анти-накрутка): до завершения — 409, после — 200.
    assert client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5}).status_code == 409
    client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"})
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5})
    assert r.status_code == 200 and r.json()["ratee_id"] == drv["id"] and r.json()["rating"] == 5.0
    # рейтинг водителя виден в /me
    assert client.get("/me", headers=drv["auth"]).json()["rating"] == 5.0
    # водитель оценивает пассажира
    r2 = client.post(f"/bookings/{bid}/rate", headers=drv["auth"], json={"stars": 4})
    assert r2.json()["ratee_id"] == pax["id"]


def test_rate_outsider_forbidden(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    outsider = user_factory("RateOut")
    assert client.post(f"/bookings/{booking['id']}/rate", headers=outsider["auth"], json={"stars": 5}).status_code == 403


# ----------------------------- семейный контроль -----------------------------
def test_trusted_contacts_and_share(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    c = client.post("/trusted-contacts", headers=pax["auth"], json={"name": "Мама", "phone": "+79990001122"})
    assert c.status_code == 200
    cid = c.json()["id"]
    assert any(x["id"] == cid for x in client.get("/trusted-contacts", headers=pax["auth"]).json())
    sh = client.post(f"/bookings/{booking['id']}/share", headers=pax["auth"], json={"contact_id": cid})
    assert sh.status_code == 200
    st = client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "sat"})
    assert st.status_code == 200 and st.json()[0]["last_status"] == "sat"


def test_share_only_passenger(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    c = client.post("/trusted-contacts", headers=drv["auth"], json={"name": "X"}).json()
    # водитель не может расшарить чужую (пассажирскую) поездку
    assert client.post(f"/bookings/{booking['id']}/share", headers=drv["auth"], json={"contact_id": c["id"]}).status_code == 403


def test_trusted_contact_validation_and_cap(client, user_factory):
    """P1: анти-SMS-бомбинг — кривой номер отклоняется, число контактов ограничено."""
    pax = user_factory("Кеп")
    # кривой номер (буквы/короткий) → 400
    assert client.post("/trusted-contacts", headers=pax["auth"],
                       json={"name": "Плохой", "phone": "abc"}).status_code == 400
    assert client.post("/trusted-contacts", headers=pax["auth"],
                       json={"name": "Короткий", "phone": "12345"}).status_code == 400
    # заполняем до потолка валидными номерами
    for i in range(10):
        r = client.post("/trusted-contacts", headers=pax["auth"],
                        json={"name": f"К{i}", "phone": f"+7999000{i:04d}"})
        assert r.status_code == 200, r.text
    # 11-й — отказ
    over = client.post("/trusted-contacts", headers=pax["auth"],
                       json={"name": "Лишний", "phone": "+79990009999"})
    assert over.status_code == 400
    # С волны 38 ошибка двуязычная: detail = {"ru": ..., "ba": ...}. Смысл проверки прежний —
    # человеку объяснили, что упёрся в число доверенных близких.
    detail = over.json()["detail"]
    assert "близ" in detail["ru"].lower(), detail
    assert detail["ba"] and detail["ba"] != detail["ru"], detail


def test_trip_status_bad_value(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    assert client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "wat"}).status_code == 400


# ----------------------------- безопасность -----------------------------
def test_sos_creates_event(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    r = client.post("/sos", headers=pax["auth"], json={"category": "medical", "booking_id": booking["id"]})
    assert r.status_code == 200 and r.json()["category"] == "medical"


def test_report_rules(client, user_factory):
    a = user_factory("RepA")
    b = user_factory("RepB")
    assert client.post("/reports", headers=a["auth"], json={"target_user_id": a["id"]}).status_code == 400  # на себя
    assert client.post("/reports", headers=a["auth"], json={"target_user_id": 99999999}).status_code == 404  # нет такого
    assert client.post("/reports", headers=a["auth"], json={"target_user_id": b["id"], "reason": "rude"}).status_code == 200


def test_block_self_forbidden(client, user_factory):
    a = user_factory("BlkSelf")
    assert client.post("/blocks", headers=a["auth"], json={"blocked_user_id": a["id"]}).status_code == 400


# ----------------------------- водитель: профиль/проверка/модерация -----------------------------
def test_driver_profile_verify_moderate(client, user_factory):
    drv = user_factory("VerDrv", role=UserRole.driver)
    client.post("/driver/profile", headers=drv["auth"], json={"car_make": "Lada", "car_model": "Vesta", "seats": 4})
    # неполная заявка → 400
    assert client.post("/driver/verify", headers=drv["auth"], json={"license_url": "a"}).status_code == 400
    img = base64.b64encode(b"\xff\xd8\xfffake-jpeg").decode()
    license_url = client.post("/upload/photo", headers=drv["auth"], json={"photo_b64": img, "ext": "jpg"}).json()["url"]
    car_photo_url = client.post("/upload/photo", headers=drv["auth"], json={"photo_b64": img, "ext": "jpg"}).json()["url"]
    ok = client.post("/driver/verify", headers=drv["auth"], json={"license_url": license_url, "car_photo_url": car_photo_url})
    assert ok.status_code == 200 and ok.json()["docs_status"] == "pending"
    assert client.get("/driver/status", headers=drv["auth"]).json()["docs_status"] == "pending"
    # модерация: обычный юзер не может
    assert client.post(f"/admin/drivers/{drv['id']}/moderate", headers=drv["auth"], json={"approve": True}).status_code == 403
    admin = user_factory("Admin", role=UserRole.admin)
    m = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"], json={"approve": True})
    assert m.status_code == 200 and m.json()["verified"] is True and m.json()["docs_status"] == "verified"


def test_driver_verify_rejects_foreign_secure_docs(client, user_factory):
    owner = user_factory("DocOwner", role=UserRole.driver)
    attacker = user_factory("DocAttacker", role=UserRole.driver)
    img = base64.b64encode(b"\xff\xd8\xfffake-jpeg").decode()
    license_url = client.post("/upload/photo", headers=owner["auth"], json={"photo_b64": img, "ext": "jpg"}).json()["url"]
    car_photo_url = client.post("/upload/photo", headers=owner["auth"], json={"photo_b64": img, "ext": "jpg"}).json()["url"]

    stolen_name = license_url.rsplit("/", 1)[-1]
    assert stolen_name.startswith(f"{owner['id']}_")
    assert client.get(f"/secure/docs/{stolen_name}", headers=owner["auth"]).status_code == 200
    assert client.get(f"/secure/docs/{stolen_name}", headers=attacker["auth"]).status_code == 403

    stolen = client.post(
        "/driver/verify",
        headers=attacker["auth"],
        json={"license_url": license_url, "car_photo_url": car_photo_url},
    )
    assert stolen.status_code == 403


# ----------------------------- загрузки -----------------------------
def test_upload_photo_and_bad_b64(client, user_factory):
    u = user_factory("UpUser")
    good = base64.b64encode(b"\xff\xd8\xfffake-jpeg").decode()   # валидная JPEG-сигнатура
    r = client.post("/upload/photo", headers=u["auth"], json={"photo_b64": good, "ext": "jpg"})
    assert r.status_code == 200 and "/secure/docs/" in r.json()["url"]
    assert client.post("/upload/photo", headers=u["auth"], json={"photo_b64": "!!!notb64!!!", "ext": "jpg"}).status_code == 400
    # Ярлык расширения от клиента игнорируем — тип берём из содержимого (фикс «фото не сохраняется»):
    # валидный JPEG, помеченный «exe», сохраняется как .jpg (не .exe), а не отвергается.
    r_exe = client.post("/upload/photo", headers=u["auth"], json={"photo_b64": good, "ext": "exe"})
    assert r_exe.status_code == 200 and r_exe.json()["url"].endswith(".jpg")
    # R2: байты без JPEG-сигнатуры под видом .jpg → 400 (magic-bytes)
    notimg = base64.b64encode(b"this is not an image").decode()
    assert client.post("/upload/photo", headers=u["auth"], json={"photo_b64": notimg, "ext": "jpg"}).status_code == 400


def test_upload_voice(client, user_factory):
    u = user_factory("VoiceUser")
    data = base64.b64encode(b"audio").decode()
    r = client.post("/voice", headers=u["auth"], json={"audio_b64": data, "ext": "m4a"})
    assert r.status_code == 200 and "/media/voice/" in r.json()["url"]


# ----------------------------- витрина / лента -----------------------------
def test_feed_and_routes_shapes(client, user_factory):
    feed = client.get("/feed").json()
    assert {"today", "week", "month", "year", "drivers"} <= set(feed)
    assert isinstance(client.get("/popular-routes").json(), list)


def test_geocode_empty_without_key(client, user_factory):
    # ключ геокодера в тестах не задан → пустой список, не падаем
    u = user_factory("Гео")
    assert client.get("/geocode", headers=u["auth"], params={"q": "Сибай"}).json() == {"items": []}
    # без авторизации — 401 (защита квоты Яндекса от анонимного абуза)
    assert client.get("/geocode", params={"q": "Сибай"}).status_code == 401


def test_ads_empty_without_seed(client):
    # SEED_DEMO=false в тестах → демо-креативы не подмешиваем
    assert client.get("/ads").json() == []


# ----------------------------- авторизация: полный OTP-цикл -----------------------------
def test_otp_login_flow(client):
    phone = "+79991234567"
    sent = client.post("/auth/request-code", json={"phone": phone}).json()
    assert sent["sent"] is True and "dev_code" in sent   # dev отдаёт код
    v = client.post("/auth/verify", json={"phone": phone, "code": sent["dev_code"], "name": "Тест"})
    assert v.status_code == 200
    token = v.json()["access_token"]
    me = client.get("/me", headers={"Authorization": f"Bearer {token}"}).json()
    assert me["phone"] == phone and me["name"] == "Тест"


def test_verify_wrong_code(client):
    phone = "+79997654321"
    client.post("/auth/request-code", json={"phone": phone})
    # Первый неверный код → именно 400 (неверный код), не замаскировано троттлингом.
    assert client.post("/auth/verify", json={"phone": phone, "code": "0000", "name": "X"}).status_code == 400
    # Перебор: после лимита попыток на код → 429. Так регрессия анти-brute-force лока не пройдёт зелёной.
    statuses = [
        client.post("/auth/verify", json={"phone": phone, "code": str(c), "name": "X"}).status_code
        for c in range(2000, 2010)
    ]
    assert 429 in statuses, f"ожидался 429 после перебора попыток, получили {statuses}"


def _login(client, phone, name="U"):
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    return client.post("/auth/verify", json={"phone": phone, "code": code, "name": name}).json()["access_token"]


def test_refresh_rotation(client):
    phone = "+79990007722"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    login = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "RefUser"}).json()
    assert login["access_token"] and login["refresh_token"]      # выдаётся пара
    r = client.post("/auth/refresh", json={"refresh_token": login["refresh_token"]})
    assert r.status_code == 200
    new = r.json()
    assert new["access_token"] and new["refresh_token"] != login["refresh_token"]   # ротация
    # старый refresh после ротации — недействителен
    assert client.post("/auth/refresh", json={"refresh_token": login["refresh_token"]}).status_code == 401
    # новый access работает
    assert client.get("/me", headers={"Authorization": f"Bearer {new['access_token']}"}).status_code == 200


def test_logout_revokes_refresh(client):
    phone = "+79990007733"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    login = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "RfLogout"}).json()
    h = {"Authorization": f"Bearer {login['access_token']}"}
    assert client.post("/auth/logout", headers=h).status_code == 200
    # после logout refresh тоже погашен
    assert client.post("/auth/refresh", json={"refresh_token": login["refresh_token"]}).status_code == 401


def test_ride_geocoded_on_create(client, user_factory):
    drv = user_factory("GeoDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Сибай", to="Уфа")   # оба в CITY_COORDS
    assert ride["from_lat"] is not None and ride["to_lat"] is not None   # концы геокодированы


def test_logout_revokes_token(client):
    phone = "+79990008811"
    tok = _login(client, phone, "LogoutUser")
    h = {"Authorization": f"Bearer {tok}"}
    assert client.get("/me", headers=h).status_code == 200          # токен работает
    assert client.post("/auth/logout", headers=h).status_code == 200
    assert client.get("/me", headers=h).status_code == 401          # тот же токен после logout — недействителен
    # повторный вход выдаёт новый рабочий токен
    tok2 = _login(client, phone, "LogoutUser")
    assert client.get("/me", headers={"Authorization": f"Bearer {tok2}"}).status_code == 200


def test_tg_share_contact_sets_real_phone(client):
    """Telegram «Поделиться номером»: бот получает contact → реальный номер
    попадает юзеру при верификации (а не плейсхолдер tg<id>)."""
    tid = "900900900"
    req = client.post("/auth/tg/start").json()["request_id"]
    # /start <req> от юзера → бот отдаёт код
    r = client.post("/telegram/webhook", json={
        "message": {"text": f"/start {req}", "from": {"id": int(tid), "first_name": "Айдар"},
                    "chat": {"id": int(tid)}}
    }).json()
    assert r["method"] == "sendMessage"
    code = __import__("re").search(r"\d{6}", r["text"]).group()
    # юзер делится своим номером (contact.user_id == отправитель)
    client.post("/telegram/webhook", json={
        "message": {"from": {"id": int(tid)}, "chat": {"id": int(tid)},
                    "contact": {"phone_number": "+7 925 111-22-33", "user_id": int(tid)}}
    })
    # верификация → юзер с РЕАЛЬНЫМ номером
    user = client.post("/auth/tg/verify", json={"request_id": req, "code": code}).json()["user"]
    assert user["phone"] == "+79251112233"
    assert user["telegram_id"] == tid


def test_tg_rejects_foreign_contact(client):
    """Пересланный ЧУЖОЙ контакт (user_id != отправитель) не сохраняется → вход не проходит."""
    tid = "901901901"
    req = client.post("/auth/tg/start").json()["request_id"]
    r = client.post("/telegram/webhook", json={
        "message": {"text": f"/start {req}", "from": {"id": int(tid), "first_name": "Тимур"},
                    "chat": {"id": int(tid)}}
    }).json()
    code = __import__("re").search(r"\d{6}", r["text"]).group()
    client.post("/telegram/webhook", json={  # чужой номер
        "message": {"from": {"id": int(tid)}, "chat": {"id": int(tid)},
                    "contact": {"phone_number": "+79990000000", "user_id": 555}}
    })
    # чужой номер не сохранён → реального номера нет → 403 phone_required
    resp = client.post("/auth/tg/verify", json={"request_id": req, "code": code})
    assert resp.status_code == 403 and resp.json()["detail"] == "phone_required"


def test_tg_phone_required_then_share_unlocks(client):
    """Номер ОБЯЗАТЕЛЕН: без контакта verify=403, код не сгорает; после шеринга — вход."""
    tid = "902902902"
    req = client.post("/auth/tg/start").json()["request_id"]
    r = client.post("/telegram/webhook", json={
        "message": {"text": f"/start {req}", "from": {"id": int(tid), "first_name": "Гузель"},
                    "chat": {"id": int(tid)}}
    }).json()
    code = __import__("re").search(r"\d{6}", r["text"]).group()
    # ввод кода без номера → 403, код остаётся валидным
    assert client.post("/auth/tg/verify", json={"request_id": req, "code": code}).status_code == 403
    # юзер делится своим номером
    client.post("/telegram/webhook", json={
        "message": {"from": {"id": int(tid)}, "chat": {"id": int(tid)},
                    "contact": {"phone_number": "+79261239988", "user_id": int(tid)}}
    })
    # тот же код снова → вход проходит, номер реальный
    ok = client.post("/auth/tg/verify", json={"request_id": req, "code": code})
    assert ok.status_code == 200
    body = ok.json()
    assert body["user"]["phone"] == "+79261239988"
    # токен рабочий (current_user пропускает — номер есть)
    assert client.get("/me", headers={"Authorization": f"Bearer {body['access_token']}"}).status_code == 200


def test_upload_chat_photo_public(client, user_factory):
    """Фото чата → публичный URL (/media/chat/...), не приватный /secure/docs."""
    import base64 as _b64
    u = user_factory("ChatPhoto")
    img = _b64.b64encode(b"\xff\xd8\xff\xe0fake-jpeg").decode()
    r = client.post("/upload/chat-photo", headers=u["auth"], json={"photo_b64": img, "ext": "jpg"})
    assert r.status_code == 200
    url = r.json()["url"]
    assert "/media/chat/" in url and "/secure/" not in url
    # битый base64 → 400
    assert client.post("/upload/chat-photo", headers=u["auth"], json={"photo_b64": "!!!", "ext": "jpg"}).status_code == 400
    # без токена → 401
    assert client.post("/upload/chat-photo", json={"photo_b64": img, "ext": "jpg"}).status_code == 401


def test_message_edit_and_delete(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    bid = booking["id"]
    # пассажир пишет
    m = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "Виду у рынка"}).json()
    mid = m["id"]
    # чужой не может править
    assert client.post(f"/bookings/{bid}/messages/{mid}/edit", headers=drv["auth"], json={"text": "хак"}).status_code == 403
    # автор правит → edited
    e = client.post(f"/bookings/{bid}/messages/{mid}/edit", headers=pax["auth"], json={"text": "Жду у рынка"})
    assert e.status_code == 200 and e.json()["text"] == "Жду у рынка" and e.json()["edited"] is True
    # «удалить у себя» (водитель скрывает у себя) — у него пропадает, у пассажира остаётся
    assert client.delete(f"/bookings/{bid}/messages/{mid}?scope=me", headers=drv["auth"]).status_code == 200
    assert all(x["id"] != mid for x in client.get(f"/bookings/{bid}/messages", headers=drv["auth"]).json())
    assert any(x["id"] == mid for x in client.get(f"/bookings/{bid}/messages", headers=pax["auth"]).json())
    # «удалить у всех» — только автор
    assert client.delete(f"/bookings/{bid}/messages/{mid}?scope=all", headers=drv["auth"]).status_code == 403
    d = client.delete(f"/bookings/{bid}/messages/{mid}?scope=all", headers=pax["auth"])
    assert d.status_code == 200 and d.json()["deleted"] is True and d.json()["text"] == ""
    # после удаления у всех — пометка видна обоим (сообщение остаётся в списке как deleted)
    msgs = client.get(f"/bookings/{bid}/messages", headers=pax["auth"]).json()
    assert any(x["id"] == mid and x["deleted"] for x in msgs)


# ----------------------------- R3: /ads/stats только админ -----------------------------
def test_ad_stats_admin_only(client, user_factory):
    u = user_factory("AdViewer")
    assert client.get("/ads/stats", headers=u["auth"]).status_code == 403   # обычный юзер
    assert client.get("/ads/stats").status_code in (401, 403)               # без токена
    admin = user_factory("AdAdmin", role=UserRole.admin)
    assert client.get("/ads/stats", headers=admin["auth"]).status_code == 200


# ----------------------------- R2: суточная квота загрузок -----------------------------
def test_upload_daily_quota(client, user_factory):
    from app.config import settings
    u = user_factory("QuotaUser")
    img = base64.b64encode(b"\xff\xd8\xfffake").decode()
    orig = settings.max_uploads_per_day
    settings.max_uploads_per_day = 2
    try:
        assert client.post("/upload/photo", headers=u["auth"], json={"photo_b64": img, "ext": "jpg"}).status_code == 200
        assert client.post("/upload/photo", headers=u["auth"], json={"photo_b64": img, "ext": "jpg"}).status_code == 200
        # третья за сутки — превышение квоты → 429
        assert client.post("/upload/photo", headers=u["auth"], json={"photo_b64": img, "ext": "jpg"}).status_code == 429
    finally:
        settings.max_uploads_per_day = orig


# ----------------------------- Boost (платное поднятие, mock-оплата) -----------------------------
def test_boost_plans_and_sorting(client, user_factory):
    plans = client.get("/boost/plans").json()
    assert {p["tier"] for p in plans} == {"quick", "day", "urgent"}
    drv = user_factory("BoostDrv", role=UserRole.driver)
    city = "БустГрад"
    a = _publish(client, drv, frm=city, to="Сибай", depart_at="2030-01-01T08:00:00")
    b = _publish(client, drv, frm=city, to="Сибай", depart_at="2030-01-01T12:00:00")
    ids = [r["id"] for r in client.get("/rides", params={"from_city": city}).json()]
    assert ids.index(a["id"]) < ids.index(b["id"])          # без буста — по времени (A раньше B)
    r = client.post("/boost/create", headers=drv["auth"], json={"ride_id": b["id"], "tier": "day"})
    assert r.status_code == 200 and r.json()["status"] == "succeeded"   # mock-оплата прошла сразу
    out = client.get("/rides", params={"from_city": city}).json()
    assert out[0]["id"] == b["id"] and out[0]["boosted"] is True        # B поднят → первым


def test_boost_only_own_ride(client, user_factory):
    drv = user_factory("BoostOwnDrv", role=UserRole.driver)
    ride = _publish(client, drv)
    other = user_factory("BoostOther", role=UserRole.driver)
    assert client.post("/boost/create", headers=other["auth"], json={"ride_id": ride["id"], "tier": "quick"}).status_code == 403
    assert client.post("/boost/create", headers=drv["auth"], json={"ride_id": ride["id"], "tier": "nope"}).status_code == 400


# ----------------------------- СБП-перевод (интерим) + админ-подтверждение -----------------------------
def test_boost_sbp_manual_flow(client, user_factory):
    from app.config import settings
    drv = user_factory("SbpDrv", role=UserRole.driver)
    city = "СбпГрад"
    a = _publish(client, drv, frm=city, to="Сибай", depart_at="2030-01-01T08:00:00")
    b = _publish(client, drv, frm=city, to="Сибай", depart_at="2030-01-01T12:00:00")
    op, ph = settings.payments_provider, settings.sbp_phone
    settings.payments_provider, settings.sbp_phone, settings.sbp_bank = "sbp_manual", "+79990000000", "Сбербанк"
    try:
        r = client.post("/boost/create", headers=drv["auth"], json={"ride_id": b["id"], "tier": "day"})
        assert r.status_code == 200
        js = r.json()
        assert js["status"] == "pending" and js["method"] == "sbp_manual"
        assert js["payee"]["phone"] == "+79990000000" and js["amount"] == 50
        pid = js["payment_id"]
        # пока НЕ подтверждён — поездка не поднята
        assert client.get("/rides", params={"from_city": city}).json()[0]["id"] == a["id"]
        # обычный юзер не может подтвердить
        assert client.post(f"/admin/payments/{pid}/confirm", headers=drv["auth"]).status_code == 403
        admin = user_factory("SbpAdmin", role=UserRole.admin)
        assert client.get("/admin/payments/pending", headers=admin["auth"]).json()  # в очереди
        assert client.post(f"/admin/payments/{pid}/confirm", headers=admin["auth"]).status_code == 200
        # после подтверждения — B поднят
        out = client.get("/rides", params={"from_city": city}).json()
        assert out[0]["id"] == b["id"] and out[0]["boosted"] is True
    finally:
        settings.payments_provider, settings.sbp_phone = op, ph


def test_driver_rides_own_only(client, user_factory):
    drv = user_factory("MyRidesDrv", role=UserRole.driver)
    _publish(client, drv, frm="МойГрад", to="Сибай")
    other = user_factory("OtherDrv", role=UserRole.driver)
    _publish(client, other, frm="ЧужГрад", to="Сибай")
    rows = client.get("/driver/rides", headers=drv["auth"]).json()
    assert rows and all(r["driver_id"] == drv["id"] for r in rows)
    assert client.get("/driver/rides").status_code == 401   # нужен токен


def test_yookassa_webhook_only_known_payment(client, user_factory, monkeypatch):
    """P1: вебхук активирует ТОЛЬКО известный платёж И только при активном yookassa;
    чужой/случайный id — no-op (анти-амплификация); mock/sbp_manual — вебхук не активирует ничего."""
    from app.db import engine
    from app.models import Payment, Ride
    from sqlmodel import Session
    drv = user_factory("WhDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="ХукГрад", to="Сибай")
    # эмулируем выпущенный нами платёж (pending)
    with Session(engine) as s:
        s.add(Payment(user_id=drv["id"], purpose="boost", ride_id=ride["id"], tier="day",
                      amount_kop=5000, provider_id="pid_known", status="pending"))
        s.commit()
    # SECURITY: при провайдере != yookassa (в тестах дефолт mock) вебхук НЕ активирует даже
    # известный платёж — иначе поддельный POST активировал бы sbp_manual/mock-платёж бесплатно.
    assert client.post("/payments/yookassa/webhook", json={"object": {"id": "pid_known"}}).status_code == 200
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).boosted_until is None      # mock-провайдер → не активировано

    # Дальше — реальный путь yookassa: fetch_payment замокан на succeeded.
    monkeypatch.setattr("app.config.settings.payments_provider", "yookassa")
    monkeypatch.setattr("app.routers.payments.fetch_payment", lambda pid: {"status": "succeeded", "metadata": {}})
    # чужой id — ничего не активирует, 200
    assert client.post("/payments/yookassa/webhook", json={"object": {"id": "pid_random_attacker"}}).status_code == 200
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).boosted_until is None      # не тронуто
    # наш id + yookassa → активируется
    assert client.post("/payments/yookassa/webhook", json={"object": {"id": "pid_known"}}).status_code == 200
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).boosted_until is not None   # поднято
    # пустое тело / без id — 200, без падения
    assert client.post("/payments/yookassa/webhook", json={}).status_code == 200
