# -*- coding: utf-8 -*-
"""Запертая дверь всегда имеет ключ, а тумблер не врёт (аудит сценариев 2026-08-30, P0).

Два места, где человек упирался в стену и не понимал почему.

ПЕРВОЕ — ЗАМКНУТЫЙ КРУГ У КУРЬЕРА. Блокировка по фотоконтролю встала на общий гейт, а
кабинет курьера жил за тем же гейтом. Просрочил фото → кабинет закрыт → а войти на экран
фотоконтроля можно только из кабинета. Выхода не было вообще, и построили его мы сами
накануне. Правило теперь: блокировка закрывает РАБОТУ, но никогда не закрывает дверь,
через которую блокировку снимают, и не мешает отдать деньги.

ВТОРОЕ — ТУМБЛЕР «Я НА ЛИНИИ», КОТОРЫЙ ВРАЛ. Он не проверял ничего: человек нажимал, видел
зелёный и 200 OK, а линия была закрыта долгом, отдыхом, документами или паузой. Узнавал он
об этом по тому, что заказы не приходят. Теперь тумблер спрашивает те же правила, что и
линия, и отвечает словами, что именно чинить.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import carphoto as cp
from app.config import settings
from app.db import engine
from app.models import (CarPhotoCheck, CourierApplication, DriverProfile, TaxiApplication,
                        UserRole)
from app.timeutil import utcnow


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


@pytest.fixture
def режимы_включены():
    было = (settings.courier_enabled, settings.car_photo_taxi_enabled,
            settings.car_photo_courier_enabled)
    settings.courier_enabled = True
    settings.car_photo_taxi_enabled = True
    settings.car_photo_courier_enabled = True
    yield
    (settings.courier_enabled, settings.car_photo_taxi_enabled,
     settings.car_photo_courier_enabled) = было


def _курьер_с_просрочкой(user_factory, name: str):
    """Одобренный курьер, у которого фотоконтроль просрочен больше недели → он заблокирован."""
    u = user_factory(name, taxi_approved=False)
    with _сессия() as s:
        s.add(CourierApplication(user_id=u["id"], status="approved", reviewed_at=utcnow()))
        s.commit()
        проверка = cp.ensure(s, u["id"], cp.COURIER)
        проверка.due_at = utcnow() - timedelta(days=10)
        s.add(проверка)
        s.commit()
        assert cp.blocked(s, u["id"], cp.COURIER) is True
    return u


# ==================== 1. Замкнутый круг у курьера ====================
def test_a_blocked_courier_can_still_open_his_cabinet(client, user_factory, режимы_включены):
    """Кабинет открыт даже при блокировке — это единственная дверь к экрану фотоконтроля."""
    к = _курьер_с_просрочкой(user_factory, "КурьерВКруге")
    ответ = client.get("/courier/me", headers=к["auth"])
    assert ответ.status_code == 200, ответ.text


def test_a_blocked_courier_can_still_pay_his_debt(client, user_factory, режимы_включены):
    """Отдать деньги можно всегда. Блокировка, мешающая заплатить, — ловушка, а не мера."""
    к = _курьер_с_просрочкой(user_factory, "КурьерСДолгом")
    ответ = client.post("/courier/pay-commission", headers=к["auth"])
    # Долга нет — сервер честно об этом скажет; важно, что это НЕ отказ по фотоконтролю.
    assert ответ.status_code != 403, ответ.text


def test_a_blocked_courier_can_still_see_his_money(client, user_factory, режимы_включены):
    """Заработок и приоритет — чтение о себе, блокировать их не за что."""
    к = _курьер_с_просрочкой(user_factory, "КурьерСмотритДеньги")
    assert client.get("/courier/earnings", headers=к["auth"]).status_code == 200
    assert client.get("/courier/priority", headers=к["auth"]).status_code == 200


def test_but_the_blocked_courier_still_cannot_work(client, user_factory, режимы_включены):
    """Работа при этом закрыта — иначе правило потеряло бы смысл."""
    к = _курьер_с_просрочкой(user_factory, "КурьерНеРаботает")
    ответ = client.post("/courier/online", headers=к["auth"], json={"zone": "region"})
    assert ответ.status_code == 403, ответ.text
    текст = ответ.json()["detail"]
    текст = текст.get("ru", "") if isinstance(текст, dict) else str(текст)
    assert "фото" in текст.lower(), f"курьеру не сказали, что делать: {текст}"


def test_the_photo_screen_is_reachable_while_blocked(client, user_factory, режимы_включены):
    """Экран фотоконтроля отвечает заблокированному — иначе круг остался бы замкнут."""
    к = _курьер_с_просрочкой(user_factory, "КурьерИдётФотать")
    ответ = client.get("/carphoto?mode=courier", headers=к["auth"])
    assert ответ.status_code == 200, ответ.text
    assert ответ.json()["required"] is True


# ==================== 2. Тумблер «Я на линии» ====================
def _таксист(user_factory, name: str):
    u = user_factory(name, role=UserRole.driver)
    with _сессия() as s:
        if s.exec(select(DriverProfile).where(DriverProfile.user_id == u["id"])).first() is None:
            s.add(DriverProfile(user_id=u["id"], online=False))
            s.commit()
    return u


def test_the_switch_refuses_when_the_line_is_closed(client, user_factory, режимы_включены):
    """Документы просрочены → тумблер отказывает СРАЗУ и говорит, что чинить.

    Раньше он загорался зелёным, а человек сидел и ждал заказов, которых не будет.
    """
    d = _таксист(user_factory, "ТумблерСДокументами")
    with _сессия() as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == d["id"])).first()
        app.docs_expired = True
        s.add(app)
        s.commit()

    ответ = client.post("/driver/online", headers=d["auth"], json={"online": True})
    assert ответ.status_code == 403, ответ.text
    текст = ответ.json()["detail"]
    текст = текст.get("ru", "") if isinstance(текст, dict) else str(текст)
    assert "документ" in текст.lower(), f"не сказали, что чинить: {текст}"

    with _сессия() as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        assert prof.online is False, "тумблер загорелся, хотя линия закрыта"


def test_a_healthy_driver_still_goes_online(client, user_factory, режимы_включены):
    """У здорового водителя тумблер работает как работал — правило не должно ломать рабочих."""
    d = _таксист(user_factory, "ТумблерЗдоровый")
    ответ = client.post("/driver/online", headers=d["auth"], json={"online": True})
    assert ответ.status_code == 200, ответ.text
    assert ответ.json()["online"] is True


def test_going_offline_is_never_blocked(client, user_factory, режимы_включены):
    """Уйти с линии можно всегда, что бы ни случилось: запирать человека НА работе нельзя."""
    d = _таксист(user_factory, "ТумблерУходит")
    client.post("/driver/online", headers=d["auth"], json={"online": True})
    with _сессия() as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == d["id"])).first()
        app.docs_expired = True
        s.add(app)
        s.commit()
    ответ = client.post("/driver/online", headers=d["auth"], json={"online": False})
    assert ответ.status_code == 200, ответ.text
    assert ответ.json()["online"] is False


def test_a_poputka_driver_is_not_touched(client, user_factory, режимы_включены):
    """У водителя попутки заявки таксиста нет — для него тумблер значит другое, не ломаем."""
    d = user_factory("ТолькоПопутка", role=UserRole.driver, taxi_approved=False)
    with _сессия() as s:
        s.add(DriverProfile(user_id=d["id"], online=False))
        s.commit()
    ответ = client.post("/driver/online", headers=d["auth"], json={"online": True})
    assert ответ.status_code == 200, ответ.text
