"""leaf-1.2 — F2 (повторное независимое ревью Opus 5.5, 2026-10-02): тело вебхука ЮKassa не
должно исполняться на потоке event loop.

`yookassa_webhook` объявлен `async def`, но раньше ВСЁ тело после `await request.json()`
(поиск своей строки, `_sync_provider_status`, внутри него — синхронный `httpx.get` до 15 с)
выполнялось прямо на потоке event loop. На `--workers 2` (backend/Dockerfile) это держит
весь воркер: два медленных ответа ЮKassa подряд — и воркер не обслуживает никого. С блокировкой
заказа (`with_for_update` в `pay_instant_order`/`pay_booking`, добавленной в этом же листе) риск
стал ещё выше — другой запрос на том же воркере может ждать снятия этой блокировки прямо в
event loop, и обе стороны виснут без таймаута (`lock_timeout` не задан).

Доказываем детерминированно: `conftest.py` держит `TestClient` открытым (`with TestClient(app)`),
все запросы идут через один общий portal-loop. Подменяем `fetch_payment` на заглушку, которая
проверяет `asyncio.get_running_loop()`: внутри event loop это удаётся (бага — код там, где не
должен быть), в потоке threadpool — бросает `RuntimeError` (обещанное поведение `run_in_threadpool`).
"""
import asyncio

from app.config import settings
from app.models import Payment, UserRole
from app.routers import payments as payments_router

from test_ledger import _make_done_order


def test_webhook_body_runs_off_the_event_loop(client, user_factory, monkeypatch):
    driver = user_factory("L12WebhookLoopDriver", role=UserRole.driver)
    passenger = user_factory("L12WebhookLoopPassenger")
    order_id = _make_done_order(driver["id"], passenger["id"], price_rub=230)

    from sqlmodel import Session
    from app.db import engine

    with Session(engine) as session:
        payment = Payment(user_id=passenger["id"], purpose="ride", order_id=order_id,
                          amount_kop=23000, method="yookassa",
                          provider_id="qa-loop-check", status="pending")
        session.add(payment)
        session.commit()

    monkeypatch.setattr(settings, "payments_provider", "yookassa")

    observed = {}

    def fake_fetch_payment(provider_id):
        try:
            asyncio.get_running_loop()
            observed["on_event_loop"] = True
        except RuntimeError:
            observed["on_event_loop"] = False
        return {"status": "pending", "metadata": {}, "confirmation_url": ""}

    monkeypatch.setattr(payments_router, "fetch_payment", fake_fetch_payment)

    response = client.post("/payments/yookassa/webhook",
                           json={"object": {"id": "qa-loop-check"}})

    assert response.status_code == 200 and response.json() == {"ok": True}, response.text
    assert "on_event_loop" in observed, "fetch_payment не вызвался вовсе — проба бессмысленна"
    assert observed["on_event_loop"] is False, (
        "тело вебхука (поиск строки + сверка со статусом провайдера, внутри — синхронный HTTP) "
        "выполнилось прямо на потоке event loop — на --workers 2 это держит весь воркер, пока "
        "ЮKassa отвечает (до 15 с), и может взаимно подвесить запрос, ждущий блокировку заказа"
    )
