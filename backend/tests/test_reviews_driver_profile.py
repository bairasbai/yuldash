"""F7 — текстовые отзывы (Rating.text) + публичный профиль водителя.

Проверяем: публичный профиль без ПДн (телефона), текстовый отзыв сохраняется и
проходит модерацию (в профиль попадает только одобренное), агрегаты (число done-поездок,
средний рейтинг, стаж) считаются, приватные данные не текут.
"""
from datetime import timedelta

from app.models import UserRole
from app.timeutil import utcnow


# ----------------------------- helpers -----------------------------
def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    # Выезд «только что»: весь файл про ЗАВЕРШЁННЫЕ поездки и публичный профиль, а завершить
    # можно лишь начавшуюся поездку (аудит 2026-08-07). Поездка «в 2030 году», закрытая как
    # состоявшаяся, рисовала бы отзывы и бейдж «N поездок» без единого метра пути.
    body = {"from_city": frm, "to_city": to,
            "depart_at": (utcnow() - timedelta(minutes=1)).replace(microsecond=0).isoformat() + "+00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _done_trip(client, user_factory):
    """Завершённая поездка: (drv, pax, ride, booking) со статусом done."""
    drv = user_factory("Ильдар", role=UserRole.driver)
    pax = user_factory("Гульназ")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "done"})
    return drv, pax, ride, booking


# ----------------------------- публичный профиль -----------------------------
def test_public_profile_shape_no_pdn(client, user_factory):
    drv, pax, ride, booking = _done_trip(client, user_factory)
    r = client.get(f"/drivers/{drv['id']}/public")
    assert r.status_code == 200, r.text
    body = r.json()
    # ПДн НЕ течёт: телефона нет ни на каком уровне ответа.
    assert "phone" not in body
    assert "phone" not in str(body).lower() or "телефон" not in str(body).lower()
    # витрина доверия на месте
    assert body["id"] == drv["id"]
    assert body["name"] == "Ильдар"
    assert "verified" in body and "joined_at" in body
    assert body["days_in_service"] >= 0
    assert "reviews" in body


def test_public_profile_404_for_missing(client):
    assert client.get("/drivers/99999999/public").status_code == 404


# ----------------------------- текстовый отзыв + модерация -----------------------------
def test_text_review_saved_moderated_then_public(client, user_factory):
    drv, pax, ride, booking = _done_trip(client, user_factory)
    bid = booking["id"]
    # Пассажир оценивает водителя со ЗВЁЗДАМИ + ТЕКСТОМ.
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                    json={"stars": 5, "text": "Довёз спокойно и вовремя, спасибо!"})
    assert r.status_code == 200, r.text
    # Звёзды считаются сразу — рейтинг обновился.
    assert r.json()["rating"] == 5.0

    # Текст ещё НЕ прошёл модерацию → в публичном профиле его нет.
    pub = client.get(f"/drivers/{drv['id']}/public").json()
    assert pub["reviews"] == []
    assert pub["rating"] == 5.0            # но звёзды уже видны

    # Отзыв висит в очереди модерации админа.
    admin = user_factory("Админ", role=UserRole.admin)
    pending = client.get("/admin/ratings/pending", headers=admin["auth"]).json()
    mine = [x for x in pending if x["ratee_id"] == drv["id"]]
    assert len(mine) == 1
    assert mine[0]["text"] == "Довёз спокойно и вовремя, спасибо!"
    assert mine[0]["author"] == "Гульназ"
    rating_id = mine[0]["id"]

    # Админ одобряет → отзыв появляется в публичном профиле.
    ok = client.post(f"/admin/ratings/{rating_id}/publish", headers=admin["auth"], json={"published": True})
    assert ok.status_code == 200
    pub2 = client.get(f"/drivers/{drv['id']}/public").json()
    assert len(pub2["reviews"]) == 1
    assert pub2["reviews"][0]["text"] == "Довёз спокойно и вовремя, спасибо!"
    assert pub2["reviews"][0]["stars"] == 5
    assert pub2["reviews"][0]["author"] == "Гульназ"

    # Снятие с публикации убирает из профиля.
    client.post(f"/admin/ratings/{rating_id}/publish", headers=admin["auth"], json={"published": False})
    assert client.get(f"/drivers/{drv['id']}/public").json()["reviews"] == []


def test_pending_moderation_admin_only(client, user_factory):
    pax = user_factory("Не админ")
    assert client.get("/admin/ratings/pending", headers=pax["auth"]).status_code == 403


def test_empty_text_not_in_moderation_queue(client, user_factory):
    drv, pax, ride, booking = _done_trip(client, user_factory)
    # Оценка БЕЗ текста — только звёзды.
    client.post(f"/bookings/{booking['id']}/rate", headers=pax["auth"], json={"stars": 4})
    admin = user_factory("Админ2", role=UserRole.admin)
    pending = client.get("/admin/ratings/pending", headers=admin["auth"]).json()
    assert all(x["ratee_id"] != drv["id"] for x in pending)   # пустого текста в очереди нет


def test_edited_text_returns_to_moderation(client, user_factory):
    drv, pax, ride, booking = _done_trip(client, user_factory)
    bid = booking["id"]
    r1 = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5, "text": "Первый вариант отзыва"})
    assert r1.status_code == 200
    admin = user_factory("Админ3", role=UserRole.admin)
    rid = [x for x in client.get("/admin/ratings/pending", headers=admin["auth"]).json() if x["ratee_id"] == drv["id"]][0]["id"]
    client.post(f"/admin/ratings/{rid}/publish", headers=admin["auth"], json={"published": True})
    assert len(client.get(f"/drivers/{drv['id']}/public").json()["reviews"]) == 1
    # Пассажир меняет текст → снова на модерацию, из профиля исчезает.
    client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5, "text": "Подменённый текст"})
    assert client.get(f"/drivers/{drv['id']}/public").json()["reviews"] == []


def test_text_length_capped(client, user_factory):
    drv, pax, ride, booking = _done_trip(client, user_factory)
    long_text = "а" * 900
    r = client.post(f"/bookings/{booking['id']}/rate", headers=pax["auth"], json={"stars": 5, "text": long_text})
    # 500-символьный лимит валидатора → 422 (pydantic max_length).
    assert r.status_code == 422


# ----------------------------- агрегаты -----------------------------
def test_aggregates_trips_and_rating(client, user_factory):
    drv = user_factory("Айгуль", role=UserRole.driver)
    # Две завершённые поездки от одного водителя, два разных пассажира.
    for i in range(2):
        pax = user_factory(f"Пасс{i}")
        ride = _publish(client, drv, frm="Учалы", to=f"Город{i}")
        b = _book(client, pax, ride["id"])
        client.post(f"/bookings/{b['id']}/trip-status", headers=pax["auth"], json={"status": "done"})
        client.post(f"/bookings/{b['id']}/rate", headers=pax["auth"], json={"stars": 4 + i})  # 4 и 5
    pub = client.get(f"/drivers/{drv['id']}/public").json()
    assert pub["trips_count"] == 2
    assert pub["rating_count"] == 2
    assert pub["rating"] == 4.5           # (4+5)/2


def test_no_ratings_rating_is_null(client, user_factory):
    drv = user_factory("Новичок", role=UserRole.driver)
    pub = client.get(f"/drivers/{drv['id']}/public").json()
    assert pub["rating"] is None
    assert pub["rating_count"] == 0
    assert pub["trips_count"] == 0
