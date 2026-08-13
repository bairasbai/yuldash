"""Нельзя звонить человеку в карман всю ночь бронями и откликами.

История. Каждая бронь будит водителя уведомлением, отмена брони — будит второй раз. Каждый
отклик на заявку будит пассажира. Это правильно: люди должны узнавать о делах сразу.

Но отменить бронь и забронировать снова можно было мгновенно и сколько угодно: сорок кругов
подряд — восемьдесят уведомлений одному человеку. У откликов было то же самое через «отозвать
и откликнуться заново», и там пряталась вторая беда: отзыв УДАЛЯЛ строку отклика, то есть
стирал единственный след того, что отклик был. Считать было нечего.

В чате от этого защищались давно («триста сообщений ночью — травля кнопкой, а не словами»),
а здесь тот же способ достать человека работал в обход: молча, без единого слова
(проверено запросами, аудит 2026-08-12, волна 51).
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.config import settings
from app.models import UserRole
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


def _ride(client, driver) -> int:
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats_total": 4, "price": 500,
        "depart_at": _local(timedelta(days=1)),
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _request(client, passenger, tag: str) -> int:
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": tag, "to_city": "Уфа", "seats": 1, "comment": "еду",
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_круг_бронь_отмена_не_будит_водителя_бесконечно(client, user_factory, pushes):
    drv = user_factory("RingDrv", role=UserRole.driver)
    pax = user_factory("RingPax", role=UserRole.passenger)
    ride_id = _ride(client, drv)
    pushes.clear()

    refused = 0
    for _ in range(30):
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1})
        if b.status_code == 429:
            refused += 1
            continue
        assert b.status_code == 200, b.text
        client.post(f"/bookings/{b.json()['id']}/cancel", headers=pax["auth"])

    assert refused > 0
    to_driver = [x for x in pushes if x[0] == drv["id"]]
    # Бронь + отмена = два уведомления, поэтому потолок в штуках виден именно так.
    assert len(to_driver) <= settings.flood_create_per_minute * 2


def test_отзыв_отклика_не_обнуляет_счёт(client, user_factory, pushes):
    """Главное в находке: отзыв удалял строку, и всё начиналось сначала."""
    pax = user_factory("RingReqPax", role=UserRole.passenger)
    drv = user_factory("RingReqDrv", role=UserRole.driver)
    req_id = _request(client, pax, "RingA")
    pushes.clear()

    refused = 0
    for i in range(30):
        r = client.post(f"/requests/{req_id}/respond", headers=drv["auth"], json={"price": 500 + i})
        if r.status_code == 429:
            refused += 1
            continue
        assert r.status_code == 200, r.text
        client.delete(f"/responses/{r.json()['id']}", headers=drv["auth"])

    assert refused > 0
    to_pax = [x for x in pushes if x[0] == pax["id"]]
    assert len(to_pax) <= settings.flood_create_per_minute


def test_отозванный_отклик_пассажиру_не_виден_и_принять_его_нельзя(client, user_factory):
    """Строка теперь остаётся в базе — но только как след. Для пассажира предложения нет."""
    pax = user_factory("RingHidePax", role=UserRole.passenger)
    drv = user_factory("RingHideDrv", role=UserRole.driver)
    req_id = _request(client, pax, "RingB")

    resp_id = client.post(f"/requests/{req_id}/respond", headers=drv["auth"],
                          json={"price": 400}).json()["id"]
    assert client.delete(f"/responses/{resp_id}", headers=drv["auth"]).status_code == 200

    listed = client.get(f"/requests/{req_id}/responses", headers=pax["auth"]).json()
    assert all(r["id"] != resp_id for r in listed)
    assert client.post(f"/responses/{resp_id}/accept", headers=pax["auth"]).status_code == 409


def test_водитель_может_вернуться_к_заявке(client, user_factory):
    """Передумал — откликается заново, и это новый живой отклик, а не «уже откликался»."""
    pax = user_factory("RingBackPax", role=UserRole.passenger)
    drv = user_factory("RingBackDrv", role=UserRole.driver)
    req_id = _request(client, pax, "RingC")

    first = client.post(f"/requests/{req_id}/respond", headers=drv["auth"],
                        json={"price": 400}).json()["id"]
    client.delete(f"/responses/{first}", headers=drv["auth"])

    again = client.post(f"/requests/{req_id}/respond", headers=drv["auth"], json={"price": 450})
    assert again.status_code == 200
    assert again.json()["id"] != first

    listed = client.get(f"/requests/{req_id}/responses", headers=pax["auth"]).json()
    assert [r["id"] for r in listed] == [again.json()["id"]]

    # В ленте водителя старый отклик тоже не «висит»: кнопка снова живая.
    feed = client.get("/requests/feed", headers=drv["auth"]).json()
    row = next(x for x in feed if x["id"] == req_id)
    assert row["my_response_id"] == again.json()["id"]


def test_обычная_бронь_и_отклик_проходят(client, user_factory, pushes):
    """Страховка от перестраховки: один человек, одна бронь, один отклик — всё как раньше."""
    drv = user_factory("RingOkDrv", role=UserRole.driver)
    pax = user_factory("RingOkPax", role=UserRole.passenger)
    ride_id = _ride(client, drv)
    assert client.post("/bookings", headers=pax["auth"],
                       json={"ride_id": ride_id, "seats": 1}).status_code == 200

    req_id = _request(client, pax, "RingD")
    assert client.post(f"/requests/{req_id}/respond", headers=drv["auth"],
                       json={"price": 500}).status_code == 200
