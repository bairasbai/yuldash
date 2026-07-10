"""Тесты «Долг по комиссии за такси» (Модель А «на доверии», Фаза 3).

Покрываем плотно (деньги + доверие — критично):
- долг начисляется на завершённый instant-заказ (8% с цены, целые копейки, идемпотентно);
- блок такси при ПРОСРОЧКЕ (presence/offer/accept → 403), и при превышении порога;
- ПОПУТКА (плановые Ride) НЕ блокируется при долге — важнейший тест доверия;
- цикл оплаты: «Я оплатил» → pending → админ confirm → разблок; reject → снова unpaid → блок;
- анти-IDOR (чужой долг не виден/не трогается; админ-эндпоинты только админу);
- границы (нулевая комиссия долг не создаёт; ничего не должен → пусто).
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app.db import engine
from app import instant_service as isv
from app.config import settings
from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus as S, User, UserRole
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _sbp_reqs(monkeypatch):
    """Реквизиты СБП Александра — из конфига (в тестах подставляем, НЕ хардкод в коде)."""
    monkeypatch.setattr(settings, "owner_sbp_phone", "+79990001122")
    monkeypatch.setattr(settings, "owner_sbp_name", "Александр А.")
    yield


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="DbtDrv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _heartbeat(client, d, coord=ORIG):
    return client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _order_to_done(client, user_factory, fake_redis, dname="DoneDrv", pname="DonePax"):
    """Прогнать заказ до done: онлайн-водитель → оффер → accept → arrived → onboard → done."""
    d = _driver_online(client, user_factory, dname)
    assert _heartbeat(client, d).status_code == 200
    pax = user_factory(pname)
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["status"] == "offered", order
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).status_code == 200
    assert client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).status_code == 200
    assert client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"]).status_code == 200
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()
    assert done["status"] == "done"
    return d, pax, done


def _make_overdue(driver_id: int):
    """Сдвинуть срок всех неоплаченных долгов водителя в прошлое (эмуляция просрочки)."""
    with Session(engine) as s:
        rows = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id, CommissionDebt.status == DebtStatus.unpaid)).all()
        for d in rows:
            d.due_at = utcnow() - timedelta(days=1)
            s.add(d)
        s.commit()


def _debt_row(driver_id: int) -> CommissionDebt:
    with Session(engine) as s:
        return s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id).order_by(CommissionDebt.id.desc())).first()


# ============================ Начисление долга ============================
def test_debt_accrued_on_instant_done(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "AccDrv", "AccPax")
    price_rub = done["price_final"]
    row = _debt_row(d["id"])
    assert row is not None
    assert row.order_id == done["id"]
    assert row.status == DebtStatus.unpaid
    # Новичок (первый done-заказ) платит 1-ю ступень лесенки (3%), копейки, ROUND_HALF_UP.
    expected = round(price_rub * 100 * settings.fee_tier1_percent / 100)
    assert row.amount_kop == expected
    assert row.amount_kop > 0
    assert row.week.startswith(str(utcnow().year))


def test_debt_accrual_idempotent(client, user_factory, fake_redis):
    """Повторный тап done (идемпотентный переход) НЕ задваивает долг."""
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "IdemDrv", "IdemPax")
    oid = done["id"]
    client.post(f"/instant/orders/{oid}/done", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/done", headers=d["auth"])
    with Session(engine) as s:
        rows = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).all()
    assert len(rows) == 1


# ============================ /driver/debt ============================
def test_driver_debt_summary(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "SumDrv", "SumPax")
    r = client.get("/driver/debt", headers=d["auth"])
    assert r.status_code == 200
    body = r.json()
    assert body["unpaid_kop"] > 0
    assert body["due_at"] is not None
    assert body["blocked"] is False              # свежий долг (не просрочен, ниже порога)
    assert body["sbp"]["phone"] == "+79990001122"   # реквизиты СБП из конфига
    assert body["sbp"]["name"] == "Александр А."
    assert len(body["weeks"]) >= 1


def test_driver_no_debt_empty(client, user_factory):
    d = _driver_online(client, user_factory, "NoDebtDrv")
    body = client.get("/driver/debt", headers=d["auth"]).json()
    assert body["unpaid_kop"] == 0
    assert body["blocked"] is False
    assert body["due_at"] is None


# ============================ Блок такси при просрочке ============================
def test_overdue_blocks_presence(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "OvPresDrv", "OvPresPax")
    assert _heartbeat(client, d).status_code == 200   # пока не просрочено — можно
    _make_overdue(d["id"])
    r = client.get("/driver/debt", headers=d["auth"]).json()
    assert r["blocked"] is True and r["overdue"] is True
    resp = _heartbeat(client, d)
    assert resp.status_code == 403
    assert "долг" in resp.json()["detail"].lower()


def test_overdue_blocks_accept(client, user_factory, fake_redis):
    """Просроченный долг → водитель не может принять новый такси-оффер (accept → 403)."""
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "OvAccDrv", "OvAccPax")
    _make_overdue(d["id"])
    # Новый заказ этого же пассажира; оффер уйдёт нашему водителю (он единственный онлайн рядом)…
    # но presence уже заблокирован — сматчиться он не сможет. Проверяем сам guard на accept:
    # создаём заказ и вручную выставляем оффер нашему водителю в БД.
    pax2 = user_factory("OvAccPax2")
    order = client.post("/instant/orders", headers=pax2["auth"], json=_order_body()).json()
    oid = order["id"]
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.status = S.offered
        o.current_offer_driver_id = d["id"]
        o.offer_expires_at = utcnow() + timedelta(minutes=5)
        s.add(o)
        s.commit()
    resp = client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    assert resp.status_code == 403
    assert "долг" in resp.json()["detail"].lower()


def test_overdue_blocks_offer_poll(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "OvOffDrv", "OvOffPax")
    _make_overdue(d["id"])
    pax2 = user_factory("OvOffPax2")
    order = client.post("/instant/orders", headers=pax2["auth"], json=_order_body()).json()
    with Session(engine) as s:
        o = s.get(InstantOrder, order["id"])
        o.status = S.offered
        o.current_offer_driver_id = d["id"]
        o.offer_expires_at = utcnow() + timedelta(minutes=5)
        s.add(o)
        s.commit()
    assert client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"] is None


def test_threshold_blocks_even_if_not_overdue(client, user_factory, fake_redis, monkeypatch):
    """Долг выше порога блокирует такси даже до наступления срока."""
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "ThrDrv", "ThrPax")
    row = _debt_row(d["id"])
    # Ставим порог ниже суммы долга — блок должен сработать без просрочки.
    monkeypatch.setattr(settings, "debt_block_threshold_kop", row.amount_kop - 1)
    body = client.get("/driver/debt", headers=d["auth"]).json()
    assert body["blocked"] is True and body["overdue"] is False
    assert body["block_reason"] == "over_threshold"
    assert _heartbeat(client, d).status_code == 403


# ============================ ПОПУТКА не блокируется (важный тест) ============================
def test_poputka_not_blocked_by_debt(client, user_factory, fake_redis):
    """Просроченный долг блокирует ТАКСИ, но НЕ мешает попутке (создать плановую поездку)."""
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "PopDrv", "PopPax")
    _make_overdue(d["id"])
    # Такси заблокировано:
    assert _heartbeat(client, d).status_code == 403
    # А попутку (плановую поездку) публикуем спокойно:
    ride_body = {"from_city": "Уфа", "to_city": "Сибай",
                 "depart_at": (utcnow() + timedelta(days=1)).isoformat(),
                 "seats_total": 3, "price": 500}
    r = client.post("/rides", headers=d["auth"], json=ride_body)
    assert r.status_code == 200, r.text
    assert r.json()["from_city"] == "Уфа"


# ============================ Цикл оплаты ============================
def test_declare_pay_confirm_unblocks(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "PayDrv", "PayPax")
    _make_overdue(d["id"])
    assert _heartbeat(client, d).status_code == 403          # заблокирован
    # Водитель жмёт «Я оплатил» → pending → блок снят (на доверии), долг ждёт админа.
    paid = client.post("/driver/debt/paid", headers=d["auth"]).json()
    assert paid["pending_kop"] > 0
    summ = client.get("/driver/debt", headers=d["auth"]).json()
    assert summ["blocked"] is False and summ["pending_kop"] > 0 and summ["unpaid_kop"] == 0
    assert _heartbeat(client, d).status_code == 200          # уже может возить
    # Админ подтверждает → долг paid.
    admin = user_factory("PayAdmin", role=UserRole.admin)
    debts = client.get("/admin/debts", headers=admin["auth"]).json()
    mine = [g for g in debts if g["driver_id"] == d["id"]]
    assert len(mine) == 1 and mine[0]["amount_kop"] > 0
    debt_id = mine[0]["debt_id"]
    conf = client.post(f"/admin/debts/{debt_id}/confirm", headers=admin["auth"]).json()
    assert conf["status"] == "paid" and conf["paid_kop"] > 0
    after = client.get("/driver/debt", headers=d["auth"]).json()
    assert after["unpaid_kop"] == 0 and after["pending_kop"] == 0 and after["blocked"] is False


def test_reject_returns_to_unpaid_and_blocks_again(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "RejDrv", "RejPax")
    _make_overdue(d["id"])
    client.post("/driver/debt/paid", headers=d["auth"])
    admin = user_factory("RejAdmin", role=UserRole.admin)
    debt_id = [g for g in client.get("/admin/debts", headers=admin["auth"]).json()
               if g["driver_id"] == d["id"]][0]["debt_id"]
    rej = client.post(f"/admin/debts/{debt_id}/reject", headers=admin["auth"]).json()
    assert rej["status"] == "unpaid"
    summ = client.get("/driver/debt", headers=d["auth"]).json()
    assert summ["unpaid_kop"] > 0 and summ["pending_kop"] == 0
    assert summ["blocked"] is True                            # долг снова просрочен → снова блок
    assert _heartbeat(client, d).status_code == 403


# ============================ Анти-IDOR / права ============================
def test_debt_is_per_token_no_idor(client, user_factory, fake_redis):
    """Чужой долг не виден: другой водитель по своему токену видит нулевой долг."""
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "IdorDrv", "IdorPax")
    other = _driver_online(client, user_factory, "IdorOther")
    body = client.get("/driver/debt", headers=other["auth"]).json()
    assert body["unpaid_kop"] == 0 and body["blocked"] is False


def test_admin_debts_requires_admin(client, user_factory, fake_redis):
    d, pax, done = _order_to_done(client, user_factory, fake_redis, "AdmDrv", "AdmPax")
    client.post("/driver/debt/paid", headers=d["auth"])
    # Обычный водитель не видит админ-очередь и не подтверждает чужой долг.
    assert client.get("/admin/debts", headers=d["auth"]).status_code == 403
    debt_id = _debt_row(d["id"]).id
    assert client.post(f"/admin/debts/{debt_id}/confirm", headers=pax["auth"]).status_code == 403
    assert client.post(f"/admin/debts/{debt_id}/reject", headers=pax["auth"]).status_code == 403


def test_admin_confirm_unknown_debt_404(client, user_factory):
    admin = user_factory("Admin404", role=UserRole.admin)
    assert client.post("/admin/debts/99999999/confirm", headers=admin["auth"]).status_code == 404


# ============================ Границы ============================
def test_zero_price_no_debt(client, user_factory, fake_redis):
    """Заказ с нулевой ценой (грошовый) не создаёт долг (комиссия 0)."""
    d = _driver_online(client, user_factory, "ZeroDrv")
    assert _heartbeat(client, d).status_code == 200
    pax = user_factory("ZeroPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"])
    # Обнуляем цену до done → комиссия = 0 → долг не заводим.
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.price_estimate = 0
        o.price_final = 0
        s.add(o)
        s.commit()
    client.post(f"/instant/orders/{oid}/done", headers=d["auth"])
    assert _debt_row(d["id"]) is None


def test_declare_paid_with_no_debt_is_noop(client, user_factory):
    d = _driver_online(client, user_factory, "NoopDrv")
    r = client.post("/driver/debt/paid", headers=d["auth"]).json()
    assert r["ok"] is True and r["pending_kop"] == 0
