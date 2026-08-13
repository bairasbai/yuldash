"""Подтверждение готовности принадлежит смене, а не календарю.

Перед выходом на линию таксист подтверждает три вещи: самочувствие, исправность машины,
отсутствие алкоголя. Действовало это подтверждение «на сегодня» — и об обычную ночную работу
правило ломалось с двух сторон (проверено запросом, аудит 2026-08-12, волна 55):

  • вышел в 22:00, подтвердил — в 00:01 подтверждение «протухало», и сервер снимал водителя
    с линии ПОСРЕДИ смены. Подтверждать «самочувствие» в три часа ночи, отработав пять часов,
    это не проверка, а издевательство над правилом;

  • обратная сторона: подтверждение в 00:10 действовало до 23:59 — можно было отработать смену,
    выспаться и выйти вечером на том же самом «сегодня подтвердил».

Теперь подтверждение живёт, пока идёт смена, и кончается вместе с ней. Та же единица, что
у лимита часов (волна 54): усталость и готовность меряются работой, а не сутками.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from sqlmodel import Session

from app import pretrip as pt
from app import workday as wd_mod
from app.config import settings
from app.db import engine
from app.models import PreTripCheck, TaxiWorkDay, UserRole
from app.timeutil import utcnow


@pytest.fixture
def check_required(monkeypatch):
    """На проде правило пока выключено флагом — здесь включаем, иначе гейт всегда молчит."""
    monkeypatch.setattr(settings, "pretrip_check_required", True, raising=False)
    yield


def _confirmed_on(session: Session, driver_id: int, day, ago: timedelta) -> None:
    c = PreTripCheck(driver_id=driver_id, day=day, health_ok=True, car_ok=True, no_alcohol=True)
    c.created_at = utcnow() - ago
    session.add(c)
    session.commit()


def _was_online(session: Session, driver_id: int, day, ago: timedelta, hours: float = 2.0) -> None:
    session.add(TaxiWorkDay(driver_id=driver_id, day=day, seconds_online=int(hours * 3600),
                            last_heartbeat_at=utcnow() - ago))
    session.commit()


def test_ночная_смена_не_прерывается_полуночью(client, user_factory, check_required):
    """Главная история: вышел вечером, работает — в полночь его не выкидывают."""
    drv = user_factory("PretripNight", role=UserRole.driver)
    yesterday = wd_mod.local_day() - timedelta(days=1)
    with Session(engine) as s:
        _confirmed_on(s, drv["id"], yesterday, ago=timedelta(hours=3))
        _was_online(s, drv["id"], yesterday, ago=timedelta(minutes=5))

        assert pt.is_confirmed(s, drv["id"]) is True
        pt.guard_pretrip(s, drv["id"])          # не бросает — смена продолжается


def test_после_отдыха_нужна_новая_самопроверка(client, user_factory, check_required):
    """Обратная сторона: выспался — это новая смена, подтверждай заново.
    Раньше подтверждение «в начале суток» покрывало и вечерний выход."""
    drv = user_factory("PretripRested", role=UserRole.driver)
    today = wd_mod.local_day()
    with Session(engine) as s:
        # Подтвердил ночью, отработал до утра, потом двенадцать часов не выходил на линию.
        _confirmed_on(s, drv["id"], today, ago=timedelta(hours=20))
        _was_online(s, drv["id"], today, ago=timedelta(hours=12))

        assert pt.is_confirmed(s, drv["id"]) is False
        with pytest.raises(Exception):
            pt.guard_pretrip(s, drv["id"])


def test_подтвердил_и_сразу_на_линию(client, user_factory, check_required):
    """Контроль обычного дня: подтвердил — работаешь, никаких лишних вопросов."""
    drv = user_factory("PretripPlain", role=UserRole.driver)
    with Session(engine) as s:
        pt.confirm(s, drv["id"], health_ok=True, car_ok=True, no_alcohol=True)
        assert pt.is_confirmed(s, drv["id"]) is True
        pt.guard_pretrip(s, drv["id"])


def test_обеденный_перерыв_подтверждение_не_сбрасывает(client, user_factory, check_required):
    """Два часа не выходил на линию — это перерыв, а не новая смена."""
    drv = user_factory("PretripBreak", role=UserRole.driver)
    today = wd_mod.local_day()
    with Session(engine) as s:
        _confirmed_on(s, drv["id"], today, ago=timedelta(hours=6))
        _was_online(s, drv["id"], today, ago=timedelta(hours=2))

        assert pt.is_confirmed(s, drv["id"]) is True


def test_не_подтверждал_вовсе_на_линию_нельзя(client, user_factory, check_required):
    """Контроль, что правило вообще работает."""
    drv = user_factory("PretripNone", role=UserRole.driver)
    with Session(engine) as s:
        assert pt.is_confirmed(s, drv["id"]) is False
        with pytest.raises(Exception):
            pt.guard_pretrip(s, drv["id"])


def test_экран_показывает_то_же_что_и_сервер(client, user_factory, check_required):
    """Экран, который показывает «готов», пока сервер отвечает 403, хуже отсутствия экрана."""
    drv = user_factory("PretripScreen", role=UserRole.driver)
    today = wd_mod.local_day()
    with Session(engine) as s:
        _confirmed_on(s, drv["id"], today, ago=timedelta(hours=20))
        _was_online(s, drv["id"], today, ago=timedelta(hours=12))

        assert pt.payload(s, drv["id"])["confirmed"] is False
        assert pt.is_confirmed(s, drv["id"]) is False


def test_разбор_дтп_смотрит_на_конкретный_день(client, user_factory):
    """Вопрос «что водитель заявил в тот день» остаётся вопросом про дату, а не про смену."""
    drv = user_factory("PretripAudit", role=UserRole.driver)
    day = wd_mod.local_day() - timedelta(days=5)
    with Session(engine) as s:
        _confirmed_on(s, drv["id"], day, ago=timedelta(days=5))
        assert pt.is_confirmed(s, drv["id"], day) is True
        assert pt.is_confirmed(s, drv["id"], day - timedelta(days=1)) is False
