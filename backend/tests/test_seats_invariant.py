"""Число свободных мест обязано сходиться с бронями — после ЛЮБОЙ последовательности действий.

Почему это отдельный тест, а не «и так понятно». Счётчик мест меняется в пяти разных местах:
при бронировании, при отмене пассажиром, при отмене водителем, при правке поездки, при
авто-создании поездки из заявки. Каждое место писали отдельно, и сходиться они обязаны все
вместе. Если одно забудет вернуть место, поездка молча теряет вместимость: водитель везёт
троих, а приложение показывает «мест нет» — и четвёртый попутчик не сядет, хотя место есть.
Обратная ошибка хуже: мест «остаётся» больше, чем в машине, и на трассе окажется лишний
человек.

Инвариант один и простой: **свободных мест = всего мест − сумма мест по живым броням**.
Живые — это ожидающие подтверждения и подтверждённые; отменённые и завершённые место не держат.

Тест гоняет разные последовательности и после каждой сверяет инвариант. Считает он не по
счётчику, а по самим броням — то есть проверяет счётчик независимым способом.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, UserRole

from test_api import _ride

_LIVE = (BookingStatus.pending, BookingStatus.confirmed)


def _check(ride_id: int, where: str) -> None:
    """Сверить счётчик мест с реальными бронями."""
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        live = list(s.exec(select(Booking).where(
            Booking.ride_id == ride_id, Booking.status.in_(_LIVE))).all())
    taken = sum(b.seats for b in live)
    expected = ride.seats_total - taken
    assert ride.seats_left == expected, (
        f"{where}: счётчик мест разошёлся с бронями. Всего {ride.seats_total}, "
        f"занято по броням {taken}, должно остаться {expected}, а в поездке {ride.seats_left}. "
        f"Живых броней: {[(b.id, b.status, b.seats) for b in live]}"
    )


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, f"бронь не создалась: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def test_бронь_и_отмена_пассажиром_сходятся(client, user_factory):
    driver = user_factory("SeatsA_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    _check(ride_id, "сразу после публикации")

    pax = user_factory("SeatsA_Pax")
    bid = _book(client, pax, ride_id)
    _check(ride_id, "после брони")

    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"],
                       json={"reason": ""}).status_code == 200
    _check(ride_id, "после отмены пассажиром")


def test_подтверждение_место_не_трогает(client, user_factory):
    """Место занимается при бронировании; подтверждение — это ответ водителя, а не второе место."""
    driver = user_factory("SeatsB_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("SeatsB_Pax")
    bid = _book(client, pax, ride_id)
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    _check(ride_id, "после подтверждения")


def test_отмена_водителем_возвращает_место(client, user_factory):
    driver = user_factory("SeatsC_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("SeatsC_Pax")
    bid = _book(client, pax, ride_id)
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    r = client.post(f"/bookings/{bid}/cancel", headers=driver["auth"], json={"reason": ""})
    assert r.status_code == 200, f"водитель не смог отменить бронь: {r.status_code} {r.text[:200]}"
    _check(ride_id, "после отмены водителем")


def test_несколько_пассажиров_и_частичные_отмены(client, user_factory):
    """Самый близкий к жизни случай: трое записались, один передумал, другого отменил водитель."""
    driver = user_factory("SeatsD_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=4)
    bookings = []
    for i in range(3):
        pax = user_factory(f"SeatsD_Pax{i}")
        bookings.append((pax, _book(client, pax, ride_id)))
        _check(ride_id, f"после брони №{i + 1}")

    (pax0, bid0), (pax1, bid1), (pax2, bid2) = bookings
    client.post(f"/bookings/{bid0}/confirm", headers=driver["auth"])
    _check(ride_id, "после подтверждения первого")

    assert client.post(f"/bookings/{bid1}/cancel", headers=pax1["auth"],
                       json={"reason": ""}).status_code == 200
    _check(ride_id, "после отмены вторым")

    client.post(f"/bookings/{bid2}/confirm", headers=driver["auth"])
    client.post(f"/bookings/{bid2}/cancel", headers=driver["auth"], json={"reason": ""})
    _check(ride_id, "после отмены третьего водителем")


def test_бронь_на_несколько_мест_считается_целиком(client, user_factory):
    """«Едем вдвоём» — два места одной бронью. Возвращаться они должны тоже вдвоём."""
    driver = user_factory("SeatsE_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=4)
    pax = user_factory("SeatsE_Pax")
    bid = _book(client, pax, ride_id, seats=2)
    _check(ride_id, "после брони на два места")
    assert client.post(f"/bookings/{bid}/cancel", headers=pax["auth"],
                       json={"reason": ""}).status_code == 200
    _check(ride_id, "после отмены брони на два места")


def test_двойная_отмена_не_возвращает_место_дважды(client, user_factory):
    """Повторный тап по «Отменить» не должен добавить поездке несуществующее место —
    иначе на трассе окажется лишний человек."""
    driver = user_factory("SeatsF_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("SeatsF_Pax")
    bid = _book(client, pax, ride_id)
    client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": ""})
    client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": ""})
    _check(ride_id, "после двойной отмены")


def test_с_активными_бронями_число_мест_не_меняют(client, user_factory):
    """Сервер отказывает — и правильно делает: пересчитать счётчик под уже занятые места
    можно только зная, чьи брони отменить. Отказ честнее, чем тихий пересчёт.

    Проверяем именно отказ, а не «как-нибудь пересчиталось»: молчаливый пересчёт — это ровно
    тот способ, которым счётчик мест обычно и расходится с реальностью."""
    driver = user_factory("SeatsG_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("SeatsG_Pax")
    _book(client, pax, ride_id)
    r = client.post(f"/rides/{ride_id}/edit", headers=driver["auth"], json={"seats_total": 4})
    assert r.status_code == 409, (
        f"число мест поменялось при живой брони: {r.status_code} {r.text[:200]}"
    )
    _check(ride_id, "после отказа в правке мест")


def test_без_броней_число_мест_править_можно(client, user_factory):
    """Обратная сторона: пока никто не записался, водитель волен менять вместимость."""
    driver = user_factory("SeatsG2_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    r = client.post(f"/rides/{ride_id}/edit", headers=driver["auth"], json={"seats_total": 4})
    assert r.status_code == 200, f"правка без броней не прошла: {r.status_code} {r.text[:200]}"
    _check(ride_id, "после правки мест без броней")


def test_нельзя_забронировать_больше_чем_есть(client, user_factory):
    """Прямая проверка потолка: сумма броней не должна перевалить за вместимость."""
    driver = user_factory("SeatsH_Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    p1 = user_factory("SeatsH_Pax1")
    p2 = user_factory("SeatsH_Pax2")
    _book(client, p1, ride_id, seats=2)
    over = client.post("/bookings", headers=p2["auth"], json={"ride_id": ride_id, "seats": 1})
    assert over.status_code != 200, "продали место, которого в машине нет"
    _check(ride_id, "после попытки взять лишнее место")


@pytest.mark.parametrize("seats_total", [1, 2, 8])
def test_инвариант_держится_на_границах(client, user_factory, seats_total):
    """Односместная и полная машина — крайние случаи, где ошибка на единицу заметнее всего."""
    driver = user_factory(f"SeatsI_Drv{seats_total}", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=seats_total)
    ids = []
    for i in range(seats_total):
        pax = user_factory(f"SeatsI_Pax{seats_total}_{i}")
        ids.append((pax, _book(client, pax, ride_id)))
        _check(ride_id, f"машина на {seats_total}: бронь №{i + 1}")
    for pax, bid in ids:
        client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": ""})
        _check(ride_id, f"машина на {seats_total}: отмена")
