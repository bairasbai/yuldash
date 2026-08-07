"""F1: отмена и завершение поездки водителем (каскад по броням, права, идемпотентность)."""

from app.models import UserRole

from test_flows import _book, _publish, just_left


def test_driver_cancels_ride_cascades_bookings(client, user_factory):
    drv = user_factory("CancelDrv", role=UserRole.driver)
    pax1 = user_factory("CancelPax1")
    pax2 = user_factory("CancelPax2")
    ride = _publish(client, drv, frm="ОтменаА", to="ОтменаБ", seats=3)
    b1 = _book(client, pax1, ride["id"])
    b2 = _book(client, pax2, ride["id"])
    # чужой не может отменить
    outsider = user_factory("CancelOutsider", role=UserRole.driver)
    assert client.post(f"/rides/{ride['id']}/cancel", headers=outsider["auth"]).status_code == 403
    # владелец отменяет
    r = client.post(f"/rides/{ride['id']}/cancel", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    # брони каскадно отменены (видно пассажирам в /bookings/mine)
    for pax, b in ((pax1, b1), (pax2, b2)):
        mine = client.get("/bookings/mine", headers=pax["auth"]).json()
        row = next(x for x in mine if x["id"] == b["id"])
        assert row["status"] == "cancelled"
    # поездка ушла из выдачи
    assert all(x["id"] != ride["id"] for x in client.get("/rides", params={"from_city": "ОтменаА"}).json())
    # идемпотентно: повторная отмена — 200, не падает
    assert client.post(f"/rides/{ride['id']}/cancel", headers=drv["auth"]).status_code == 200


def test_driver_completes_ride(client, user_factory):
    drv = user_factory("DoneDrv", role=UserRole.driver)
    pax = user_factory("DonePax")
    pending_pax = user_factory("DonePendingPax")
    # Рейс УЖЕ выехал: завершить можно только начавшуюся поездку — это третья дверь к тому же
    # переходу, и она закрыта той же планкой, что водительская и пассажирская
    # (независимая проверка аудита 2026-08-07). По смыслу теста рейс как раз состоялся.
    ride = _publish(client, drv, frm="ФинишА", to="ФинишБ", seats=3, depart_at=just_left())
    b_confirmed = _book(client, pax, ride["id"])
    client.post(f"/bookings/{b_confirmed['id']}/confirm", headers=drv["auth"])
    b_pending = _book(client, pending_pax, ride["id"])   # осталась pending
    r = client.post(f"/rides/{ride['id']}/complete", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["status"] == "done"
    # подтверждённая бронь → done, ожидающая → cancelled (рейс кончился)
    mine = client.get("/bookings/mine", headers=pax["auth"]).json()
    assert next(x for x in mine if x["id"] == b_confirmed["id"])["status"] == "done"
    mine2 = client.get("/bookings/mine", headers=pending_pax["auth"]).json()
    assert next(x for x in mine2 if x["id"] == b_pending["id"])["status"] == "cancelled"
    # идемпотентно
    assert client.post(f"/rides/{ride['id']}/complete", headers=drv["auth"]).status_code == 200
    # отменённую завершить нельзя / завершённую отменить нельзя
    ride2 = _publish(client, drv, frm="ФинишВ", to="ФинишГ")
    client.post(f"/rides/{ride2['id']}/cancel", headers=drv["auth"])
    assert client.post(f"/rides/{ride2['id']}/complete", headers=drv["auth"]).status_code == 400
    assert client.post(f"/rides/{ride['id']}/cancel", headers=drv["auth"]).status_code == 400
