from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Ad, Payment, UserRole
from app.timeutil import utcnow


def _ad_payload(**overrides):
    body = {
        "partner_name": "Partner",
        "partner_contact": "+79990000000",
        "title": "Title",
        "text": "Text",
        "button": "Open",
        "target": "https://example.test",
        "image_url": "https://example.test/ad.jpg",
        "erid": "erid-test",
        "plan": "standard",
        "placements": "map,profile",
        "cities": "Ufa,Sibay",
        "priority": 1,
        "starts_at": (utcnow() - timedelta(minutes=1)).isoformat(),
        "ends_at": (utcnow() + timedelta(days=1)).isoformat(),
    }
    body.update(overrides)
    return body


def test_admin_create_paid_ad_defaults_bad_plan_and_creates_payment(client, user_factory):
    admin = user_factory("AdsEdgeAdmin", role=UserRole.admin)

    created = client.post(
        "/admin/ads",
        headers=admin["auth"],
        json=_ad_payload(plan="bad-plan", price=123),
    )
    assert created.status_code == 200, created.text

    with Session(engine) as session:
        payment = session.exec(
            select(Payment).where(Payment.user_id == admin["id"], Payment.purpose == "ad").order_by(Payment.id.desc())
        ).first()
        assert payment is not None
        assert payment.amount_kop == 12_300
        ad = session.get(Ad, payment.ad_id)
        assert ad.plan == "standard"
        assert ad.status == "draft"


def test_admin_update_status_delete_and_public_sorting_edges(client, user_factory):
    admin = user_factory("AdsEdgeAdmin2", role=UserRole.admin)
    first = client.post(
        "/admin/ads",
        headers=admin["auth"],
        json=_ad_payload(title="Standard", priority=1, plan="standard"),
    ).json()
    second = client.post(
        "/admin/ads",
        headers=admin["auth"],
        json=_ad_payload(title="Premium", priority=1, plan="premium"),
    ).json()

    missing_update = client.post("/admin/ads/99999999", headers=admin["auth"], json=_ad_payload())
    assert missing_update.status_code == 404
    missing_status = client.post("/admin/ads/99999999/status", headers=admin["auth"], json={"status": "active"})
    assert missing_status.status_code == 404
    bad_status = client.post(f"/admin/ads/{first['id']}/status", headers=admin["auth"], json={"status": "live"})
    assert bad_status.status_code == 400

    updated = client.post(
        f"/admin/ads/{first['id']}",
        headers=admin["auth"],
        json=_ad_payload(title="Updated", plan="premium", placements="route", cities="Ufa"),
    )
    assert updated.status_code == 200
    assert updated.json()["title"] == "Updated"
    assert updated.json()["founder_lock"] is False

    assert client.post(f"/admin/ads/{first['id']}/status", headers=admin["auth"], json={"status": "active"}).status_code == 200
    assert client.post(f"/admin/ads/{second['id']}/status", headers=admin["auth"], json={"status": "active"}).status_code == 200

    public_ads = client.get("/ads", params={"city": "Ufa"}).json()
    public_ids = [row["id"] for row in public_ads]
    assert str(first["id"]) in public_ids
    assert str(second["id"]) in public_ids

    missing_delete = client.delete("/admin/ads/99999999", headers=admin["auth"])
    assert missing_delete.status_code == 404
    deleted = client.delete(f"/admin/ads/{first['id']}", headers=admin["auth"])
    assert deleted.status_code == 200
    assert client.delete(f"/admin/ads/{second['id']}", headers=admin["auth"]).status_code == 200
    admin_rows = client.get("/admin/ads", headers=admin["auth"]).json()["items"]
    assert str(first["id"]) not in {row["id"] for row in admin_rows}
    assert str(second["id"]) not in {row["id"] for row in admin_rows}


def test_ad_event_missing_ad_returns_404(client, user_factory):
    user = user_factory("AdEventUser")
    response = client.post("/ads/99999999/event", headers=user["auth"], json={"type": "click"})
    assert response.status_code == 404
