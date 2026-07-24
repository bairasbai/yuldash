# -*- coding: utf-8 -*-
"""Безопасность выплат (аудит release-2026-07): V7 (идемпотентность) + V8 (лок vs банк).

V7 — пустой ключ идемпотентности обходил защиту от двойной выплаты (проверка «уже проведён»
     срабатывала только при непустом ключе) → повтор запроса провёл бы вторую РЕАЛЬНУЮ выплату.
     Теперь пустой ключ → отказ.
V8 — request_payout держал row-lock строки водителя весь ~30-сек HTTP к ЮKassa → под нагрузкой
     вычерпывался пул соединений БД. Теперь списание резервируется под КОРОТКИМ локом и коммитится
     ДО вызова банка; сам вызов банка идёт уже без лока. Явный отказ банка компенсируется append-only
     записью (+сумма, kind=adj); неоднозначный сбой (таймаут) резерв не трогает (разбирает сверка).

Выплаты латентны (payouts_enabled=False по умолчанию) — фиксы «до флипа выплат».
DB тестов общая на сессию → проверяем на свежих (уникальных) водителях.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app import ledger
from app.models import LedgerEntry, LedgerKind, UserRole

_TEST_PAN = "2202200112349876"


def _bal(driver_id: int) -> int:
    with Session(engine) as s:
        return ledger.driver_balance(s, driver_id)


def _seed_balance(driver_id: int, amount_kop: int) -> None:
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=driver_id, kind=LedgerKind.earn, amount_kop=amount_kop, note="seed"))
        s.commit()


def _save_card(client, drv):
    return client.post("/wallet/payout/requisite", headers=drv["auth"], json={"card_number": _TEST_PAN})


@pytest.fixture
def payouts_on():
    old = settings.payouts_enabled
    settings.payouts_enabled = True
    try:
        yield
    finally:
        settings.payouts_enabled = old


def _payout_rows(driver_id: int, ext_id: str):
    with Session(engine) as s:
        return s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == driver_id, LedgerEntry.ext_id == ext_id)).all()


# ------------------------------- V7 -------------------------------

def test_payout_empty_idempotency_key_rejected(client, user_factory, payouts_on):
    """V7: пустой ключ → отказ (иначе повтор = вторая выплата). Баланс цел, ничего не зарезервировано."""
    drv = user_factory("PoNoKeyDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)
    r = client.post("/wallet/payout", headers=drv["auth"], json={"amount_kop": 30000})  # БЕЗ idempotency_key
    assert r.status_code == 400, r.text
    assert _bal(drv["id"]) == 50000
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == drv["id"], LedgerEntry.kind == LedgerKind.payout)).all()
        assert rows == []


# ------------------------------- V8 -------------------------------

def test_payout_provider_decline_reverses_reserve(client, user_factory, payouts_on, monkeypatch):
    """Банк ЯВНО отклонил (не succeeded/pending) → резерв возвращается: payout(−) + adj(+) = 0, баланс цел."""
    drv = user_factory("PoDeclDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "x", "status": "canceled", "mock": True})
    r = client.post("/wallet/payout", headers=drv["auth"],
                    json={"amount_kop": 30000, "idempotency_key": "k-decl"})
    assert r.status_code == 400, r.text                 # провайдер отклонил
    assert _bal(drv["id"]) == 50000                     # резерв возвращён — баланс восстановлен
    kinds = [e.kind for e in _payout_rows(drv["id"], f"payout:{drv['id']}:k-decl")]
    assert LedgerKind.payout in kinds and LedgerKind.adj in kinds   # списание + компенсация


def test_payout_provider_exception_keeps_reserve(client, user_factory, payouts_on, monkeypatch):
    """Неоднозначный сбой банка (исключение/таймаут): деньги могли уйти → резерв НЕ откатываем
    (списание остаётся, компенсации нет), баланс уменьшен — дальше разбирает сверка. Здесь же доказываем,
    что резерв закоммичен ДО вызова банка (V8): запись payout существует, хотя банк упал."""
    drv = user_factory("PoExcDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)

    def _boom(*a, **k):
        raise RuntimeError("provider timeout")
    monkeypatch.setattr("app.payments.create_payout", _boom)
    r = client.post("/wallet/payout", headers=drv["auth"],
                    json={"amount_kop": 30000, "idempotency_key": "k-exc"})
    assert r.status_code == 400, r.text
    assert _bal(drv["id"]) == 20000                     # резерв остался (50000 − 30000): контроль над суммой не потерян
    kinds = [e.kind for e in _payout_rows(drv["id"], f"payout:{drv['id']}:k-exc")]
    assert LedgerKind.payout in kinds and LedgerKind.adj not in kinds   # списание есть, компенсации нет


def test_payout_success_still_debits_once(client, user_factory, payouts_on):
    """Контроль: успешный вывод (mock succeeded) по-прежнему пишет один payout(−сумма) и уменьшает баланс."""
    drv = user_factory("PoOkSafeDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)
    r = client.post("/wallet/payout", headers=drv["auth"],
                    json={"amount_kop": 30000, "idempotency_key": "k-ok-safe"})
    assert r.status_code == 200 and r.json()["status"] == "ok"
    assert _bal(drv["id"]) == 20000
    rows = _payout_rows(drv["id"], f"payout:{drv['id']}:k-ok-safe")
    assert len(rows) == 1 and rows[0].kind == LedgerKind.payout and rows[0].amount_kop == -30000


def test_payout_matches_legacy_raw_ext_id(client, user_factory, payouts_on):
    """Обратная совместимость: выплата со СТАРЫМ (сырым) ext_id (до неймспейс-деплоя) находится
    ретраем с тем же ключом → второй раз НЕ списываем (нет двойного резерва через момент деплоя)."""
    drv = user_factory("PoLegacyDrv", role=UserRole.driver)
    _seed_balance(drv["id"], 50000)
    _save_card(client, drv)
    with Session(engine) as s:   # эмулируем «до деплоя»: payout со сырым ext_id, как писал старый код
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.payout, amount_kop=-30000,
                          ext_id="legacy-key", note="pre-deploy"))
        s.commit()
    assert _bal(drv["id"]) == 20000
    r = client.post("/wallet/payout", headers=drv["auth"],
                    json={"amount_kop": 30000, "idempotency_key": "legacy-key"})
    assert r.status_code == 200 and r.json()["status"] == "already", r.text
    assert _bal(drv["id"]) == 20000    # НЕ списано второй раз
