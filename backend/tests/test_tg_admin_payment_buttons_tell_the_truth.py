"""Кнопки ✅/❌ под платежом в Telegram — то же ядро, что у админки (ревью 2026-10-12, P2).

Кнопка в Telegram жила своей копией логики. Админ жал ✅ по уже отменённому платежу и читал
«Подтверждена оплата #N», хотя ничего не применилось; админка в том же случае честно отвечала
409. Кнопка ❌ читала статус без блокировки и отвечала «Отклонена» даже по проведённому
платежу, а плательщику ничего не сообщала. Теперь обе двери ходят через одно ядро в payments.py.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app import models as M
from app.db import engine


def _нажать(monkeypatch, кнопка: str, payment_id: int) -> list[str]:
    """Тап владельца бота по кнопке; возвращает тексты, которые бот ему показал."""
    from app.config import settings
    from app.routers import auth as auth_router
    сказано: list[str] = []
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "777001")
    monkeypatch.setattr(auth_router, "_telegram_api",
                        lambda method, payload: сказано.append(payload.get("text", "")))
    auth_router._handle_admin_callback({
        "id": "cb-truth", "from": {"id": 777001}, "data": f"{кнопка}:{payment_id}",
        "message": {"chat": {"id": 777001}, "message_id": 1},
    })
    return [t for t in сказано if t]


def _платёж(user_id: int, **поля) -> int:
    with Session(engine) as s:
        p = M.Payment(user_id=user_id, purpose="boost", amount_kop=10_000, method="sbp", **поля)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _статус(payment_id: int) -> str:
    with Session(engine) as s:
        return s.get(M.Payment, payment_id).status


def _уведомлений(user_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(select(M.Notification).where(M.Notification.user_id == user_id)).all())


def test_галочка_по_отменённому_не_врёт_про_подтверждение(client, user_factory, monkeypatch):
    payer = user_factory("TgTruthCanceled")
    pid = _платёж(payer["id"], status="canceled")

    сказано = _нажать(monkeypatch, "pay:ok", pid)

    assert _статус(pid) == "canceled"
    assert not any("Подтверждена оплата" in t for t in сказано), (
        f"админу сказали «подтверждена» по отменённому платежу: {сказано}"
    )
    assert any("уже закрыт" in t for t in сказано), сказано


def test_крестик_по_проведённому_не_врёт_про_отказ(client, user_factory, monkeypatch):
    payer = user_factory("TgTruthSucceeded")
    pid = _платёж(payer["id"], status="succeeded")

    сказано = _нажать(monkeypatch, "pay:no", pid)

    assert _статус(pid) == "succeeded"
    assert not any("Отклонена оплата" in t for t in сказано), (
        f"админу сказали «отклонена» по проведённым деньгам: {сказано}"
    )
    assert any("отклонить нельзя" in t for t in сказано), сказано


def test_крестик_по_ожидающему_отменяет_и_сообщает_плательщику(client, user_factory, monkeypatch):
    """Как в админке (волна 84): молчащий отказ — человек ждёт вечно."""
    payer = user_factory("TgTruthPending")
    pid = _платёж(payer["id"], status="pending")
    было = _уведомлений(payer["id"])

    сказано = _нажать(monkeypatch, "pay:no", pid)

    assert _статус(pid) == "canceled"
    assert any("Отклонена оплата" in t for t in сказано), сказано
    assert _уведомлений(payer["id"]) > было, "плательщику не сказали, что перевод не принят"


def test_галочка_не_подтверждает_карточный_без_провайдера(client, user_factory, monkeypatch):
    """Карточный (method=yookassa) руками не подтверждаем — как и в админке, даже без provider_id."""
    payer = user_factory("TgTruthCard")
    pid = _платёж(payer["id"], status="pending", **{})
    with Session(engine) as s:
        p = s.get(M.Payment, pid)
        p.method = "yookassa"
        s.add(p)
        s.commit()

    сказано = _нажать(monkeypatch, "pay:ok", pid)

    assert _статус(pid) == "pending", "карточный платёж подтвердили руками — начисление без денег"
    assert any("у провайдера" in t for t in сказано), сказано


def test_галочка_по_ожидающему_переводу_подтверждает(client, user_factory, monkeypatch):
    payer = user_factory("TgTruthOk")
    pid = _платёж(payer["id"], status="pending")

    сказано = _нажать(monkeypatch, "pay:ok", pid)

    assert _статус(pid) == "succeeded"
    assert any("Подтверждена оплата" in t for t in сказано), сказано
