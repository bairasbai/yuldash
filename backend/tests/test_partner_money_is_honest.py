"""Деньги партнёров: счёт совпадает с выбором, а конец размещения не молчит.

Три находки роя про один кабинет.

**Счёт из прошлого тарифа.** Хозяйка кафе ткнула «Базовый 990 ₽», передумала и выбрала
«Премиум 2 990 ₽» — приложение снова просило 990, а после оплаты включался базовый тариф.
В обратную сторону злее: с премиума на базовый она навсегда видела счёт на 2 990 без кнопки
«отменить». Причина: повторное нажатие возвращало старую заявку, не глядя на выбранный тариф
(аудит 2026-08-08, волна 125).

**Реклама гасла молча.** На 31-й день показы прекращались, но в кабинете горело зелёным
«Оплачено · объявление показывается», и уведомления не было. Человек видел, что рекламы нет,
и шёл в поддержку с обвинением — по-своему справедливо: ему никто не сказал.

**Оплата воскрешала снятое.** Объявление, снятое модерацией за обман, возвращалось в ленту
района от подтверждения вчерашнего платежа. Снаружи это не эксплуатируется — нужен сам
админ, — но один случайный тап в Telegram не должен отменять решение модерации.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Ad, UserRole
from app.timeutil import utcnow

from test_ads_edges import _ad_payload


@pytest.fixture
def кафе(client, user_factory):
    хозяйка = user_factory("ДеньгиХозяйка")
    админ = user_factory("ДеньгиАдмин", role=UserRole.admin)
    pid = client.post("/partner", headers=хозяйка["auth"], json={
        "name": "Чак-чак кафе", "city": "Сибай", "address": "Ленина 1",
        "phone": "+79990000125", "category": "cafe",
    }).json()["id"]
    assert client.post(f"/admin/partners/{pid}/approve", headers=админ["auth"]).status_code == 200
    return хозяйка, админ, pid


def test_передумал_на_другой_тариф_счёт_меняется(client, кафе):
    хозяйка, _, _ = кафе

    базовый = client.post("/partner/subscribe", headers=хозяйка["auth"], json={"plan": "basic"}).json()
    премиум = client.post("/partner/subscribe", headers=хозяйка["auth"], json={"plan": "premium"}).json()

    assert премиум["plan"] == "premium", (
        f"выбрали «Премиум», а счёт остался на «{премиум['plan']}»: человек заплатит одну сумму, "
        "а получит другой тариф"
    )
    assert премиум["amount_kop"] > базовый["amount_kop"], (базовый, премиум)


def test_повторное_нажатие_того_же_тарифа_не_плодит_счета(client, кафе):
    """Обратная сторона: двойной тап по одной кнопке — по-прежнему один счёт."""
    хозяйка, _, _ = кафе

    первый = client.post("/partner/subscribe", headers=хозяйка["auth"], json={"plan": "basic"}).json()
    второй = client.post("/partner/subscribe", headers=хозяйка["auth"], json={"plan": "basic"}).json()

    assert первый["payment_id"] == второй["payment_id"], "два счёта на одно и то же"


@pytest.fixture
def реклама_в_эфире(client, user_factory):
    владелец = user_factory("СрокВладелец")
    админ = user_factory("СрокАдмин", role=UserRole.admin)
    ad_id = client.post("/ads", headers=владелец["auth"],
                        json=_ad_payload(package="city")).json()["id"]
    client.post(f"/ads/{ad_id}/submit", headers=владелец["auth"])
    client.post(f"/admin/ads/{ad_id}/approve", headers=админ["auth"], json={"erid": "e"})
    pid = client.post(f"/ads/{ad_id}/pay", headers=владелец["auth"]).json()["payment_id"]
    assert client.post(f"/admin/payments/{pid}/confirm", headers=админ["auth"]).status_code == 200
    return владелец, админ, int(ad_id)


def _робот() -> int:
    from app import taxi_worker
    with Session(engine) as s:
        return taxi_worker.run_once(s).get("ads_expired", 0)


def test_вышедший_срок_гасит_объявление_и_говорит_об_этом(client, реклама_в_эфире):
    владелец, _, ad_id = реклама_в_эфире
    with Session(engine) as s:                    # срок вышел вчера
        ad = s.get(Ad, ad_id)
        ad.ends_at = utcnow().replace(microsecond=0)
        ad.ends_at = ad.ends_at.replace(year=ad.ends_at.year - 1)
        s.add(ad)
        s.commit()

    assert _робот() >= 1

    with Session(engine) as s:
        assert s.get(Ad, ad_id).status == "expired", (
            "в кабинете по-прежнему «Оплачено · показывается», хотя показы кончились — "
            "человек пойдёт в поддержку с обвинением"
        )
    лента = client.get("/notifications", headers=владелец["auth"])
    тексты = str(лента.json())
    assert "Размещение закончилось" in тексты, (
        f"о конце размещения не сказали: {тексты[:200]}"
    )


def test_живую_рекламу_робот_не_трогает(client, реклама_в_эфире):
    """Обратная сторона: оплаченное размещение не должно гаснуть раньше времени."""
    _, _, ad_id = реклама_в_эфире

    _робот()

    with Session(engine) as s:
        assert s.get(Ad, ad_id).status == "active", "робот погасил ещё живое объявление"


def test_оплата_не_возвращает_снятое_модерацией(client, реклама_в_эфире):
    """Порядок как в жизни: счёт выставлен вчера, сегодня объявление сняли, а платёж
    подтверждают потом — случайным тапом «✅ Подтвердить» в Telegram."""
    владелец, админ, ad_id = реклама_в_эфире
    счёт = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"])
    assert счёт.status_code == 200, счёт.text

    assert client.post(f"/admin/ads/{ad_id}/reject", headers=админ["auth"],
                       json={"reason": "обман"}).status_code == 200
    client.post(f"/admin/payments/{счёт.json()['payment_id']}/confirm", headers=админ["auth"])

    with Session(engine) as s:
        assert s.get(Ad, ad_id).status != "active", (
            "снятое за обман объявление вернулось в ленту района от подтверждения платежа"
        )
