"""leaf-1.3 — деньги: купонный маркетплейс «Скидки по пути» (app/routers/coupons.py).

Фокус группы «Деньги»:
  R1 — купон нельзя погасить дважды, даже двумя одновременными запросами (PostgreSQL);
  R2 — бизнес не может погасить чужой купон (чужой владелец → тот же 404, код не раскрывается);
  R3 — лимит «один купон на человека» не превышается даже под двумя одновременными активациями
       одного и того же пассажира (PostgreSQL) — единственная защита здесь — row-lock на
       Coupon (нет атомарного CAS-UPDATE, как у погашения), так что эта гонка проверяется
       ТОЛЬКО на настоящем Postgres: на SQLite её не ловит никто (FOR UPDATE — no-op);
  R4 — срок действия купона считается в едином времени: уфимское время из анкеты бизнеса
       (без явного пояса) конвертируется в UTC со сдвигом −5ч, и сравнение с «сейчас» идёт
       уже в UTC с обеих сторон.
"""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

import pytest
from sqlalchemy import func, text
from sqlmodel import Session, select

from app.db import engine
from app.models import Coupon, CouponRedemption, Partner, User, UserRole
from app.routers import coupons as coupons_router
from app.routers.coupons import RedeemIn
from app.timeutil import client_dt_to_utc


def _past():
    return (datetime.utcnow() - timedelta(days=1)).replace(microsecond=0).isoformat()


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _register_active_partner(client, user_factory, name="Купон-бизнес"):
    """Компактная копия сценария test_coupons.py: регистрация → одобрение → оплаченная подписка."""
    owner = user_factory(name)
    admin = user_factory(f"{name}-админ", role=UserRole.admin)
    r = client.post("/partner", headers=owner["auth"], json={"name": name, "city": "Уфа"})
    assert r.status_code == 200, r.text
    pid = r.json()["id"]
    assert client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"]).status_code == 200
    rs = client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"})
    assert rs.status_code == 200, rs.text
    payment_id = rs.json()["payment_id"]
    assert client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"]).status_code == 200
    return owner, admin, pid


def _make_active_coupon(client, owner, **overrides):
    body = {"title": "Скидка леафа 1.3", "discount_text": "−15%", "limit_per_user": 1}
    body.update(overrides)
    r = client.post("/partner/coupons", headers=owner["auth"], json=body)
    assert r.status_code == 200, r.text
    cid = r.json()["id"]
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200
    return cid


def _pg_session():
    s = Session(engine)
    s.execute(text("SET lock_timeout = '4s'"))
    s.execute(text("SET statement_timeout = '8s'"))
    return s


# ============================ R1: не погашается дважды (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_redeem_only_one_wins(client, user_factory):
    """Два кассира одного бизнеса нажимают «погасить» на один и тот же код одновременно —
    должен пройти РОВНО один, счётчик погашений вырасти РОВНО на 1."""
    owner, _admin, _pid = _register_active_partner(client, user_factory, "ГонкаПогашения")
    cid = _make_active_coupon(client, owner, limit_per_user=1)
    pax = user_factory("ГонкаПогашенияПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    barrier = threading.Barrier(2)

    def redeem():
        with _pg_session() as s:
            biz_user = s.get(User, owner["id"])
            barrier.wait(timeout=10)
            try:
                return coupons_router.coupon_redeem(RedeemIn(code=code), user=biz_user, session=s)
            except Exception as exc:  # HTTPException — ожидаемый отказ второго кассира
                return exc

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(redeem) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]

    oks = [r for r in results if isinstance(r, dict)]
    fails = [r for r in results if not isinstance(r, dict)]
    assert len(oks) == 1, f"погашение прошло не ровно один раз: {results}"
    assert len(fails) == 1
    assert getattr(fails[0], "status_code", None) == 409
    assert "погаш" in fails[0].detail["ru"].lower() and fails[0].detail["ba"]

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 1, "счётчик погашений не должен задваиваться"
        red = s.exec(select(CouponRedemption).where(CouponRedemption.code == code)).first()
        assert red.status == "redeemed"


# ============================ R2: бизнес не может погасить чужой купон ============================
def test_business_cannot_redeem_foreign_coupon(client, user_factory):
    owner_a, _admin_a, _pid_a = _register_active_partner(client, user_factory, "СвойБизнесА")
    owner_b, _admin_b, _pid_b = _register_active_partner(client, user_factory, "ЧужойБизнесБ")
    cid = _make_active_coupon(client, owner_a, limit_per_user=1)
    pax = user_factory("ЧужойКупонПас")
    act = client.post(f"/coupons/{cid}/activate", headers=pax["auth"])
    assert act.status_code == 200, act.text
    code = act.json()["code"]

    # Бизнес Б пытается погасить код бизнеса А.
    r = client.post("/coupons/redeem", headers=owner_b["auth"], json={"code": code})
    assert r.status_code == 404, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], "отказ должен быть понятным человеку на двух языках"

    # Код остался цел — законный владелец бизнеса А всё ещё может его погасить.
    r_owner = client.post("/coupons/redeem", headers=owner_a["auth"], json={"code": code})
    assert r_owner.status_code == 200, r_owner.text

    with Session(engine) as s:
        coupon = s.get(Coupon, cid)
        assert coupon.redeemed_count == 1, "чужая попытка не должна была засчитаться"


# ============================ R3: лимит на человека (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_activation_respects_per_user_limit(client, user_factory, monkeypatch):
    """Один и тот же пассажир жмёт «активировать купон» дважды почти одновременно на купоне
    с limit_per_user=1 — в базе должна остаться РОВНО одна бронь, а не две.

    Голого барьера перед вызовом мало: после него поток ещё должен реально оказаться внутри
    критического участка (после чтения купона, до вставки брони) ОДНОВРЕМЕННО со вторым, а
    планировщик (особенно на Windows) может прогнать один поток целиком раньше, чем вообще
    переключится на другой. Поэтому дополнительно притормаживаем генерацию кода (`_gen_code`) —
    единственный шаг, который звучит ПОСЛЕ проверки лимита и ДО вставки, и его зовут оба потока."""
    original_gen_code = coupons_router._gen_code

    def delayed_gen_code(session):
        time.sleep(0.2)
        return original_gen_code(session)

    monkeypatch.setattr(coupons_router, "_gen_code", delayed_gen_code)

    owner, _admin, _pid = _register_active_partner(client, user_factory, "ГонкаЛимита")
    cid = _make_active_coupon(client, owner, limit_per_user=1, limit_total=0)
    pax = user_factory("ГонкаЛимитаПас")

    barrier = threading.Barrier(2)

    def activate():
        with _pg_session() as s:
            user = s.get(User, pax["id"])
            barrier.wait(timeout=10)
            return coupons_router.coupon_activate(cid, user=user, session=s)

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(activate) for _ in range(2)]
        results = [f.result(timeout=20) for f in futures]

    assert results[0]["code"] == results[1]["code"], "обе активации должны сойтись на ОДНОЙ брони"

    with Session(engine) as s:
        count = s.exec(
            select(func.count()).select_from(CouponRedemption).where(
                CouponRedemption.coupon_id == cid, CouponRedemption.user_id == pax["id"],
            )
        ).one()
    assert count == 1, "под гонкой лимит «один купон на человека» не должен пробиваться"


# ============================ R4: срок действия и часовой пояс Уфы ============================
def test_window_respects_ufa_timezone():
    """Бизнес ставит срок «до 23:59» в анкете — это уфимское время (UTC+5), а не UTC.
    Купон обязан закрыться в 18:59 UTC (= 23:59 Уфа), а не в 23:59 UTC."""
    valid_until_local_ufa = datetime(2026, 6, 15, 23, 59, 0)
    coupon = Coupon(partner_id=1, title="Срок по Уфе", valid_until=client_dt_to_utc(valid_until_local_ufa))

    # Конвертация должна сдвинуть время на −5 часов (Уфа = UTC+5).
    assert coupon.valid_until == datetime(2026, 6, 15, 18, 59, 0)

    # 18:00 UTC того же дня = 23:00 по Уфе — купон ещё в силе.
    assert coupons_router._in_window(coupon, datetime(2026, 6, 15, 18, 0, 0)) is True
    # 20:00 UTC того же дня = 01:00 по Уфе УЖЕ СЛЕДУЮЩИХ суток — если бы сервер по ошибке
    # сравнивал уфимское время напрямую с UTC (забыв про сдвиг), купон здесь ещё казался бы
    # действующим (20:00 < 23:59) — а на самом деле уфимский дедлайн уже прошёл.
    assert coupons_router._in_window(coupon, datetime(2026, 6, 15, 20, 0, 0)) is False
