"""Тесты доверия «между своими» (Фаза 4 / D5): уровни L0..L3, инвайты, «только для своих», согласия.

Проверяем: уровень считается верно, редим инвайта поднимает до L3 и пишет invited_by,
нельзя редимить свой/исчерпанный код, «только для своих» скрыто от L<3, согласие фиксируется
с таймстампом, приватность (чужой уровень/инвайты/согласия не течёт — IDOR)."""
import uuid

from sqlmodel import Session

from app.db import engine
from app.models import User, UserRole
from app.security import make_token
from app.trust_service import MAX_INVITES_PER_USER


_n = {"i": 0}


def mk_user(name="", avatar="", verified=False, role=UserRole.passenger):
    """Пользователь с точным контролем над данными уровня (реальный номер → проходит current_user)."""
    _n["i"] += 1
    i = _n["i"]
    with Session(engine) as s:
        u = User(phone=f"real-trust-{i}", name=name, avatar_url=avatar,
                 verified=verified, role=role, telegram_id=f"trust{i}")
        s.add(u)
        s.commit()
        s.refresh(u)
        tok = make_token(u.id)
        return {"id": u.id, "token": tok, "auth": {"Authorization": f"Bearer {tok}"}}


def _city():
    return "Город" + uuid.uuid4().hex[:8]   # уникальный маршрут → изоляция от чужих поездок и кеша


def _publish(client, drv, frm, to, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


# ----------------------------- Уровни L0..L3 -----------------------------

def test_level_l0_phone_only(client):
    u = mk_user()   # без имени/фото/документов
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["level"] == 0
    assert body["title"]["ru"] and body["title"]["ba"]   # двуязычно
    assert body["next"]["level"] == 1
    assert body["is_insider"] is False


def test_level_l1_name_and_photo(client):
    u = mk_user(name="Айгуль", avatar="https://x/y.jpg")
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["level"] == 1
    assert body["next"]["level"] == 2


def test_level_l1_needs_both_name_and_photo(client):
    only_name = mk_user(name="Ильдар")            # фото нет
    assert client.get("/me/trust", headers=only_name["auth"]).json()["level"] == 0


def test_level_l2_verified(client):
    u = mk_user(name="Рустам", avatar="https://x/a.jpg", verified=True)
    body = client.get("/me/trust", headers=u["auth"]).json()
    assert body["level"] == 2
    assert body["next"]["level"] == 3
    assert body["can_invite"] is True   # L2 может пригл,ашать


def test_level_l3_via_invite_sets_invited_by(client):
    inviter = mk_user(name="Салауат", avatar="https://x/i.jpg", verified=True)   # L2
    code = client.post("/invites", headers=inviter["auth"]).json()["code"]
    newbie = mk_user()   # L0
    r = client.post("/invites/redeem", headers=newbie["auth"], json={"code": code})
    assert r.status_code == 200, r.text
    assert r.json()["level"] == 3
    assert r.json()["invited_by"] == inviter["id"]
    body = client.get("/me/trust", headers=newbie["auth"]).json()
    assert body["level"] == 3
    assert body["is_insider"] is True
    assert body["invited_by"] == inviter["id"]
    assert body["next"] is None   # L3 — верхний уровень


# ----------------------------- Инвайты: анти-абьюз -----------------------------

def test_invite_requires_l2(client):
    l1 = mk_user(name="Тимур", avatar="https://x/t.jpg")   # L1 — приглашать не может
    assert client.post("/invites", headers=l1["auth"]).status_code == 403


def test_cannot_redeem_own_code(client):
    u = mk_user(name="Азат", avatar="https://x/z.jpg", verified=True)
    code = client.post("/invites", headers=u["auth"]).json()["code"]
    r = client.post("/invites/redeem", headers=u["auth"], json={"code": code})
    assert r.status_code == 400


def test_cannot_redeem_exhausted_code(client):
    inviter = mk_user(name="Гали", avatar="https://x/g.jpg", verified=True)
    code = client.post("/invites", headers=inviter["auth"]).json()["code"]
    first = mk_user()
    assert client.post("/invites/redeem", headers=first["auth"], json={"code": code}).status_code == 200
    second = mk_user()
    r = client.post("/invites/redeem", headers=second["auth"], json={"code": code})
    assert r.status_code == 400   # запас кода исчерпан (uses_left=0)


def test_cannot_redeem_unknown_code(client):
    u = mk_user()
    assert client.post("/invites/redeem", headers=u["auth"], json={"code": "ZZZZZZ"}).status_code == 404


def test_cannot_redeem_twice_already_insider(client):
    inv1 = mk_user(name="Марат", avatar="https://x/m.jpg", verified=True)
    inv2 = mk_user(name="Наиль", avatar="https://x/n.jpg", verified=True)
    c1 = client.post("/invites", headers=inv1["auth"]).json()["code"]
    c2 = client.post("/invites", headers=inv2["auth"]).json()["code"]
    newbie = mk_user()
    assert client.post("/invites/redeem", headers=newbie["auth"], json={"code": c1}).status_code == 200
    r = client.post("/invites/redeem", headers=newbie["auth"], json={"code": c2})
    assert r.status_code == 400   # уже «свой»


def test_invite_quota(client):
    u = mk_user(name="Данис", avatar="https://x/d.jpg", verified=True)
    for _ in range(MAX_INVITES_PER_USER):
        assert client.post("/invites", headers=u["auth"]).status_code == 200
    assert client.post("/invites", headers=u["auth"]).status_code == 400   # запас исчерпан


def test_invites_mine_is_private(client):
    a = mk_user(name="А", avatar="https://x/1.jpg", verified=True)
    b = mk_user(name="Б", avatar="https://x/2.jpg", verified=True)
    ca = client.post("/invites", headers=a["auth"]).json()["code"]
    client.post("/invites", headers=b["auth"])
    a_codes = {i["code"] for i in client.get("/invites/mine", headers=a["auth"]).json()}
    b_codes = {i["code"] for i in client.get("/invites/mine", headers=b["auth"]).json()}
    assert ca in a_codes and ca not in b_codes   # чужие коды не видны (IDOR)


# ----------------------------- «Только для своих» -----------------------------

def _make_insider(client):
    inviter = mk_user(name="Приглаш", avatar="https://x/inv.jpg", verified=True)
    code = client.post("/invites", headers=inviter["auth"]).json()["code"]
    u = mk_user()
    client.post("/invites/redeem", headers=u["auth"], json={"code": code})
    return u


def test_only_trusted_ride_hidden_from_low_level(client):
    drv = mk_user(name="Вод", avatar="https://x/v.jpg", verified=True, role=UserRole.driver)
    frm, to = _city(), _city()
    ride = _publish(client, drv, frm, to, only_trusted=True)
    # L0-пассажир НЕ видит поездку «только для своих»
    l0 = mk_user()
    seen = client.get("/rides", params={"from_city": frm}, headers=l0["auth"]).json()
    assert all(r["id"] != ride["id"] for r in seen)
    # L3 (свой) — видит
    insider = _make_insider(client)
    seen3 = client.get("/rides", params={"from_city": frm}, headers=insider["auth"]).json()
    assert any(r["id"] == ride["id"] and r["only_trusted"] for r in seen3)


def test_only_trusted_ride_visible_to_owner(client):
    drv = mk_user(name="Хозяин", avatar="https://x/h.jpg", verified=True, role=UserRole.driver)  # L2, не L3
    frm, to = _city(), _city()
    ride = _publish(client, drv, frm, to, only_trusted=True)
    seen = client.get("/rides", params={"from_city": frm}, headers=drv["auth"]).json()
    assert any(r["id"] == ride["id"] for r in seen)   # свою поездку водитель видит всегда


def test_only_trusted_ride_hidden_from_anonymous(client):
    drv = mk_user(name="Вод2", avatar="https://x/v2.jpg", verified=True, role=UserRole.driver)
    frm, to = _city(), _city()
    ride = _publish(client, drv, frm, to, only_trusted=True)
    seen = client.get("/rides", params={"from_city": frm}).json()   # без токена
    assert all(r["id"] != ride["id"] for r in seen)


def test_only_trusted_ride_in_near(client):
    drv = mk_user(name="Вод3", avatar="https://x/v3.jpg", verified=True, role=UserRole.driver)
    frm, to = _city(), _city()
    ride = _publish(client, drv, frm, to, only_trusted=True)
    l0 = mk_user()
    body = client.get("/rides/near", params={"from_city": frm}, headers=l0["auth"]).json()
    assert all(it["id"] != ride["id"] for it in body["items"])
    insider = _make_insider(client)
    body3 = client.get("/rides/near", params={"from_city": frm}, headers=insider["auth"]).json()
    assert any(it["id"] == ride["id"] for it in body3["items"])


def test_only_trusted_request_hidden_in_feed(client):
    pax = mk_user(name="Пасс", avatar="https://x/p.jpg", verified=True)
    frm, to = _city(), _city()
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": frm, "to_city": to, "only_trusted": True}).json()
    # обычный водитель (L2, не L3) не видит заявку «только для своих»
    drv = mk_user(name="ВодF", avatar="https://x/vf.jpg", verified=True, role=UserRole.driver)
    feed = client.get("/requests/feed", headers=drv["auth"]).json()
    assert all(r["id"] != req["id"] for r in feed)
    # свой водитель видит
    insider = _make_insider(client)
    feed3 = client.get("/requests/feed", headers=insider["auth"]).json()
    assert any(r["id"] == req["id"] for r in feed3)


def test_only_trusted_request_respond_blocked_for_non_insider(client):
    pax = mk_user(name="Пасс2", avatar="https://x/p2.jpg", verified=True)
    frm, to = _city(), _city()
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": frm, "to_city": to, "only_trusted": True}).json()
    drv = mk_user(name="ВодR", avatar="https://x/vr.jpg", verified=True, role=UserRole.driver)  # L2
    r = client.post(f"/requests/{req['id']}/respond", headers=drv["auth"], json={"price": 200})
    assert r.status_code == 403   # прямой id не обходит фильтр (IDOR)


# ----------------------------- Согласия (152-ФЗ) -----------------------------

def test_consent_recorded_with_timestamp(client):
    u = mk_user()
    r = client.post("/me/consents", headers=u["auth"], json={"kind": "privacy"})
    assert r.status_code == 200
    assert r.json()["kind"] == "privacy" and r.json()["granted_at"]
    lst = client.get("/me/consents", headers=u["auth"]).json()
    assert any(c["kind"] == "privacy" for c in lst)


def test_consent_idempotent_keeps_first_timestamp(client):
    u = mk_user()
    first = client.post("/me/consents", headers=u["auth"], json={"kind": "offer"}).json()
    second = client.post("/me/consents", headers=u["auth"], json={"kind": "offer"}).json()
    assert first["granted_at"] == second["granted_at"]   # первое время — доказательство, не перезаписываем
    lst = client.get("/me/consents", headers=u["auth"]).json()
    assert len([c for c in lst if c["kind"] == "offer"]) == 1   # без дублей


def test_consent_invalid_kind(client):
    u = mk_user()
    assert client.post("/me/consents", headers=u["auth"], json={"kind": "hack"}).status_code == 400


def test_consent_private_per_user(client):
    a = mk_user()
    b = mk_user()
    client.post("/me/consents", headers=a["auth"], json={"kind": "geo"})
    assert client.get("/me/consents", headers=b["auth"]).json() == []   # чужие согласия не течут


# ----------------------------- Приватность уровня -----------------------------

def test_trust_requires_auth(client):
    assert client.get("/me/trust").status_code in (401, 403)
    assert client.post("/invites").status_code in (401, 403)
    assert client.get("/invites/mine").status_code in (401, 403)
    assert client.get("/me/consents").status_code in (401, 403)
