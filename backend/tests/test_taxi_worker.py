# -*- coding: utf-8 -*-
"""Фоновый воркер такси (app/taxi_worker.py) — три блокера аудита 2026-07-26.

Проверяем ровно те сценарии, из-за которых людей теряли:
- бабушка заказала машину на 6:00 → предзаказ должен запуститься САМ (раньше ждал, пока
  пассажир вручную откроет экран);
- у водителя сел телефон в дороге → заказ не должен висеть вечно, блокируя обоих;
- «рядом никого» в райцентре ночью → пассажир жмёт «подождать», поиск повторяется сам;
- оффер протух при закрытом приложении → подбор всё равно двигается дальше.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import models as M
from app import taxi_worker as tw
from app.db import engine
from app.models import InstantOrderStatus as S
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _no_push(monkeypatch):
    """Пуши в тестах глушим: проверяем состояние заказов, а не доставку уведомлений."""
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)


def _order(passenger_id, **kw):
    """Заказ с нужными полями. Координаты — Баймак, чтобы гейт города не мешал."""
    base = dict(passenger_id=passenger_id, from_lat=52.59, from_lng=58.31,
                to_lat=52.60, to_lng=58.32, from_text="Баймак", to_text="Сибай",
                price_estimate=200)
    base.update(kw)
    with Session(engine) as s:
        o = M.InstantOrder(**base)
        s.add(o); s.commit(); s.refresh(o)
        return o.id


def _get(order_id: int) -> M.InstantOrder:
    with Session(engine) as s:
        return s.get(M.InstantOrder, order_id)


# ============================ 1. Предзаказы ============================
def test_scheduled_order_activates_by_itself(client, user_factory, monkeypatch):
    """Бабушка заказала на 6:00 → воркер запускает поиск сам, без открытия приложения."""
    pax = user_factory("SchedPax")
    oid = _order(pax["id"], status=S.scheduled, scheduled_at=utcnow() - timedelta(minutes=1))
    # Водителей нет — важен сам факт запуска поиска (статус уходит из scheduled).
    with Session(engine) as s:
        activated = tw.activate_due_scheduled(s)
    assert oid in activated
    assert _get(oid).status != S.scheduled


def test_future_scheduled_order_untouched(client, user_factory):
    """Предзаказ на завтра трогать рано — иначе машина приедет на сутки раньше."""
    pax = user_factory("SchedPax2")
    oid = _order(pax["id"], status=S.scheduled, scheduled_at=utcnow() + timedelta(hours=5))
    with Session(engine) as s:
        assert oid not in tw.activate_due_scheduled(s)
    assert _get(oid).status == S.scheduled


# ============================ 2. Зависшие заказы ============================
def test_stuck_order_is_closed_and_unblocks_both(client, user_factory):
    """У водителя сел телефон: заказ «в пути» 7 часов. Раньше висел вечно — отменить мог
    только водитель, а пассажир не мог заказать другое такси."""
    pax = user_factory("StuckPax")
    drv = user_factory("StuckDrv", role=M.UserRole.driver)
    old = utcnow() - timedelta(hours=7)
    oid = _order(pax["id"], driver_id=drv["id"], status=S.onboard,
                 created_at=old, accepted_at=old, onboard_at=old)
    with Session(engine) as s:
        closed = tw.close_stuck_orders(s)
    assert oid in closed
    o = _get(oid)
    assert o.status == S.cancelled and o.cancel_by == "system" and o.cancel_reason == "stuck_timeout"


def test_fresh_order_not_closed(client, user_factory):
    """Живой заказ (двигался только что) воркер не трогает — иначе рубил бы поездки в процессе."""
    pax = user_factory("FreshPax")
    drv = user_factory("FreshDrv", role=M.UserRole.driver)
    oid = _order(pax["id"], driver_id=drv["id"], status=S.onboard,
                 created_at=utcnow() - timedelta(hours=7), onboard_at=utcnow())
    with Session(engine) as s:
        assert oid not in tw.close_stuck_orders(s)
    assert _get(oid).status == S.onboard


def test_stuck_without_driver_becomes_expired(client, user_factory):
    """Заказ завис ДО назначения водителя → это не «отмена», а «истёк»: винить некого."""
    pax = user_factory("StuckNoDrv")
    old = utcnow() - timedelta(hours=8)
    oid = _order(pax["id"], status=S.searching, created_at=old, searching_at=old)
    with Session(engine) as s:
        assert oid in tw.close_stuck_orders(s)
    assert _get(oid).status == S.expired


# ============================ 3. Очередь «рядом никого» ============================
def test_wait_endpoint_and_retry(client, user_factory):
    """Пассажир жмёт «подождать» → воркер перезапускает поиск (тихо, без спама «рядом никого»)."""
    pax = user_factory("WaitPax")
    oid = _order(pax["id"], status=S.expired, searching_at=utcnow() - timedelta(minutes=10))
    r = client.post(f"/instant/orders/{oid}/wait", headers=pax["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["wait_minutes"] >= 1 and _get(oid).wait_until is not None
    with Session(engine) as s:
        retried = tw.retry_waiting_orders(s)
    assert oid in retried
    assert (_get(oid).retry_count or 0) >= 1


def test_wait_is_owner_only_and_status_gated(client, user_factory):
    """Ждать можно только СВОЙ заказ и только пока машина не найдена."""
    pax = user_factory("WaitOwner")
    other = user_factory("WaitStranger")
    drv = user_factory("WaitDrv", role=M.UserRole.driver)
    oid = _order(pax["id"], status=S.expired)
    assert client.post(f"/instant/orders/{oid}/wait", headers=other["auth"]).status_code == 403
    done_id = _order(pax["id"], driver_id=drv["id"], status=S.done)
    assert client.post(f"/instant/orders/{done_id}/wait", headers=pax["auth"]).status_code == 409


def test_retry_respects_interval(client, user_factory):
    """Между попытками выдерживаем паузу — иначе воркер долбил бы поиск каждую минуту."""
    pax = user_factory("WaitInterval")
    oid = _order(pax["id"], status=S.expired, searching_at=utcnow(),
                 wait_until=utcnow() + timedelta(minutes=10))
    with Session(engine) as s:
        assert oid not in tw.retry_waiting_orders(s)


def test_wait_expires_with_honest_message(client, user_factory):
    """Время вышло, машины так и нет → закрываем честно, а не держим человека в неведении."""
    pax = user_factory("WaitOver")
    oid = _order(pax["id"], status=S.expired, wait_until=utcnow() - timedelta(minutes=1))
    with Session(engine) as s:
        assert oid in tw.finish_expired_waits(s)
    o = _get(oid)
    assert o.status == S.expired and o.wait_until is None


# ============================ 4. Протухшие офферы ============================
def test_stale_offer_moves_on(client, user_factory):
    """Оффер висит без ответа, приложение пассажира закрыто → подбор всё равно идёт дальше."""
    pax = user_factory("OfferPax")
    drv = user_factory("OfferDrv", role=M.UserRole.driver)
    oid = _order(pax["id"], status=S.offered, current_offer_driver_id=drv["id"],
                 offered_at=utcnow() - timedelta(minutes=10),
                 offer_expires_at=utcnow() - timedelta(minutes=5))
    with Session(engine) as s:
        assert oid in tw.advance_stale_offers(s)
    assert _get(oid).status != S.offered


# ============================ Общий прогон ============================
def test_run_once_is_safe_on_empty_and_dry_run(client, user_factory):
    """Полный прогон не падает на пустых данных, а --dry-run ничего не меняет."""
    pax = user_factory("DryPax")
    oid = _order(pax["id"], status=S.scheduled, scheduled_at=utcnow() - timedelta(minutes=1))
    with Session(engine) as s:
        res = tw.run_once(s, dry_run=True)
    assert oid in res["scheduled_activated"]
    assert _get(oid).status == S.scheduled, "сухой прогон не должен менять состояние"
    with Session(engine) as s:
        res2 = tw.run_once(s)
    # parcels_handled — разбор зависших посылок: воркер обслуживает и такси, и доставку
    # (аудит 2026-08-03; детали ветки — в test_parcel_worker.py).
    # sos_escalated — повтор по непринятому сигналу SOS: он живёт здесь же, потому что этот
    # воркер крутится чаще всех, а отдельная запись в cron — ещё одно место, где её могут
    # забыть включить (волна 82; сама ветка — в test_unanswered_sos_calls_again.py).
    assert set(res2) == {"scheduled_activated", "stuck_closed", "waits_retried",
                         "waits_finished", "offers_advanced", "parcels_handled",
                         # Зимний протокол переехал в робота (волна 114): помощь молчащему
                         # человеку больше не зависит от того, открыл ли он приложение.
                         "sos_escalated", "winter_escalated",
                         # Реклама с вышедшим сроком (волна 125): гасим статус и говорим
                         # владельцу, иначе в кабинете вечно «Оплачено · показывается».
                         "ads_expired",
                         # Жалоба, на которую обвинённый не ответил за три дня (волна 175):
                         # молчание перестало быть способом похоронить разбор.
                         "incidents_escalated"}
