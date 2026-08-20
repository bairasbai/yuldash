"""Одно длинное поле не должно топить ленту у всего района.

История. У поездки есть строка «остановки по пути». Потолка длины у неё не было, и туда
влезало сколько угодно — проверено запросом: двести двадцать тысяч символов сохранились
и стали частью карточки (аудит 2026-08-08, волна 96).

Дальше эта карточка попадает в КАЖДЫЙ ответ ленты. Пять таких поездок — и лента весит
мегабайты. На сельском интернете это не «медленнее», а «экран не открывается» — причём
у всех подряд, а не у того, кто написал.

Тест держит обе стороны: слишком длинное не принимаем, а обычные остановки («Тубинский,
поворот на Юлук») по-прежнему сохраняются и видны.
"""
from __future__ import annotations

from app.models import UserRole

NORMAL_STOPS = "Тубинский, поворот на Юлук, АЗС у Сибая"


def _publish(client, driver, waypoints: str):
    return client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-10-01T10:00:00", "seats_total": 3, "price": 300,
        "waypoints": waypoints,
    })


def test_гигантские_остановки_не_принимаем(client, user_factory):
    driver = user_factory("ДлинныйТекстВодитель", role=UserRole.driver)

    r = _publish(client, driver, "остановка, " * 20000)

    # 422 — поле не прошло проверку длины; 413 — запрос целиком отбит по размеру ещё
    # на входе (волна 148 поставила потолок на тело запроса). Оба ответа означают одно:
    # такая карточка в ленту не попала. Важно, что не 200.
    assert r.status_code in (413, 422), \
        f"поле «остановки» приняло {len('остановка, ' * 20000)} символов — такая карточка утопит ленту"


def test_обычные_остановки_работают_как_прежде(client, user_factory):
    """Обратная сторона: поставив потолок, нельзя отобрать нормальный сценарий."""
    driver = user_factory("ОбычныеОстановкиВодитель", role=UserRole.driver)

    r = _publish(client, driver, NORMAL_STOPS)

    assert r.status_code == 200, r.text
    assert r.json().get("waypoints") == NORMAL_STOPS


def test_длинная_причина_отмены_не_проходит(client, user_factory):
    """В поле «почему отменил» ждут код из пресетов, а не сочинение."""
    driver = user_factory("ОтменаВодитель", role=UserRole.driver)
    passenger = user_factory("ОтменаПассажир")
    ride_id = _publish(client, driver, NORMAL_STOPS).json()["id"]
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]

    r = client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"],
                    json={"reason": "потому что " * 5000})

    assert r.status_code == 422, "причина отмены приняла мегабайтный текст"


def test_обычная_отмена_проходит(client, user_factory):
    driver = user_factory("ОтменаОкВодитель", role=UserRole.driver)
    passenger = user_factory("ОтменаОкПассажир")
    ride_id = _publish(client, driver, NORMAL_STOPS).json()["id"]
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]

    r = client.post(f"/bookings/{booking_id}/cancel", headers=passenger["auth"],
                    json={"reason": "changed_mind"})

    assert r.status_code == 200, r.text
