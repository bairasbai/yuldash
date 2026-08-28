"""Цена предзаказа: показываем то, по чему поедем (решение Александра, 2026-08-28).

Что было. Экран считал цену на «сейчас», а предзаказ оформлялся по цене на ВРЕМЯ ПОДАЧИ.
Заказ на пять утра, сделанный днём, показывал дневную цену и уезжал по ночной. Человек
запоминал увиденное число и утром получал другое — формально мы предупреждали «цену уточним
при подаче», по-человечески это выглядело как подмена.

Что стало. Клиент передаёт время подачи в расчёт цены, и оценка считается на этот момент.
Заморозка на предзаказ НЕ распространяется: его цену сервер всё равно пересчитает при подаче,
и обещать неизменность значит обещать то, чего мы не держим.

Плюс здесь же — обещание потолка («больше 420 ₽ не возьмём»), которое написано и ВЫКЛЮЧЕНО
до запуска: пока нет данных, как часто цена ночью в метель улетает, обещать доплату нельзя.
"""
from datetime import timedelta

import pytest

from app import instant_service as isv
from app.config import settings
from app.timeutil import utcnow

from test_instant import _order_body, fake_redis  # noqa: F401 — фикстура реэкспортом


def _через(часов: int) -> str:
    return (utcnow() + timedelta(hours=часов)).isoformat()


# ============================ 1. Оценка на время подачи ============================
def test_estimate_accepts_the_pickup_time(client, user_factory):
    """Оценка принимает время подачи и не падает."""
    pax = user_factory("ПредзаказЦена")
    body = dict(_order_body(), scheduled_at=_через(3))
    r = client.post("/instant/estimate", headers=pax["auth"], json=body)
    assert r.status_code == 200, r.text
    assert r.json()["price"] > 0


def test_night_preorder_is_priced_by_night(client, user_factory, monkeypatch):
    """Заказ на ночь считается по ночной ставке, даже если оформлен днём.

    Это и есть та самая подмена: человек видел дневное число, а уезжал по ночному.

    Сравниваем ДВА предзаказа — на местный полдень и на местную полночь. Сравнивать
    «сейчас» с «ночью» нельзя: прогон, запущенный вечером, сам оказывается ночным,
    и проверка молча превращается в сравнение двух одинаковых чисел (поймано 28.08).
    """
    from sqlmodel import Session, select

    from app.db import engine
    from app.models import Tariff
    monkeypatch.setattr(settings, "night_k_default", 1.15)
    # Спрос держим ровным: в общей тестовой базе полно чужих заказов, и надбавка за спрос
    # может упереться в общий потолок ×1,5 — тогда ночной прибавке в нём уже нет места.
    monkeypatch.setattr(isv, "surge_k_for", lambda *a, **k: 1.0)
    with Session(engine) as s:
        isv._seed_night(s)
        t = s.exec(select(Tariff).where(Tariff.zone == "city",
                                        Tariff.category == "standard")).first()
        assert t is not None and t.night_k > 1.0, "ночная ставка не проставилась в тариф"
    try:
        pax = user_factory("ПредзаказНочь")
        днём = _оценка_на(client, pax, местный_час=12)
        ночью = _оценка_на(client, pax, местный_час=0)
        assert днём["night_k"] == 1.0, "в полдень посчитали по ночной ставке"
        assert ночью["night_k"] > 1.0, "в полночь ночная ставка не применилась"
        assert ночью["price"] > днём["price"], "ночной предзаказ считается по дневной ставке"
    finally:
        with Session(engine) as s:
            for row in s.exec(select(Tariff)).all():
                row.night_k = 1.0
                s.add(row)
            s.commit()


def _оценка_на(client, pax, местный_час: int) -> dict:
    """Оценка предзаказа на ЗАВТРА в указанный местный (уфимский) час."""
    сдвиг = int(settings.local_tz_offset_hours)
    когда = utcnow().replace(minute=30, second=0, microsecond=0)
    когда = когда.replace(hour=(местный_час - сдвиг) % 24) + timedelta(days=1)
    r = client.post("/instant/estimate", headers=pax["auth"],
                    json=dict(_order_body(), scheduled_at=когда.isoformat()))
    assert r.status_code == 200, r.text
    return r.json()


def test_preorder_price_is_not_frozen(client, user_factory, fake_redis):
    """Предзаказ не замораживаем: его цену сервер пересчитает в момент подачи."""
    pax = user_factory("ПредзаказБезЗаморозки")
    сейчас = client.post("/instant/estimate", headers=pax["auth"],
                         json=_order_body()).json()
    потом = client.post("/instant/estimate", headers=pax["auth"],
                        json=dict(_order_body(), scheduled_at=_через(5))).json()
    assert сейчас["price_locked_sec"] == settings.price_freeze_sec
    assert потом["price_locked_sec"] == 0, "предзаказу обещана заморозка, которой не будет"


def test_bad_pickup_time_is_refused(client, user_factory):
    """Время в прошлом — та же проверка, что и при оформлении предзаказа."""
    pax = user_factory("ПредзаказПрошлое")
    прошлое = (utcnow() - timedelta(hours=1)).isoformat()
    r = client.post("/instant/estimate", headers=pax["auth"],
                    json=dict(_order_body(), scheduled_at=прошлое))
    assert r.status_code == 422


# ============================ 2. Обещание потолка (выключено) ============================
def test_price_guarantee_is_off_by_default(client):
    """По умолчанию обещания нет: пока не знаем, как часто цена улетает, обещать нельзя."""
    assert settings.scheduled_price_guarantee_percent == 0.0
    assert isv.scheduled_price_cap_rub(350) is None


def test_price_guarantee_caps_the_ride_when_enabled(client, monkeypatch):
    """Включённое обещание считает потолок от показанного числа."""
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 20.0)
    assert isv.scheduled_price_cap_rub(350) == 420


def test_price_guarantee_ignores_a_missing_price(client, monkeypatch):
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 20.0)
    assert isv.scheduled_price_cap_rub(0) is None


def test_guarantee_never_cuts_the_driver_compensations(client, monkeypatch):
    """Потолок режет цену ПОЕЗДКИ, а не бензин водителя.

    Обещание платит платформа из своей комиссии — по общему правилу «скидки платит платформа,
    а не водитель». Урезать компенсацию значило бы оплатить наше обещание его бензином.
    """
    monkeypatch.setattr(settings, "scheduled_price_guarantee_percent", 10.0)
    показано, компенсации = 300, 200
    потолок = isv.scheduled_price_cap_rub(показано)
    поездка = max(потолок - компенсации, 0)
    assert поездка + компенсации <= потолок
    assert компенсации == 200, "компенсация водителю осталась нетронутой"
