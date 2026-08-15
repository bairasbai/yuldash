"""Ночью телефон звонит только по делу.

История. В деревне ложатся рано. Ильдар публикует рейс в полночь — и десятку подписчиков
«карауль Баймак → Сибай» звонит телефон. Гульнара закончила поездку в час ночи — через
полчаса ей приходит «оцените поездку». Круглосуточно, потому что тихих часов не было вовсе
(аудит 2026-08-08, волна 105).

Разбудить человека ради новости, которая прекрасно ждёт до утра, — это не забота о продукте,
а способ получить выключенные уведомления навсегда. А выключенные уведомления однажды
не покажут то, что действительно важно.

Поэтому ночью молчит только ЗВУК: запись в Центре уведомлений остаётся, утром человек
всё увидит.

Что обязано звонить всегда, даже в три часа ночи:
* беда — SOS и решения по безопасности;
* сообщение от попутчика: человек стоит на трассе и пишет «я подъезжаю»;
* идущая сделка — такси в поиске и подаче, статусы начатой доставки и поездки;
* деньги и долг.
"""
from __future__ import annotations

from datetime import datetime

import pytest
from sqlmodel import Session, select

import app.services as svc
from app.db import engine
from app.models import Notification, UserRole

# Местное время = UTC + 5. Ночь и день задаём в мировом, в скобках — часы у человека.
NIGHT = datetime(2026, 8, 14, 21, 30)   # 02:30 по Уфе
DAY = datetime(2026, 8, 14, 9, 30)      # 14:30 по Уфе


@pytest.fixture(autouse=True)
def тишина_включена(monkeypatch):
    """Тихие часы включаем себе сами.

    В тестовой среде они выключены целиком (волна 111): иначе прогон, начатый после десяти
    вечера, ронял семнадцать посторонних тестов про уведомления — код при этом был в порядке,
    просто наступила ночь. Правило простое: что проверяем, тем и управляем явно.
    """
    monkeypatch.setattr(svc.settings, "quiet_hours_from", 22)
    monkeypatch.setattr(svc.settings, "quiet_hours_to", 7)


@pytest.fixture
def caught_pushes(monkeypatch):
    sent: list[str] = []
    monkeypatch.setattr(svc, "send_push", lambda s, uid, t, b, data=None: sent.append(t))
    return sent


def _at(monkeypatch, moment: datetime):
    monkeypatch.setattr(svc, "utcnow", lambda: moment)


def _notify(user_id: int, ntype: str, title: str = "Заголовок"):
    with Session(engine) as s:
        svc.push_notification(s, user_id, ntype, title, "Баш", "текст", "текст",
                              ref_kind="ride", ref_id=user_id)


def _notes(user_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(select(Notification.id).where(Notification.user_id == user_id)).all())


def test_ночью_новости_не_будят(client, user_factory, caught_pushes, monkeypatch):
    person = user_factory("НочьЗухра")
    _at(monkeypatch, NIGHT)

    _notify(person["id"], "ride", "Оцените поездку")

    assert not caught_pushes, f"телефон зазвонил в половине третьего ночи: {caught_pushes}"


def test_но_уведомление_сохраняется_до_утра(client, user_factory, caught_pushes, monkeypatch):
    """Молчит звук, а не сама новость: утром человек должен её увидеть."""
    person = user_factory("НочьАйгуль")
    _at(monkeypatch, NIGHT)

    before = _notes(person["id"])
    _notify(person["id"], "ride", "Оцените поездку")

    assert _notes(person["id"]) == before + 1, "новость пропала совсем — человек её не увидит"


def test_беда_звонит_и_ночью(client, user_factory, caught_pushes, monkeypatch):
    person = user_factory("НочьБедаРинат")
    _at(monkeypatch, NIGHT)

    _notify(person["id"], "safety", "Открыт разбор")

    assert caught_pushes, "сигнал безопасности промолчал ночью — это как раз то время, когда он нужен"


def test_сообщение_попутчика_звонит_ночью(client, user_factory, caught_pushes, monkeypatch):
    """Человек стоит на трассе и пишет «я подъезжаю» — ждать до утра нечего."""
    person = user_factory("НочьСообщение")
    _at(monkeypatch, NIGHT)

    _notify(person["id"], "message", "Новое сообщение")

    assert caught_pushes, "сообщение от попутчика не разбудило — а он ждёт ответа сейчас"


def test_днём_звонит_всё(client, user_factory, caught_pushes, monkeypatch):
    """Обратная сторона: тишина не должна расползтись на день."""
    person = user_factory("ДеньЗухра")
    _at(monkeypatch, DAY)

    _notify(person["id"], "ride", "Оцените поездку")

    assert caught_pushes, "днём обычное уведомление тоже промолчало — тишина съела рабочее время"


def test_оповещение_о_маршруте_ночью_молчит(client, user_factory, caught_pushes, monkeypatch):
    """Рассылка подписчикам идёт своим путём, мимо общей точки, — её проверяем отдельно."""
    watcher = user_factory("НочьКараул")
    r = client.post("/route-watch", headers=watcher["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "direction": "forward", "watch_kind": "rides",
    })
    assert r.status_code == 200, r.text

    _at(monkeypatch, NIGHT)
    driver = user_factory("НочьВодитель", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-08-20T10:00:00", "seats_total": 3, "price": 300,
    })
    assert r.status_code == 200, r.text

    assert not caught_pushes, f"ночная публикация рейса разбудила подписчиков: {caught_pushes}"
