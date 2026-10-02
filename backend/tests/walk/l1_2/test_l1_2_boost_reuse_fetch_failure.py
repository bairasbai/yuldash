"""leaf-1.2 — F5 (независимое ревью Opus 5.5, 2026-10-02): boost_create не должен падать 500,
если перепроверка уже заведённого у провайдера счёта споткнулась о сеть.

В отличие от _sync_provider_status (кошелёк/долг/курьер), повторный тап «Поднять» на уже
заведённый у ЮKassa Boost-счёт звал fetch_payment без try/except: сетевая ошибка/таймаут
заваливала весь запрос в 500 вместо вежливого повтора через тот же Idempotence-Key.
"""
from app.config import settings
from app.models import RideStatus, UserRole
from app.routers import payments as payments_router


def test_boost_create_retry_survives_fetch_payment_network_error(client, user_factory, monkeypatch):
    driver = user_factory("L12BoostFetchDriver", role=UserRole.driver)
    from datetime import timedelta, timezone
    from app.timeutil import utcnow
    depart = (utcnow() + timedelta(minutes=30)).replace(tzinfo=timezone.utc).isoformat()
    ride = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": depart,
        "seats_total": 3, "price": 1000,
    })
    assert ride.status_code == 200, ride.text
    ride_id = ride.json()["id"]

    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "shop", raising=False)
    monkeypatch.setattr(settings, "yookassa_secret_key", "secret", raising=False)

    def fake_create_payment(amount_kop, description, metadata, customer_phone="", idempotence_key=""):
        return {"provider_id": "qa-boost-fetch-1", "confirmation_url": "https://pay.example/1",
                "status": "pending", "mock": False}

    monkeypatch.setattr(payments_router, "create_payment", fake_create_payment)
    first = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride_id, "tier": "quick"})
    assert first.status_code == 200 and first.json()["status"] == "pending", first.text

    def boom(provider_id):
        raise TimeoutError("ЮKassa не отвечает")

    monkeypatch.setattr(payments_router, "fetch_payment", boom)
    second = client.post("/boost/create", headers=driver["auth"], json={"ride_id": ride_id, "tier": "quick"})

    assert second.status_code < 500, (
        f"сетевая ошибка перепроверки уронила запрос целиком: {second.status_code} {second.text}"
    )
