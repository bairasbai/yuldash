# -*- coding: utf-8 -*-
"""PAYMENTS_PROVIDER: пусто или опечатка не превращают оплату в подарок (13.10.2026).

Дыра. Допустимых значений три: `mock` (на проде оплата выключена — кнопки отвечают
«Оплата скоро будет доступна»), `sbp_manual` (перевод по СБП, подтверждает админ),
`yookassa` (карта). Любое другое значение сервер молча принимал. Адаптер платежей отвечал
«оплачено» на всё, что не «yookassa с ключами», а эндпоинты отказывали только при точном
`mock` на проде. Итог: `PAYMENTS_PROVIDER=` (пусто) или `sbp-manual` (дефис вместо
подчёркивания) на проде — и поднятие поездки, донат, оплата поездки картой включались без
единого рубля.

Закрыто в два слоя:
1. Сервер не стартует с неизвестным значением (проверка в настройках, `config.py`);
   регистр и пробелы прощаем — «SBP_Manual » это всё ещё `sbp_manual`.
2. Если проверку всё-таки обойти (подмена настроек, как в этих тестах), неизвестный провайдер
   нигде не даёт «оплачено»: кнопки отвечают «скоро», а адаптер отказывает, а не выдумывает успех.
   В проде фиктивного «оплачено» нет вообще — ни при каком провайдере.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import fakeredis
import pytest
from pydantic import ValidationError
from sqlmodel import Session, select

from app import instant_service as isv
from app import payments
from app.config import Settings, settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, ParcelDelivery, Payment, Ride, UserRole,
)

from test_courier_c2 import _accept, _make_courier, _order
from test_debt import _order_to_done
from test_flows import _publish
from test_release_blockers import _make_done_order

BACKEND = Path(__file__).resolve().parents[1]

# Как это выглядело бы в .env: пусто, одни пробелы, дефис вместо подчёркивания, опечатка,
# чужой провайдер, «выключено» словами (такого значения тоже нет — выключено = mock).
BAD_VALUES = ["", "   ", "sbp-manual", "yookasa", "stripe", "off"]

# (значение, окружение): неизвестный провайдер и в разработке, и на проде.
BAD_CASES = [("", "dev"), ("sbp-manual", "prod")]


def _refuses(call) -> None:
    """Вызов обязан ОТКАЗАТЬ, а не вернуть фиктивный «оплачено»."""
    try:
        result = call()
    except payments.PaymentsUnavailable:
        return
    pytest.fail(f"вместо отказа вернулось {result!r}")


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


# ================================ 1. Сервер не стартует ================================

@pytest.mark.parametrize("bad", BAD_VALUES)
def test_unknown_provider_is_rejected_by_settings(bad):
    """Пусто/опечатка → настройки не создаются, ошибка называет переменную и допустимые значения."""
    with pytest.raises(ValidationError) as e:
        Settings(_env_file=None, payments_provider=bad)
    text = str(e.value)
    assert "PAYMENTS_PROVIDER" in text
    assert "sbp_manual" in text and "yookassa" in text and "mock" in text


def test_empty_provider_from_environment_is_rejected(monkeypatch):
    """Ровно как на сервере: `PAYMENTS_PROVIDER=` в окружении, а не аргумент конструктора."""
    monkeypatch.setenv("PAYMENTS_PROVIDER", "")
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_server_does_not_start_with_typo(tmp_path):
    """Сервер с опечаткой в провайдере не поднимается вовсе (импорт приложения падает)
    и говорит в логе, что не так. Раньше он стартовал и раздавал «оплачено» бесплатно."""
    env = {
        **os.environ,
        "PAYMENTS_PROVIDER": "sbp-manual",
        "DATABASE_URL": f"sqlite:///{(tmp_path / 'boot.db').as_posix()}",
        "PYTHONIOENCODING": "utf-8",
    }
    proc = subprocess.run(
        [sys.executable, "-c", "import app.main"],
        cwd=BACKEND, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=120,
    )
    assert proc.returncode != 0, "сервер стартовал с PAYMENTS_PROVIDER=sbp-manual"
    assert "PAYMENTS_PROVIDER" in proc.stderr, proc.stderr[-2000:]


@pytest.mark.parametrize("raw, expected", [
    ("mock", "mock"), ("sbp_manual", "sbp_manual"), ("yookassa", "yookassa"),
    (" SBP_Manual ", "sbp_manual"), ("YooKassa\n", "yookassa"), ("MOCK", "mock"),
])
def test_three_known_values_are_accepted_and_normalized(raw, expected):
    """Три допустимых значения работают как раньше; регистр и пробелы не ломают прод."""
    assert Settings(_env_file=None, payments_provider=raw).payments_provider == expected


def test_default_is_mock(monkeypatch):
    """Переменная не задана → mock (на проде это «оплата скоро», не бесплатно)."""
    monkeypatch.delenv("PAYMENTS_PROVIDER", raising=False)
    assert Settings(_env_file=None).payments_provider == "mock"


# ============================ 2. Адаптер не выдумывает успех ============================

@pytest.mark.parametrize("env", ["dev", "prod"])
@pytest.mark.parametrize("bad", BAD_VALUES)
def test_adapter_refuses_unknown_provider(monkeypatch, bad, env):
    """Проверку старта обошли (подмена настроек) → адаптер отказывает, а не говорит «succeeded»."""
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    _refuses(lambda: payments.create_payment(5000, "Юлдаш · тест", {"payment_id": "1"}))
    _refuses(lambda: payments.fetch_payment("mock_abc"))


@pytest.mark.parametrize("provider", ["mock", "sbp_manual", "yookassa"])
def test_adapter_never_fakes_success_in_prod(monkeypatch, provider):
    """В проде фиктивного «оплачено» нет ни при каком провайдере: mock там = оплата выключена,
    sbp_manual подтверждает человек, yookassa без ключей сервер и так не пустит на старте."""
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "payments_provider", provider)
    monkeypatch.setattr(settings, "yookassa_shop_id", "")
    monkeypatch.setattr(settings, "yookassa_secret_key", "")
    _refuses(lambda: payments.create_payment(5000, "Юлдаш · тест", {"payment_id": "1"}))
    _refuses(lambda: payments.fetch_payment("mock_abc"))


@pytest.mark.parametrize("provider", ["mock", "sbp_manual", "yookassa"])
def test_adapter_dev_mock_works_as_before(monkeypatch, provider):
    """Контроль: в разработке известный провайдер без ключей — по-прежнему мгновенный «оплачено»
    (на этом стоят сотни тестов и ручная проверка экранов)."""
    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "payments_provider", provider)
    monkeypatch.setattr(settings, "yookassa_shop_id", "")
    monkeypatch.setattr(settings, "yookassa_secret_key", "")
    res = payments.create_payment(5000, "Юлдаш · тест", {"payment_id": "1"})
    assert res["status"] == "succeeded" and res["mock"] is True
    assert payments.fetch_payment("mock_abc")["status"] == "succeeded"


# ============================ 3. Кнопки оплаты отвечают «скоро» ============================

def _assert_soon(r) -> None:
    assert r.status_code == 503, r.text
    assert "скоро" in r.text.lower() or "тиҙҙән" in r.text or "оҙаҡламай" in r.text, r.text


@pytest.mark.parametrize("bad, env", BAD_CASES)
def test_boost_is_not_free(client, user_factory, monkeypatch, bad, env):
    driver = user_factory("GuardBoostDrv", role=UserRole.driver)
    ride = _publish(client, driver, frm=f"GuardBoost{env}", to="Ufa")
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    r = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "urgent"})
    _assert_soon(r)
    with Session(engine) as s:
        assert s.get(Ride, ride["id"]).boosted_until is None          # поездка не поднята
        assert not s.exec(select(Payment).where(Payment.ride_id == ride["id"])).all()


@pytest.mark.parametrize("bad, env", BAD_CASES)
@pytest.mark.parametrize("path, body", [
    ("/donate", {"amount": 100}),
    ("/support/donate", {"amount_kop": 5000}),
])
def test_donations_are_not_marked_paid(client, user_factory, monkeypatch, bad, env, path, body):
    user = user_factory("GuardDonor")
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    _assert_soon(client.post(path, headers=user["auth"], json=body))
    with Session(engine) as s:
        assert not s.exec(select(Payment).where(Payment.user_id == user["id"])).all()


@pytest.mark.parametrize("bad, env", BAD_CASES)
def test_ride_card_payment_is_not_free(client, user_factory, monkeypatch, bad, env):
    """Оплата поездки картой: водителю не начисляются деньги, которых не было."""
    pax = user_factory("GuardPayPax")
    drv = user_factory("GuardPayDrv", role=UserRole.driver)
    oid = _make_done_order(pax["id"], drv["id"])
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    _assert_soon(client.post(f"/instant/orders/{oid}/pay", headers=pax["auth"], json={"method": "card"}))
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).paid is False
        assert not s.exec(select(Payment).where(Payment.order_id == oid)).all()


@pytest.mark.parametrize("bad, env", BAD_CASES)
def test_taxi_debt_is_not_cleared(client, user_factory, fake_redis, monkeypatch, bad, env):
    """Долг по комиссии такси не уходит ни в «оплачено», ни в «ждёт подтверждения»."""
    monkeypatch.setattr(settings, "owner_sbp_phone", "+79990001122")
    d, _pax, _done = _order_to_done(client, user_factory, fake_redis, f"GuardDebtDrv{env}", f"GuardDebtPax{env}")
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    _assert_soon(client.post("/driver/debt/paid", headers=d["auth"]))
    with Session(engine) as s:
        rows = s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == d["id"])).all()
        assert rows and all(row.status == DebtStatus.unpaid for row in rows)


@pytest.mark.parametrize("bad, env", BAD_CASES)
def test_courier_commission_is_not_cleared(client, user_factory, monkeypatch, bad, env):
    monkeypatch.setattr(settings, "courier_enabled", True)
    courier = _make_courier(client, user_factory, name="ГвардКурьер")
    sender = user_factory(name="ГвардОтпр")
    ro = _order(client, sender)
    pid, code = ro.json()["id"], ro.json()["confirm_code"]
    assert _accept(client, courier, pid).status_code == 200
    client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"], json={"actual_kop": 100000})
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "delivered", "code": code}).status_code == 200
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", bad)
    _assert_soon(client.post("/courier/pay-commission", headers=courier["auth"]))
    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).commission_paid is False
        assert not s.exec(select(Payment).where(
            Payment.user_id == courier["id"], Payment.purpose == "courier_commission")).all()


@pytest.mark.parametrize("bad", ["", "sbp-manual"])
def test_health_says_off_for_unknown_provider(client, monkeypatch, bad):
    """Приложение не показывает кнопку «Оплатить онлайн», если провайдер непонятный."""
    monkeypatch.setattr(settings, "payments_provider", bad)
    assert client.get("/health").json()["payments"] == "off"


def test_known_providers_still_work_end_to_end(client, user_factory, monkeypatch):
    """Контроль на живых эндпоинтах: sbp_manual — счёт ждёт админа, dev-mock — оплачено сразу."""
    driver = user_factory("GuardOkDrv", role=UserRole.driver)
    ride = _publish(client, driver, frm="GuardOkCity", to="Ufa")
    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    monkeypatch.setattr(settings, "sbp_phone", "+79990000009")
    r = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "quick"})
    assert r.status_code == 200 and r.json()["status"] == "pending", r.text
    monkeypatch.setattr(settings, "payments_provider", "mock")
    r = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "day"})
    assert r.status_code == 200 and r.json()["status"] == "succeeded", r.text
    # Прод на mock — по-прежнему «скоро», а не бесплатно.
    monkeypatch.setattr(settings, "env", "prod")
    _assert_soon(client.post("/boost/create", headers=driver["auth"],
                             json={"ride_id": ride["id"], "tier": "urgent"}))


@pytest.mark.parametrize("provider, env, closed", [
    ("mock", "dev", False), ("sbp_manual", "dev", False), ("yookassa", "dev", False),
    ("mock", "prod", True), ("sbp_manual", "prod", False), ("yookassa", "prod", False),
    ("", "dev", True), ("", "prod", True), ("sbp-manual", "dev", True), ("sbp-manual", "prod", True),
])
def test_payments_closed_table(monkeypatch, provider, env, closed):
    """Когда кнопки оплаты отвечают «скоро»: прод на mock и любое неизвестное значение — и только."""
    monkeypatch.setattr(settings, "env", env)
    monkeypatch.setattr(settings, "payments_provider", provider)
    assert payments.payments_closed() is closed


@pytest.mark.parametrize("path, body", [
    ("/boost/create", None),
    ("/donate", {"amount": 100}),
    ("/support/donate", {"amount_kop": 5000}),
])
def test_prod_sbp_manual_still_waits_for_admin(client, user_factory, monkeypatch, path, body):
    """Главная прод-конфигурация (sbp_manual) работает как раньше: счёт «ждёт админа»,
    реквизиты СБП в ответе, никакого «скоро» и никакого «оплачено»."""
    user = user_factory("GuardSbpProd", role=UserRole.driver)
    if body is None:
        ride = _publish(client, user, frm="GuardSbpProdCity", to="Ufa")
        body = {"ride_id": ride["id"], "tier": "quick"}
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    monkeypatch.setattr(settings, "sbp_phone", "+79990000009")
    r = client.post(path, headers=user["auth"], json=body)
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "pending" and r.json()["method"] == "sbp_manual", r.text
    assert r.json()["payee"]["phone"] == "+79990000009"
    with Session(engine) as s:
        rows = s.exec(select(Payment).where(Payment.user_id == user["id"])).all()
        assert [p.status for p in rows] == ["pending"]


def test_preflight_names_the_bad_value(tmp_path):
    """Предполётная проверка перед выкаткой говорит «ВЫКАТЫВАТЬ НЕЛЬЗЯ» и называет переменную,
    а не падает трассировкой (настройки с опечаткой теперь не создаются вовсе)."""
    env = {**os.environ, "PAYMENTS_PROVIDER": "sbp-manual", "ENV": "prod", "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run(
        [sys.executable, str(BACKEND / "ops" / "preflight.py"), "--env-file", str(tmp_path / "missing.env")],
        cwd=tmp_path, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=120,
    )
    assert proc.returncode == 1, proc.stdout + proc.stderr
    assert "ВЫКАТЫВАТЬ НЕЛЬЗЯ" in proc.stdout and "PAYMENTS_PROVIDER" in proc.stdout, proc.stdout
    assert "Traceback" not in proc.stderr, proc.stderr[-2000:]
