"""Тесты системы «Справедливость» (Trust, Safety & Fairness).

Контракт: docs/trust-safety.md §5–§6, §8. Проверяем: создание инцидента и права,
respond/withdraw/appeal, admin resolve → страйк/standing/пауза по лестнице §2,
no-show создаёт инцидент, exclude_rating убирает оценку из среднего, приватность
(телефон не течёт участнику), нельзя на себя, гейты статусов, отмена с причиной,
надёжность, /me/standing, /users/{id}/trust, /safety/policy.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Ride, RideStatus
from app.models import UserRole
from app.timeutil import utcnow
from datetime import timedelta


# ----------------------------- helpers -----------------------------
def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _confirm(client, drv, bid):
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200


def _done(client, pax, bid):
    assert client.post(f"/bookings/{bid}/trip-status", headers=pax["auth"], json={"status": "done"}).status_code == 200


def _rate(client, who, bid, stars):
    r = client.post(f"/bookings/{bid}/rate", headers=who["auth"], json={"stars": stars})
    assert r.status_code == 200, r.text
    return r.json()


def _trip(client, user_factory):
    drv = user_factory("Drv", role=UserRole.driver)
    pax = user_factory("Pax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    return drv, pax, ride, booking


def _file(client, reporter, respondent_id, itype, booking_id=None, description="проблема"):
    body = {"respondent_id": respondent_id, "type": itype, "description": description}
    if booking_id is not None:
        body["booking_id"] = booking_id
    return client.post("/incidents", headers=reporter["auth"], json=body)


def _set_departed(ride_id, minutes_ago=5):
    """Сдвинуть время выезда в прошлое (для no-show/поздней отмены — API не даёт публиковать прошлым)."""
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(minutes=minutes_ago)
        s.add(r)
        s.commit()


# ----------------------------- создание инцидента -----------------------------
def test_file_incident_happy(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    r = _file(client, pax, drv["id"], "rude", booking_id=booking["id"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["type"] == "rude"
    assert body["my_role"] == "reporter"
    assert body["other_name"] == "Drv"
    assert body["status"] == "awaiting_response"
    assert body["reporter_role"] == "passenger"
    assert body["booking_route"] == "Баймак→Сибай"


def test_cannot_report_self(client, user_factory):
    pax = user_factory("SelfRep")
    assert _file(client, pax, pax["id"], "rude").status_code == 400


def test_unknown_type_rejected(client, user_factory):
    a = user_factory("A"); b = user_factory("B")
    assert _file(client, a, b["id"], "not_a_real_type").status_code == 400


def test_respondent_must_exist(client, user_factory):
    a = user_factory("A2")
    assert _file(client, a, 99999999, "rude").status_code == 404


def test_incident_requires_booking_participation(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    outsider = user_factory("Outsider")
    # чужой не участник брони → 403 (booking_and_ride_for_user)
    assert _file(client, outsider, drv["id"], "rude", booking_id=booking["id"]).status_code == 403


def test_respondent_must_be_counterparty(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    other = user_factory("Bystander")
    # обвинять по брони можно только участника этой поездки
    assert _file(client, pax, other["id"], "rude", booking_id=booking["id"]).status_code == 400


# ----------------------------- доступ / mine / деталь -----------------------------
def test_mine_and_detail_access(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    # reporter видит в mine как reporter
    mine_pax = client.get("/incidents/mine", headers=pax["auth"]).json()
    assert any(i["id"] == inc["id"] and i["my_role"] == "reporter" for i in mine_pax)
    # respondent видит в mine как respondent
    mine_drv = client.get("/incidents/mine", headers=drv["auth"]).json()
    assert any(i["id"] == inc["id"] and i["my_role"] == "respondent" for i in mine_drv)
    # оба видят деталь
    assert client.get(f"/incidents/{inc['id']}", headers=pax["auth"]).status_code == 200
    assert client.get(f"/incidents/{inc['id']}", headers=drv["auth"]).status_code == 200
    # посторонний — 403
    outsider = user_factory("NosyDetail")
    assert client.get(f"/incidents/{inc['id']}", headers=outsider["auth"]).status_code == 403


def test_privacy_phone_not_leaked_to_participant(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    body = client.get(f"/incidents/{inc['id']}", headers=pax["auth"]).json()
    # ни одного телефонного поля в участниковой схеме
    assert "reporter_phone" not in body and "respondent_phone" not in body
    assert "phone" not in body
    # админ же видит телефоны обеих сторон
    admin = user_factory("SafetyAdmin", role=UserRole.admin)
    admin_rows = client.get("/admin/incidents", headers=admin["auth"]).json()
    row = next(i for i in admin_rows if i["id"] == inc["id"])
    assert "reporter_phone" in row and "respondent_phone" in row
    assert row["reporter_phone"] and row["respondent_phone"]


# ----------------------------- respond / withdraw / appeal -----------------------------
def test_respond_only_respondent(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    # заявитель не может «объясниться»
    assert client.post(f"/incidents/{inc['id']}/respond", headers=pax["auth"], json={"statement": "х"}).status_code == 403
    # обвинённый может → under_review
    r = client.post(f"/incidents/{inc['id']}/respond", headers=drv["auth"], json={"statement": "Я был вежлив"})
    assert r.status_code == 200 and r.json()["status"] == "under_review"
    assert r.json()["respondent_statement"] == "Я был вежлив"


def test_withdraw_only_reporter_mutual(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    # обвинённый не может закрыть миром
    assert client.post(f"/incidents/{inc['id']}/withdraw", headers=drv["auth"]).status_code == 403
    r = client.post(f"/incidents/{inc['id']}/withdraw", headers=pax["auth"])
    assert r.status_code == 200
    assert r.json()["status"] == "closed" and r.json()["resolution"] == "mutual_resolved"


def test_appeal_participant(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    r = client.post(f"/incidents/{inc['id']}/appeal", headers=drv["auth"], json={"text": "Несправедливо"})
    assert r.status_code == 200
    assert r.json()["appeal_status"] == "requested" and r.json()["status"] == "appealed"
    # посторонний не может обжаловать
    outsider = user_factory("NosyAppeal")
    assert client.post(f"/incidents/{inc['id']}/appeal", headers=outsider["auth"], json={"text": "я"}).status_code in (403, 404)


# ----------------------------- admin resolve / лестница §2 -----------------------------
def test_admin_only_resolve(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    assert client.post(f"/admin/incidents/{inc['id']}/resolve", headers=pax["auth"],
                       json={"resolution": "warning", "fault": "respondent", "note": "нет"}).status_code == 403


def test_resolve_strike_ladder_to_suspend(client, user_factory):
    """3 страйка → пауза 3 дня, standing=suspended, can_act=False (лестница §2)."""
    drv = user_factory("LadderDrv", role=UserRole.driver)
    pax = user_factory("LadderPax")
    admin = user_factory("LadderAdmin", role=UserRole.admin)
    ride = _publish(client, drv, seats=3)
    booking = _book(client, pax, ride["id"])

    def strike_once():
        inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
        r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                        json={"resolution": "strike", "fault": "respondent", "note": "Подтверждено", "strike": True})
        assert r.status_code == 200, r.text
        return r.json()

    strike_once()
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 1 and st["standing"] == "warned" and st["can_act"] is True

    strike_once()
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 2 and st["standing"] == "limited"

    res = strike_once()
    assert res["resolution"] == "suspend"          # 3-й страйк → авто-пауза, записано как suspend
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 3 and st["standing"] == "suspended"
    assert st["can_act"] is False and st["suspended_until"] is not None


def test_resolve_dismiss_no_penalty(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    admin = user_factory("DismAdmin", role=UserRole.admin)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                    json={"resolution": "dismissed", "fault": "reporter", "note": "Не подтвердилось"})
    assert r.status_code == 200
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 0 and st["standing"] == "good"


# ----------------------------- no-show -----------------------------
def test_no_show_creates_incident(client, user_factory):
    drv = user_factory("NsDrv", role=UserRole.driver)
    pax = user_factory("NsPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _confirm(client, drv, booking["id"])
    _set_departed(ride["id"])          # время выезда в прошлом → окно открыто
    r = client.post(f"/bookings/{booking['id']}/no-show", headers=drv["auth"], json={"note": "Ждал 10 минут"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["type"] == "passenger_no_show"
    assert body["my_role"] == "reporter"
    # повторно — 409 (одна на бронь)
    assert client.post(f"/bookings/{booking['id']}/no-show", headers=drv["auth"]).status_code == 409
    # пассажир (обвинённый) видит спор
    mine = client.get("/incidents/mine", headers=pax["auth"]).json()
    assert any(i["type"] == "passenger_no_show" and i["my_role"] == "respondent" for i in mine)


def test_no_show_gate_status(client, user_factory):
    drv = user_factory("NsGateDrv", role=UserRole.driver)
    pax = user_factory("NsGatePax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])   # ещё pending, не confirmed
    _set_departed(ride["id"])
    assert client.post(f"/bookings/{booking['id']}/no-show", headers=drv["auth"]).status_code == 409


def test_no_show_reliability_drops(client, user_factory):
    drv = user_factory("RelDrv", role=UserRole.driver)
    pax = user_factory("RelPax")
    admin = user_factory("RelAdmin", role=UserRole.admin)
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _confirm(client, drv, booking["id"])
    _set_departed(ride["id"])
    assert client.get("/me/standing", headers=pax["auth"]).json()["reliability"] == 100  # новичок
    inc = client.post(f"/bookings/{booking['id']}/no-show", headers=drv["auth"], json={"note": "не вышел"}).json()
    # НЕПОДТВЕРЖДЁННЫЙ no-show НЕ роняет Надёжность (фикс аудита: защита от доноса-мести —
    # раньше метрика невиновного падала мгновенно и необратимо ещё до разбора).
    assert client.get("/me/standing", headers=pax["auth"]).json()["reliability"] == 100
    # админ подтвердил вину пассажира (fault=respondent) → теперь Надёжность падает.
    r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                    json={"resolution": "strike", "fault": "respondent", "note": "Подтверждено", "strike": True})
    assert r.status_code == 200, r.text
    assert client.get("/me/standing", headers=pax["auth"]).json()["reliability"] < 100


# ----------------------------- харднинг из аудита (P1) -----------------------------
def test_suspended_user_blocked_from_actions(client, user_factory):
    """Приостановленный (§2) НЕ может бронировать/жаловаться — иначе лестница косметическая."""
    drv = user_factory("SusDrv", role=UserRole.driver)
    pax = user_factory("SusPax")
    admin = user_factory("SusAdmin", role=UserRole.admin)
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    inc = _file(client, drv, pax["id"], "rude", booking_id=booking["id"]).json()
    r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                    json={"resolution": "suspend", "fault": "respondent", "suspend_days": 3, "strike": True})
    assert r.status_code == 200, r.text
    assert client.get("/me/standing", headers=pax["auth"]).json()["can_act"] is False
    ride2 = _publish(client, drv)
    assert client.post("/bookings", headers=pax["auth"],
                       json={"ride_id": ride2["id"], "seats": 1}).status_code == 403
    assert client.post("/incidents", headers=pax["auth"],
                       json={"respondent_id": drv["id"], "type": "rude", "booking_id": booking["id"]}).status_code == 403


def test_resolve_idempotent_no_double_strike(client, user_factory):
    """Повторный resolve одного спора → 409, страйк НЕ добавляется второй раз."""
    drv = user_factory("IdemRDrv", role=UserRole.driver)
    pax = user_factory("IdemRPax")
    admin = user_factory("IdemRAdmin", role=UserRole.admin)
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    r1 = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                     json={"resolution": "strike", "fault": "respondent", "strike": True})
    assert r1.status_code == 200, r1.text
    r2 = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                     json={"resolution": "strike", "fault": "respondent", "strike": True})
    assert r2.status_code == 409
    assert client.get("/me/standing", headers=drv["auth"]).json()["strikes"] == 1


# ----------------------------- оплата наличными -----------------------------
def test_payment_unpaid_creates_incident(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    bid = booking["id"]
    _done(client, pax, bid)
    # только водитель
    assert client.post(f"/bookings/{bid}/payment", headers=pax["auth"], json={"received": False}).status_code == 403
    r = client.post(f"/bookings/{bid}/payment", headers=drv["auth"], json={"received": False, "note": "не заплатил"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["payment_state"] == "unpaid" and body["incident_id"] is not None
    # инцидент non_payment на пассажира
    mine = client.get("/incidents/mine", headers=drv["auth"]).json()
    assert any(i["type"] == "non_payment" for i in mine)


def test_payment_received_ok(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    bid = booking["id"]
    _done(client, pax, bid)
    r = client.post(f"/bookings/{bid}/payment", headers=drv["auth"], json={"received": True})
    assert r.status_code == 200 and r.json()["payment_state"] == "received"
    assert r.json()["incident_id"] is None


def test_payment_only_after_done(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    # ещё не завершена → 409
    assert client.post(f"/bookings/{booking['id']}/payment", headers=drv["auth"], json={"received": True}).status_code == 409


# ----------------------------- отмена с причиной -----------------------------
def test_cancel_with_reason_and_late_flag(client, user_factory):
    drv = user_factory("CxDrv", role=UserRole.driver)
    pax = user_factory("CxPax")
    ride = _publish(client, drv, seats=2)
    booking = _book(client, pax, ride["id"])
    _set_departed(ride["id"])           # выезд в прошлом → отмена поздняя
    r = client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"],
                    json={"reason": "plans_changed", "note": "Планы изменились"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "cancelled"
    assert body["cancel_reason"] == "plans_changed"
    assert body["late"] is True


def test_cancel_emergency_is_shielded(client, user_factory):
    drv = user_factory("EmDrv", role=UserRole.driver)
    pax = user_factory("EmPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    _set_departed(ride["id"])
    r = client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"], json={"reason": "emergency"})
    assert r.status_code == 200
    assert r.json()["late"] is False    # форс-мажор — щит от штрафа


def test_cancel_backward_compatible_no_body(client, user_factory):
    drv = user_factory("BcDrv", role=UserRole.driver)
    pax = user_factory("BcPax")
    ride = _publish(client, drv, seats=2)
    booking = _book(client, pax, ride["id"])
    # без тела — как раньше
    r = client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    assert client.get(f"/rides/{ride['id']}").json()["seats_left"] == 2   # места вернулись


# ----------------------------- exclude_rating -----------------------------
def test_exclude_rating_removes_from_average(client, user_factory):
    drv = user_factory("XrDrv", role=UserRole.driver)
    p_good = user_factory("XrGood")
    p_bad = user_factory("XrBad")
    admin = user_factory("XrAdmin", role=UserRole.admin)
    ride = _publish(client, drv, seats=3)
    b_good = _book(client, p_good, ride["id"])
    b_bad = _book(client, p_bad, ride["id"])
    _done(client, p_good, b_good["id"])
    _done(client, p_bad, b_bad["id"])
    _rate(client, p_good, b_good["id"], 5)     # честная 5★
    _rate(client, p_bad, b_bad["id"], 1)       # месть 1★
    # среднее = 3.0 по двум оценкам
    trust = client.get(f"/users/{drv['id']}/trust", headers=p_good["auth"]).json()
    assert trust["rating"] == 3.0 and trust["rating_count"] == 2
    # обвинённый водитель, заявитель — мстительный пассажир; админ исключает его оценку
    inc = _file(client, p_bad, drv["id"], "rude", booking_id=b_bad["id"]).json()
    r = client.post(f"/admin/incidents/{inc['id']}/resolve", headers=admin["auth"],
                    json={"resolution": "dismissed", "fault": "reporter", "note": "Оценка-месть",
                          "exclude_rating": True, "shield": True})
    assert r.status_code == 200, r.text
    # осталась только честная 5★
    trust2 = client.get(f"/users/{drv['id']}/trust", headers=p_good["auth"]).json()
    assert trust2["rating"] == 5.0 and trust2["rating_count"] == 1
    # и водитель получил щит рейтинга
    assert client.get("/me/standing", headers=drv["auth"]).json()["rating_shield"] is True


# ----------------------------- trust / standing / policy -----------------------------
def test_user_trust_shape_no_phone(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    t = client.get(f"/users/{drv['id']}/trust", headers=pax["auth"])
    assert t.status_code == 200
    body = t.json()
    assert {"rating", "rating_count", "trips", "verified", "reliability", "member_since"} <= set(body)
    assert "phone" not in body


def test_standing_new_user_defaults(client, user_factory):
    u = user_factory("FreshStanding")
    st = client.get("/me/standing", headers=u["auth"]).json()
    assert st["standing"] == "good" and st["strikes"] == 0
    assert st["reliability"] == 100 and st["can_act"] is True
    assert st["active_incidents"] == 0


def test_active_incidents_counter(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    _file(client, pax, drv["id"], "rude", booking_id=booking["id"])
    assert client.get("/me/standing", headers=pax["auth"]).json()["active_incidents"] == 1
    assert client.get("/me/standing", headers=drv["auth"]).json()["active_incidents"] == 1


def test_safety_policy(client):
    p = client.get("/safety/policy").json()
    assert p["free_cancel_min"] == 5
    assert p["strikes_to_suspend"] == 3
    assert p["suspend_1_days"] == 3
    assert p["reliability_window"] == 30
    assert p["strike_decay_days"] == 60


# ----------------------------- parcel-photo -----------------------------
def test_parcel_photo_saved(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    r = client.post(f"/bookings/{booking['id']}/parcel-photo", headers=drv["auth"],
                    json={"phase": "pickup", "url": "https://yulbash.ru/media/chat/x.jpg"})
    assert r.status_code == 200, r.text
    assert r.json()["parcel_pickup_photo"] == "https://yulbash.ru/media/chat/x.jpg"
    # плохая фаза → 400
    assert client.post(f"/bookings/{booking['id']}/parcel-photo", headers=drv["auth"],
                       json={"phase": "wat", "url": "x"}).status_code == 400


# ----------------------------- severe → под разбор человеком -----------------------------
def test_severe_type_goes_under_review(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    r = _file(client, pax, drv["id"], "harassment", booking_id=booking["id"])
    assert r.status_code == 200
    assert r.json()["status"] == "under_review" and r.json()["severe"] is True


# ----------------------------- удаление аккаунта с инцидентами (152-ФЗ / FK) -----------------------------
def test_delete_account_with_incident_and_profile(client, user_factory):
    drv, pax, ride, booking = _trip(client, user_factory)
    inc = _file(client, pax, drv["id"], "rude", booking_id=booking["id"]).json()
    client.get("/me/standing", headers=pax["auth"])   # ленивое создание SafetyProfile
    client.get("/me/standing", headers=drv["auth"])
    # заявитель удаляет аккаунт — не должно упасть на FK инцидента/профиля
    assert client.post("/me/delete", headers=pax["auth"]).status_code == 200
    # спор исчез, вторая сторона жива и её standing доступен
    assert client.get(f"/incidents/{inc['id']}", headers=drv["auth"]).status_code == 404
    assert client.get("/me/standing", headers=drv["auth"]).status_code == 200


# ----------------------------- §1.1 «Бампинг»: детект фиктивной «не еду» -----------------------------
def _bump_setup(client, user_factory, seats=3):
    drv = user_factory("BumpDrv", role=UserRole.driver)
    pax = user_factory("BumpPax")
    ride = _publish(client, drv, seats=seats)
    b = _book(client, pax, ride["id"])
    _confirm(client, drv, b["id"])
    return drv, pax, ride, b


def test_bump_ride_stayed_active_creates_incident(client, user_factory):
    """Водитель сбросил подтверждённого пассажира, поездку не отменил → авто-инцидент suspected_bump."""
    drv, pax, ride, b = _bump_setup(client, user_factory)
    r = client.post(f"/bookings/{b['id']}/cancel", headers=drv["auth"], json={"reason": "not_going"})
    assert r.status_code == 200 and r.json()["status"] == "cancelled"
    # пассажир — reporter авто-инцидента driver_no_show + suspected_bump + under_review
    mine = client.get("/incidents/mine", headers=pax["auth"]).json()
    inc = next((i for i in mine if i["type"] == "driver_no_show"), None)
    assert inc is not None
    assert inc["suspected_bump"] is True
    assert inc["status"] == "under_review"
    assert inc["my_role"] == "reporter"
    # водитель — respondent того же инцидента
    dmine = client.get("/incidents/mine", headers=drv["auth"]).json()
    assert any(i["type"] == "driver_no_show" and i["suspected_bump"] and i["my_role"] == "respondent" for i in dmine)


def test_detect_bump_seat_rebooked_and_republish_unit(client, user_factory):
    """Юнит на сигналы seat_rebooked и republish в изоляции (ride уже неактивна → нет ride_still_active)."""
    from app.safety_logic import detect_bump
    # уникальные пользователи (общая сессионная БД)
    drv = user_factory("BumpUDrv", role=UserRole.driver)
    pax = user_factory("BumpUPax")
    depart = utcnow() + timedelta(hours=3)
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="Сибай", to_city="Уфа", depart_at=depart,
                    seats_total=3, seats_left=3, status=RideStatus.cancelled)   # неактивна → изолируем сигналы
        s.add(ride); s.commit(); s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax["id"], seats=1, status=BookingStatus.cancelled,
                    cancelled_by=drv["id"], cancelled_at=utcnow())
        s.add(b); s.commit(); s.refresh(b)
        # нет сигналов
        assert detect_bump(s, ride, b, "not_going") == []
        # перебронь освободившегося места после отмены
        nb = Booking(ride_id=ride.id, passenger_id=pax["id"], seats=1, status=BookingStatus.pending,
                     created_at=utcnow() + timedelta(minutes=1))
        s.add(nb); s.commit()
        sig = detect_bump(s, ride, b, "not_going")
        assert "seat_rebooked" in sig and "ride_still_active" not in sig
        # republish тем же маршрутом в ±2ч, создан в окне
        rp = Ride(driver_id=drv["id"], from_city="Сибай", to_city="Уфа",
                  depart_at=depart + timedelta(minutes=30), seats_total=3, seats_left=3,
                  status=RideStatus.active, created_at=utcnow())
        s.add(rp); s.commit()
        assert "republish" in detect_bump(s, ride, b, "not_going")


def test_passenger_cancel_no_bump(client, user_factory):
    drv, pax, ride, b = _bump_setup(client, user_factory)
    r = client.post(f"/bookings/{b['id']}/cancel", headers=pax["auth"], json={"reason": "plans_changed"})
    assert r.status_code == 200
    # отмена пассажиром НЕ создаёт bump-инцидент
    assert not any(i["type"] == "driver_no_show" for i in client.get("/incidents/mine", headers=pax["auth"]).json())
    assert not any(i["type"] == "driver_no_show" for i in client.get("/incidents/mine", headers=drv["auth"]).json())


def test_driver_emergency_no_signals_no_incident_no_strike(client, user_factory):
    drv, pax, ride, b = _bump_setup(client, user_factory)
    # реальный форс-мажор: водитель отменил САМУ поездку (ride не активна) → сигналов нет
    with Session(engine) as s:
        rd = s.get(Ride, ride["id"])
        rd.status = RideStatus.cancelled
        s.add(rd); s.commit()
    r = client.post(f"/bookings/{b['id']}/cancel", headers=drv["auth"], json={"reason": "emergency"})
    assert r.status_code == 200 and r.json()["late"] is False
    # ни авто-инцидента, ни страйка (форс-мажор защищён)
    assert not any(i["type"] == "driver_no_show" for i in client.get("/incidents/mine", headers=drv["auth"]).json())
    st = client.get("/me/standing", headers=drv["auth"]).json()
    assert st["strikes"] == 0 and st["standing"] == "good"


def test_driver_bump_hits_reliability_harder(client, user_factory):
    """Отмена водителем подтверждённой брони весит в Надёжности тяжелее (§1.1, Рычаг 3):
    1 завершённая + 1 бамп → 1/(1+3)=25%, а не 1/2=50%."""
    drv = user_factory("RelBumpDrv", role=UserRole.driver)
    p1 = user_factory("RelBumpP1")
    p2 = user_factory("RelBumpP2")
    ride = _publish(client, drv, seats=3)
    b1 = _book(client, p1, ride["id"])
    _confirm(client, drv, b1["id"])
    _done(client, p1, b1["id"])                      # 1 завершённая поездка
    b2 = _book(client, p2, ride["id"])
    _confirm(client, drv, b2["id"])
    client.post(f"/bookings/{b2['id']}/cancel", headers=drv["auth"], json={"reason": "not_going"})  # бамп
    rel = client.get("/me/standing", headers=drv["auth"]).json()["reliability"]
    assert rel == 25                                 # 1 / (1 + вес 3) = 25%
