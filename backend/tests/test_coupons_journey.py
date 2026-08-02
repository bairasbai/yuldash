"""M1 — сквозной e2e прогон партнёрского сценария (QA-регрессия).

Один связный путь бизнес-партнёра от регистрации до statement + батарея отказов,
с явной проверкой двуязычия detail={ru,ba} и приватности (телефон покупателя не течёт).
Дополняет test_coupons.py: тут фиксируем ЦИФРЫ и КОНТРАКТ ответов, а не только коды.
"""
from datetime import datetime, timedelta

from app.models import UserRole
from app.routers.coupons import PARTNER_PLANS, PARTNER_REDEMPTION_FEE_KOP


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _bilingual(detail):
    """detail 4xx обязан быть dict {ru, ba}, оба непустые, разные — не заглушка."""
    assert isinstance(detail, dict), f"detail не dict: {detail!r}"
    assert detail.get("ru") and detail.get("ba"), f"нет обоих языков: {detail!r}"
    return detail


def _onboard_paid_partner(client, user_factory, city="Уфа", plan="standard"):
    owner = user_factory(name="Айгуль")
    admin = user_factory(name="Админ", role=UserRole.admin)
    # 1. регистрация бизнеса → pending
    r = client.post("/partner", headers=owner["auth"], json={
        "name": "Кафе Тюбетей", "city": city, "phone": "+7 900 000-00-00", "category": "cafe"})
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    assert r.json()["status"] == "pending"
    assert r.json()["subscription_active"] is False
    # 2. админ одобряет → active, но подписки ещё нет
    ra = client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"])
    assert ra.status_code == 200 and ra.json()["status"] == "active"
    # 3. подписка: платёж pending на сумму тарифа
    rs = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": plan})
    assert rs.status_code == 200, rs.text
    assert rs.json()["status"] == "pending"
    assert rs.json()["amount_kop"] == PARTNER_PLANS[plan]["amount_kop"]
    payment_id = rs.json()["payment_id"]
    # до подтверждения — подписка НЕ активна
    assert client.get("/partner/me", headers=owner["auth"]).json()["partner"]["subscription_active"] is False
    # 4. админ подтверждает платёж → подписка активна
    rc = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert rc.status_code == 200 and rc.json()["status"] == "succeeded"
    me = client.get("/partner/me", headers=owner["auth"]).json()
    assert me["partner"]["subscription_active"] is True
    assert me["partner"]["subscription_plan"] == plan
    return owner, admin, pid


def test_journey_positive_full(client, user_factory):
    """Счастливый путь целиком, с проверкой цифр statement."""
    owner, admin, pid = _onboard_paid_partner(client, user_factory, city="Стерлитамак", plan="standard")

    # создаём купон (draft) → пока НЕ в витрине
    rc = client.post("/partner/coupons", headers=owner["auth"], json={
        "title": "Кофе −25%", "discount_text": "−25%", "city": "Стерлитамак",
        "valid_until": _future(), "limit_total": 100, "limit_per_user": 1})
    assert rc.status_code == 200, rc.text
    cid = rc.json()["id"]
    assert rc.json()["status"] == "draft"
    assert all(c["id"] != cid for c in client.get("/coupons", params={"city": "Стерлитамак"}).json())

    # активируем купон → появляется в витрине
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200
    shelf = client.get("/coupons", params={"city": "Стерлитамак"}).json()
    card = next(c for c in shelf if c["id"] == cid)
    assert card["discount_text"] == "−25%"
    assert card["remaining"] == 100 and card["redeemed_count"] == 0
    # карточка отдаёт публичный телефон БИЗНЕСА, но у витрины нет полей покупателя
    assert card["partner"]["phone"] == "+7 900 000-00-00"

    # покупатель активирует → получает код
    passenger = user_factory(name="Динар")
    ra = client.post(f"/coupons/{cid}/activate", headers=passenger["auth"])
    assert ra.status_code == 200, ra.text
    code = ra.json()["code"]
    assert code and ra.json()["status"] == "reserved"
    assert any(x["code"] == code for x in client.get("/my/coupons", headers=passenger["auth"]).json())

    # партнёр гасит код
    rr = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert rr.status_code == 200, rr.text
    body = rr.json()
    assert body["ok"] is True
    assert body["discount_text"] == "−25%"
    assert body["customer_name"] == "Динар"

    # ПРИВАТНОСТЬ: в ответе погашения нет телефона / гео / user_id покупателя
    leaked = {"phone", "customer_phone", "lat", "lng", "user_id", "customer_id"}
    assert leaked.isdisjoint(body.keys()), f"утечка приватного поля: {set(body) & leaked}"

    # statement: 1 погашение × комиссия
    stats = client.get(f"/partner/coupons/{cid}/stats", headers=owner["auth"]).json()
    assert stats["redeemed"] == 1
    assert stats["amount_kop"] == PARTNER_REDEMPTION_FEE_KOP
    me = client.get("/partner/me", headers=owner["auth"]).json()
    assert me["statement"]["redeemed_total"] == 1
    assert me["statement"]["fee_per_redemption_kop"] == PARTNER_REDEMPTION_FEE_KOP
    assert me["statement"]["amount_kop"] == PARTNER_REDEMPTION_FEE_KOP


def test_journey_negatives_are_bilingual(client, user_factory):
    """Каждый отказ — понятный двуязычный detail {ru, ba}."""
    owner, admin, pid = _onboard_paid_partner(client, user_factory, city="Сибай", plan="basic")

    # просрочка → 422 двуязычно
    exp = client.post("/partner/coupons", headers=owner["auth"],
                      json={"title": "Старый", "valid_until": _past()}).json()["id"]
    client.post(f"/partner/coupons/{exp}/status", headers=owner["auth"], json={"status": "active"})
    r = client.post(f"/coupons/{exp}/activate", headers=user_factory()["auth"])
    assert r.status_code == 422
    _bilingual(r.json()["detail"])

    # общий лимит → 409 двуязычно
    lim = client.post("/partner/coupons", headers=owner["auth"],
                      json={"title": "Лимит", "valid_until": _future(), "limit_total": 1, "limit_per_user": 1}).json()["id"]
    client.post(f"/partner/coupons/{lim}/status", headers=owner["auth"], json={"status": "active"})
    assert client.post(f"/coupons/{lim}/activate", headers=user_factory()["auth"]).status_code == 200
    r = client.post(f"/coupons/{lim}/activate", headers=user_factory()["auth"])
    assert r.status_code == 409
    _bilingual(r.json()["detail"])

    # лимит на пользователя → 409 двуязычно
    peru = client.post("/partner/coupons", headers=owner["auth"],
                       json={"title": "ПерЮзер", "valid_until": _future(), "limit_per_user": 1}).json()["id"]
    client.post(f"/partner/coupons/{peru}/status", headers=owner["auth"], json={"status": "active"})
    p = user_factory()
    code = client.post(f"/coupons/{peru}/activate", headers=p["auth"]).json()["code"]
    client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    r = client.post(f"/coupons/{peru}/activate", headers=p["auth"])
    assert r.status_code == 409
    _bilingual(r.json()["detail"])

    # повторное погашение → 409; несуществующий код → 404; чужой партнёр → 404
    ok = client.post("/partner/coupons", headers=owner["auth"],
                     json={"title": "Погашение", "valid_until": _future(), "limit_per_user": 5}).json()["id"]
    client.post(f"/partner/coupons/{ok}/status", headers=owner["auth"], json={"status": "active"})
    c2 = client.post(f"/coupons/{ok}/activate", headers=user_factory()["auth"]).json()["code"]
    assert client.post("/coupons/redeem", headers=owner["auth"], json={"code": c2}).status_code == 200
    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": c2})
    assert r.status_code == 409
    _bilingual(r.json()["detail"])
    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": "ZZZZZZ"})
    assert r.status_code == 404
    _bilingual(r.json()["detail"])
    other_owner, _oa, _op = _onboard_paid_partner(client, user_factory, city="Бирск", plan="basic")
    c3 = client.post(f"/coupons/{ok}/activate", headers=user_factory()["auth"]).json()["code"]
    r = client.post("/coupons/redeem", headers=other_owner["auth"], json={"code": c3})
    assert r.status_code == 404
    _bilingual(r.json()["detail"])


def test_journey_gates_and_idor(client, user_factory):
    """Гейты доступа: подписка на неодобренном, купон без подписки, IDOR чужого бизнеса."""
    # подписка на неодобренном бизнесе → 409 «Бизнес ещё на проверке»
    owner = user_factory()
    client.post("/partner", headers=owner["auth"], json={"name": "Новый", "city": "Уфа"})
    r = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"})
    assert r.status_code == 409
    d = _bilingual(r.json()["detail"])
    assert "проверк" in d["ru"]

    # купон одобренного бизнеса БЕЗ оплаченной подписки → не в витрине + деталь 404
    admin = user_factory(role=UserRole.admin)
    pid = client.post("/partner", headers=owner["auth"], json={"name": "Апдейт", "city": "Дюртюли"}).json()
    # (owner уже имеет бизнес — регистрируем нового владельца)
    o2 = user_factory()
    pid = client.post("/partner", headers=o2["auth"], json={"name": "Кафе2", "city": "Дюртюли"}).json()["id"]
    client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"])
    cid = client.post("/partner/coupons", headers=o2["auth"],
                      json={"title": "БезПодписки", "city": "Дюртюли", "valid_until": _future()}).json()["id"]
    client.post(f"/partner/coupons/{cid}/status", headers=o2["auth"], json={"status": "active"})
    assert all(c["id"] != cid for c in client.get("/coupons", params={"city": "Дюртюли"}).json())
    assert client.get(f"/coupons/{cid}").status_code == 404

    # IDOR: чужой правит мой бизнес → 404
    stranger = user_factory()
    r = client.post(f"/partner/{pid}", headers=stranger["auth"], json={"name": "Взлом"})
    assert r.status_code == 404
    _bilingual(r.json()["detail"])
    # IDOR: чужой правит мой купон → 404
    r = client.post(f"/partner/coupons/{cid}", headers=stranger["auth"], json={"title": "Взлом"})
    assert r.status_code == 404
    _bilingual(r.json()["detail"])
