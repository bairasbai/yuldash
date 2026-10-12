"""N6+N7 (независимое ревью): жалоба — ровно ОДНА привязка к поездке/заказу/доставке.

N7: `ReportIn` принимала `order_id`, `booking_id` и `parcel_id` одновременно, и все три
сохранялись в жалобу. Четыре функции разбора (вторая сторона, «не заплатил», дедуп, «была ли
встреча») проверяют их в РАЗНОМ порядке — непроверенная вторая/третья привязка потом
используется побочками разбора (списание/возврат комиссии курьеру X, страйк пассажиру S),
хотя стороны никогда не пересекались. Правка: ровно одна привязка, иначе 400.

N6: `_dedup_report` не знала `parcel_id` — вторая жалоба «не заплатили» по ДРУГОЙ посылке
того же отправителя в тот же день молча склеивалась с первой (ветка «без привязки, окно
суток»): комиссия за вторую посылку не возвращалась курьеру.
"""
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, ParcelDelivery, UserRole


def _done_order(pax_id: int, drv_id: int) -> int:
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax_id, driver_id=drv_id, status=S.done,
                         price_estimate=300, price_final=300)
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _booking_done(pax_id: int, drv_id: int) -> int:
    from app.models import Booking, BookingStatus, Ride, RideStatus
    from app.timeutil import utcnow
    with Session(engine) as s:
        ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Баймак",
                   depart_at=utcnow(), seats_total=3, seats_left=2, price=300,
                   status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=300,
                   status=BookingStatus.done)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def test_заказ_плюс_чужая_доставка_отклоняется_400(client, user_factory):
    """N7: водитель подаёт unpaid по своему заказу и дописывает parcel_id чужой доставки —
    раньше проверялся только заказ, и побочки разбора били бы по чужому курьеру."""
    drv = user_factory("N7Drv1", role=UserRole.driver)
    pax = user_factory("N7Pax1")
    stranger_sender = user_factory("N7Sender1")
    stranger_courier = user_factory("N7Courier1")
    oid = _done_order(pax["id"], drv["id"])
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=stranger_sender["id"], courier_id=stranger_courier["id"],
                           from_city="Сибай", to_city="Баймак", commission_paid=True,
                           commission_kop=500)
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id

    r = client.post("/reports", headers=drv["auth"],
                    json={"order_id": oid, "parcel_id": pid, "category": "unpaid",
                          "reason": "не заплатил"})
    assert r.status_code == 400, f"жалоба с двумя привязками должна отклоняться: {r.status_code} {r.text}"

    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        assert p.commission_kop == 500, "чужая доставка не должна быть тронута отклонённой жалобой"


def test_заказ_плюс_несуществующая_бронь_отклоняется_400_не_500(client, user_factory):
    """N7: несуществующий booking_id в связке раньше давал 500, теперь — понятный 400
    (привязка больше одной — отказ ДО того, как сервер полез проверять вторую)."""
    drv = user_factory("N7Drv2", role=UserRole.driver)
    pax = user_factory("N7Pax2")
    oid = _done_order(pax["id"], drv["id"])

    r = client.post("/reports", headers=drv["auth"],
                    json={"order_id": oid, "booking_id": 999999999, "category": "unpaid"})
    assert r.status_code == 400, r.text


def test_одна_привязка_по_прежнему_работает(client, user_factory):
    """Контроль: обычная жалоба с ОДНОЙ привязкой (как раньше) проходит нормально."""
    drv = user_factory("N7Drv3", role=UserRole.driver)
    pax = user_factory("N7Pax3")
    oid = _done_order(pax["id"], drv["id"])
    r = client.post("/reports", headers=drv["auth"],
                    json={"order_id": oid, "category": "unpaid"})
    assert r.status_code == 200, r.text


def test_две_разные_посылки_в_день_дают_две_жалобы_и_два_возврата(client, user_factory):
    """N6: без parcel_id в дедупе вторая жалоба за день молча склеивалась с первой."""
    courier = user_factory("N6Courier1")
    sender = user_factory("N6Sender1")
    with Session(engine) as s:
        p1 = ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"],
                            from_city="Сибай", to_city="Баймак", status="delivered",
                            commission_paid=True, commission_kop=300)
        p2 = ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"],
                            from_city="Сибай", to_city="Акъяр", status="delivered",
                            commission_paid=True, commission_kop=400)
        s.add(p1); s.add(p2)
        s.commit()
        s.refresh(p1); s.refresh(p2)
        pid1, pid2 = p1.id, p2.id

    r1 = client.post("/reports", headers=courier["auth"],
                     json={"parcel_id": pid1, "category": "unpaid"})
    r2 = client.post("/reports", headers=courier["auth"],
                     json={"parcel_id": pid2, "category": "unpaid"})
    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json()["id"] != r2.json()["id"], (
        "жалоба по ВТОРОЙ, другой посылке не должна склеиваться с первой — "
        "иначе курьер теряет возврат комиссии за вторую доставку"
    )
