"""Лист 2.1 (независимое ревью): Б-4 (перебор фикс-кода стора) и Б-5 (код бота в группе)."""
from __future__ import annotations

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import OtpCode, TgAuth, User
from app.timeutil import utcnow


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


def test_review_guard_history_can_never_log_in_through_the_normal_sms_door(client, monkeypatch):
    """Н-2 (повторное независимое ревью): счётчик неудач фикс-кода стора раньше держал ОДНУ
    живую строку с постоянным кодом ("review-login-guard") 24 часа. Если режим стора для этого
    номера потом выключат (обычная SMS-дверь снова работает), а кто-то пришлёт этот известный
    код обычным SMS-входом — `verify` нашёл бы эту "живую" строку и впустил бы без единой
    настоящей SMS. Проверяем: после накопленных неудач и ВЫКЛЮЧЕННОГО режима стора ни этим,
    ни любым другим кодом-подстрокой войти нельзя, и аккаунт не создаётся."""
    phone = _activate_review_login(monkeypatch, "+79996660099")
    for _ in range(5):
        client.post("/auth/verify", json={"phone": phone, "code": "000000"})

    # Режим стора для этого номера выключили — теперь это обычная SMS-дверь.
    monkeypatch.setattr(settings, "review_phone", "", raising=False)
    monkeypatch.setattr(settings, "review_code", "", raising=False)

    r = client.post("/auth/verify", json={"phone": phone, "code": "review-login-guard"})
    assert r.status_code == 400, (
        f"служебная запись-счётчик review-входа впустила по обычной SMS-двери: "
        f"{r.status_code} {r.text}"
    )
    with Session(engine) as s:
        assert s.exec(select(User).where(User.phone == phone)).first() is None, (
            "аккаунт создан по служебному коду-счётчику review-входа"
        )
        # Каждая неудача — своя, заведомо мёртвая строка; ни одна не должна быть «живой».
        rows = s.exec(select(OtpCode).where(OtpCode.phone == phone)).all()
        assert rows, "счётчик неудач review-входа не оставил следа для суточного бюджета"
        assert all(row.code == "" and row.expires_at <= utcnow() for row in rows), (
            f"запись счётчика review-входа живая/с непустым кодом: {rows}"
        )


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
