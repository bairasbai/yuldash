"""M1 — партнёрский слой + купоны «Скидки по пути»: позитив и негатив.

Проверяем ОБА опыта: счастливый путь (регистрация бизнеса → одобрение → подписка →
купон → активация → погашение → statement) и ошибки (просрочка, лимиты, чужой код,
приватность видимости). Все detail для 4xx — двуязычный dict {ru, ba}: ассертим str(detail).
"""
from datetime import datetime, timedelta

from app.models import UserRole


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _register_active_partner(client, user_factory, city="Уфа", plan="basic"):
    """Хелпер: владелец регистрирует бизнес → админ одобряет → подписка → админ подтверждает.
    Возвращает (owner, partner_id, plan)."""
    owner = user_factory(name="Кафе-владелец")
    admin = user_factory(name="Админ", role=UserRole.admin)
    r = client.post("/partner", headers=owner["auth"], json={"name": "Чак-чак кафе", "city": city})
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    assert r.json()["status"] == "pending"
    # админ одобряет бизнес
    ra = client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"])
    assert ra.status_code == 200, ra.text
    assert ra.json()["status"] == "active"
    # подписка
    rs = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": plan})
    assert rs.status_code == 200, rs.text
    payment_id = rs.json()["payment_id"]
    assert rs.json()["status"] == "pending"
    # админ подтверждает платёж → подписка активна
    rc = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert rc.status_code == 200, rc.text
    me = client.get("/partner/me", headers=owner["auth"]).json()
    assert me["partner"]["subscription_active"] is True
    return owner, admin, pid, plan


def _make_active_coupon(client, owner, **overrides):
    body = {"title": "Скидка на кофе", "discount_text": "−20%", "limit_per_user": 1}
    body.update(overrides)
    r = client.post("/partner/coupons", headers=owner["auth"], json=body)
    assert r.status_code == 200, r.text
    cid = r.json()["id"]
    rs = client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "active"})
    assert rs.status_code == 200, rs.text
    return cid


# ------------------------- ПОЗИТИВ -------------------------

def test_full_happy_path(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory, city="Стерлитамак")
    cid = _make_active_coupon(client, owner, city="Стерлитамак", valid_until=_future())

    # виден в витрине по городу
    lst = client.get("/coupons", params={"city": "Стерлитамак"}).json()
    assert any(c["id"] == cid for c in lst)

    # пользователь активирует → код
    passenger = user_factory(name="Пассажир")
    ra = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    assert ra.status_code == 200, ra.text
    code = ra.json()["code"]
    assert code and ra.json()["status"] == "reserved"

    # код в /my/coupons
    mine = client.get("/my/coupons", headers=passenger["auth"]).json()
    assert any(x["code"] == code for x in mine)

    # бизнес гасит код
    rr = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert rr.status_code == 200, rr.text
    assert rr.json()["ok"] is True
    assert rr.json()["discount_text"] == "−20%"

    # счётчик +1 и statement показывает сумму к оплате (1 погашение × 10 ₽ = 1000 коп)
    stats = client.get(f"/partner/coupons/{cid}/stats", headers=owner["auth"]).json()
    assert stats["redeemed"] == 1
    assert stats["amount_kop"] == 1000
    me = client.get("/partner/me", headers=owner["auth"]).json()
    assert me["statement"]["redeemed_total"] == 1
    assert me["statement"]["amount_kop"] == 1000


def test_activate_idempotent_returns_same_code(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_future(), limit_per_user=1)
    passenger = user_factory()
    r1 = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    r2 = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    assert r1.status_code == 200 and r2.status_code == 200
    # повторная активация в пределах лимита → та же бронь (тот же код)
    assert r1.json()["code"] == r2.json()["code"]


def test_partner_plans_public(client):
    r = client.get("/partner/plans")
    assert r.status_code == 200
    codes = {p["code"] for p in r.json()}
    assert {"basic", "standard", "premium"} <= codes


# ------------------------- НЕГАТИВ -------------------------

def test_activate_expired_coupon(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_past())
    passenger = user_factory()
    r = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    assert r.status_code == 422
    assert "истёк" in str(r.json()["detail"])


def test_activate_over_total_limit(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_future(), limit_total=1, limit_per_user=1)
    u1 = user_factory()
    u2 = user_factory()
    assert client.post(f"/coupons/{cid}/activate", headers=u1["auth"]).status_code == 200
    r = client.post(f"/coupons/{cid}/activate", headers=u2["auth"])
    assert r.status_code == 409
    assert "закончил" in str(r.json()["detail"])


def test_activate_over_per_user_limit(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_future(), limit_per_user=1)
    passenger = user_factory()
    r1 = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    code = r1.json()["code"]
    # гасим — теперь у юзера redeemed=1, лимит на пользователя исчерпан
    client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    r2 = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    assert r2.status_code == 409
    assert "воспользовал" in str(r2.json()["detail"])


def test_redeem_already_redeemed(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_future(), limit_per_user=5)
    passenger = user_factory()
    code = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"]).json()["code"]
    assert client.post("/coupons/redeem", headers=owner["auth"], json={"code": code}).status_code == 200
    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert r.status_code == 409
    assert "погаш" in str(r.json()["detail"])


def test_redeem_by_other_partner_404(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    cid = _make_active_coupon(client, owner, valid_until=_future())
    passenger = user_factory()
    code = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"]).json()["code"]
    # чужой бизнес пытается погасить
    other_owner, _oa, _opid, _ = _register_active_partner(client, user_factory, city="Сибай")
    r = client.post("/coupons/redeem", headers=other_owner["auth"], json={"code": code})
    assert r.status_code == 404
    assert "не найден" in str(r.json()["detail"])


def test_redeem_nonexistent_code_404(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": "ZZZZZZ"})
    assert r.status_code == 404


def test_coupon_hidden_without_subscription(client, user_factory):
    """Купон одобренного бизнеса БЕЗ оплаченной подписки не виден в витрине."""
    owner = user_factory()
    admin = user_factory(role=UserRole.admin)
    pid = client.post("/partner", headers=owner["auth"], json={"name": "АЗС на трассе", "city": "Мелеуз"}).json()["id"]
    client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"])
    # НЕ подписываемся, но создаём активный купон
    cid = _make_active_coupon(client, owner, city="Мелеуз", valid_until=_future())
    lst = client.get("/coupons", params={"city": "Мелеуз"}).json()
    assert all(c["id"] != cid for c in lst)
    # деталь тоже 404 (нет оплаченной подписки)
    assert client.get(f"/coupons/{cid}").status_code == 404


def test_paused_coupon_hidden(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory, city="Бирск")
    cid = _make_active_coupon(client, owner, city="Бирск", valid_until=_future())
    # ставим на паузу
    client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "paused"})
    lst = client.get("/coupons", params={"city": "Бирск"}).json()
    assert all(c["id"] != cid for c in lst)


def test_edit_other_partner_404(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    other = user_factory()
    r = client.post(f"/partner/{pid}", headers=other["auth"], json={"name": "Взлом"})
    assert r.status_code == 404
    assert "не найден" in str(r.json()["detail"])


def test_create_coupon_without_partner(client, user_factory):
    stranger = user_factory()
    r = client.post("/partner/coupons", headers=stranger["auth"], json={"title": "Скидка"})
    assert r.status_code == 404
    assert "бизнес" in str(r.json()["detail"]).lower()


def test_create_coupon_on_unapproved_partner(client, user_factory):
    owner = user_factory()
    client.post("/partner", headers=owner["auth"], json={"name": "Шиномонтаж", "city": "Туймазы"})
    r = client.post("/partner/coupons", headers=owner["auth"], json={"title": "Скидка"})
    assert r.status_code == 409
    assert "проверк" in str(r.json()["detail"])


def test_register_partner_requires_name_and_city(client, user_factory):
    owner = user_factory()
    r1 = client.post("/partner", headers=owner["auth"], json={"name": "", "city": "Уфа"})
    assert r1.status_code == 422 and "азвание" in str(r1.json()["detail"])
    r2 = client.post("/partner", headers=owner["auth"], json={"name": "Кафе", "city": ""})
    assert r2.status_code == 422 and "город" in str(r2.json()["detail"]).lower()


def test_one_owner_one_partner(client, user_factory):
    owner = user_factory()
    assert client.post("/partner", headers=owner["auth"], json={"name": "Первый", "city": "Уфа"}).status_code == 200
    r = client.post("/partner", headers=owner["auth"], json={"name": "Второй", "city": "Уфа"})
    assert r.status_code == 409
    assert "бизнес" in str(r.json()["detail"]).lower()


def test_subscribe_requires_approved_business(client, user_factory):
    owner = user_factory()
    client.post("/partner", headers=owner["auth"], json={"name": "Аптека", "city": "Уфа"})
    r = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"})
    assert r.status_code == 409
    assert "проверк" in str(r.json()["detail"])


def test_subscribe_idempotent_pending(client, user_factory):
    owner = user_factory()
    admin = user_factory(role=UserRole.admin)
    pid = client.post("/partner", headers=owner["auth"], json={"name": "Магазин", "city": "Уфа"}).json()["id"]
    client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"])
    p1 = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"}).json()
    # Тот же тариф — тот же счёт: двойной тап не плодит заявок.
    p1b = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"}).json()
    assert p1["payment_id"] == p1b["payment_id"]
    # А смена тарифа выставляет НОВЫЙ счёт (волна 125). Раньше возвращался старый: человек
    # выбирал «Стандарт», приложение просило цену «Базового», и после оплаты включался базовый.
    p2 = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "standard"}).json()
    assert p1["payment_id"] != p2["payment_id"]
    assert p2["plan"] == "standard" and p2["amount_kop"] > p1["amount_kop"]


def test_premium_flag_only_on_premium_plan(client, user_factory):
    """На basic-подписке premium-метку не проставить (форсим False)."""
    owner, admin, pid, _ = _register_active_partner(client, user_factory, plan="basic")
    r = client.post("/partner/coupons", headers=owner["auth"],
                    json={"title": "VIP", "premium": True, "valid_until": _future()})
    assert r.status_code == 200
    assert r.json()["premium"] is False


def test_premium_flag_on_premium_plan(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory, plan="premium")
    r = client.post("/partner/coupons", headers=owner["auth"],
                    json={"title": "VIP", "premium": True, "valid_until": _future()})
    assert r.status_code == 200
    assert r.json()["premium"] is True


def test_admin_only_moderation(client, user_factory):
    owner, admin, pid, _ = _register_active_partner(client, user_factory)
    stranger = user_factory()
    assert client.get("/admin/partners", headers=stranger["auth"]).status_code == 403
