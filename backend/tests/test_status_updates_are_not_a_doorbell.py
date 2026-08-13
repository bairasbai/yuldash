"""Кнопка «Выехал» — не дверной звонок.

История. Водитель отмечает «выехал» и «подъезжаю», пассажир получает уведомление и выходит
к машине. Правильно и нужно. Но уведомление уходило на КАЖДОЕ нажатие: тридцать раз подряд
«Водитель выехал» одному человеку, молча, без единого слова (проверено запросом, аудит
2026-08-12, волна 52).

Обидно то, что правило уже существовало рядом: SMS близким по тому же поводу шлются «только
тем, у кого статус реально сменился». У пуша пассажиру его просто забыли.

Здесь же проверяется общий предохранитель: сколько бы новых мест ни научилось будить человека,
по одному объекту (броне, заявке) он не получит больше горстки уведомлений в минуту.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.config import settings
from app.models import UserRole
from app.services import _NOTIFY_STORM_PER_MIN
from app.timeutil import utcnow


@pytest.fixture
def pushes(monkeypatch) -> list:
    got: list = []
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, **k: got.append((uid, title)))
    return got


def _local(delta: timedelta) -> str:
    return (utcnow() + timedelta(hours=settings.local_tz_offset_hours) + delta).replace(
        microsecond=0).isoformat()


def _ride_with_booking(client, user_factory, tag: str):
    drv = user_factory(f"{tag}Drv", role=UserRole.driver)
    pax = user_factory(f"{tag}Pax", role=UserRole.passenger)
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats_total": 4, "price": 500,
        "depart_at": _local(timedelta(days=1)),
    }).json()
    b = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "seats": 1}).json()
    assert client.post(f"/bookings/{b['id']}/confirm", headers=drv["auth"]).status_code == 200
    return drv, pax, b["id"]


def test_тридцать_нажатий_выехал_это_одно_уведомление(client, user_factory, pushes):
    drv, pax, bid = _ride_with_booking(client, user_factory, "Doorbell")
    pushes.clear()

    for _ in range(30):
        r = client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                        json={"status": "departed"})
        assert r.status_code == 200, r.text      # нажимать не запрещаем — просто не повторяем новость

    assert len([x for x in pushes if x[0] == pax["id"]]) == 1


def test_настоящая_смена_фазы_доходит(client, user_factory, pushes):
    """Контроль: «выехал» → «подъезжаю» — это новость, и пассажир обязан её получить."""
    drv, pax, bid = _ride_with_booking(client, user_factory, "DoorbellReal")
    pushes.clear()

    client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})

    titles = [t for uid, t in pushes if uid == pax["id"]]
    assert titles == ["Водитель выехал", "Водитель подъезжает"]


def test_качели_между_фазами_упираются_в_предохранитель(client, user_factory, pushes):
    """Каждое переключение формально «новость», поэтому одной проверки смены фазы мало.
    Общий потолок по объекту не даёт превратить это в звонок в карман."""
    drv, pax, bid = _ride_with_booking(client, user_factory, "DoorbellSwing")
    pushes.clear()

    for i in range(30):
        client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"],
                    json={"status": "departed" if i % 2 else "arriving"})

    assert len([x for x in pushes if x[0] == pax["id"]]) <= _NOTIFY_STORM_PER_MIN


def test_обычная_поездка_все_уведомления_получает(client, user_factory, pushes):
    """Страховка от перестраховки: за поездку пассажир узнаёт обо всём — подтверждение,
    выезд, подача, завершение. Предохранитель в такие цифры не вмешивается."""
    drv, pax, bid = _ride_with_booking(client, user_factory, "DoorbellNormal")
    titles_before = len([x for x in pushes if x[0] == pax["id"]])

    client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "departed"})
    client.post(f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "arriving"})

    got = len([x for x in pushes if x[0] == pax["id"]]) - titles_before
    assert got == 2
