# -*- coding: utf-8 -*-
"""Курьер: заработок, блокировка за комиссию, кто и на чём везёт.

Три дыры из разбора полноты (аудит 2026-07-26):
- курьер видел только «должен Юлдашу столько-то» — работа выглядела сплошным долгом,
  хотя у водителя экран заработка есть с самого начала;
- комиссию курьера можно было не платить ВООБЩЕ: блокировки, как у такси, не существовало;
- «стать курьером» = селфи + «легковой/грузовой»: ни ФИО, ни номера машины, ни согласия
  с правилами. Человеку доверяли чужую посылку, зная о нём меньше, чем о попутчике.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    """Режим курьера включён: сам гейт выключения проверяется в test_courier.py."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.send_push", lambda *a, **k: None)


def _make_courier(client, user_factory, name="Курьер"):
    u = user_factory(name)
    with Session(engine) as s:
        s.add(M.CourierApplication(user_id=u["id"], transport="car",
                                   selfie_url="secure/docs/s.jpg", status="approved"))
        s.add(M.CourierProfile(user_id=u["id"], online=False))
        s.commit()
    return u


def _delivered(courier_id: int, sender_id: int, price_kop: int, commission_kop: int, paid: bool = False):
    with Session(engine) as s:
        p = M.ParcelDelivery(
            sender_id=sender_id, courier_id=courier_id, from_city="Баймак", to_city="Сибай",
            size="s", receiver_name="Алыусы", receiver_phone="+79990000000",
            delivery_type="courier", status="delivered", delivered_at=utcnow(),
            delivery_price_kop=price_kop, commission_kop=commission_kop, commission_paid=paid,
        )
        s.add(p); s.commit(); s.refresh(p)
        return p.id


# ============================== заработок ==============================

def test_courier_earnings_shows_net_and_commission(client, user_factory):
    """«Чистыми» — это цена минус комиссия: ровно то, что человек оставляет себе."""
    cur = _make_courier(client, user_factory, "Заработок")
    sender = user_factory("Ебәреүсе")
    _delivered(cur["id"], sender["id"], price_kop=20000, commission_kop=1600)
    _delivered(cur["id"], sender["id"], price_kop=30000, commission_kop=2400)
    r = client.get("/courier/earnings?period=all", headers=cur["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["deliveries"] == 2
    assert body["commission_kop"] == 4000
    assert body["net_kop"] == 50000 - 4000
    assert len(body["by_day"]) >= 1


def test_courier_earnings_only_own_deliveries(client, user_factory):
    mine = _make_courier(client, user_factory, "Мои")
    other = _make_courier(client, user_factory, "Чужие")
    sender = user_factory("Ебәреүсе2")
    _delivered(other["id"], sender["id"], price_kop=99900, commission_kop=9990)
    r = client.get("/courier/earnings?period=all", headers=mine["auth"])
    assert r.json()["deliveries"] == 0 and r.json()["net_kop"] == 0


def test_courier_earnings_requires_approved_courier(client, user_factory):
    u = user_factory("Не курьер")
    assert client.get("/courier/earnings", headers=u["auth"]).status_code == 403


# ============================== блокировка за комиссию ==============================

def test_debt_over_threshold_blocks_going_online(client, user_factory, monkeypatch):
    """У такси блокировка была с начала, у курьера — не было вообще."""
    monkeypatch.setattr(settings, "courier_debt_block_threshold_kop", 5000)
    cur = _make_courier(client, user_factory, "Должник")
    sender = user_factory("Ебәреүсе3")
    _delivered(cur["id"], sender["id"], price_kop=60000, commission_kop=6000, paid=False)
    r = client.post("/courier/online", headers=cur["auth"], json={"online": True})
    assert r.status_code == 403
    assert "60" in r.json()["detail"]["ru"]      # в тексте видно, сколько именно платить


def test_debt_blocks_taking_new_order(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_debt_block_threshold_kop", 5000)
    cur = _make_courier(client, user_factory, "Должник2")
    sender = user_factory("Ебәреүсе4")
    _delivered(cur["id"], sender["id"], price_kop=60000, commission_kop=6000, paid=False)
    with Session(engine) as s:
        fresh = M.ParcelDelivery(
            sender_id=sender["id"], from_city="Баймак", to_city="Сибай", size="s",
            receiver_name="Алыусы", receiver_phone="+79990000001",
            delivery_type="courier", status="created",
        )
        s.add(fresh); s.commit(); s.refresh(fresh)
        fresh_id = fresh.id
    r = client.post(f"/parcels/{fresh_id}/accept", headers=cur["auth"], json={})
    assert r.status_code == 403


def test_paid_commission_does_not_block(client, user_factory, monkeypatch):
    """Рассчитался — работай. Блокировка не должна «залипать» после оплаты."""
    monkeypatch.setattr(settings, "courier_debt_block_threshold_kop", 5000)
    cur = _make_courier(client, user_factory, "Оплатил")
    sender = user_factory("Ебәреүсе5")
    _delivered(cur["id"], sender["id"], price_kop=60000, commission_kop=6000, paid=True)
    r = client.post("/courier/online", headers=cur["auth"], json={"online": True})
    assert r.status_code == 200, r.text


def test_small_debt_does_not_block(client, user_factory):
    """Порог существует, чтобы не блокировать за 20 ₽ — это было бы мелочно."""
    cur = _make_courier(client, user_factory, "Мелкий долг")
    sender = user_factory("Ебәреүсе6")
    _delivered(cur["id"], sender["id"], price_kop=20000, commission_kop=1600, paid=False)
    r = client.post("/courier/online", headers=cur["auth"], json={"online": True})
    assert r.status_code == 200, r.text


# ============================== кто и на чём везёт ==============================

def test_apply_stores_identity_fields(client, user_factory):
    u = user_factory("Новый курьер")
    r = client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "selfie_url": "secure/docs/x.jpg",
        "full_name": "Ахметов Ильдар", "car_plate": "х123ух102", "rules_accepted": True,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["full_name"] == "Ахметов Ильдар"
    assert body["car_plate"] == "Х123УХ102"        # приводим к верхнему регистру
    assert body["rules_accepted"] is True


def test_apply_without_identity_still_works_while_flag_off(client, user_factory):
    """Старое приложение этих полей не шлёт — закрывать ему регистрацию нельзя."""
    assert settings.courier_identity_required is False
    u = user_factory("Старое приложение")
    r = client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "selfie_url": "secure/docs/y.jpg",
    })
    assert r.status_code == 200, r.text
    assert r.json()["full_name"] == "" and r.json()["rules_accepted"] is False


def test_identity_required_when_flag_on(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_identity_required", True)
    u = user_factory("Строгий режим")
    r = client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "selfie_url": "secure/docs/z.jpg",
    })
    assert r.status_code == 422
    assert "фамилию" in r.json()["detail"]["ru"]


def test_one_word_name_rejected_even_with_flag_off(client, user_factory):
    """Прислали одним словом — это опечатка, а не старый клиент: говорим сразу."""
    u = user_factory("Односложный")
    r = client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "selfie_url": "secure/docs/w.jpg", "full_name": "Ильдар",
    })
    assert r.status_code == 422


def test_rules_acceptance_is_recorded_with_timestamp(client, user_factory):
    u = user_factory("Согласился")
    client.post("/courier/apply", headers=u["auth"], json={
        "transport": "cargo", "selfie_url": "secure/docs/q.jpg",
        "full_name": "Ишбулатов Айрат", "car_plate": "А001АА102", "rules_accepted": True,
    })
    with Session(engine) as s:
        app = s.exec(
            M.CourierApplication.__table__.select().where(M.CourierApplication.user_id == u["id"])
        ).first()
    assert app.rules_accepted is True and app.rules_accepted_at is not None
