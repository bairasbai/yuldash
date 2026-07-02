"""Кабинет партнёра (реклама, План B): тарифы /ad-packages, приватность /ads/mine.

Ключевое — IDOR: партнёр А не видит объявления партнёра Б.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Ad, UserRole


def _make_ad(owner_id, title="Моя реклама", status="draft", package="city"):
    with Session(engine) as s:
        ad = Ad(owner_id=owner_id, title=title, text="текст", status=status, package=package)
        s.add(ad)
        s.commit()
        s.refresh(ad)
        return ad.id


def test_ad_packages_public(client):
    r = client.get("/ad-packages")
    assert r.status_code == 200
    data = r.json()
    codes = {p["code"] for p in data}
    assert {"city", "route", "main"} <= codes
    city = next(p for p in data if p["code"] == "city")
    assert city["amount_kop"] > 0 and city["period_days"] > 0


def test_ads_mine_requires_auth(client):
    assert client.get("/ads/mine").status_code == 401


def test_ads_mine_empty_for_new_user(client, user_factory):
    u = user_factory(name="Партнёр")
    r = client.get("/ads/mine", headers=u["auth"])
    assert r.status_code == 200
    assert r.json() == []


def test_ads_mine_owner_scoped_idor(client, user_factory):
    a = user_factory(name="Партнёр А")
    b = user_factory(name="Партнёр Б")
    ad_id = _make_ad(a["id"], title="Реклама А")
    # А видит своё
    ra = client.get("/ads/mine", headers=a["auth"])
    assert ra.status_code == 200
    mine = ra.json()
    assert [x["id"] for x in mine] == [str(ad_id)]
    assert mine[0]["title"] == "Реклама А"
    assert mine[0]["status"] == "draft"
    assert mine[0]["paid"] is False
    # Б НЕ видит чужое (IDOR закрыт)
    rb = client.get("/ads/mine", headers=b["auth"])
    assert rb.status_code == 200
    assert all(x["id"] != str(ad_id) for x in rb.json())


# ---------- Ф2: создать / править / сабмит ----------

def test_ad_create_makes_draft_and_advertiser(client, user_factory):
    u = user_factory(name="Новый партнёр")
    r = client.post("/ads", headers=u["auth"], json={"title": "Кафе у дороги", "package": "city"})
    assert r.status_code == 200
    ad = r.json()
    assert ad["status"] == "draft"
    assert ad["package"] == "city" and ad["budget_kop"] > 0
    # появилось в моих
    mine = client.get("/ads/mine", headers=u["auth"]).json()
    assert any(x["id"] == ad["id"] for x in mine)


def test_ad_create_empty_title_422(client, user_factory):
    u = user_factory()
    assert client.post("/ads", headers=u["auth"], json={"title": "  "}).status_code == 422


def test_ad_update_own_only(client, user_factory):
    a = user_factory(name="Владелец")
    b = user_factory(name="Чужой")
    ad = client.post("/ads", headers=a["auth"], json={"title": "Черновик", "package": "city"}).json()
    # владелец правит
    r = client.post(f"/ads/{ad['id']}", headers=a["auth"], json={"title": "Обновлён", "package": "route"})
    assert r.status_code == 200 and r.json()["title"] == "Обновлён" and r.json()["package"] == "route"
    # чужой не может — 404 (не раскрываем существование)
    assert client.post(f"/ads/{ad['id']}", headers=b["auth"], json={"title": "Взлом"}).status_code == 404


def test_ad_submit_flow(client, user_factory):
    u = user_factory(name="Партнёр")
    ad = client.post("/ads", headers=u["auth"], json={"title": "На модерацию", "package": "city"}).json()
    r = client.post(f"/ads/{ad['id']}/submit", headers=u["auth"])
    assert r.status_code == 200 and r.json()["status"] == "pending_review"
    # повторный сабмит уже на модерации → 409 (не редактируемо)
    assert client.post(f"/ads/{ad['id']}/submit", headers=u["auth"]).status_code == 409
    # редактировать на модерации нельзя
    assert client.post(f"/ads/{ad['id']}", headers=u["auth"], json={"title": "x"}).status_code == 409


def test_ad_submit_requires_package(client, user_factory):
    u = user_factory()
    ad = client.post("/ads", headers=u["auth"], json={"title": "Без тарифа"}).json()
    assert client.post(f"/ads/{ad['id']}/submit", headers=u["auth"]).status_code == 422


# ---------- Ф3: модерация (админ) ----------

def _pending_ad(client, owner_auth):
    ad = client.post("/ads", headers=owner_auth, json={"title": "Кафе", "package": "city"}).json()
    client.post(f"/ads/{ad['id']}/submit", headers=owner_auth)
    return ad["id"]


def test_admin_approve(client, user_factory):
    owner = user_factory(name="Партнёр")
    admin = user_factory(name="Админ", role=UserRole.admin)
    ad_id = _pending_ad(client, owner["auth"])
    r = client.post(f"/admin/ads/{ad_id}/approve", headers=admin["auth"], json={"erid": "2Ru-TEST"})
    assert r.status_code == 200 and r.json()["status"] == "active"
    # владелец видит active
    mine = client.get("/ads/mine", headers=owner["auth"]).json()
    assert next(x for x in mine if x["id"] == str(ad_id))["status"] == "active"


def test_admin_reject_with_reason(client, user_factory):
    owner = user_factory(name="Партнёр")
    admin = user_factory(name="Админ", role=UserRole.admin)
    ad_id = _pending_ad(client, owner["auth"])
    r = client.post(f"/admin/ads/{ad_id}/reject", headers=admin["auth"], json={"reason": "Нет маркировки erid"})
    assert r.status_code == 200 and r.json()["status"] == "rejected"
    # владелец видит причину и снова может редактировать
    mine = client.get("/ads/mine", headers=owner["auth"]).json()
    row = next(x for x in mine if x["id"] == str(ad_id))
    assert row["status"] == "rejected" and row["reject_reason"] == "Нет маркировки erid"
    assert client.post(f"/ads/{ad_id}", headers=owner["auth"], json={"title": "Исправлено", "package": "city"}).status_code == 200


def test_moderation_admin_only(client, user_factory):
    owner = user_factory(name="Партнёр")
    ad_id = _pending_ad(client, owner["auth"])
    # обычный юзер не модерирует
    assert client.post(f"/admin/ads/{ad_id}/approve", headers=owner["auth"], json={}).status_code == 403
    assert client.post(f"/admin/ads/{ad_id}/reject", headers=owner["auth"], json={"reason": "x"}).status_code == 403
