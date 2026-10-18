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
from datetime import timedelta

import fakeredis
import pytest

from app import instant_service as isv
from app import livepos
from app.config import settings
from app.models import UserRole
from app.timeutil import utcnow

# Баймак — координаты центра, чтобы «рядом» и «далеко» были из реальной географии.
BAYMAK = (52.5906, 58.3169)
FAR = (52.7200, 58.6600)          # ≈ 25 км от Баймака (сторона Сибая)


def _soon() -> str:
    """Выезд через 10 минут: «Подъезжаю» принимается не раньше чем за 30 минут до выезда (приёмка PR184),
    поэтому GPS-проверку гоняем на поездке «сегодня», а не в 2030 году."""
    return (utcnow() + timedelta(minutes=10)).isoformat() + "+00:00"


def _publish(client, drv, **extra):
    body = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": _soon(),
            "seats_total": 3, "price": 300,
            "pickup_lat": BAYMAK[0], "pickup_lng": BAYMAK[1], **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _trip(client, user_factory):
    """Подтверждённая бронь: точку водителя канал пишет только по подтверждённой брони и «в пути»,
    и проверка «Подъезжаю» читает только их (`livepos.driver_points_for_ride`)."""
    drv = user_factory("ArrDrv", role=UserRole.driver)
    pax = user_factory("ArrPax")
    ride = _publish(client, drv)
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 200, r.text
    assert client.post(f"/bookings/{r.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, ride, r.json()


@pytest.fixture
def at():
    """Поставить водителя в точку (или «позиции нет» через None) — тем же кэшем, что пишет канал
    координат водителя по брони `bid` (`location.py` → `livepos_set`)."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r

    def place(point, bid):
        r.flushall()
        if point is not None:
            livepos.livepos_set("booking", bid, point[0], point[1])
    yield place
    isv._redis_override = None


def test_arriving_from_far_away_is_refused(client, user_factory, at):
    """Водитель за 25 км жмёт «подъезжаю» → отказ, и в тексте видно, насколько он далеко."""
    drv, pax, ride, booking = _trip(client, user_factory)
    at(FAR, booking["id"])
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
    at(BAYMAK, booking["id"])
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
    at(None, booking["id"])
    r = client.post(f"/bookings/{booking['id']}/driver-status",
                    headers=drv["auth"], json={"status": "arriving"})
    assert r.status_code == 200, r.text
    role = client.get(f"/bookings/{booking['id']}/role", headers=pax["auth"]).json()
    assert role["driver_phase"] == "arriving"
    assert role["arrival_verified"] is False     # честно: «не подтверждено», а не ложная галочка


def test_departed_is_never_blocked_and_clears_the_badge(client, user_factory, at):
    """«Выехал» — не заявка о близости, его не проверяем и галочку снимаем."""
    drv, pax, ride, booking = _trip(client, user_factory)
    at(BAYMAK, booking["id"])
    client.post(f"/bookings/{booking['id']}/driver-status",
                headers=drv["auth"], json={"status": "arriving"})
    at(FAR, booking["id"])
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
    at(FAR, booking["id"])
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
                          "depart_at": _soon(), "seats_total": 3, "price": 300})
    assert r.status_code == 200, r.text
    ride = r.json()
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    at(FAR, booking["id"])
    assert client.post(f"/bookings/{booking['id']}/driver-status",
                       headers=drv["auth"], json={"status": "arriving"}).status_code == 200
