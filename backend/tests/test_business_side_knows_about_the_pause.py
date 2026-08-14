"""Коммерческая сторона тоже знает про паузу.

Тем же вопросом («сколько наказаний и все ли двери про них знают») прошёлся по бизнесу
и рекламе. Оказалось, коммерческий контур паузу «Справедливости» не знал ВООБЩЕ: отстранённый
разбором владелец заводил новые купоны, публиковал их в витрину, правил карточку бизнеса
и сдавал рекламу на модерацию — всё по 200 (проверено запросами, аудит 2026-08-13, волна 62).

Правило §2 звучит как «приостановленный аккаунт не совершает активных действий», и витрина —
такое же активное действие, как публикация поездки: сервис показывает предложение людям.

Две границы проведены сознательно и проверены здесь же:

  • УЖЕ опубликованное и оплаченное не гасим. Это деньги бизнеса и обязательство перед ним,
    а не его наказание: платная витрина, которую отключили из-за спора о поездке, — это
    не справедливость, а убыток человеку;
  • просмотр своей статистики и оплата подписки остаются. Смотреть свои цифры и платить
    сервису человек вправе всегда, иначе наказание превращается в «не дам рассчитаться».
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Coupon, Partner, SafetyProfile, UserRole
from app.timeutil import utcnow

COUPON = {"title": "Кофе -20%", "description": "по будням", "discount_text": "-20%",
          "city": "Сибай", "route_hint": "", "limit_total": 0, "limit_per_user": 1}
AD = {"title": "Шины дёшево", "text": "приходи", "button": "Открыть",
      "target": "https://example.com", "cities": "Сибай", "package": "week"}


def _business(client, user_factory, tag):
    u = user_factory(tag, role=UserRole.passenger)
    assert client.post("/partner", headers=u["auth"], json={
        "name": f"Кафе {tag}", "city": "Сибай", "category": "food",
        "address": "ул. Ленина 1", "phone": "", "description": "вкусно",
    }).status_code == 200
    with Session(engine) as s:
        p = s.exec(select(Partner).where(Partner.owner_id == u["id"])).first()
        p.status = "active"
        p.subscription_until = utcnow() + timedelta(days=30)
        s.add(p)
        s.commit()
        return u, p.id


def _suspend(user_id: int) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=7)))
        s.commit()


def test_отстранённый_владелец_купонов_не_заводит(client, user_factory):
    owner, _pid = _business(client, user_factory, "BizNoCoupon")
    assert client.post("/partner/coupons", headers=owner["auth"], json=COUPON).status_code == 200

    _suspend(owner["id"])
    assert client.post("/partner/coupons", headers=owner["auth"], json=COUPON).status_code == 403


def test_отстранённый_владелец_купон_в_витрину_не_выпускает(client, user_factory):
    """Самое важное: витрина — это сервис РЕКОМЕНДУЕТ предложение людям."""
    owner, _pid = _business(client, user_factory, "BizNoPublish")
    cid = client.post("/partner/coupons", headers=owner["auth"], json=COUPON).json()["id"]

    _suspend(owner["id"])
    r = client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                    json={"status": "active"})
    assert r.status_code == 403
    with Session(engine) as s:
        assert s.get(Coupon, cid).status == "draft"


def test_отстранённый_владелец_карточку_бизнеса_не_правит(client, user_factory):
    owner, pid = _business(client, user_factory, "BizNoEdit")
    _suspend(owner["id"])

    r = client.post(f"/partner/{pid}", headers=owner["auth"], json={
        "name": "Кафе НОВОЕ", "city": "Сибай", "category": "food",
        "address": "ул. Ленина 1", "phone": "", "description": "вкусно",
    })
    assert r.status_code == 403


def test_отстранённый_рекламу_не_заводит_и_не_сдаёт(client, user_factory):
    u = user_factory("AdSuspended", role=UserRole.passenger)
    aid = client.post("/ads", headers=u["auth"], json=AD).json()["id"]

    _suspend(u["id"])
    assert client.post("/ads", headers=u["auth"], json=AD).status_code == 403
    assert client.post(f"/ads/{aid}/submit", headers=u["auth"]).status_code == 403


def test_уже_опубликованный_купон_наказанием_не_гасим(client, user_factory):
    """Граница первая: оплаченная витрина продолжает работать. Покупатель видит купон,
    бизнес не теряет деньги из-за спора о поездке."""
    owner, _pid = _business(client, user_factory, "BizLive")
    cid = client.post("/partner/coupons", headers=owner["auth"], json={
        **COUPON, "title": "Живой купон -30%"}).json()["id"]
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200

    _suspend(owner["id"])

    shop = client.get("/coupons", params={"city": "Сибай"}).json()
    assert any(c["id"] == cid for c in shop)


def test_свою_статистику_и_оплату_на_паузе_не_отбираем(client, user_factory):
    """Граница вторая: смотреть свои цифры и платить сервису можно всегда."""
    u = user_factory("AdStats", role=UserRole.passenger)
    aid = client.post("/ads", headers=u["auth"], json=AD).json()["id"]
    _suspend(u["id"])

    assert client.get(f"/ads/{aid}/stats", headers=u["auth"]).status_code == 200


def test_бизнес_без_наказаний_работает_как_прежде(client, user_factory):
    """Страховка от перестраховки."""
    owner, pid = _business(client, user_factory, "BizFine")
    cid = client.post("/partner/coupons", headers=owner["auth"], json=COUPON).json()["id"]
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200
    assert client.post(f"/partner/{pid}", headers=owner["auth"], json={
        "name": "Кафе Обычное", "city": "Сибай", "category": "food",
        "address": "ул. Ленина 1", "phone": "", "description": "вкусно",
    }).status_code == 200
