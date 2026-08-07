"""Из статуса «водитель найден» у заявки должен быть выход.

Приняли отклик — заявка становится `matched`. Дальше из этого статуса не ведёт НИ ОДНА дверь:
отмена требует `active`, правка требует `active`, ночная чистка закрывает тоже только `active`.
Если сделка развалилась (бронь отменили, водитель пропал), заявка остаётся «в работе» навсегда,
а чужие отклики на ней навсегда висят `offered`.

Отмена — это тот самый выход, и она ничего не ломает: заявка — это объявление «ищу машину»,
а не сама поездка. Поездка и бронь живут своей жизнью, их отменяют отдельно.
"""
import uuid

from app.models import UserRole


def _city():
    return "Город" + uuid.uuid4().hex[:8]


def _deal(client, pax, drv):
    """Заявка → отклик → принятие. Возвращает (заявка, бронь)."""
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": _city(), "to_city": _city(), "seats": 1}).json()
    resp = client.post(f"/requests/{req['id']}/respond", headers=drv["auth"], json={"price": 300})
    assert resp.status_code == 200, resp.text
    accepted = client.post(f"/responses/{resp.json()['id']}/accept", headers=pax["auth"])
    assert accepted.status_code == 200, accepted.text
    return req, accepted.json()["booking_id"]


def test_matched_request_can_be_cancelled_after_the_deal_falls_apart(client, user_factory):
    pax = user_factory("ТупикПас")
    drv = user_factory("ТупикВод", role=UserRole.driver)
    req, booking_id = _deal(client, pax, drv)
    # водитель через час передумал — бронь отменена, а заявка «в работе» до скончания века
    assert client.post(f"/bookings/{booking_id}/cancel", headers=drv["auth"]).status_code == 200

    r = client.post(f"/requests/{req['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "cancelled"


def test_cancel_of_matched_request_is_idempotent(client, user_factory):
    """Повторный тап — не ошибка (как и у активной заявки)."""
    pax = user_factory("ТупикПас2")
    drv = user_factory("ТупикВод2", role=UserRole.driver)
    req, _ = _deal(client, pax, drv)
    assert client.post(f"/requests/{req['id']}/cancel", headers=pax["auth"]).status_code == 200
    r = client.post(f"/requests/{req['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"


def test_stranger_still_cannot_cancel_matched_request(client, user_factory):
    """Выход открыт владельцу, а не кому попало."""
    pax = user_factory("ТупикПас3")
    drv = user_factory("ТупикВод3", role=UserRole.driver)
    stranger = user_factory("ТупикЧужой")
    req, _ = _deal(client, pax, drv)
    assert client.post(f"/requests/{req['id']}/cancel", headers=stranger["auth"]).status_code == 403


def test_cancelling_request_does_not_touch_the_booking(client, user_factory):
    """Заявка — объявление, а не поездка: её отмена не отменяет уже созданную бронь."""
    pax = user_factory("ТупикПас4")
    drv = user_factory("ТупикВод4", role=UserRole.driver)
    req, booking_id = _deal(client, pax, drv)
    assert client.post(f"/requests/{req['id']}/cancel", headers=pax["auth"]).status_code == 200

    details = client.get(f"/bookings/{booking_id}/details", headers=pax["auth"])
    assert details.status_code == 200
    assert details.json()["status"] == "confirmed"
