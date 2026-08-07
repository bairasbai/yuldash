# -*- coding: utf-8 -*-
"""Честное «подъезжаю»: слово водителя сверяется с его же GPS (разбор конкурентов 2026-08-07).

Массовая жалоба на inDrive (пост на 4684 голоса в r/Philippines): водитель жмёт «Я приехал»,
находясь за километры, — таймер ожидания идёт, пассажир стоит на улице. У нас пассажир получает
пуш «Водитель подъезжает» и выходит из дома; зимой в Баймаке цена этого вранья — не «неудобно»,
а десять минут на морозе с ребёнком на руках.

Правило: «подъезжаю» принимается, только если живая позиция водителя реально рядом с точкой
подачи. Проверяем ТОЛЬКО когда позиция известна — нет данных (Redis выключен, GPS ещё не пошёл,
старое приложение) пропускаем как раньше. Ложный отказ честному водителю дороже пропущенного
обмана: кнопка, которая иногда не работает, убивает доверие ко всему приложению.
"""
import pytest

from app import livepos
from app.config import settings
from app.models import UserRole

# Баймак — координаты центра, чтобы «рядом» и «далеко» были из реальной географии.
BAYMAK = (52.5906, 58.3169)
FAR = (52.7200, 58.6600)          # ≈ 25 км от Баймака (сторона Сибая)


def _publish(client, drv, **extra):
    body = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300,
            "pickup_lat": BAYMAK[0], "pickup_lng": BAYMAK[1], **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _trip(client, user_factory):
    drv = user_factory("ArrDrv", role=UserRole.driver)
    pax = user_factory("ArrPax")
    ride = _publish(client, drv)
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 200, r.text
    return drv, pax, ride, r.json()


@pytest.fixture
def at(monkeypatch):
    """Поставить водителя в заданную точку (или «позиции нет» через None)."""
    def place(point):
        def fake(kind, ref_id):
            if point is None:
                return None
            return {"lat": point[0], "lng": point[1], "bearing": None, "ts": ""}
        monkeypatch.setattr(livepos, "livepos_get", fake)
    return place


def test_arriving_from_far_away_is_refused(client, user_factory, at):
    """Водитель за 25 км жмёт «подъезжаю» → отказ, и в тексте видно, насколько он далеко."""
    drv, pax, ride, booking = _trip(client, user_factory)
    at(FAR)
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "arriving"})
    assert r.status_code == 409, r.text
    assert "км" in r.json()["detail"]["ru"]
    # Пассажиру ложный пуш не ушёл: фаза осталась прежней.
    assert client.get(f"/bookings/{booking['id']}/role",
                      headers=pax["auth"]).json()["driver_phase"] == ""


def test_arriving_from_nearby_is_accepted_and_verified(client, user_factory, at):
    """Водитель реально у точки подачи → статус проходит и помечается подтверждённым."""
    drv, pax, ride, booking = _trip(client, user_factory)
    at(BAYMAK)
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "arriving"})
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{booking['id']}/role", headers=pax["auth"]).json()
    assert role["driver_phase"] == "arriving"
    assert role["arrival_verified"] is True      # пассажир увидит «подтверждено по GPS»


def test_no_position_still_works_but_unverified(client, user_factory, at):
    """Позиции нет (Redis выключен / GPS не пошёл) → не блокируем, но и не подтверждаем.

    Это главный предохранитель: приложение не имеет права переставать работать оттого,
    что у водителя в дороге пропал интернет.
    """
    drv, pax, ride, booking = _trip(client, user_factory)
    at(None)
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "arriving"})
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{booking['id']}/role", headers=pax["auth"]).json()
    assert role["driver_phase"] == "arriving"
    assert role["arrival_verified"] is False     # честно: «не подтверждено», а не ложная галочка


def test_departed_is_never_blocked_and_clears_the_badge(client, user_factory, at):
    """«Выехал» — не заявка о близости, его не проверяем и галочку снимаем."""
    drv, pax, ride, booking = _trip(client, user_factory)
    at(BAYMAK)
    client.post(f"/bookings/{booking['id']}/driver-status",
                headers=drv["auth"], json={"status": "arriving"})
    at(FAR)
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "departed"})
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{booking['id']}/role", headers=pax["auth"]).json()
    assert role["driver_phase"] == "departed"
    assert role["arrival_verified"] is False


def test_switch_off_disables_the_check(client, user_factory, at, monkeypatch):
    """Рубильник в конфиге возвращает прежнее поведение — если GPS в поле окажется шумным."""
    drv, pax, ride, booking = _trip(client, user_factory)
    monkeypatch.setattr(settings, "arrival_verify_enabled", False)
    at(FAR)
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "arriving"})
    assert r.status_code == 200, r.text


def test_ride_without_pickup_pin_is_not_blocked(client, user_factory, at):
    """Нет точного пина подачи → сверять не с чем, статус проходит как раньше.

    Центр города как запасная цель НЕ годится: Уфа больше 20 км в поперечнике, и водитель,
    честно забирающий пассажира на окраине, попал бы под отказ ни за что.
    """
    drv = user_factory("NoPinDrv", role=UserRole.driver)
    pax = user_factory("NoPinPax")
    r = client.post("/rides", headers=drv["auth"],
                    json={"from_city": "Баймак", "to_city": "Сибай",
                          "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300})
    assert r.status_code == 200, r.text
    ride = r.json()
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    at(FAR)
    assert client.post(f"/bookings/{booking['id']}/driver-status",
                       headers=drv["auth"], json={"status": "arriving"}).status_code == 200
