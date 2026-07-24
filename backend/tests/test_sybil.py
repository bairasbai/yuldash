"""Детект Sybil/сговора (консервативный, только сигнал админу).

Проверяем: взаимный реферал → флаг; много взаимных done-броней + 5★ → флаг (2 сигнала);
честная одиночная поездка → НЕ флаг; эндпоинт только для админа.
"""
from app.models import UserRole


def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
        "seats_total": seats, "price": price})
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code == 200, r.text
    return r.json()


def _confirm(client, drv, bid):
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200


def _done(client, who, bid):
    assert client.post(f"/bookings/{bid}/trip-status", headers=who["auth"], json={"status": "done"}).status_code == 200


def _rate(client, who, bid, stars=5):
    assert client.post(f"/bookings/{bid}/rate", headers=who["auth"], json={"stars": stars}).status_code == 200


def _suspect_for(suspects, a_id, b_id):
    want = sorted([a_id, b_id])
    for s in suspects:
        if sorted(s["pair"]) == want:
            return s
    return None


def test_sybil_admin_only(client, user_factory):
    u = user_factory("SybNon")
    assert client.get("/admin/sybil/suspects", headers=u["auth"]).status_code == 403


def test_sybil_reciprocal_invite_flagged(client, user_factory):
    a = user_factory("SybInvA")
    b = user_factory("SybInvB")
    admin = user_factory("SybInvAdmin", role=UserRole.admin)
    code_a = client.get("/referral/me", headers=a["auth"]).json()["code"]
    code_b = client.get("/referral/me", headers=b["auth"]).json()["code"]
    assert client.post("/referral/redeem", headers=a["auth"], json={"code": code_b}).status_code == 200
    assert client.post("/referral/redeem", headers=b["auth"], json={"code": code_a}).status_code == 200
    suspects = client.get("/admin/sybil/suspects", headers=admin["auth"]).json()["suspects"]
    s = _suspect_for(suspects, a["id"], b["id"])
    assert s is not None and "reciprocal_invite" in s["signals"]


def test_sybil_mutual_trips_and_ratings_flagged(client, user_factory):
    # A и B оба водители: гоняют 4 брони между собой (A везёт B), оба ставят 5★ на каждой.
    a = user_factory("SybRingA", role=UserRole.driver)
    b = user_factory("SybRingB", role=UserRole.driver)
    admin = user_factory("SybRingAdmin", role=UserRole.admin)
    for _ in range(4):
        ride = _publish(client, a)
        bk = _book(client, b, ride["id"])
        _confirm(client, a, bk["id"])
        _done(client, b, bk["id"])
        _rate(client, a, bk["id"], 5)   # водитель A → пассажир B
        _rate(client, b, bk["id"], 5)   # пассажир B → водитель A
    suspects = client.get("/admin/sybil/suspects", headers=admin["auth"]).json()["suspects"]
    s = _suspect_for(suspects, a["id"], b["id"])
    assert s is not None
    assert any(sig.startswith("pair_trips") for sig in s["signals"])
    assert any(sig.startswith("mutual_5star") for sig in s["signals"])
    assert s["score"] >= 2


def test_sybil_honest_pair_not_flagged(client, user_factory):
    # Одна честная поездка + одна оценка — ниже порогов, не попадает в список.
    drv = user_factory("SybHonDrv", role=UserRole.driver)
    pax = user_factory("SybHonPax")
    admin = user_factory("SybHonAdmin", role=UserRole.admin)
    ride = _publish(client, drv)
    bk = _book(client, pax, ride["id"])
    _confirm(client, drv, bk["id"])
    _done(client, pax, bk["id"])
    _rate(client, pax, bk["id"], 5)
    suspects = client.get("/admin/sybil/suspects", headers=admin["auth"]).json()["suspects"]
    assert _suspect_for(suspects, drv["id"], pax["id"]) is None
