"""Рекламодатель/бизнес — владение объектами, а не право администратора."""
import pytest
from datetime import timedelta
from sqlmodel import Session

from app.db import engine
from app.models import Ad, Coupon, Partner, User, UserRole
from app.timeutil import utcnow


def test_guest_can_read_storefront_but_cannot_open_private_business(client):
    for path in ("/ads", "/ad-packages", "/coupons", "/partner/plans", "/medical-partners"):
        assert client.get(path).status_code == 200, path
    for path in ("/ads/mine", "/partner/me", "/trusted-contacts", "/admin/partners"):
        assert client.get(path).status_code == 401, path
    for path, body in (
        ("/ads", {"title": "Кафе", "package": "city"}),
        ("/partner", {"name": "Кафе", "city": "Уфа"}),
        ("/trusted-contacts", {"name": "Брат", "phone": "+79990000221"}),
    ):
        assert client.post(path, json=body).status_code == 401, path


def test_business_signup_cannot_assign_foreign_owner_or_admin_privileges(client, user_factory):
    owner = user_factory(name="Владелец")
    other = user_factory(name="Другой")
    forged = {"owner_id": other["id"], "status": "active", "role": "admin"}
    ad_response = client.post("/ads", headers=owner["auth"], json={
        "title": "Кафе у дороги", "package": "city", **forged,
    })
    assert ad_response.status_code == 200, ad_response.text
    partner_response = client.post("/partner", headers=owner["auth"], json={
        "name": "Кафе", "city": "Уфа", **forged,
    })
    assert partner_response.status_code == 200, partner_response.text
    with Session(engine) as session:
        ad = session.get(Ad, int(ad_response.json()["id"]))
        partner = session.get(Partner, partner_response.json()["id"])
        user = session.get(User, owner["id"])
        assert (ad.owner_id, ad.status) == (owner["id"], "draft")
        assert (partner.owner_id, partner.status) == (owner["id"], "pending")
        assert user.is_advertiser is True
        assert user.role == UserRole.passenger
    assert client.get("/admin/partners", headers=owner["auth"]).status_code == 403
    assert client.get("/ads/mine", headers=other["auth"]).json() == []
    assert client.get("/partner/me", headers=other["auth"]).json()["partner"] is None


def test_admin_revocation_applies_to_existing_token_without_losing_own_ads(client, user_factory):
    owner = user_factory(name="Бывший администратор", role=UserRole.admin)
    created = client.post("/ads", headers=owner["auth"], json={"title": "Моя реклама", "package": "city"})
    assert created.status_code == 200, created.text
    assert client.get("/admin/partners", headers=owner["auth"]).status_code == 200
    # Администраторские права отозваны в authoritative User, access-token остаётся тем же.
    with Session(engine) as session:
        user = session.get(User, owner["id"])
        user.role = UserRole.passenger
        session.add(user)
        session.commit()
    assert client.get("/admin/partners", headers=owner["auth"]).status_code == 403
    own_ads = client.get("/ads/mine", headers=owner["auth"])
    assert own_ads.status_code == 200
    assert [ad["id"] for ad in own_ads.json()] == [created.json()["id"]]


def test_rejected_business_can_return_to_review_after_correcting_details(client, user_factory):
    owner = user_factory(name="Владелец")
    admin = user_factory(name="Администратор", role=UserRole.admin)
    response = client.post("/partner", headers=owner["auth"], json={"name": "Кафе", "city": "Уфа"})
    assert response.status_code == 200, response.text
    partner_id = response.json()["id"]
    # Даже оплаченная подписка и готовый купон не должны обходить повторную проверку.
    with Session(engine) as session:
        partner = session.get(Partner, partner_id)
        partner.subscription_until = utcnow() + timedelta(days=30)
        coupon = Coupon(partner_id=partner_id, title="Кофе", city="Уфа", status="active", review="approved")
        session.add(partner)
        session.add(coupon)
        session.commit()
        session.refresh(coupon)
        coupon_id = coupon.id
    rejected = client.post(f"/admin/partners/{partner_id}/reject", headers=admin["auth"],
                          json={"reason": "Уточни адрес и отправь снова"})
    assert rejected.status_code == 200, rejected.text
    corrected = client.post(f"/partner/{partner_id}", headers=owner["auth"],
                            json={"name": "Кафе", "city": "Уфа", "address": "Ленина 10"})
    assert corrected.status_code == 200, corrected.text
    assert corrected.json()["status"] == "pending", "Исправленный бизнес не вернулся на проверку"
    assert corrected.json()["reject_reason"] == ""
    assert client.get(f"/coupons/{coupon_id}").status_code == 404
    assert client.post("/partner/coupons", headers=owner["auth"], json={"title": "Новый купон"}).status_code == 409
    approved = client.post(f"/admin/partners/{partner_id}/approve", headers=admin["auth"])
    assert approved.status_code == 200, approved.text
    assert client.get(f"/coupons/{coupon_id}").status_code == 200


@pytest.mark.parametrize("status,changed,expected", [
    ("active", True, "pending"),
    ("active", False, "active"),
    ("rejected", False, "rejected"),
    ("paused", True, "paused"),
    ("archived", True, "archived"),
])
def test_edit_only_requeues_reviewable_business_with_changed_text(client, user_factory, status, changed, expected):
    owner = user_factory(name="Владелец")
    with Session(engine) as session:
        partner = Partner(owner_id=owner["id"], name="Кафе", city="Уфа", address="Ленина 10",
                          status=status, reject_reason="Исправь адрес" if status == "rejected" else "")
        session.add(partner)
        session.commit()
        session.refresh(partner)
        partner_id = partner.id
    result = client.post(f"/partner/{partner_id}", headers=owner["auth"], json={
        "name": "Кафе", "city": "Уфа", "address": "Ленина 12" if changed else "Ленина 10",
    })
    assert result.status_code == 200, result.text
    assert result.json()["status"] == expected
    if expected == "rejected":
        assert result.json()["reject_reason"] == "Исправь адрес"
