# -*- coding: utf-8 -*-
"""Стаж вождения считается по ДАТАМ, а не вычитанием годов (аудит 2026-08-03).

Было: `today.year - license_since_year >= 3`. Права, выданные 31.12.2023, проходили проверку
уже 01.01.2026 — по календарю «три года», по факту 2 года и 1 день. Требование к перевозчику
(580-ФЗ) обходилось одним днём, и за руль такси садился человек с недобранным стажем.

Стало: принимаем точную дату выдачи (опциональное поле — старые клиенты по-прежнему шлют год),
считаем полные годы по датам; если пришёл только год — отсчитываем от 31 декабря этого года,
то есть ошибаемся в пользу безопасности пассажира, а не в пользу допуска.
"""
from datetime import date, datetime

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import TaxiApplication, UserRole

BASE = {"inn": "123456789012", "permit_number": "Т-0777", "birth_date": "1990-01-01"}


@pytest.fixture
def frozen_today(monkeypatch):
    """«Сегодня» = 1 января 2026: ровно та дата, на которой ломалась старая арифметика."""
    from app.routers import taxi as taxi_router

    def fake_utcnow():
        return datetime(2026, 1, 1, 12, 0, 0)

    monkeypatch.setattr(taxi_router, "utcnow", fake_utcnow)
    return date(2026, 1, 1)


def _apply(client, drv, **fields):
    return client.post("/taxi/apply", headers=drv["auth"], json={**BASE, **fields})


def test_license_from_last_day_of_year_is_rejected_on_new_year(client, user_factory, frozen_today):
    """Права от 31.12.2023 и 01.01.2026 на календаре: стажа 2 года и 1 день → отказ."""
    drv = user_factory("СтажНедобрал", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=2023, license_since_date="2023-12-31")
    assert r.status_code == 400, r.text
    assert "стаж" in r.json()["detail"]["ru"].lower()
    assert r.json()["detail"]["ba"], "отказ обязан быть и на башкирском"


def test_year_only_is_counted_from_31_december(client, user_factory, frozen_today):
    """Клиент прислал ТОЛЬКО год (старая версия приложения) → считаем от 31 декабря.

    Именно этот случай раньше и пропускал недобравших: 2026 − 2023 = 3 «года» на бумаге.
    """
    drv = user_factory("ТолькоГод", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=2023)
    assert r.status_code == 400, "год без даты обязан считаться консервативно, от 31 декабря"
    assert "стаж" in r.json()["detail"]["ru"].lower()


def test_three_full_years_by_date_is_accepted(client, user_factory, frozen_today):
    """Ровно три полных года по датам (01.01.2023 → 01.01.2026) — допуск есть."""
    drv = user_factory("СтажРовно3", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=2023, license_since_date="2023-01-01")
    assert r.status_code == 200, r.text
    assert r.json()["license_since_date"] == "2023-01-01"


def test_year_only_veteran_still_passes(client, user_factory, frozen_today):
    """Старый клиент со старым годом не пострадал: настоящий стаж проходит как раньше."""
    drv = user_factory("Ветеран20лет", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=2010)
    assert r.status_code == 200, r.text
    assert r.json()["license_since_date"] is None      # даты не присылали — и не выдумываем


def test_future_license_date_is_rejected(client, user_factory, frozen_today):
    """Дата выдачи в будущем — очевидная подделка/опечатка, отказ."""
    drv = user_factory("ПраваИзБудущего", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=2026, license_since_date="2026-06-01")
    assert r.status_code == 400, r.text
    assert "будущем" in r.json()["detail"]["ru"]


def test_exact_date_is_stored_and_year_follows_it(client, user_factory, frozen_today):
    """Дата сохраняется в заявке, а год приводится к ней — модератор не видит противоречий."""
    drv = user_factory("ДатаВЗаявке", role=UserRole.driver, taxi_approved=False)
    r = _apply(client, drv, license_since_year=1999, license_since_date="2015-05-20")
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        app = s.exec(select(TaxiApplication)
                     .where(TaxiApplication.user_id == drv["id"])).first()
        assert app.license_since_date == date(2015, 5, 20)
        assert app.license_since_year == 2015     # год подтянулся к дате, а не остался 1999
