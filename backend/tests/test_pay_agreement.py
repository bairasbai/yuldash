"""F10: договорённость об оплате в брони (pay_method + pay_amount).

Это ЗАПИСЬ договорённости («как решили платить»), НЕ платёж и не движение денег.
Проверяем: запись способа/суммы при создании, дефолт (способ «договоримся» + сумма из
цены поездки), видимость обеим сторонам, правку через /pay-agreement, валидацию enum и суммы.
"""
from app.models import UserRole


def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _pair(client, user_factory, price=300):
    drv = user_factory("PayDrv", role=UserRole.driver)
    pax = user_factory("PayPax")
    ride = _publish(client, drv, price=price)
    return drv, pax, ride


def test_pay_agreement_recorded_on_book(client, user_factory):
    """Способ и сумма, заданные при бронировании, сохраняются."""
    drv, pax, ride = _pair(client, user_factory)
    r = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "seats": 1, "pay_method": "cash", "pay_amount": 400})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["pay_method"] == "cash"
    assert b["pay_amount"] == 400


def test_pay_agreement_default_from_ride_price(client, user_factory):
    """Дефолт: способ «договоримся», сумма — из цены поездки (price*seats), если не задана."""
    drv, pax, ride = _pair(client, user_factory, price=350)
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 2})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["pay_method"] == "negotiate"
    assert b["pay_amount"] == 700   # 350 * 2 мест


def test_pay_agreement_visible_to_both_sides(client, user_factory):
    """Договорённость видна и пассажиру, и водителю в деталях брони."""
    drv, pax, ride = _pair(client, user_factory)
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "pay_method": "sbp", "pay_amount": 500}).json()
    bid = booking["id"]
    pax_view = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    drv_view = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert pax_view["pay_method"] == "sbp" and pax_view["pay_amount"] == 500
    assert drv_view["pay_method"] == "sbp" and drv_view["pay_amount"] == 500


def test_pay_agreement_invalid_method_rejected(client, user_factory):
    """Неизвестный способ оплаты не принимается (валидация enum)."""
    drv, pax, ride = _pair(client, user_factory)
    r = client.post("/bookings", headers=pax["auth"],
                    json={"ride_id": ride["id"], "pay_method": "bitcoin"})
    assert r.status_code == 422, r.text


def test_pay_agreement_invalid_amount_rejected(client, user_factory):
    """Мусорная сумма (отрицательная / нереально большая) отклоняется."""
    drv, pax, ride = _pair(client, user_factory)
    assert client.post("/bookings", headers=pax["auth"],
                       json={"ride_id": ride["id"], "pay_amount": -5}).status_code == 400
    assert client.post("/bookings", headers=pax["auth"],
                       json={"ride_id": ride["id"], "pay_amount": 10_000_000}).status_code == 400


def test_pay_agreement_editable_by_both_parties(client, user_factory):
    """Обе стороны могут поправить договорённость; изменение видно обоим."""
    drv, pax, ride = _pair(client, user_factory)
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "pay_method": "negotiate"}).json()
    bid = booking["id"]
    # Водитель фиксирует способ.
    r1 = client.post(f"/bookings/{bid}/pay-agreement", headers=drv["auth"], json={"pay_method": "cash"})
    assert r1.status_code == 200 and r1.json()["pay_method"] == "cash"
    # Пассажир уточняет сумму (способ не трогает — остаётся cash).
    r2 = client.post(f"/bookings/{bid}/pay-agreement", headers=pax["auth"], json={"pay_amount": 450})
    assert r2.status_code == 200 and r2.json()["pay_amount"] == 450 and r2.json()["pay_method"] == "cash"
    # Обе стороны видят итог.
    for who in (pax, drv):
        v = client.get(f"/bookings/{bid}/details", headers=who["auth"]).json()
        assert v["pay_method"] == "cash" and v["pay_amount"] == 450


def test_pay_agreement_edit_forbidden_for_outsider(client, user_factory):
    """Посторонний не может менять чужую договорённость."""
    drv, pax, ride = _pair(client, user_factory)
    outsider = user_factory("Outsider")
    booking = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"]}).json()
    r = client.post(f"/bookings/{booking['id']}/pay-agreement", headers=outsider["auth"],
                    json={"pay_method": "cash"})
    assert r.status_code == 403
