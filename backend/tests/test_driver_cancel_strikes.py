# -*- coding: utf-8 -*-
"""Отмена заказа водителем перестала быть бесплатной (разбор №2, 2026-08-03).

Было: штрафные баллы считались ТОЛЬКО пассажиру. Водитель мог принимать заказ, смотреть адрес
и отменять — сколько угодно раз, без единого следствия. Пассажир после каждой такой отмены
уходил в поиск с нуля: терял очередь, время и — в райцентре ночью — единственную машину.

Стало: несколько брошенных ПРИНЯТЫХ заказов за окно → пауза офферов. Цифры мягче пассажирских
осознанно: в райцентре свободных машин две-три, и сутки без водителя бьют по пассажирам сильнее,
чем сам проступок. Три часа — это «остынь», а не «уволен».

Отдельно проверяем, что честное поведение НЕ наказывается: отказ от оффера (водитель просто
не взял) и «пассажир не вышел» (доехал, отждал, отметил) страйками не считаются. Иначе водители
перестанут отказываться и отмечать честно — просто уйдут в офлайн, и это хуже для всех.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, OfferDecline, User, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _taxi_on():
    prev = settings.taxi_enabled
    settings.taxi_enabled = True
    yield
    settings.taxi_enabled = prev


def _seed_passenger(s: Session, driver_id: int) -> int:
    """Настоящий пассажир для посева — один на водителя.

    Раньше тут стояло арифметическое `driver_id + 100_000`: номер, которому не соответствует
    ни один пользователь. На SQLite это проходило (проверка связей там выключена по умолчанию),
    а на Postgres — падало: сослаться на несуществующего человека нельзя. Тест «зелёный дома,
    красный в CI» — худший вид, потому что доверие к прогону теряется целиком.
    """
    marker = f"seed-pass-{driver_id}"
    u = s.exec(select(User).where(User.phone == marker)).first()
    if u is None:
        u = User(phone=marker, name="ПассажирПосев", telegram_id=marker,
                 verified=True, role=UserRole.passenger)
        s.add(u)
        s.commit()
        s.refresh(u)
    return u.id


def _cancelled_by_driver(driver_id: int, when, *, accepted=True, no_show=False) -> int:
    """Готовая отменённая поездка в истории — прямой посев, без прогона всего матчинга."""
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=_seed_passenger(s, driver_id), driver_id=driver_id, status=S.cancelled,
            from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7,
            cancelled_at=when, cancel_by="driver", no_show=no_show,
            accepted_at=(when - timedelta(minutes=5)) if accepted else None,
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _driver_id(user_factory) -> int:
    return user_factory(name="ОтменщикВодитель", role=UserRole.driver)["id"]


def test_a_couple_of_cancels_do_not_pause(client, user_factory):
    """Один-два раза бывает у каждого: сломался, попал в аварию, ошибся адресом. Не наказываем."""
    did = _driver_id(user_factory)
    with Session(engine) as s:
        for i in range(settings.driver_cancel_limit - 1):
            _cancelled_by_driver(did, utcnow() - timedelta(hours=i + 1))
        assert isv.driver_pause_until(s, did) is None


def test_repeated_cancels_pause_offers(client, user_factory):
    did = _driver_id(user_factory)
    for i in range(settings.driver_cancel_limit):
        _cancelled_by_driver(did, utcnow() - timedelta(minutes=i + 1))
    with Session(engine) as s:
        until = isv.driver_pause_until(s, did)
    assert until is not None
    assert until <= utcnow() + timedelta(hours=settings.driver_cancel_pause_hours)


def test_no_show_is_not_a_strike(client, user_factory):
    """«Пассажир не вышел» — водитель как раз всё сделал правильно. Наказывать за честную
    отметку значит учить его молча уезжать."""
    did = _driver_id(user_factory)
    for i in range(settings.driver_cancel_limit + 1):
        _cancelled_by_driver(did, utcnow() - timedelta(minutes=i + 1), no_show=True)
    with Session(engine) as s:
        assert isv.driver_pause_until(s, did) is None


def test_declined_offer_is_not_a_strike(client, user_factory):
    """Отказ от оффера пассажиру ничего не стоит — заказ тут же уходит следующему."""
    did = _driver_id(user_factory)
    for i in range(settings.driver_cancel_limit + 1):
        _cancelled_by_driver(did, utcnow() - timedelta(minutes=i + 1), accepted=False)
    with Session(engine) as s:
        assert isv.driver_pause_until(s, did) is None


def test_old_cancels_fall_out_of_the_window(client, user_factory):
    """Пауза не вечная: провинился месяц назад — сегодня работаешь как все."""
    did = _driver_id(user_factory)
    long_ago = utcnow() - timedelta(days=settings.driver_cancel_window_days + 3)
    for i in range(settings.driver_cancel_limit + 2):
        _cancelled_by_driver(did, long_ago - timedelta(minutes=i))
    with Session(engine) as s:
        assert isv.driver_pause_until(s, did) is None


def test_paused_driver_is_not_offered_orders(client, user_factory):
    """Главное следствие: пауза должна реально убирать водителя из подбора."""
    did = _driver_id(user_factory)
    for i in range(settings.driver_cancel_limit):
        _cancelled_by_driver(did, utcnow() - timedelta(minutes=i + 1))
    with Session(engine) as s:
        order = InstantOrder(passenger_id=_seed_passenger(s, did), status=S.searching,
                             from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7)
        assert isv.eligible(s, [did], order) == []


# ----------------------- Причина отказа: диагностика, не наказание -----------------------

def _offered_to(driver_id: int) -> int:
    """Заказ, уже предложенный этому водителю. Сеем напрямую: полный матчинг требует Redis,
    а проверяем мы не подбор, а то, что причина отказа записывается."""
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=_seed_passenger(s, driver_id), status=S.offered,
            from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7,
            current_offer_driver_id=driver_id, offer_expires_at=utcnow() + timedelta(minutes=1),
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def test_decline_reason_is_recorded(client, user_factory):
    """Без причины видно только «не берут». С причиной видно, ЧТО чинить: далеко, дёшево,
    не по пути. Это и есть весь смысл — диагностика матчинга."""
    drv = user_factory(name="ОтказВодитель", role=UserRole.driver)
    oid = _offered_to(drv["id"])
    r = client.post(f"/instant/orders/{oid}/decline", headers=drv["auth"], json={"reason": "far"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        rows = s.exec(select(OfferDecline).where(OfferDecline.order_id == oid)).all()
    assert [x.reason for x in rows] == ["far"]


def test_decline_without_reason_still_works(client, user_factory):
    """Старый клиент тела не шлёт. Отказ обязан пройти — статистика не важнее отказа."""
    drv = user_factory(name="ОтказСтарый", role=UserRole.driver)
    oid = _offered_to(drv["id"])
    assert client.post(f"/instant/orders/{oid}/decline", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        assert s.exec(select(OfferDecline).where(OfferDecline.order_id == oid)).all() == []


def test_unknown_reason_becomes_other(client, user_factory):
    """Мусор в поле не должен мешать водителю отказаться — складываем в «другое»."""
    drv = user_factory(name="ОтказМусор", role=UserRole.driver)
    oid = _offered_to(drv["id"])
    r = client.post(f"/instant/orders/{oid}/decline", headers=drv["auth"],
                    json={"reason": "потомучто"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        rows = s.exec(select(OfferDecline).where(OfferDecline.order_id == oid)).all()
    assert [x.reason for x in rows] == ["other"]


def test_decline_reason_values_are_normalised():
    """Незнакомое значение не должно ронять отказ: отказаться важнее, чем классифицировать."""
    from app.routers.instant import _DECLINE_REASONS
    assert {"far", "cheap", "direction", "busy", "break", "other"} == _DECLINE_REASONS


def test_decline_log_is_wiped_with_the_account(client, user_factory):
    """Новая таблица с FK на user обязана исчезать вместе с аккаунтом (урок из lessons.md)."""
    from app import account as acct
    did = _driver_id(user_factory)
    oid = _cancelled_by_driver(did, utcnow())
    with Session(engine) as s:
        s.add(OfferDecline(order_id=oid, driver_id=did, reason="far"))
        s.commit()
        acct.delete_user_account(s, s.get(User, did))
        s.commit()
        assert s.exec(select(OfferDecline).where(OfferDecline.driver_id == did)).all() == []
