"""Повторное нажатие «Оплатить» не создаёт второй платёж.

Ключ идемпотентности ЮKassa раньше генерировался заново на каждый вызов
(`uuid.uuid4()`), то есть защита от повтора была выключена: оборванный по сети запрос,
который на самом деле дошёл, при повторе списывал деньги второй раз. И даже с рабочим
ключом повтор с новой строкой платежа создавал вторую оплату. Подписка бизнеса так
не делала — переносим её правило на поднятие, донат и поддержку
(аудит монетизации 2026-08-31, §3.3).
"""
import httpx
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Payment, UserRole

from test_flows import _publish


class _FakeResp:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


def _sbp_on():
    old = settings.payments_provider
    settings.payments_provider = "sbp_manual"
    return old


def test_two_taps_on_boost_make_one_payment(client, user_factory):
    driver = user_factory("TwiceBoost", role=UserRole.driver)
    ride = _publish(client, driver, frm="Баймак", to="Сибай")
    old = _sbp_on()
    try:
        first = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "day"})
        second = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "day"})
        assert first.status_code == second.status_code == 200
        assert first.json()["payment_id"] == second.json()["payment_id"]
        assert second.json()["payee"]["phone"] == first.json()["payee"]["phone"]

        with Session(engine) as session:
            rows = session.exec(
                select(Payment).where(Payment.user_id == driver["id"], Payment.purpose == "boost")
            ).all()
            assert len(rows) == 1
    finally:
        settings.payments_provider = old


def test_two_taps_on_support_make_one_payment_but_another_sum_is_a_new_one(client, user_factory):
    """Та же сумма — тот же платёж. Другая сумма — это новая покупка, её не подменяем."""
    user = user_factory("TwiceSupport")
    old = _sbp_on()
    try:
        first = client.post("/support/donate", headers=user["auth"], json={"amount_kop": 5000})
        again = client.post("/support/donate", headers=user["auth"], json={"amount_kop": 5000})
        bigger = client.post("/support/donate", headers=user["auth"], json={"amount_kop": 20000})
        assert first.json()["payment_id"] == again.json()["payment_id"]
        assert bigger.json()["payment_id"] != first.json()["payment_id"]

        with Session(engine) as session:
            rows = session.exec(
                select(Payment).where(Payment.user_id == user["id"], Payment.purpose == "support")
            ).all()
            assert len(rows) == 2
    finally:
        settings.payments_provider = old


def test_yookassa_gets_a_key_tied_to_the_payment_row(client, user_factory, monkeypatch):
    """Ключ повтора — `pay-<id строки платежа>`, а не случайный: ретрай не создаст вторую оплату."""
    driver = user_factory("KeyBoostDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="Баймак", to="Уфа")
    old = (settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key)
    settings.payments_provider = "yookassa"
    settings.yookassa_shop_id = "shop"
    settings.yookassa_secret_key = "secret"
    seen: list[str] = []
    try:
        def _post(*_a, **kw):
            seen.append(kw["headers"]["Idempotence-Key"])
            return _FakeResp({"id": "yk_key_1", "status": "pending",
                              "confirmation": {"confirmation_url": "https://pay.example/9"}})

        monkeypatch.setattr(httpx, "post", _post)
        created = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "quick"})
        payment_id = created.json()["payment_id"]
        assert seen == [f"yuldash-payment-{payment_id}"]

        # Повтор того же нажатия: в ЮKassa второй раз не идём вовсе.
        monkeypatch.setattr("app.routers.payments.fetch_payment",
                            lambda pid: {"status": "pending", "metadata": {},
                                         "confirmation_url": "https://pay.example/9"})
        again = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride["id"], "tier": "quick"})
        assert again.json()["payment_id"] == payment_id
        assert again.json()["confirmation_url"] == "https://pay.example/9"
        assert len(seen) == 1
    finally:
        settings.payments_provider, settings.yookassa_shop_id, settings.yookassa_secret_key = old
