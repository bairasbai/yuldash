"""Наказания курьера доходят до всех дверей, включая рассылку.

Тем же вопросом, что в волнах 59–60 («сколько всего наказаний и все ли двери про них знают»),
прошёлся по курьеру. У него три наказания: пауза «Справедливости» (разбор жалобы), мягкая
пауза по качеству (просел рейтинг) и долг по комиссии.

Нашлось два пробела (проверено запросами, аудит 2026-08-13, волна 61):

  • отстранённый разбором СПОКОЙНО ВЫХОДИЛ НА ЛИНИЮ и открывал витрину заказов. У таксиста
    эта дверь закрыта с самого начала, у курьера её просто не донесли. Взять заказ он бы
    не смог, но правило «наказанный не работает» держалось на честном слове;

  • рассылка «новая доставка рядом» звала ВСЕХ, кто числится на линии, не спрашивая про
    наказания вообще. Это ровно то, что волна 60 чинила в подборе такси: гейты на кнопках
    есть, а тот, кто раздаёт работу, про них не знает.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (CourierApplication, CourierProfile, ParcelDelivery, SafetyProfile,
                        UserRole)
from app.routers.parcels import _notify_couriers_new_parcel
from app.timeutil import utcnow
from conftest import upload_doc


@pytest.fixture
def courier_mode(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    yield


def _approved_courier(client, user_factory, tag):
    u = user_factory(tag, role=UserRole.passenger)
    selfie = upload_doc(client, u["auth"])
    assert client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "full_name": "Курьер Курьеров", "car_plate": "А111АА102",
        "selfie_url": selfie, "rules_accepted": True,
    }).status_code == 200
    with Session(engine) as s:
        row = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == u["id"])).first()
        row.status = "approved"
        row.reviewed_at = utcnow()
        s.add(row)
        s.commit()
    return u


def _suspend(user_id: int) -> None:
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=7)))
        s.commit()


def _on_line(user_id: int, **fields) -> None:
    """Курьер числится на линии — как если бы вышел до наказания."""
    with Session(engine) as s:
        prof = s.exec(select(CourierProfile).where(CourierProfile.user_id == user_id)).first()
        if prof is None:
            prof = CourierProfile(user_id=user_id)
        prof.online = True
        prof.zone = "region"
        prof.work_regions = True
        prof.work_intercity = True
        for k, v in fields.items():
            setattr(prof, k, v)
        s.add(prof)
        s.commit()


def _courier_parcel(session: Session, sender_id: int) -> ParcelDelivery:
    p = ParcelDelivery(sender_id=sender_id, from_city="Баймак", to_city="Сибай", size="small",
                       description="лекарство", receiver_name="Гөлнара",
                       receiver_phone="+79995550001", status="created",
                       delivery_type="courier", rules_accepted=True)
    session.add(p)
    session.commit()
    session.refresh(p)
    return p


def test_отстранённый_курьер_на_линию_не_выходит(client, user_factory, courier_mode):
    cour = _approved_courier(client, user_factory, "CourSuspLine")
    _suspend(cour["id"])

    r = client.post("/courier/online", headers=cour["auth"], json={"zone": "city"})
    assert r.status_code == 403


def test_отстранённому_курьеру_витрина_закрыта(client, user_factory, courier_mode):
    cour = _approved_courier(client, user_factory, "CourSuspShop")
    _suspend(cour["id"])

    assert client.get("/courier/available", headers=cour["auth"]).status_code == 403


def test_рассылка_обходит_наказанных(client, user_factory, courier_mode, monkeypatch):
    """Главное: тот, кто раздаёт работу, обязан знать про наказания — как подбор такси."""
    got: list = []
    # Ловим именно уведомление, а не отправку в FCM: без зарегистрированного устройства
    # push не уходит, и тест был бы зелёным на пустой рассылке.
    monkeypatch.setattr("app.routers.parcels.push_notification",
                        lambda session, uid, *a, **k: got.append(uid))
    # Зона курьера — отдельное правило и отдельная волна; здесь проверяем ТОЛЬКО наказания,
    # поэтому географию открываем всем.
    monkeypatch.setattr("app.geo.zone_allows", lambda *a, **k: True)

    sender = user_factory("CourNotifySender", role=UserRole.passenger)
    honest = _approved_courier(client, user_factory, "CourHonest")
    suspended = _approved_courier(client, user_factory, "CourSuspended")
    rated_out = _approved_courier(client, user_factory, "CourPaused")

    _on_line(honest["id"])
    _on_line(suspended["id"])
    _on_line(rated_out["id"], paused_until=utcnow() + timedelta(days=2))
    _suspend(suspended["id"])

    with Session(engine) as s:
        parcel = _courier_parcel(s, sender["id"])
        _notify_couriers_new_parcel(s, parcel)

    assert honest["id"] in got                  # честный курьер узнаёт о заказе
    assert suspended["id"] not in got           # отстранённый — нет
    assert rated_out["id"] not in got           # и на паузе по качеству — тоже нет


def test_честный_курьер_работает_как_прежде(client, user_factory, courier_mode):
    """Страховка от перестраховки: без наказаний обе двери открыты."""
    cour = _approved_courier(client, user_factory, "CourFine")

    assert client.post("/courier/online", headers=cour["auth"],
                       json={"zone": "city"}).status_code == 200
    assert client.get("/courier/available", headers=cour["auth"]).status_code == 200


def test_наказание_кончилось_курьер_вернулся(client, user_factory, courier_mode):
    """Пауза не приговор: срок вышел — снова на линию."""
    cour = _approved_courier(client, user_factory, "CourBack")
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=cour["id"], suspended_until=utcnow() - timedelta(hours=1)))
        s.commit()

    assert client.post("/courier/online", headers=cour["auth"],
                       json={"zone": "city"}).status_code == 200
