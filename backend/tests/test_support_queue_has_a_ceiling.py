# -*- coding: utf-8 -*-
"""Очередь Александра — тоже ресурс, и у неё должен быть потолок.

Аудит 2026-08-12, волна 28. Обращение в поддержку — самое дешёвое действие для человека
и самое дорогое для нас: каждое падает Александру в Telegram с телефоном и текстом, а разбирает
он их руками, имея 5–10 минут в день. У жалоб на человека потолок поставили ещё в августе,
у жалоб на купон — тоже. Обращения пропустили.

Проба волны 28: 50 обращений подряд, ни одного отказа, 50 сообщений в Telegram.

Отдельно проверяем две вещи, которые ломать НЕЛЬЗЯ: разговор внутри уже открытого обращения
и сигнал SOS. Потолок, который затыкает человека в беде, хуже отсутствующего.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import SosEvent, SupportTicket, UserRole
from app.routers.support import MAX_TICKETS_PER_HOUR


@pytest.fixture
def limiter_on(monkeypatch):
    """Лимитер в тестах выключен глобально — включаем локально, иначе сторож слеп."""
    monkeypatch.setattr(settings, "rate_limit_enabled", True)


def _ticket(client, user, i=0):
    return client.post("/support/tickets", headers=user["auth"],
                       json={"subject": f"Вопрос {i}", "body": "Здравствуйте, у меня проблема"})


def test_поток_обращений_к_админу_ограничен(client, user_factory, monkeypatch, limiter_on):
    """Один человек не может забить очередь поддержки — и Telegram Александра вместе с ней."""
    sent = []
    monkeypatch.setattr("app.routers.support.notify_admin_telegram", lambda *a, **k: sent.append(1))
    author = user_factory("Очень настойчивый")

    codes = [_ticket(client, author, i).status_code for i in range(MAX_TICKETS_PER_HOUR + 5)]

    assert codes.count(200) == MAX_TICKETS_PER_HOUR, codes
    assert 429 in codes, codes
    assert len(sent) == MAX_TICKETS_PER_HOUR, "в Telegram ушло больше, чем создано обращений"

    # Отказ объясняет, что делать, и звучит на обоих языках.
    refused = _ticket(client, author, 99)
    assert refused.status_code == 429
    detail = refused.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"], detail


def test_разговор_в_уже_открытом_обращении_потолок_не_рвёт(client, user_factory, limiter_on):
    """Человек уже пишет о своей беде — обрывать его на полуслове нельзя.

    Потолок стоит на СОЗДАНИИ нового обращения, а не на ответах: ответы админа не дёргают.
    """
    author = user_factory("Человек с одной проблемой")
    first = _ticket(client, author, 0)
    assert first.status_code == 200
    tid = first.json()["id"]

    for i in range(MAX_TICKETS_PER_HOUR + 5):     # выбираем весь бюджет новых обращений
        _ticket(client, author, i + 1)
    assert _ticket(client, author, 100).status_code == 429    # новых больше не даём

    for i in range(10):                            # а в своём треде пишем сколько нужно
        r = client.post(f"/support/tickets/{tid}/messages", headers=author["auth"],
                        json={"body": f"дополню: {i}"})
        assert r.status_code == 200, r.text


def test_сигнал_sos_потолком_не_ограничен(client, user_factory, limiter_on):
    """Помощь проходит ВСЕГДА. Если человек жмёт SOS десять раз — значит, ему плохо."""
    victim = user_factory("Человек в беде")
    for _ in range(10):
        r = client.post("/sos", headers=victim["auth"], json={"category": "other", "note": "Помогите"})
        assert r.status_code == 200, r.text

    with Session(engine) as s:
        events = s.exec(select(SosEvent).where(SosEvent.user_id == victim["id"])).all()
    assert len(events) == 10, "часть сигналов SOS потерялась — так нельзя"


def test_системное_обращение_о_возврате_создаётся_мимо_потолка(client, user_factory, limiter_on):
    """Тикет, который заводит САМ сервер (возврат лишней оплаты, волна 26), от потолка не зависит:
    человек его не просил, а деньги вернуть надо."""
    from app.models import Payment
    from app.routers.payments import _handle_unclaimed_payment

    payer = user_factory("Плательщик дважды")
    for i in range(MAX_TICKETS_PER_HOUR + 2):     # выбираем бюджет обычных обращений
        _ticket(client, payer, i)

    with Session(engine) as s:
        p = Payment(user_id=payer["id"], purpose="ride", amount_kop=50000, method="card",
                    status="canceled", provider_id="pid_sys_ticket")
        s.add(p)
        s.commit()
        s.refresh(p)
        _handle_unclaimed_payment(s, p)
        tickets = s.exec(select(SupportTicket).where(SupportTicket.user_id == payer["id"])).all()

    assert any("озврат" in (t.subject or "") for t in tickets), [t.subject for t in tickets]
