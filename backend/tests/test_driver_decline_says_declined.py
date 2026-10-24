"""«Отклонить» у водителя — это отказ, и пассажир должен слышать именно отказ (приёмка 18.10, п.6).

Водитель жмёт «Отклонить» на брони, которую ещё не подтверждал. Раньше пассажиру приходило то же,
что при любой отмене, — «Бронь отменена»: будто он сам что-то отменил или поездка сорвалась.
Теперь сервер узнаёт отказ сам (бронь ждала подтверждения, отменяет водитель) — так и старые
клиенты, которые шлют обычную отмену без причины, дают правильный текст.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, UserRole

from test_flows import _book, _publish


def _notes(client, user):
    r = client.get("/notifications", headers=user["auth"])
    assert r.status_code == 200, r.text
    return [n for n in r.json()["items"] if n["type"] == "booking"]


def test_driver_declining_pending_booking_tells_passenger_it_was_declined(client, user_factory):
    drv = user_factory("Отказ Водитель", role=UserRole.driver)
    pax = user_factory("Отказ Пассажир")
    ride = _publish(client, drv, frm="Баймак", to="Уфа")
    booking = _book(client, pax, ride["id"])

    r = client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "cancelled"

    note = next(n for n in _notes(client, pax) if n["ref_id"] == booking["id"])
    assert note["title_ru"] == "Водитель отклонил бронь"
    assert note["title_ba"] and note["title_ba"] != "Бронь ҡалдырылды"
    assert "Баймак → Уфа" in note["body_ru"]
    assert "Посмотри другие поездки" in note["body_ru"]
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).cancel_reason == "driver_declined"
    # Экран брони у пассажира тоже говорит «отклонил», а не «отменена».
    assert client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()["declined"] is True


def test_driver_cancelling_confirmed_booking_is_still_a_cancellation(client, user_factory):
    drv = user_factory("Отмена Водитель", role=UserRole.driver)
    pax = user_factory("Отмена Пассажир")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"]).status_code == 200

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"],
                       json={"reason": "not_going"}).status_code == 200

    titles = [n["title_ru"] for n in _notes(client, pax) if n["ref_id"] == booking["id"]]
    assert "Бронь отменена" in titles
    assert "Водитель отклонил бронь" not in titles
    assert client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()["declined"] is False


def test_passenger_withdrawing_pending_booking_is_not_a_decline(client, user_factory):
    drv = user_factory("Отзыв Водитель", role=UserRole.driver)
    pax = user_factory("Отзыв Пассажир")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"]).status_code == 200

    titles = [n["title_ru"] for n in _notes(client, drv) if n["ref_id"] == booking["id"]]
    assert "Бронь отменена" in titles      # «Новая бронь» водителю тоже лежит в ленте
    assert "Водитель отклонил бронь" not in titles
    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).cancel_reason is None


def test_decline_with_a_reason_is_still_a_decline_on_both_screens(client, user_factory):
    """Водитель отклонил неподтверждённую бронь, выбрав причину: пуш и экран брони говорят одно (ревью Opus 23.10)."""
    drv = user_factory("Причина Водитель", role=UserRole.driver)
    pax = user_factory("Причина Пассажир")
    booking = _book(client, pax, _publish(client, drv)["id"])

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"],
                       json={"reason": "not_going"}).status_code == 200

    note = next(n for n in _notes(client, pax) if n["ref_id"] == booking["id"])
    assert note["title_ru"] == "Водитель отклонил бронь"
    assert "Поездка не состоится" in note["body_ru"], "причину водителя пассажир всё равно видит"
    assert client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()["declined"] is True


def test_decline_keeps_the_drivers_reason_for_review(client, user_factory):
    """Причина отказа не теряется в пуше: она в базе отдельным полем — для разборов и статистики (приёмка PR210)."""
    drv = user_factory("ПричинаБаза Водитель", role=UserRole.driver)
    pax = user_factory("ПричинаБаза Пассажир")
    booking = _book(client, pax, _publish(client, drv)["id"])

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=drv["auth"],
                       json={"reason": "safety"}).status_code == 200

    with Session(engine) as s:
        saved = s.get(Booking, booking["id"])
        assert saved.cancel_reason == "driver_declined"
        assert saved.decline_reason == "safety"


def test_decline_without_a_reason_and_plain_cancels_leave_it_empty(client, user_factory):
    """Нет причины — NULL; обычная отмена (не отказ) поле не трогает: её причина в `cancel_reason`."""
    drv = user_factory("ПричинаПусто Водитель", role=UserRole.driver)
    pax = user_factory("ПричинаПусто Пассажир")
    declined = _book(client, pax, _publish(client, drv)["id"])
    assert client.post(f"/bookings/{declined['id']}/cancel", headers=drv["auth"]).status_code == 200

    confirmed = _book(client, pax, _publish(client, drv)["id"])
    assert client.post(f"/bookings/{confirmed['id']}/confirm", headers=drv["auth"]).status_code == 200
    assert client.post(f"/bookings/{confirmed['id']}/cancel", headers=drv["auth"],
                       json={"reason": "safety"}).status_code == 200

    withdrawn = _book(client, pax, _publish(client, drv)["id"])
    assert client.post(f"/bookings/{withdrawn['id']}/cancel", headers=pax["auth"],
                       json={"reason": "safety"}).status_code == 200

    with Session(engine) as s:
        assert s.get(Booking, declined["id"]).decline_reason is None
        assert s.get(Booking, confirmed["id"]).decline_reason is None
        assert s.get(Booking, confirmed["id"]).cancel_reason == "safety"
        assert s.get(Booking, withdrawn["id"]).decline_reason is None


def test_decline_reason_column_has_a_migration():
    """Колонка доедет до прода только ревизией: на SQLite её молча допишет `db.py`, на Postgres — нет."""
    from pathlib import Path

    text = (Path(__file__).resolve().parents[1] / "alembic" / "versions"
            / "cc_booking_decline_reason_20261024.py").read_text(encoding="utf-8")
    assert '"decline_reason"' in text and '"booking"' in text


def test_client_cannot_forge_a_decline(client, user_factory):
    """Код «driver_declined» от клиента не принимаем: пассажир, отменивший сам, — не «отклонённый»."""
    drv = user_factory("Подделка Водитель", role=UserRole.driver)
    pax = user_factory("Подделка Пассажир")
    booking = _book(client, pax, _publish(client, drv)["id"])

    assert client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"],
                       json={"reason": "driver_declined"}).status_code == 200

    with Session(engine) as s:
        assert s.get(Booking, booking["id"]).cancel_reason == "other"
    assert client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()["declined"] is False
    titles = [n["title_ru"] for n in _notes(client, drv) if n["ref_id"] == booking["id"]]
    assert "Водитель отклонил бронь" not in titles
