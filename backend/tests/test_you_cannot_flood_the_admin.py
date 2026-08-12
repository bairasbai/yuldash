"""Один человек не может залить Александра сообщениями в Telegram.

История. Заявка с пометкой «нужна помощь» (её создают за пожилого или по звонку) сразу уходит
Александру в Telegram — чтобы срочное не пропустили. Ровно поэтому канал и уязвим: если его
залить, настоящий срочный вызов утонет среди сотни поддельных, а заметить это некому.

Потолок у заявок был — «не больше пятнадцати активных». Но он считает, сколько висит СЕЙЧАС,
а не сколько создано: отменил заявку — место освободилось, создавай следующую. Проба до
правки: сорок заявок подряд, сорок сообщений в Telegram, ни одного отказа.

Рядом в коде лежала готовая проверка темпа (`flood.guard_burst`) — написанная и не подключённая
никуда. Мёртвая функция и была подсказкой: кто-то уже понял, что потолка «сколько висит» мало.

У поездок такого гейта намеренно НЕТ: они Александру не пишут, а «регулярная поездка» создаёт
сразу пять рейсов одним нажатием — водитель с двумя сериями получал бы отказ на второй.
"""
from __future__ import annotations

import pytest

from app.config import settings
from app.models import UserRole


@pytest.fixture
def no_telegram(monkeypatch) -> list:
    """Считаем сообщения, которые ушли бы Александру."""
    sent: list = []
    monkeypatch.setattr("app.routers.requests.notify_admin_telegram",
                        lambda *a, **k: sent.append(a))
    return sent


def _assisted_request(client, auth, i: int):
    return client.post("/requests", headers=auth, json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
        "comment": f"нужна помощь {i}", "assisted": True,
    })


def test_поток_заявок_упирается_в_темп_а_не_в_отмену(client, user_factory, no_telegram):
    pax = user_factory("FloodAdminPax", role=UserRole.passenger)
    limit = settings.flood_create_per_minute

    refused = 0
    for i in range(limit + 15):
        r = _assisted_request(client, pax["auth"], i)
        if r.status_code == 429:
            refused += 1
            continue
        assert r.status_code == 200, r.text
        # отменяем сразу: раньше это освобождало место и цикл шёл бесконечно
        client.post(f"/requests/{r.json()['id']}/cancel", headers=pax["auth"])

    assert len(no_telegram) == limit
    assert refused > 0


def test_отказ_объясняет_что_делать_и_на_двух_языках(client, user_factory, no_telegram):
    pax = user_factory("FloodAdminMsg", role=UserRole.passenger)
    last = None
    for i in range(settings.flood_create_per_minute + 3):
        last = _assisted_request(client, pax["auth"], 100 + i)
    assert last.status_code == 429
    detail = last.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]
    assert "минут" in detail["ru"]        # человеку сказано, что делать: подождать


def test_обычному_человеку_потолок_темпа_не_мешает(client, user_factory, no_telegram):
    """Страховка от перестраховки: две заявки подряд — нормальная жизнь, не флуд."""
    pax = user_factory("FloodAdminOk", role=UserRole.passenger)
    assert _assisted_request(client, pax["auth"], 201).status_code == 200
    assert _assisted_request(client, pax["auth"], 202).status_code == 200
    assert len(no_telegram) == 2


def test_у_каждого_свой_счёт(client, user_factory, no_telegram):
    """Сосед, исчерпавший темп, не должен запирать других — иначе один человек
    выключал бы «помощь» всему району."""
    noisy = user_factory("FloodAdminNoisy", role=UserRole.passenger)
    quiet = user_factory("FloodAdminQuiet", role=UserRole.passenger)
    for i in range(settings.flood_create_per_minute + 3):
        _assisted_request(client, noisy["auth"], 300 + i)

    assert _assisted_request(client, quiet["auth"], 400).status_code == 200


def test_посылки_под_тем_же_правилом(client, user_factory, monkeypatch):
    """Вторая дверь к тому же Telegram: каждая новая посылка тоже пишет Александру."""
    sent: list = []
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: sent.append(a))
    sender = user_factory("FloodAdminSender", role=UserRole.passenger)

    refused = 0
    for i in range(settings.flood_create_per_minute + 5):
        r = client.post("/parcels", headers=sender["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай", "size": "small",
            "description": f"посылка {i}", "receiver_name": "Гөлнара",
            "receiver_phone": "+79990009903", "rules_accepted": True,
        })
        if r.status_code == 429:
            refused += 1
    assert refused > 0


def test_серия_регулярных_поездок_не_ломается(client, user_factory):
    """Поездки под темп намеренно не попали: одна публикация создаёт пять рейсов,
    и водителю с двумя сериями отказали бы во второй."""
    drv = user_factory("FloodAdminDriver", role=UserRole.driver)
    for city in ("SeriesA", "SeriesB"):
        r = client.post("/rides", headers=drv["auth"], json={
            "from_city": city, "to_city": "Уфа", "depart_at": "2030-03-04T10:00:00",
            "seats_total": 2, "price": 100, "recurrence": "weekly",
        })
        assert r.status_code == 200, r.text
        assert len(client.get("/rides", params={"from_city": city}).json()) == 5
