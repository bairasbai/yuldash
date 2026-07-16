"""Деньги v2 (Фаза 3): выплаты водителям (Модель Б, готовность, ВЫКЛ по умолчанию).

Деньги критичны → покрываем плотно:
  • баланс = SUM(ledger), включая payout (−);
  • выключено по умолчанию → эндпоинт «скоро» (503), НЕ падает (не 500);
  • вывод пишет ledger payout на −сумму и уменьшает баланс;
  • нельзя вывести больше баланса (→ ошибка, ничего не списано);
  • границы: ниже минимума → ошибка;
  • идемпотентность: повтор с тем же ключом НЕ списывает баланс дважды;
  • реквизиты: полный номер карты НЕ хранится (только последние 4);
  • нужны реквизиты перед выводом; реестр выплат — только админ.

DB тестов общая на сессию → балансы проверяем на свежих (уникальных) водителях.
"""
import pytest
from sqlmodel import Session, select

from app import ledger
from app.config import settings
from app.db import engine
from app.models import DriverProfile, LedgerEntry, LedgerKind, UserRole

_TEST_PAN = "2202200112349876"   # тестовый номер карты (МИР), last4 = 9876


def _bal(driver_id: int) -> int:
    with Session(engine) as s:
        return ledger.driver_balance(s, driver_id)


def _seed_balance(driver_id: int, amount_kop: int) -> None:
    """Насыпать водителю баланс записью earn (минуем оплату — тестируем вывод)."""
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=driver_id, kind=LedgerKind.earn, amount_kop=amount_kop, note="seed"))
        s.commit()


def _save_card(client, drv, pan: str = _TEST_PAN):
    return client.post("/wallet/payout/requisite", headers=drv["auth"], json={"card_number": pan})


@pytest.fixture
def payouts_on():
    """Включить выплаты на время теста (в dev достаточно флага — create_payout идёт mock)."""
    old = settings.payouts_enabled
    settings.payouts_enabled = True
    try:
        yield
    finally:
        settings.payouts_enabled = old


# ============================ Баланс = SUM (включая payout) ============================
def test_balance_is_sum_including_payout(client, user_factory):
    drv = user_factory("PoSumDrv", role=UserRole.driver)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.earn, amount_kop=30000, note="e"))
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.payout, amount_kop=-10000, note="p"))
        s.commit()
    assert _bal(drv["id"]) == 20000


# ============================ Выключено по умолчанию → «скоро», не 500 ============================
def test_payout_disabled_by_default(client, user_factory):
    """Дефолт payouts_enabled=False → 503 «скоро», НЕ падение (500). Модель А не трогаем."""
    assert settings.payouts_enabled is False
    drv = user_factory("PoOffDrv", role=UserRole.driver)
    r = client.post("/wallet/payout", headers=drv["auth"], json={"amount_kop": 10000})
    assert r.status_code == 503, r.text
    assert "скоро" in r.json()["detail"].lower()


def test_status_disabled_by_default(client, user_factory):
    drv = user_factory("PoStatusDrv", role=UserRole.driver)
    st = client.get("/wallet/payout/status", headers=drv["auth"]).json()
    assert st["enabled"] is False
    assert st["min_kop"] == settings.payout_min_kop and st["max_kop"] == settings.payout_max_kop


# ============================ Реквизиты: полный номер НЕ хранится ============================
def test_requisite_stores_only_last4(client, user_factory):
    """Карта вводится в UI, но на сервере оседают ТОЛЬКО последние 4 — полного PAN нет нигде."""
    drv = user_factory("PoReqDrv", role=UserRole.driver)
    r = _save_card(client, drv)
    assert r.status_code == 200 and r.json()["card_last4"] == "9876"
    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        assert dp.payout_card_last4 == "9876"
        assert dp.payout_token == ""                       # PAN не осел в токене
        # ни одно строковое поле профиля не содержит полный номер карты
        blob = "|".join(str(v) for v in vars(dp).values() if isinstance(v, str))
        assert _TEST_PAN not in blob


# ============================ Вывод пишет ledger −сумма и уменьшает баланс ============================
def test_payout_writes_payout_entry_and_reduces_balance(client, user_factory, payouts_on):
    drv = user_factory("PoOkDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)          # 500 ₽
    _save_card(client, drv)
    r = client.post("/wallet/payout", headers=drv["auth"],
                    json={"amount_kop": 30000, "idempotency_key": "k-po-ok"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "ok" and r.json()["balance_kop"] == 20000
    assert _bal(drv["id"]) == 20000
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == drv["id"], LedgerEntry.kind == LedgerKind.payout)).all()
        assert len(rows) == 1 and rows[0].amount_kop == -30000
        assert rows[0].note.endswith("9876")               # только последние 4 в note, не PAN


# ============================ Нельзя больше баланса ============================
def test_payout_more_than_balance_rejected(client, user_factory, payouts_on):
    drv = user_factory("PoOverDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 20000)
    _save_card(client, drv)
    r = client.post("/wallet/payout", headers=drv["auth"], json={"amount_kop": 30000})
    assert r.status_code == 400, r.text
    assert _bal(drv["id"]) == 20000          # ничего не списано


def test_payout_below_min_rejected(client, user_factory, payouts_on):
    drv = user_factory("PoMinDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)
    r = client.post("/wallet/payout", headers=drv["auth"], json={"amount_kop": 500})  # 5 ₽ < 100 ₽
    assert r.status_code == 400
    assert _bal(drv["id"]) == 50000


# ============================ Идемпотентность ============================
def test_payout_idempotent_same_key_no_double(client, user_factory, payouts_on):
    drv = user_factory("PoIdemDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 100000)
    _save_card(client, drv)
    body = {"amount_kop": 40000, "idempotency_key": "idem-xyz"}
    r1 = client.post("/wallet/payout", headers=drv["auth"], json=body)
    r2 = client.post("/wallet/payout", headers=drv["auth"], json=body)
    assert r1.status_code == 200 and r2.status_code == 200
    assert r2.json()["status"] == "already"
    assert _bal(drv["id"]) == 60000          # списано ровно один раз
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == drv["id"], LedgerEntry.kind == LedgerKind.payout)).all()
        assert len(rows) == 1


# ============================ Нужны реквизиты / права ============================
def test_payout_requires_requisite(client, user_factory, payouts_on):
    drv = user_factory("PoNoCardDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    r = client.post("/wallet/payout", headers=drv["auth"], json={"amount_kop": 20000})
    assert r.status_code == 400
    assert "карт" in r.json()["detail"].lower()


def test_admin_payouts_admin_only(client, user_factory):
    pax = user_factory("PoNotAdmin")
    assert client.get("/admin/payouts", headers=pax["auth"]).status_code == 403


def test_admin_payouts_lists_payout(client, user_factory, payouts_on):
    admin = user_factory("PoAdmin", role=UserRole.admin)
    drv = user_factory("PoListDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 40000)
    _save_card(client, drv)
    client.post("/wallet/payout", headers=drv["auth"],
                json={"amount_kop": 30000, "idempotency_key": "k-list"})
    rows = client.get("/admin/payouts", headers=admin["auth"]).json()
    mine = [r for r in rows if r["driver_id"] == drv["id"]]
    assert len(mine) == 1 and mine[0]["amount_kop"] == 30000
