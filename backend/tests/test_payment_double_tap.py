"""Двойной тап по «Поддержать» не должен делать два счёта.

У Boost эта защита стоит с волны 13 (аудит 2026-08-08): три тапа давали три счёта,
человек видел три QR на одну поездку, а админу приходило три «поступил платёж».
У доната и поддержки её не было — те же три тапа делали три счёта на одну сумму.

Плюс здесь проверяется ключ идемпотентности ЮKassa. Он существует ровно для того, чтобы
повтор ОДНОГО И ТОГО ЖЕ запроса не создал второй платёж. Раньше в него шёл свежий uuid
на каждый вызов, то есть защита была выключена: оборвалась сеть на ответе, клиент повторил —
у ЮKassa два платежа на один наш счёт. Теперь ключ привязан к нашей строке Payment.
"""

import pytest

from app.routers import payments as payments_router


@pytest.fixture
def sbp_mode(monkeypatch):
    """Режим «перевод по СБП вручную»: счёт висит неоплаченным до подтверждения админом.
    Именно в нём двойной тап и плодил счета."""
    monkeypatch.setattr(payments_router.settings, "payments_provider", "sbp_manual")


def test_donate_double_tap_reuses_the_same_invoice(client, user_factory, sbp_mode):
    """В mock-режиме счёт оплачивается сразу, поэтому переиспользовать нечего.
    Защита нужна там, где счёт висит неоплаченным: перевод по СБП и живая ЮKassa."""
    user = user_factory("DoubleTapDonor")

    first = client.post("/donate", headers=user["auth"], json={"amount": 300})
    second = client.post("/donate", headers=user["auth"], json={"amount": 300})

    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert first.json()["payment_id"] == second.json()["payment_id"]


def test_donate_other_amount_gets_its_own_invoice(client, user_factory, sbp_mode):
    """Другая сумма — осознанный выбор человека, а не промах пальцем."""
    user = user_factory("DonorTwoAmounts")

    first = client.post("/donate", headers=user["auth"], json={"amount": 300})
    second = client.post("/donate", headers=user["auth"], json={"amount": 500})

    assert first.json()["payment_id"] != second.json()["payment_id"]


def test_support_double_tap_reuses_the_same_invoice(client, user_factory, sbp_mode):
    user = user_factory("DoubleTapSupporter")

    first = client.post("/support/donate", headers=user["auth"], json={"amount_kop": 20000})
    second = client.post("/support/donate", headers=user["auth"], json={"amount_kop": 20000})

    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert first.json()["payment_id"] == second.json()["payment_id"]


def test_donations_of_different_people_do_not_merge(client, user_factory, sbp_mode):
    """Совпала сумма у двух разных людей — это два разных счёта, а не один."""
    one = user_factory("DonorOne")
    two = user_factory("DonorTwo")

    first = client.post("/donate", headers=one["auth"], json={"amount": 400})
    second = client.post("/donate", headers=two["auth"], json={"amount": 400})

    assert first.json()["payment_id"] != second.json()["payment_id"]


def test_yookassa_gets_a_key_tied_to_our_payment_row(client, user_factory, monkeypatch):
    """Ключ идемпотентности — от нашей строки платежа, а не случайный.

    Проверяем через подмену create_payment: важно не то, что там за строка, а что она
    одна и та же для одного счёта. Со случайным uuid повтор запроса создавал бы у ЮKassa
    второй платёж на тот же наш счёт.
    """
    seen = {}

    def fake_create_payment(amount_kop, description, metadata, customer_phone="", idempotence_key=""):
        seen["key"] = idempotence_key
        seen["payment_id"] = metadata.get("payment_id")
        return {"provider_id": "test-provider-id", "confirmation_url": "", "status": "pending", "mock": False}

    monkeypatch.setattr(payments_router, "create_payment", fake_create_payment)
    monkeypatch.setattr(payments_router.settings, "payments_provider", "yookassa")

    user = user_factory("IdempotencyDonor")
    created = client.post("/donate", headers=user["auth"], json={"amount": 250})
    assert created.status_code == 200, created.text

    assert seen["key"], "ключ не передан — защита ЮKassa от повтора выключена"
    assert seen["payment_id"] in seen["key"], "ключ должен быть привязан к нашему счёту"
