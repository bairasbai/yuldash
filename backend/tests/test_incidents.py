"""Система «Справедливость»: инциденты (двусторонний разбор) + лестница наказаний.

Покрываем жизненный цикл (подача → объяснение → решение), права сторон, анти-абуз (нельзя на
себя / не участника / без общей поездки), лестницу (3 страйка → пауза + гейт действий), апелляцию,
мир, идемпотентность решения, щит рейтинга (снятие оценки-мести).
"""
from app.db import engine
from app.models import Booking, BookingStatus, Rating, Ride, UserRole
from app.services import user_rating
from app.timeutil import utcnow
from sqlmodel import Session


def _booking(passenger_id, driver_id, status=BookingStatus.done):
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride); s.commit(); s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, status=status)
        s.add(b); s.commit(); s.refresh(b)
        return b.id


def _file(client, reporter, respondent_id, type="rude", booking_id=None, **extra):
    body = {"respondent_id": respondent_id, "type": type, "booking_id": booking_id, **extra}
    return client.post("/incidents", headers=reporter["auth"], json=body)


def test_full_lifecycle_strike(client, user_factory):
    drv = user_factory("IncDrv", role=UserRole.driver); pax = user_factory("IncPax")
    admin = user_factory("IncAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    r = _file(client, pax, drv["id"], booking_id=bid, description="грубил")
    assert r.status_code == 200
    inc = r.json()
    assert inc["status"] == "awaiting_response" and inc["my_role"] == "reporter" and inc["other_name"]
    iid = inc["id"]
    r2 = client.post(f"/incidents/{iid}/respond", headers=drv["auth"], json={"statement": "не грубил"})
    assert r2.status_code == 200 and r2.json()["status"] == "under_review"
    r3 = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                     json={"resolution": "strike", "fault": "respondent", "note": "подтверждено"})
    assert r3.status_code == 200 and r3.json()["status"] == "resolved"
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 1 and st["standing"] == "warned"


def test_cannot_report_self(client, user_factory):
    u = user_factory("SelfRep")
    assert _file(client, u, u["id"]).status_code == 400


def test_unknown_type(client, user_factory):
    pax = user_factory("UtPax"); drv = user_factory("UtDrv", role=UserRole.driver)
    bid = _booking(pax["id"], drv["id"])
    assert _file(client, pax, drv["id"], type="nonsense", booking_id=bid).status_code == 400


def test_respondent_must_be_in_booking(client, user_factory):
    pax = user_factory("NpPax"); drv = user_factory("NpDrv", role=UserRole.driver); other = user_factory("NpOther")
    bid = _booking(pax["id"], drv["id"])
    assert _file(client, pax, other["id"], booking_id=bid).status_code == 400


def test_non_severe_needs_booking(client, user_factory):
    pax = user_factory("NbPax"); drv = user_factory("NbDrv", role=UserRole.driver)
    assert _file(client, pax, drv["id"], type="rude", booking_id=None).status_code == 400


def test_severe_without_booking_allowed(client, user_factory):
    pax = user_factory("SvPax"); drv = user_factory("SvDrv", role=UserRole.driver)
    r = _file(client, pax, drv["id"], type="harassment", booking_id=None)
    assert r.status_code == 200 and r.json()["severe"] is True and r.json()["status"] == "under_review"


def test_respond_only_by_respondent(client, user_factory):
    pax = user_factory("RoPax"); drv = user_factory("RoDrv", role=UserRole.driver)
    bid = _booking(pax["id"], drv["id"])
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    assert client.post(f"/incidents/{iid}/respond", headers=pax["auth"], json={"statement": "x"}).status_code == 403


def test_resolve_admin_only(client, user_factory):
    pax = user_factory("RaPax"); drv = user_factory("RaDrv", role=UserRole.driver)
    bid = _booking(pax["id"], drv["id"])
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    assert client.post(f"/admin/incidents/{iid}/resolve", headers=pax["auth"], json={"resolution": "strike"}).status_code == 403


def test_withdraw_closes_without_strike(client, user_factory):
    pax = user_factory("WdPax"); drv = user_factory("WdDrv", role=UserRole.driver)
    bid = _booking(pax["id"], drv["id"])
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    r = client.post(f"/incidents/{iid}/withdraw", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "closed" and r.json()["resolution"] == "mutual_resolved"
    assert client.get("/me/standing", headers=drv["auth"]).json()["strikes"] == 0


def test_escalation_suspends_after_three_strikes_and_blocks_filing(client, user_factory):
    drv = user_factory("EsDrv", role=UserRole.driver); admin = user_factory("EsAdmin", role=UserRole.admin)
    for i in range(3):
        pax = user_factory(f"EsPax{i}")
        bid = _booking(pax["id"], drv["id"])
        iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
        client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                    json={"resolution": "strike", "fault": "respondent"})
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 3 and st["standing"] == "suspended" and st["can_act"] is False
    # приостановленный аккаунт не подаёт новую жалобу (ensure_active)
    pax2 = user_factory("EsPaxX"); bid2 = _booking(pax2["id"], drv["id"])
    assert _file(client, drv, pax2["id"], booking_id=bid2).status_code == 403


def test_appeal_moves_to_appealed(client, user_factory):
    pax = user_factory("ApPax"); drv = user_factory("ApDrv", role=UserRole.driver); admin = user_factory("ApAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"], json={"resolution": "strike", "fault": "respondent"})
    r = client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "несправедливо"})
    assert r.status_code == 200 and r.json()["status"] == "appealed" and r.json()["appeal_status"] == "requested"


def test_resolve_twice_conflicts(client, user_factory):
    pax = user_factory("R2Pax"); drv = user_factory("R2Drv", role=UserRole.driver); admin = user_factory("R2Admin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    assert client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"], json={"resolution": "warning"}).status_code == 200
    assert client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"], json={"resolution": "strike"}).status_code == 409


def test_resolve_exclude_rating_shields_target(client, user_factory):
    pax = user_factory("ShPax"); drv = user_factory("ShDrv", role=UserRole.driver); admin = user_factory("ShAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    with Session(engine) as s:
        s.add(Rating(booking_id=bid, rater_id=pax["id"], ratee_id=drv["id"], stars=1))   # месть-единица
        s.commit()
    with Session(engine) as s:
        assert user_rating(s, drv["id"])[1] == 1
    iid = _file(client, pax, drv["id"], booking_id=bid).json()["id"]
    client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                json={"resolution": "dismissed", "fault": "reporter", "exclude_rating": True})
    with Session(engine) as s:
        assert user_rating(s, drv["id"])[1] == 0   # оценка-месть снята из среднего (щит рейтинга)
