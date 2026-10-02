"""Лист 2.1, Б-1 (независимое ревью, блокер — живёт на боевом сервере).

В проде `sms_provider=mock` по умолчанию (`config.py`) — реальных SMS никто не шлёт, вход идёт
через мессенджер. Раньше `request_code` писал код в базу и коммитил его ДО звонка `send_sms`:
человеку канал отвечал 503, а код на 5 минут уже лежал в базе, и `/auth/verify` его честно
принимал. Бюджет попыток считал только ЖИВЫЕ коды — истекли за 5 минут, счётчик обнулился,
дальше ограничивала только выдача новых кодов (3/минуту на номер) — то есть ~15 попыток/5мин
и ~4300 попыток в сутки на номер. Шанс подобрать шестизначный код конкретному номеру — заметный
за месяц. Худший случай — номер из `ADMIN_PHONES`: угаданный код сразу даёт права администратора
(видит сигналы SOS с координатами, документы водителей, все телефоны).

Три независимые проверки:
1. Код не остаётся в базе живым, если SMS объективно не могла уйти (прод + канал заморожен).
2. `/auth/verify` отказывает по SMS-коду, пока канал заморожен, — даже если код всё же лежит
   в базе (старая версия, сбойный фон, окно между выдачей и обрывом канала).
3. Долгая (24-часовая) память не даёт обойти защиту, просто дождавшись, пока старые коды истекут,
   и запросив новый.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import OtpCode
from app.timeutil import utcnow


def _freeze_prod_sms(monkeypatch) -> None:
    """Прод + провайдер по умолчанию (mock) — канал объективно не может доставить SMS."""
    monkeypatch.setattr(settings, "env", "prod", raising=False)
    monkeypatch.setattr(settings, "sms_provider", "mock", raising=False)


def test_request_code_does_not_leave_a_live_code_when_the_channel_is_frozen(client, monkeypatch):
    phone = "+79995550001"
    _freeze_prod_sms(monkeypatch)
    r = client.post("/auth/request-code", json={"phone": phone})
    assert r.status_code == 503, r.text
    detail = r.json()["detail"]
    assert detail.get("ru") and detail.get("ba"), f"отказ не на двух языках: {detail}"
    with Session(engine) as s:
        live = s.exec(select(OtpCode).where(
            OtpCode.phone == phone, OtpCode.expires_at > utcnow(),
        )).all()
        assert live == [], (
            f"код остался в базе живым, хотя человеку он не ушёл — его можно угадать: {live}"
        )


def test_verify_refuses_an_sms_code_while_the_channel_is_frozen_even_if_one_exists(client, monkeypatch):
    phone = "+79995550002"
    # Код мог оказаться в базе не через запрос-ручку (старая версия сервера, ручная вставка,
    # сбойный фон) — защита обязана смотреть на ТЕКУЩЕЕ состояние канала, а не доверять тому,
    # что раз код лежит в базе, значит он кому-то ушёл.
    with Session(engine) as s:
        s.add(OtpCode(phone=phone, code="135790", expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()
    _freeze_prod_sms(monkeypatch)
    r = client.post("/auth/verify", json={"phone": phone, "code": "135790"})
    assert r.status_code == 503, (
        f"код приняли при замороженном SMS-канале: {r.status_code} {r.text}"
    )
    detail = r.json()["detail"]
    assert detail.get("ru") and detail.get("ba"), f"отказ не на двух языках: {detail}"
    with Session(engine) as s:
        # Отказ не должен тратить/гасить код: канал оживят — настоящий хозяин войдёт тем же кодом.
        row = s.exec(select(OtpCode).where(OtpCode.phone == phone)).one()
        assert row.code == "135790" and row.attempts == 0


def test_daily_failure_budget_survives_code_expiry(client, monkeypatch):
    """Короткий лимит (15 попыток за время жизни кодов — 5 минут) не единственная защита:
    истощив много кодов подряд, подбирающий не обязан получить свежий бюджет вместе с новым
    кодом. Сеем 100 уже исчерпанных попыток по ИСТЁКШИМ за последние сутки кодам — ровно то,
    что реально осталось бы в базе от перебора (окно очистки веб-хука теперь переживает сутки,
    см. правку `telegram_webhook`) — и проверяем, что СВЕЖИЙ настоящий код всё равно отклонён."""
    phone = "+79995550003"
    now = utcnow()
    with Session(engine) as s:
        for i in range(20):
            created = now - timedelta(hours=2, seconds=i)
            s.add(OtpCode(phone=phone, code=f"{i:06d}", attempts=5,
                          created_at=created, expires_at=created + timedelta(minutes=5)))
        s.commit()
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200, issued.text
    code = issued.json()["dev_code"]
    r = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert r.status_code == 429, (
        f"суточный бюджет не держит после истечения старых кодов: {r.status_code} {r.text}"
    )


def test_daily_failure_budget_does_not_trip_for_an_ordinary_day(client):
    """Сторож придирчивого сторожа: обычный человек, пару раз ошибившийся за день,
    не должен упираться в суточный потолок — порог щедрый (100), а не «с первой ошибки»."""
    phone = "+79995550004"
    now = utcnow()
    with Session(engine) as s:
        # Три прошлых кода с одной неудачной попыткой каждый — обычная жизнь, не перебор.
        for i in range(3):
            created = now - timedelta(hours=1, minutes=i)
            s.add(OtpCode(phone=phone, code=f"{i:06d}", attempts=1,
                          created_at=created, expires_at=created + timedelta(minutes=5)))
        s.commit()
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200, issued.text
    code = issued.json()["dev_code"]
    r = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert r.status_code == 200, (
        f"честного человека заблокировали за чужую/старую историю: {r.status_code} {r.text}"
    )
