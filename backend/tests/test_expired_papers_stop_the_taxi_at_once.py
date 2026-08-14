"""Просроченные документы останавливают такси сразу, а не когда проснётся фоновая задача.

580-ФЗ и обещание «проверенная служба» держатся на сроках: ОСАГО, разрешение на такси,
диагностическая карта. Когда срок истекает, фоновая задача снимает допуск и пишет водителю.

Но гейт смотрел ТОЛЬКО на флаг, который ставит эта задача. То есть допуск держался на том,
что задача успела прийти. Водитель с ОСАГО, истёкшим вчера, выходил на линию и вёз людей
(проверено запросом — 200; аудит 2026-08-14, волна 66). А если таймер на сервере не настроен
или упал, окно не «до утра», а навсегда — и заметить это некому: флаг-то честно `False`.

Цена ошибки не абстрактная: при ДТП без ОСАГО пассажир остаётся без страховых выплат, а мы —
службой, которая написала «проверено» и не проверила.

Теперь решение принимается по ДАТАМ, а флаг остаётся быстрым индексом для выборок и поводом
отправить уведомление. Считает одна функция — та же, которой пользуется фоновая задача.
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest
from sqlmodel import Session, select

from app import doc_check
from app import taxi as taxi_mod
from app.config import settings
from app.db import engine
from app.models import (DriverProfile, TaxiApplication, TaxiApplicationStatus, User, UserRole)
from app.security import make_token

YESTERDAY = date.today() - timedelta(days=1)
NEXT_YEAR = date.today() + timedelta(days=365)


@pytest.fixture
def taxi_on(monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    yield


def _driver_with_papers(name: str, **app_fields) -> tuple[int, dict]:
    """Одобренный таксист на линии. Флаг docs_expired НЕ трогаем — как будто задача ещё не ходила."""
    with Session(engine) as s:
        u = User(phone=f"w66-{name}", name=name, telegram_id=f"w66{name}", verified=True,
                 role=UserRole.driver)
        s.add(u)
        s.commit()
        s.refresh(u)
        s.add(TaxiApplication(
            user_id=u.id, inn="123456789012", permit_number="Т-1",
            birth_date=date(1990, 1, 1), license_since_year=2010,
            status=TaxiApplicationStatus.approved, **app_fields))
        s.add(DriverProfile(user_id=u.id, online=True, car_classes_available="economy"))
        s.commit()
        return u.id, {"Authorization": f"Bearer {make_token(u.id)}"}


def test_осаго_истекло_вчера_на_линию_нельзя_сразу(client, taxi_on):
    """Главная история: задача ещё не приходила, а возить уже нельзя."""
    drv, auth = _driver_with_papers("Osago", osago_until=YESTERDAY)

    with Session(engine) as s:
        app_row = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == drv)).first()
        assert app_row.docs_expired is False        # фоновая задача ещё не отработала
        assert taxi_mod.is_approved_taxi_driver(s, drv) is False

    assert client.post("/instant/presence", headers=auth,
                       json={"lat": 54.7, "lng": 55.9, "online": True}).status_code == 403


def test_разрешение_на_такси_истекло_тоже_сразу(client, taxi_on):
    drv, auth = _driver_with_papers("Permit", permit_until=YESTERDAY)
    assert client.post("/instant/presence", headers=auth,
                       json={"lat": 54.7, "lng": 55.9, "online": True}).status_code == 403


def test_диагностическая_карта_истекла_тоже_сразу(client, taxi_on):
    drv, auth = _driver_with_papers("Inspection", inspection_until=YESTERDAY)
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, drv) is False


def test_человеку_говорят_что_дело_в_документах(client, taxi_on):
    """Отказ должен объяснять, ЧТО сделать: продлить документ, а не «ты не прошёл проверку»."""
    drv, _auth = _driver_with_papers("Message", osago_until=YESTERDAY)
    with Session(engine) as s:
        assert taxi_mod.taxi_docs_expired(s, drv) is True


def test_действующие_документы_работать_не_мешают(client, taxi_on):
    """Страховка от перестраховки: сроки в порядке — человек работает как раньше."""
    drv, auth = _driver_with_papers("Valid", osago_until=NEXT_YEAR, permit_until=NEXT_YEAR,
                                    inspection_until=NEXT_YEAR)
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, drv) is True
    assert client.post("/instant/presence", headers=auth,
                       json={"lat": 54.7, "lng": 55.9, "online": True}).status_code == 200


def test_незаполненные_сроки_никого_не_блокируют(client, taxi_on):
    """Осознанная граница: поля появились позже живого приложения. Пустой срок — повод
    напомнить (`remind_missing`), а не отобрать работу у того, кто ещё не обновил приложение."""
    drv, auth = _driver_with_papers("NoDates")
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, drv) is True
    assert client.post("/instant/presence", headers=auth,
                       json={"lat": 54.7, "lng": 55.9, "online": True}).status_code == 200


def test_гейт_и_фоновая_задача_считают_одинаково(client, taxi_on):
    """Сторож на расхождение: если задача считает просрочкой одно, а гейт другое, они разъедутся
    при первой же правке списка документов (урок волны 60)."""
    drv, _auth = _driver_with_papers("SameAnswer", osago_until=YESTERDAY)
    with Session(engine) as s:
        app_row = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == drv)).first()
        by_task = bool(doc_check.overdue_docs(app_row))
        by_gate = not taxi_mod.is_approved_taxi_driver(s, drv)
        assert by_task == by_gate is True


def test_продлил_документ_вернулся_на_линию(client, taxi_on):
    """Наказания тут нет вовсе: обновил бумагу — работаешь дальше, без похода к админу."""
    drv, auth = _driver_with_papers("Renew", osago_until=YESTERDAY)
    assert client.post("/instant/presence", headers=auth,
                       json={"lat": 54.7, "lng": 55.9, "online": True}).status_code == 403

    with Session(engine) as s:
        app_row = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == drv)).first()
        app_row.osago_until = NEXT_YEAR
        s.add(app_row)
        s.commit()
        assert taxi_mod.is_approved_taxi_driver(s, drv) is True
