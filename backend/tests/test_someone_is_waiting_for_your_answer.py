"""Про человека, который ждёт решения, Александр узнаёт сразу.

Заявка таксиста — это ИНН, разрешение, ОСАГО, фото документов и человек, который хочет
работать. Она молча ложилась в очередь `/admin/taxi/applications`, и всё: уведомления не было
вовсе (проверено запросом — 20 заявок, 0 сообщений; аудит 2026-08-14, волна 68).

Про заявку КУРЬЕРА сообщение приходит с самого начала. Про документы водителя — тоже. Про
заявку на 580-ФЗ, самую тяжёлую по бумагам, просто забыли.

Цена не в безопасности, а в людях: у Александра 5–10 минут в день, он смотрит Telegram,
а не заходит в админку по расписанию. Заявка висит днями, человек решает, что до него нет
дела, и уходит к конкурентам — а мы теряем водителя в районе, где их и так мало.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import TaxiApplication, TaxiApplicationStatus, UserRole

APPLY = {"inn": "123456789012", "permit_number": "Т-0001",
         "birth_date": "1990-01-01", "license_since_year": 2010}


@pytest.fixture
def taxi_on(monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    yield


@pytest.fixture
def admin_messages(monkeypatch) -> list:
    sent: list = []
    monkeypatch.setattr("app.routers.taxi.notify_admin_telegram", lambda *a, **k: sent.append(a))
    return sent


def test_новая_заявка_таксиста_доходит_до_админа(client, user_factory, taxi_on, admin_messages):
    drv = user_factory("WaitingDrv", role=UserRole.driver, taxi_approved=False)

    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    assert len(admin_messages) == 1
    assert "заявка таксиста" in str(admin_messages[0]).lower()


def test_в_уведомлении_нет_личных_данных(client, user_factory, taxi_on, admin_messages):
    """Телефон и документы в Telegram не шлём — там только повод зайти в кабинет."""
    drv = user_factory("WaitingPrivacy", role=UserRole.driver, taxi_approved=False)
    client.post("/taxi/apply", headers=drv["auth"], json=APPLY)

    text = str(admin_messages[0])
    assert APPLY["inn"] not in text
    assert APPLY["permit_number"] not in text
    with Session(engine) as s:
        phone = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == drv["id"])).first()
        assert phone is not None
    assert "tg-test" not in text          # телефон из фикстуры тоже не должен просочиться


def test_повторная_подача_не_плодит_сообщений(client, user_factory, taxi_on, admin_messages):
    """Пока заявка на рассмотрении, повтор РАЗРЕШЁН (человек досылает фото, правит опечатку) —
    но для админа это не новая заявка, и второго сообщения быть не должно."""
    drv = user_factory("WaitingDouble", role=UserRole.driver, taxi_approved=False)
    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    assert len(admin_messages) == 1


def test_после_отказа_человек_подаёт_снова_и_об_этом_говорят(client, user_factory, taxi_on,
                                                             admin_messages):
    """Отказали, человек исправил документы — это новость, о ней сообщаем."""
    drv = user_factory("WaitingAgain", role=UserRole.driver, taxi_approved=False)
    client.post("/taxi/apply", headers=drv["auth"], json=APPLY)

    with Session(engine) as s:
        row = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == drv["id"])).first()
        row.status = TaxiApplicationStatus.rejected
        s.add(row)
        s.commit()

    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    assert len(admin_messages) == 2


def test_сбой_телеграма_заявку_не_роняет(client, user_factory, taxi_on, monkeypatch):
    """Уведомление вторично: человек должен подать заявку даже когда Telegram недоступен."""
    def _boom(*a, **k):
        raise RuntimeError("telegram down")

    monkeypatch.setattr("app.routers.taxi.notify_admin_telegram", _boom)
    drv = user_factory("WaitingBoom", role=UserRole.driver, taxi_approved=False)

    assert client.post("/taxi/apply", headers=drv["auth"], json=APPLY).status_code == 200
    with Session(engine) as s:
        assert s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == drv["id"])).first() is not None
