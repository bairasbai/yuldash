"""Лист 2.1 (независимое ревью): Б-4 (перебор фикс-кода стора) и Б-5 (код бота в группе)."""
from __future__ import annotations

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import TgAuth


def _activate_review_login(monkeypatch, phone: str) -> str:
    # Свой номер на тест: каждый вызов сам ставит review_phone — счётчик неудач (OtpCode-страж)
    # хранится ПО НОМЕРУ и должен быть отдельным для каждого сценария, иначе тесты мешают друг другу.
    monkeypatch.setattr(settings, "review_phone", phone, raising=False)
    monkeypatch.setattr(settings, "review_code", "135791", raising=False)
    return phone


def test_review_login_brute_force_hits_the_daily_budget(client, monkeypatch):
    """Б-4: до правки фикс-код стора не считал неудачи вовсе (только лимит по IP, 20/мин)."""
    phone = _activate_review_login(monkeypatch, "+79996660001")
    statuses = [
        client.post("/auth/verify", json={"phone": phone, "code": "000000"}).status_code
        for _ in range(100)
    ]
    assert statuses == [400] * 100, f"не все неудачи посчитаны как обычный отказ: {statuses}"
    # 101-я попытка — уже 429, ДАЖЕ с правильным кодом: бюджет исчерпан, а не код не подошёл.
    r = client.post("/auth/verify", json={"phone": phone, "code": "135791"})
    assert r.status_code == 429, (
        f"сотая с лишним попытка перебора фикс-кода всё ещё обрабатывается как обычная: "
        f"{r.status_code} {r.text}"
    )


def test_review_login_a_few_typos_do_not_trip_the_budget(client, monkeypatch):
    phone = _activate_review_login(monkeypatch, "+79996660002")
    for _ in range(3):
        assert client.post("/auth/verify", json={"phone": phone, "code": "000000"}).status_code == 400
    r = client.post("/auth/verify", json={"phone": phone, "code": "135791"})
    assert r.status_code == 200, f"несколько опечаток не должны блокировать честный вход: {r.text}"


def test_start_command_in_a_group_chat_does_not_deliver_a_code(client):
    """Б-5: `/start@bot <id>`, написанный в группе, раньше присылал код входа в ТУ ЖЕ группу —
    то есть всем её участникам, а не только тому, кто начал вход."""
    request_id = client.post("/auth/tg/start").json()["request_id"]
    payload = {
        "message": {
            "text": f"/start {request_id}",
            "from": {"id": 555111222, "username": "someone"},
            "chat": {"id": -100123456789, "type": "group"},
        }
    }
    r = client.post("/telegram/webhook", json=payload)
    assert r.status_code == 200
    assert r.json() == {"ok": True}, f"бот ответил в групповой чат: {r.json()}"
    with Session(engine) as s:
        row = s.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one()
        assert row.status == "waiting" and not row.code, (
            "код входа был выдан в группу, хотя /start пришёл не в личный чат"
        )


def test_start_command_in_a_private_chat_still_works(client):
    """Сторож придирчивого сторожа: обычный личный чат не задет правкой Б-5."""
    request_id = client.post("/auth/tg/start").json()["request_id"]
    payload = {
        "message": {
            "text": f"/start {request_id}",
            "from": {"id": 555111333, "username": "someone"},
            "chat": {"id": 555111333, "type": "private"},
        }
    }
    r = client.post("/telegram/webhook", json=payload)
    assert r.status_code == 200
    with Session(engine) as s:
        row = s.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one()
        assert row.status == "sent" and row.code, "личный чат перестал получать код после правки Б-5"
