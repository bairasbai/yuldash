"""Кнопка «Продлить размещение» должна доходить до сервера.

История. Хозяйка кафе разместила рекламу за 1000 ₽ на месяц. За пять дней до конца приложение
пишет: «Размещение скоро закончится. Продли, чтобы показы не прервались», и рядом кнопка
«Продлить размещение · 1000 ₽».

Она жмёт, видит QR, переводит деньги по СБП, нажимает «Я перевёл» — и всё выглядит как обычная
оплата. Только на сервере не появляется НИЧЕГО: ни заявки, ни сообщения Александру
(аудит 2026-08-08, волна 116). Через пять дней показы кончаются. Деньги ушли, реклама умерла,
человек уверен, что его обманули — и рассказывает об этом соседям.

Почему так вышло. Обычная оплата на уже оплаченном объявлении отвечает «Уже оплачено» —
и правильно, иначе повторное нажатие плодило бы заявки на первую оплату. Отдельного пути
для продления просто не написали, а кнопку в приложении сделали: она открывала QR напрямую.

Проверено пробой: реклама активна и оплачена, срок до 15 сентября; повторная оплата — 409,
ручка продления — 404. То есть заплатить второй раз было физически некуда.

Отдельно проверено, что продление добавляет период К ОСТАТКУ, а не отсчитывает заново:
кто платит заранее, не должен терять недоиспользованные дни.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Ad, Payment, UserRole

from test_ads_edges import _ad_payload


@pytest.fixture
def реклама_в_эфире(client, user_factory):
    """Объявление одобрено, оплачено и показывается. Владелец и админ рядом."""
    владелец = user_factory("ПродлениеХозяйкаКафе")
    админ = user_factory("ПродлениеАдмин", role=UserRole.admin)
    ad_id = client.post("/ads", headers=владелец["auth"],
                        json=_ad_payload(package="city")).json()["id"]
    client.post(f"/ads/{ad_id}/submit", headers=владелец["auth"])
    assert client.post(f"/admin/ads/{ad_id}/approve", headers=админ["auth"],
                       json={"erid": "erid-1"}).status_code == 200
    pid = client.post(f"/ads/{ad_id}/pay", headers=владелец["auth"]).json()["payment_id"]
    assert client.post(f"/admin/payments/{pid}/confirm", headers=админ["auth"]).status_code == 200
    return владелец, админ, int(ad_id)


def _срок(ad_id: int):
    with Session(engine) as s:
        return s.get(Ad, ad_id).ends_at


def test_продление_создаёт_заявку_на_сервере(client, реклама_в_эфире):
    """Главное: деньги за продление должны быть кому-то видны."""
    владелец, _, ad_id = реклама_в_эфире

    r = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"])

    assert r.status_code == 200, (
        f"продлить нечем: {r.status_code}. Человек переведёт деньги по QR, а на сервере "
        "не будет ни заявки, ни сигнала — реклама погаснет по сроку"
    )
    assert r.json()["amount_kop"] > 0
    with Session(engine) as s:
        заявка = s.get(Payment, r.json()["payment_id"])
        assert заявка.status == "pending" and заявка.ad_id == ad_id, "заявка не про эту рекламу"


def test_продление_добавляет_дни_к_остатку(client, реклама_в_эфире):
    """Кто платит заранее, не должен терять оплаченные дни."""
    владелец, админ, ad_id = реклама_в_эфире
    было = _срок(ad_id)

    pid = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"]).json()["payment_id"]
    assert client.post(f"/admin/payments/{pid}/confirm", headers=админ["auth"]).status_code == 200

    стало = _срок(ad_id)
    прибавка = (стало - было).days
    assert 29 <= прибавка <= 31, (
        f"вместо месяца к остатку прибавилось {прибавка} дней: продление либо сожгло "
        f"оплаченные дни, либо посчиталось дважды (было {было}, стало {стало})"
    )


def test_повторное_нажатие_не_плодит_заявки(client, реклама_в_эфире):
    """Человек нажал дважды — Александр не должен получить два счёта на одно и то же."""
    владелец, _, ad_id = реклама_в_эфире

    первый = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"]).json()["payment_id"]
    второй = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"]).json()["payment_id"]

    assert первый == второй, "две заявки на одно продление"


def test_чужую_рекламу_продлить_нельзя(client, реклама_в_эфире, user_factory):
    _, _, ad_id = реклама_в_эфире
    посторонний = user_factory("ПродлениеПосторонний")

    r = client.post(f"/ads/{ad_id}/renew", headers=посторонний["auth"])

    assert r.status_code == 404, f"чужое объявление продлевается посторонним: {r.status_code}"


def test_неоплаченное_продлевать_нечего(client, user_factory):
    """Обратная сторона: продление — это ВТОРОЙ период, а не обход первой оплаты."""
    владелец = user_factory("ПродлениеНеоплаченный")
    админ = user_factory("ПродлениеАдмин2", role=UserRole.admin)
    ad_id = client.post("/ads", headers=владелец["auth"],
                        json=_ad_payload(package="city")).json()["id"]
    client.post(f"/ads/{ad_id}/submit", headers=владелец["auth"])
    client.post(f"/admin/ads/{ad_id}/approve", headers=админ["auth"], json={"erid": "e"})

    r = client.post(f"/ads/{ad_id}/renew", headers=владелец["auth"])

    assert r.status_code == 409, "через продление можно запустить рекламу, ни разу не заплатив"
    assert "оплати" in str(r.json()).lower(), r.json()


def test_кнопка_в_приложении_ходит_на_сервер():
    """Сторож: кнопка снова может начать показывать QR, не сказав серверу.

    Проверяется по исходникам — экран живёт на телефоне, и разойтись с сервером он может
    молча. Ровно так эта дыра и появилась: серверную часть не написали, а кнопку сделали.
    """
    from pathlib import Path

    экран = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
             "com" / "yuldash" / "app" / "ProfileScreen.kt").read_text(encoding="utf-8")
    блок = экран[экран.index("onRenewAd ="):]
    блок = блок[: блок.index("\n            )") if "\n            )" in блок[:800] else 800]
    assert "renewAd(" in блок, (
        "кнопка «Продлить размещение» снова открывает QR, не спросив сервер: человек переведёт "
        "деньги, а заявки и сигнала Александру не будет"
    )
