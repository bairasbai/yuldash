"""Кошелёк водителя не уходит в минус, сколько бы раз он ни нажал «вывести».

Волна 65 прошла пробой по деньгам водителя: баланс, выплаты, компенсации промокодов,
промокоды пассажира, купоны бизнеса, поздние отмены, классы машин. Дыр не нашлось — всё это
уже покрыто прежними волнами и тестами.

Непокрытым остался один инвариант: **два вывода подряд с РАЗНЫМИ ключами** на всю сумму.
Идемпотентность (повтор с тем же ключом) проверена в `test_payout_safety.py`, а тут случай
другой: человек нажал «вывести 1000 ₽», не дождался ответа, обновил экран и нажал ещё раз —
клиент честно сгенерировал новый ключ. Если резерв списания не работает, банк платит дважды,
а баланс уходит в минус.

Отдельно проверено, что резерв не «съедает» деньги при отказе по границам суммы.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app import ledger
from app.config import settings
from app.db import engine
from app.models import LedgerEntry, LedgerKind, UserRole


@pytest.fixture
def payouts_on(monkeypatch):
    monkeypatch.setattr(settings, "payout_min_kop", 10_000, raising=False)
    monkeypatch.setattr(settings, "payout_max_kop", 500_000, raising=False)
    yield


def _earn(session: Session, driver_id: int, kop: int, ext: str) -> None:
    session.add(LedgerEntry(driver_id=driver_id, kind=LedgerKind.earn, amount_kop=kop,
                            ext_id=ext, note="заработок"))
    session.commit()


def test_второй_вывод_на_ту_же_сумму_не_уводит_в_минус(client, user_factory, payouts_on):
    """Человек нажал «вывести» дважды, клиент прислал два РАЗНЫХ ключа."""
    drv = user_factory("WalletRace", role=UserRole.driver)
    with Session(engine) as s:
        _earn(s, drv["id"], 100_000, "wallet-race-earn")   # 1000 ₽

        first = ledger.request_payout(s, drv["id"], 100_000, payout_token="tok",
                                      card_last4="1234", idempotency_key="race-1")
        assert first.get("status") == "ok"

        with pytest.raises(ledger.PayoutError) as e:
            ledger.request_payout(s, drv["id"], 100_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="race-2")
        assert e.value.code == "insufficient"

        assert ledger.driver_balance(s, drv["id"]) == 0


def test_отказ_по_границе_суммы_денег_не_съедает(client, user_factory, payouts_on):
    """Резерв списывается только у настоящего вывода: отказ «слишком много» баланс не трогает."""
    drv = user_factory("WalletCap", role=UserRole.driver)
    with Session(engine) as s:
        _earn(s, drv["id"], 100_000, "wallet-cap-earn")
        before = ledger.driver_balance(s, drv["id"])

        with pytest.raises(ledger.PayoutError) as e:
            ledger.request_payout(s, drv["id"], settings.payout_max_kop + 1, payout_token="tok",
                                  card_last4="1234", idempotency_key="cap-1")
        assert e.value.code == "max"

        with pytest.raises(ledger.PayoutError) as e:
            ledger.request_payout(s, drv["id"], settings.payout_min_kop - 1, payout_token="tok",
                                  card_last4="1234", idempotency_key="cap-2")
        assert e.value.code == "min"

        assert ledger.driver_balance(s, drv["id"]) == before


def test_обычный_вывод_работает(client, user_factory, payouts_on):
    """Страховка от перестраховки: человек выводит часть денег и остаётся с остатком."""
    drv = user_factory("WalletOk", role=UserRole.driver)
    with Session(engine) as s:
        _earn(s, drv["id"], 100_000, "wallet-ok-earn")

        ledger.request_payout(s, drv["id"], 40_000, payout_token="tok",
                              card_last4="1234", idempotency_key="ok-1")
        assert ledger.driver_balance(s, drv["id"]) == 60_000
