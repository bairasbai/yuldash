"""Четыре замечания независимого ревью PR #123 (кнопки ✅/❌ админа в Telegram), P3.

(а) ❌ и HTTP-«отклонить» отменяли карточный платёж (`provider_id` / `method=yookassa`). Деньги по
    нему может в любую секунду подтвердить банк, а вебхук применяет платёж только из pending:
    отменённый руками — так и остаётся отменённым, деньги у платформы, человеку ничего.
(б) `telegram_webhook` — async и звал синхронное ядро с блокировкой строки прямо в event loop:
    пока строка платежа занята, весь воркер стоит.
(в) Исключение внутри `_activate_payment` в Telegram-пути превращалось в 500 — Telegram повторяет
    такой апдейт, а админ ответа не получает.
(г) `admin_action(user_id, …)` писал в поле «кто решил» id платежа / водителя / объявления.
"""
from __future__ import annotations

import asyncio
import logging

from sqlmodel import Session

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole
from app.routers import auth as auth_router
from app.routers import payments as payments_router

OWNER_TG = "777123001"


def _платёж(user_id: int, **поля) -> int:
    поля.setdefault("method", "sbp")
    поля.setdefault("status", "pending")
    with Session(engine) as s:
        p = M.Payment(user_id=user_id, purpose="boost", amount_kop=10_000, **поля)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _статус(payment_id: int) -> str:
    with Session(engine) as s:
        return s.get(M.Payment, payment_id).status


def _тап(client, monkeypatch, data: str):
    """Нажатие владельца бота — через настоящий вебхук, как это делает Telegram."""
    сказано: list[str] = []
    monkeypatch.setattr(settings, "admin_telegram_chat_id", OWNER_TG)
    monkeypatch.setattr(settings, "telegram_webhook_secret", "")
    monkeypatch.setattr(auth_router, "_telegram_api",
                        lambda method, payload: сказано.append(payload.get("text", "")))
    r = client.post("/telegram/webhook", json={"callback_query": {
        "id": "cb-review-123", "from": {"id": int(OWNER_TG)}, "data": data,
        "message": {"chat": {"id": int(OWNER_TG)}, "message_id": 1},
    }})
    return r, [t for t in сказано if t]


# ─────────────── (а) карточный платёж руками не отменяем ───────────────

def test_крестик_не_отменяет_карточный_платёж(client, user_factory, monkeypatch):
    payer = user_factory("Rv123CardNo")
    по_провайдеру = _платёж(payer["id"], provider_id="qa-rv123-card")
    по_методу = _платёж(payer["id"], method="yookassa")

    for pid in (по_провайдеру, по_методу):
        r, сказано = _тап(client, monkeypatch, f"pay:no:{pid}")
        assert r.status_code == 200
        assert _статус(pid) == "pending", "карточный платёж отменён руками — оплату банка потом некуда применить"
        assert any("вручную не отклоняется" in t for t in сказано), сказано
        assert not any("Отклонена оплата" in t for t in сказано), сказано


def test_http_отклонить_не_отменяет_карточный_платёж(client, user_factory):
    admin = user_factory("Rv123Admin", role=UserRole.admin)
    payer = user_factory("Rv123CardHttp")
    for поля in ({"provider_id": "qa-rv123-http"}, {"method": "yookassa"}):
        pid = _платёж(payer["id"], **поля)
        r = client.post(f"/admin/payments/{pid}/reject", headers=admin["auth"])
        assert r.status_code == 409, r.text
        detail = r.json()["detail"]
        assert "вручную не отклоняется" in detail["ru"] and detail["ba"].strip() and detail["ba"] != detail["ru"]
        assert _статус(pid) == "pending"


def test_крестик_по_переводу_по_сбп_по_прежнему_отменяет(client, user_factory, monkeypatch):
    payer = user_factory("Rv123SbpNo")
    pid = _платёж(payer["id"])
    r, сказано = _тап(client, monkeypatch, f"pay:no:{pid}")
    assert r.status_code == 200
    assert _статус(pid) == "canceled"
    assert any("Отклонена оплата" in t for t in сказано), сказано


# ─────────────── (б) нажатие кнопки — не на потоке event loop ───────────────

def test_нажатие_кнопки_обрабатывается_не_в_event_loop(client, user_factory, monkeypatch):
    payer = user_factory("Rv123Loop")
    pid = _платёж(payer["id"])
    observed = {}

    def fake_confirm(session, payment_id):
        try:
            asyncio.get_running_loop()
            observed["on_event_loop"] = True
        except RuntimeError:
            observed["on_event_loop"] = False
        return "already", session.get(M.Payment, payment_id)

    monkeypatch.setattr(payments_router, "confirm_manual_payment", fake_confirm)

    r, _ = _тап(client, monkeypatch, f"pay:ok:{pid}")

    assert r.status_code == 200
    assert "on_event_loop" in observed, "ядро не вызвалось — проба бессмысленна"
    assert observed["on_event_loop"] is False, (
        "ядро с блокировкой строки платежа выполнилось прямо в event loop — воркер стоит, пока строка занята"
    )


# ─────────────── (в) поломка при проведении — честный ответ, не 500 ───────────────

def test_поломка_при_подтверждении_даёт_честный_ответ_а_не_500(client, user_factory, monkeypatch):
    payer = user_factory("Rv123Boom")
    pid = _платёж(payer["id"])

    def boom(session, payment):
        raise RuntimeError("invalid taxi debt payment snapshot marker")

    monkeypatch.setattr(payments_router, "_activate_payment", boom)

    r, сказано = _тап(client, monkeypatch, f"pay:ok:{pid}")

    assert r.status_code == 200, "500 — Telegram будет повторять это нажатие снова и снова"
    assert _статус(pid) == "pending"
    assert any("Не получилось подтвердить" in t and f"#{pid}" in t for t in сказано), сказано
    assert not any("Подтверждена оплата" in t for t in сказано), сказано


# ─────────────── (г) в журнале «кто решил» — админ, а не цель ───────────────

def _след(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if "[ADMIN]" in r.getMessage() and "telegram." in r.getMessage()]


def test_кто_решил_кнопкой_это_владелец_бота_а_не_водитель(client, user_factory, monkeypatch, caplog):
    водитель = user_factory("Rv123Drv", role=UserRole.driver)
    with caplog.at_level(logging.INFO, logger="yuldash"):
        r, _ = _тап(client, monkeypatch, f"drv:ok:{водитель['id']}")
    assert r.status_code == 200
    след = _след(caplog)
    assert след, "кнопка не оставила следа"
    assert f"admin={водитель['id']} " not in след[-1], f"«кто решил» записан id водителя: {след[-1]}"
    assert f"admin=tg:{OWNER_TG} " in след[-1], след[-1]
    assert f"target={водитель['id']}" in след[-1]


def test_кто_решил_кнопкой_это_аккаунт_админа_если_telegram_привязан(client, user_factory, monkeypatch, caplog):
    admin = user_factory("Rv123AdmLinked", role=UserRole.admin)
    with Session(engine) as s:
        u = s.get(M.User, admin["id"])
        u.telegram_id = OWNER_TG
        s.add(u)
        s.commit()
    payer = user_factory("Rv123PayLog")
    pid = _платёж(payer["id"])
    try:
        with caplog.at_level(logging.INFO, logger="yuldash"):
            r, _ = _тап(client, monkeypatch, f"pay:ok:{pid}")
    finally:
        with Session(engine) as s:   # общий Telegram id не должен утечь в соседние тесты
            u = s.get(M.User, admin["id"])
            u.telegram_id = f"rv123-unlinked-{admin['id']}"
            s.add(u)
            s.commit()
    assert r.status_code == 200
    след = _след(caplog)
    assert след and f"admin={admin['id']} " in след[-1], след
    assert f"target={pid}" in след[-1]
