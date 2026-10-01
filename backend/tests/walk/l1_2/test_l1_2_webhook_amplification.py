"""leaf-1.2 — backend/app/routers/payments.py: вебхук не ходит наружу за чужими id.

Правило записано прямо в коде вебхука: «Наружу ходим только за id, что сами выпустили» —
сначала ищем СВОЙ платёж по `provider_id` (параметризованный запрос), и только если нашли
pending-строку, перепроверяем её статус у ЮKassa (`fetch_payment`, настоящий HTTP). Если
не искать это напрямую, то любой внешний запрос с произвольным `object.id` заставлял бы
сервер ходить к ЮKassa за каждым таким id — бесплатный способ погонять наш сервер чужими
руками (амплификация/DoS) и потратить наш лимит на внешний API.

Существующий тест вебхука (test_payments_edges.py) проверяет reakция на битый JSON и на
отказ fetch_payment для ЗНАКОМОГО id — но не сам факт «на НЕЗНАКОМЫЙ id мы вообще не
стучимся наружу». Этот тест закрывает именно этот случай.
"""
from app.config import settings


def test_unknown_provider_id_never_triggers_outbound_fetch(client, monkeypatch):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls = []

    def tracked_fetch(provider_id):
        calls.append(provider_id)
        return {"status": "succeeded", "metadata": {}, "confirmation_url": ""}

    monkeypatch.setattr("app.routers.payments.fetch_payment", tracked_fetch)

    response = client.post("/payments/yookassa/webhook",
                           json={"object": {"id": "qa-random-unknown-id-does-not-exist"}})

    assert response.status_code == 200 and response.json() == {"ok": True}
    assert calls == [], (
        "вебхук сходил к ЮKassa за id, который мы никогда не выпускали — "
        "это и есть амплификация/DoS через чужой провайдерский id"
    )


def test_missing_object_id_never_triggers_outbound_fetch(client, monkeypatch):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    calls = []
    monkeypatch.setattr("app.routers.payments.fetch_payment", lambda pid: calls.append(pid))

    response = client.post("/payments/yookassa/webhook", json={"object": {}})

    assert response.status_code == 200 and response.json() == {"ok": True}
    assert calls == []
