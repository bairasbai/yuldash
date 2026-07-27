# -*- coding: utf-8 -*-
"""Деньги доставки «по пути»: цена есть, выдуманной выручки нет (аудит 2026-07-26).

Две находки одного корня — одна и та же цифра означала три разные вещи:
- у доставки «по пути» НЕ БЫЛО ЦЕНЫ вообще: курьер видел маршрут и размер, а за сколько везти —
  нигде. Единственное число в карточке — сервисный сбор платформы (30/60/120 ₽), и приложение
  подписывало его курьеру как «Твой сбор», хотя это не его деньги;
- этот же сбор суммировался в отчёте админа как «собрано» — при том, что платёжного пути у него
  нет: попутчик не курьер, кабинета и долга у него не существует, выставить счёт некому.

Решение: цену называет отправитель (видна ДО принятия, 0 = «по-соседски» — тоже честный ответ),
а сбор платформы за «по пути» не начисляется, пока нет пути оплаты (settings.parcel_fee_enabled).
В отчёте админа три разных числа вместо одного вранья.
"""
import pytest
from sqlmodel import Session

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole
from app.timeutil import utcnow


def _create(client, sender, **overrides):
    body = {
        "from_city": "Сибай", "to_city": "Уфа", "size": "small",
        "description": "Лекарство", "receiver_name": "Гүзәл",
        "receiver_phone": "+79990000001", "rules_accepted": True,
    }
    body.update(overrides)
    return client.post("/parcels", headers=sender["auth"], json=body)


# ============================== цена «по пути» ==============================

def test_sender_sets_price_for_courier(client, user_factory):
    """Отправитель называет сумму — она сохраняется в заявке и видна ему самому."""
    sender = user_factory(name="Отправитель-цена")
    r = _create(client, sender, price_kop=30000)          # 300 ₽ курьеру
    assert r.status_code == 200, r.text
    assert r.json()["price_kop"] == 30000


def test_courier_sees_price_before_taking(client, user_factory):
    """Главное: цена видна ДО принятия. Иначе курьер соглашается вслепую."""
    sender = user_factory(name="Отправитель-цена2")
    pid = _create(client, sender, price_kop=25000).json()["id"]
    courier = user_factory(name="Курьер-смотрит", role=UserRole.driver)
    rows = client.get("/parcels/available", headers=courier["auth"]).json()
    row = next(x for x in rows if x["id"] == pid)
    assert row["price_kop"] == 25000


def test_price_is_optional_and_zero_means_neighbourly(client, user_factory):
    """Ноль — не баг и не «не заполнил»: «завезу по-соседски» это нормальный сценарий села."""
    sender = user_factory(name="Отправитель-даром")
    r = _create(client, sender)
    assert r.status_code == 200, r.text
    assert r.json()["price_kop"] == 0


def test_price_cannot_be_negative(client, user_factory):
    sender = user_factory(name="Отправитель-минус")
    assert _create(client, sender, price_kop=-100).status_code == 422


def test_price_has_a_ceiling(client, user_factory):
    """Потолок 100 000 ₽ — защита от опечатки на два нуля."""
    sender = user_factory(name="Отправитель-лишний-ноль")
    assert _create(client, sender, price_kop=100_000_00 + 1).status_code == 422


# ============================== сбор платформы ==============================

def test_poputka_fee_is_not_charged_by_default(client, user_factory):
    """Пути оплаты нет → и сбора нет. Начислять «в воздух» нельзя."""
    sender = user_factory(name="Отправитель-сбор")
    assert _create(client, sender).json()["fee_kop"] == 0


def test_poputka_fee_returns_when_billing_is_switched_on(client, user_factory, monkeypatch):
    """Тариф никуда не делся: появится путь оплаты — включается одним флагом."""
    monkeypatch.setattr(settings, "parcel_fee_enabled", True)
    sender = user_factory(name="Отправитель-сбор-вкл")
    assert _create(client, sender, size="medium").json()["fee_kop"] == 6000


# ============================== отчёт админа ==============================

def _delivered_parcel(sender_id: int, courier_id: int, dtype: str,
                      commission_kop: int, paid: bool, fee_kop: int = 0) -> int:
    with Session(engine) as s:
        p = M.ParcelDelivery(
            sender_id=sender_id, courier_id=courier_id, from_city="Сибай", to_city="Уфа",
            size="small", receiver_name="Гүзәл", status="delivered", delivered_at=utcnow(),
            delivery_type=dtype, commission_kop=commission_kop, commission_paid=paid,
            fee_kop=fee_kop, delivery_price_kop=30000,
        )
        s.add(p); s.commit(); s.refresh(p)
        return p.id


@pytest.fixture()
def _statement(client, user_factory):
    def _get():
        admin = user_factory(name=f"Админ-отчёт-{utcnow().timestamp()}", role=UserRole.admin)
        r = client.get("/admin/parcels", headers=admin["auth"])
        assert r.status_code == 200, r.text
        return r.json()["statement"]
    return _get


def test_collected_counts_only_money_that_actually_arrived(client, user_factory, _statement):
    """«Собрано» = комиссия, которую курьер РЕАЛЬНО оплатил. Не начисления."""
    before = _statement()["collected_fee_kop"]
    sender = user_factory(name="Отпр-собрано")
    courier = user_factory(name="Курьер-собрано", role=UserRole.driver)
    _delivered_parcel(sender["id"], courier["id"], "courier", commission_kop=5000, paid=True)
    assert _statement()["collected_fee_kop"] == before + 5000


def test_unpaid_commission_is_a_debt_not_income(client, user_factory, _statement):
    """Начислили, но не получили — это долг, и он в своей строке, а не в «собрано»."""
    before = _statement()
    sender = user_factory(name="Отпр-долг")
    courier = user_factory(name="Курьер-долг", role=UserRole.driver)
    _delivered_parcel(sender["id"], courier["id"], "courier", commission_kop=7000, paid=False)
    after = _statement()
    assert after["owed_commission_kop"] == before["owed_commission_kop"] + 7000
    assert after["collected_fee_kop"] == before["collected_fee_kop"]


def test_poputka_fee_never_counts_as_income(client, user_factory, _statement):
    """Ключевая находка: сбор «по пути» — не выручка. Отдельная строка, и она так и называется."""
    before = _statement()
    sender = user_factory(name="Отпр-попутка")
    courier = user_factory(name="Курьер-попутка", role=UserRole.driver)
    _delivered_parcel(sender["id"], courier["id"], "poputka", commission_kop=0, paid=False,
                      fee_kop=3000)
    after = _statement()
    assert after["collected_fee_kop"] == before["collected_fee_kop"]
    assert after["owed_commission_kop"] == before["owed_commission_kop"]
    assert after["unbilled_fee_kop"] == before["unbilled_fee_kop"] + 3000


def test_statement_keeps_delivered_count(client, user_factory, _statement):
    """Счётчик доставленных остался на месте — по нему админ смотрит объём работы."""
    before = _statement()["delivered_count"]
    sender = user_factory(name="Отпр-счёт")
    courier = user_factory(name="Курьер-счёт", role=UserRole.driver)
    _delivered_parcel(sender["id"], courier["id"], "courier", commission_kop=1000, paid=True)
    assert _statement()["delivered_count"] == before + 1
