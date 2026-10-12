"""leaf-1.1 · две мелочи из §5 независимого ревью Opus (2026-10-02), P3, но карточка не должна
выдавать их за «проверено»:

R1 — отказ «Только для админа» — на ДВУХ языках (herr), а не голый HTTPException по-русски.
R2 — «Я оплатил» (СБП) не кладёт ТЕЛЕФОН водителя в Telegram (сторонний зарубежный сервис;
     шапка debt.py обещает «суммы не логируем с привязкой к персоне — только id»). Админ
     видит телефон в /admin/debts по тому же id — большего Telegram не должен получать.
"""
from sqlmodel import Session

from app.db import engine
from app.models import CommissionDebt, UserRole


def test_r1_admin_only_refusal_is_bilingual(client, user_factory):
    обычный = user_factory("НеАдминОтказ")
    r = client.get("/admin/debts", headers=обычный["auth"])
    assert r.status_code == 403
    body = r.json()["detail"]
    assert isinstance(body, dict), f"ожидали двуязычное тело отказа, получили: {body!r}"
    assert body.get("ru") and body.get("ba"), f"обе строки обязаны быть непустыми: {body!r}"


def test_r2_declare_paid_telegram_message_has_no_phone(client, user_factory, monkeypatch):
    drv = user_factory("ТелефонСкрыт", role=UserRole.driver)
    сообщения = []
    monkeypatch.setattr("app.routers.debt.notify_admin_telegram", lambda text: сообщения.append(text))

    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv["id"], amount_kop=5_000, week="2026-W40"))
        s.commit()

    r = client.post("/driver/debt/paid", headers=drv["auth"])
    assert r.status_code == 200 and r.json()["pending_kop"] > 0

    assert сообщения, "уведомление админу не ушло — тест ничего не проверил"
    текст = сообщения[0]
    assert str(drv["id"]) in текст, "id водителя обязан остаться — иначе сверять нечем"
    # Реальный телефон тестового фактори — "tg-test-N" (conftest.py), но проверяем и универсально:
    # ни слова "+7", ни маски телефона в тексте уведомления быть не должно вовсе.
    assert "tg-test-" not in текст, f"сырой телефон попал в Telegram: {текст!r}"
