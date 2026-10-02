"""§8 CLAUDE.md / 152-ФЗ: телефон не должен попадать в лог своего сервера.

Три места в routers/safety.py ловят ошибку ПОБОЧНОГО действия (жалоба важнее, чем требование
фото / списание комиссии) и пишут её в лог сырым текстом: `log.warning(f"...: {e}")`. Если
исключение из чужого модуля (carphoto/debt) — как и в реальном примере этого же проекта,
`tests/test_audit_20260808.py::test_phone_never_reaches_the_server_log` — несёт в себе текст
SQLAlchemy-ошибки с параметрами запроса (а там бывает телефон), этот текст уходит в лог
ЦЕЛИКОМ, в обход готового инструмента `app.observability.scrub_exc`, которым для точно такой
же беды уже лечили общий обработчик ошибок (`app/middleware.py`, волна 2026-08-08 №14).

Человеческая цена: администратор или тот, кто разбирает инцидент, открывает текстовый лог
сервера (или его считывает выгрузка/сборщик логов) и видит номер телефона человека, который
даже не подавал и не был целью этой жалобы — просто рядом упал побочный платёж.

Правило файла: поймали исключение в этих трёх местах — текст в лог идёт ТОЛЬКО через
`scrub_exc`, как и в middleware.py.
"""
import logging

from sqlmodel import Session

from app.db import engine
from app.models import (Booking, BookingStatus, InstantOrder, InstantOrderStatus,
                        ParcelDelivery, Report, Ride, RideStatus, UserRole)
from app.timeutil import utcnow

FAKE_PHONE = "+79990001122"
FAKE_EXC_TEXT = (
    "(sqlite3.IntegrityError) UNIQUE constraint failed: user.phone "
    f"[parameters: ('{FAKE_PHONE}', 'ТестовыйЧеловек', 'Сибай')]"
)


def _admin(user_factory):
    return user_factory("LogPrivacyAdmin", role=UserRole.admin)


def test_сбой_списания_долга_по_заказу_не_пишет_телефон_в_лог(client, user_factory, monkeypatch, caplog):
    pax = user_factory("LogPrivPax1")
    drv = user_factory("LogPrivDrv1", role=UserRole.driver)
    admin = _admin(user_factory)
    with Session(engine) as s:
        order = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                             status=InstantOrderStatus.done, price_estimate=300, price_final=300)
        s.add(order)
        s.commit()
        s.refresh(order)
        report = Report(reporter_id=drv["id"], target_user_id=pax["id"], category="unpaid",
                        order_id=order.id, status="new")
        s.add(report)
        s.commit()
        s.refresh(report)
        rid, oid = report.id, order.id

    import app.debt as debt_mod

    def _boom(session, order_id, note=""):
        raise RuntimeError(FAKE_EXC_TEXT)

    monkeypatch.setattr(debt_mod, "void_debt_for_order", _boom)

    with caplog.at_level(logging.WARNING, logger="yuldash"):
        r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                        json={"resolution": "confirmed"})
    assert r.status_code == 200, r.text   # разбор жалобы не должен падать из-за побочки

    leaked = [rec.getMessage() for rec in caplog.records if FAKE_PHONE in rec.getMessage()]
    assert not leaked, f"телефон утёк в лог сервера при сбое списания долга: {leaked}"
    assert any("[DEBT]" in rec.getMessage() for rec in caplog.records), (
        "сам факт сбоя должен остаться виден в логе — просто без телефона"
    )


def test_сбой_списания_комиссии_по_доставке_не_пишет_телефон_в_лог(client, user_factory, monkeypatch, caplog):
    sender = user_factory("LogPrivSender1")
    courier = user_factory("LogPrivCourier1")
    admin = _admin(user_factory)
    with Session(engine) as s:
        parcel = ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"],
                                from_city="Сибай", to_city="Баймак",
                                commission_paid=True, commission_kop=500)
        s.add(parcel)
        s.commit()
        s.refresh(parcel)
        report = Report(reporter_id=sender["id"], target_user_id=courier["id"], category="unpaid",
                        parcel_id=parcel.id, status="new")
        s.add(report)
        s.commit()
        s.refresh(report)
        rid = report.id

    import app.debt as debt_mod

    def _boom(session, driver_id, amount_kop, **kw):
        raise RuntimeError(FAKE_EXC_TEXT)

    monkeypatch.setattr(debt_mod, "refund_commission_to_wallet", _boom)

    with caplog.at_level(logging.WARNING, logger="yuldash"):
        r = client.post(f"/admin/reports/{rid}/resolve", headers=admin["auth"],
                        json={"resolution": "confirmed"})
    assert r.status_code == 200, r.text

    leaked = [rec.getMessage() for rec in caplog.records if FAKE_PHONE in rec.getMessage()]
    assert not leaked, f"телефон утёк в лог сервера при сбое возврата комиссии: {leaked}"


def _live_booking(pax_id: int, drv_id: int) -> int:
    with Session(engine) as s:
        ride = Ride(driver_id=drv_id, from_city="Сибай", to_city="Баймак",
                   depart_at=utcnow(), seats_total=3, seats_left=2, price=300,
                   status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax_id, seats=1, price=300,
                   status=BookingStatus.confirmed)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def test_сбой_требования_фото_по_жалобе_не_пишет_телефон_в_лог(client, user_factory, monkeypatch, caplog):
    pax = user_factory("LogPrivPax2")
    drv = user_factory("LogPrivDrv2", role=UserRole.driver)
    bid = _live_booking(pax["id"], drv["id"])

    import app.carphoto as cp

    def _boom(session, report, mode):
        raise RuntimeError(FAKE_EXC_TEXT)

    monkeypatch.setattr(cp, "open_complaint", _boom)

    with caplog.at_level(logging.WARNING, logger="yuldash"):
        r = client.post("/reports", headers=pax["auth"],
                        json={"booking_id": bid, "category": "dirty_car", "reason": "грязно в салоне"})
    assert r.status_code == 200, r.text   # жалоба всё равно принята

    leaked = [rec.getMessage() for rec in caplog.records if FAKE_PHONE in rec.getMessage()]
    assert not leaked, f"телефон утёк в лог сервера при сбое требования фото: {leaked}"


def test_координаты_sos_не_попадают_в_лог_сервера(client, user_factory, monkeypatch, caplog):
    """§8: лог сервера — не то же самое, что SMS/Telegram. Координаты человека в беде уместны
    в ссылке на карту, которая уходит близким и дежурному, но не в текстовом логе на диске."""
    u = user_factory("LogPrivCoords")
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda phones, text: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda text, **kw: True)

    LAT, LNG = 53.123456, 58.654321
    with caplog.at_level(logging.INFO, logger="yuldash"):
        r = client.post("/sos", headers=u["auth"],
                        json={"category": "other", "lat": LAT, "lng": LNG})
    assert r.status_code == 200, r.text

    for rec in caplog.records:
        text = rec.getMessage()
        assert str(LAT) not in text and str(LNG) not in text, (
            f"координаты попали в лог сервера: {text}"
        )
