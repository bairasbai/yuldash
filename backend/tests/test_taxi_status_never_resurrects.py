# -*- coding: utf-8 -*-
"""Отменённый и завершённый заказ такси не воскресает (аудит 2026-08-07).

Весь заказной слой двигает статус атомарно: «поменяй статус, ТОЛЬКО ЕСЛИ он всё ещё такой,
каким я его видел» (`transition`, `cancel_order`). Две функции подбора это правило нарушали и
писали статус безусловно — «поменяй статус, и точка»:

    update(InstantOrder).where(InstantOrder.id == order.id).values(status=S.searching, ...)

Почему это стреляет. Их зовут фоновые задачи, которые сначала выбирают ПАЧКУ до 200-500 строк,
а потом обрабатывают их по одной — с походом в Redis и пушем на каждую. Между «выбрал» и
«записал» проходят секунды, и объект в руках воркера успевает устареть. Всё, что за это время
стало `cancelled` или `done`, безусловный UPDATE откатывал обратно в поиск:

  1. Пассажир нажал «Отмена», получил подтверждение, промокод вернулся, водителю ушёл пуш
     «заказ отменён».
  2. Очередь воркера доходит до этой строки → заказ снова `searching` → матчер шлёт оффер
     новому водителю → человек едет к тому, кто отменил заказ полминуты назад.

С `done` ещё дороже: комиссия по завершённой поездке уже начислена (`CommissionDebt`), а
воскрешённый заказ может уйти в `cancelled` — долг за поездку, которой по базе не было.

Чинится тем же приёмом, что и во всём остальном файле: условие статуса в самом UPDATE плюс
проверка `rowcount`. Здесь тесты зовут функции напрямую с уже терминальным заказом — это и
есть та ситуация, в которой воркер оказывается с устаревшей строкой на руках.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow

ORIG = (52.591, 58.317)      # Баймак
DEST = (52.716, 58.664)      # Сибай


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    monkeypatch.setattr("app.instant_service.send_push", lambda *a, **k: None)


def _order(pax_id, status, driver_id=None, **kw) -> int:
    base = dict(passenger_id=pax_id, driver_id=driver_id, status=status,
                from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                from_text="Баймак", to_text="Сибай", price_estimate=300)
    base.update(kw)
    with Session(engine) as s:
        o = InstantOrder(**base)
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _status(order_id: int) -> S:
    with Session(engine) as s:
        return s.get(InstantOrder, order_id).status


# ============== Оффер протух, а заказ уже закрыт ==============
def test_stale_offer_does_not_revive_cancelled_order(client, user_factory, fake_redis):
    """Воркер выбрал пачку протухших офферов; пока он шёл по ней, пассажир отменил заказ."""
    pax = user_factory("ВоскресОтмена")
    drv = user_factory("ВоскресВодитель", role=UserRole.driver)
    oid = _order(pax["id"], S.cancelled, current_offer_driver_id=drv["id"],
                 cancelled_at=utcnow(), cancel_by="passenger")

    with Session(engine) as s:
        isv.advance_after_no_accept(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.cancelled, "отменённый заказ снова ушёл в поиск — водитель поедет зря"


def test_stale_offer_does_not_revive_finished_order(client, user_factory, fake_redis):
    """То же самое с завершённой поездкой: по ней уже начислена комиссия."""
    pax = user_factory("ВоскресГотово")
    drv = user_factory("ВоскресВодитель2", role=UserRole.driver)
    oid = _order(pax["id"], S.done, driver_id=drv["id"], price_final=300, done_at=utcnow())

    with Session(engine) as s:
        isv.advance_after_no_accept(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.done, "завершённая поездка переписана — долг остался без заказа"


def test_stale_offer_does_not_steal_accepted_order(client, user_factory, fake_redis):
    """Водитель успел принять заказ ровно в момент, когда воркер решил, что оффер протух."""
    pax = user_factory("ВоскресПринят")
    drv = user_factory("ВоскресВодитель3", role=UserRole.driver)
    oid = _order(pax["id"], S.accepted, driver_id=drv["id"], accepted_at=utcnow())

    with Session(engine) as s:
        isv.advance_after_no_accept(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.accepted, "принятый заказ отобрали у водителя и вернули в поиск"


# ============== Перезапуск поиска из очереди ожидания ==============
def test_restart_search_does_not_revive_cancelled_order(client, user_factory, fake_redis):
    """Заказ был в очереди «подожду машину», человек его отменил — воркер не должен искать."""
    pax = user_factory("ВоскресОчередь")
    oid = _order(pax["id"], S.cancelled, cancelled_at=utcnow(), cancel_by="passenger")

    with Session(engine) as s:
        isv.start_matching(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.cancelled, "отменённый заказ снова в поиске"


def test_restart_search_does_not_revive_finished_order(client, user_factory, fake_redis):
    """И завершённую поездку перезапуск поиска трогать не должен."""
    pax = user_factory("ВоскресОчередь2")
    drv = user_factory("ВоскресВодитель4", role=UserRole.driver)
    oid = _order(pax["id"], S.done, driver_id=drv["id"], price_final=300, done_at=utcnow())

    with Session(engine) as s:
        isv.start_matching(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.done


# ============== Обратная сторона: обычная работа не сломалась ==============
def test_search_still_starts_for_a_fresh_order(client, user_factory, fake_redis):
    """Проверка не должна мешать штатному пути: новый заказ по-прежнему уходит в поиск."""
    pax = user_factory("ВоскресОбычный")
    oid = _order(pax["id"], S.created)

    with Session(engine) as s:
        isv.start_matching(s, s.get(InstantOrder, oid), notify=False)

    # Водителей на линии нет → честный «рядом никого», но поиск состоялся.
    assert _status(oid) == S.expired


def test_offer_still_advances_to_the_next_driver(client, user_factory, fake_redis):
    """И протухший оффер по-прежнему двигается дальше."""
    pax = user_factory("ВоскресДальше")
    drv = user_factory("ВоскресВодитель5", role=UserRole.driver)
    oid = _order(pax["id"], S.offered, current_offer_driver_id=drv["id"],
                 offered_at=utcnow(), offer_expires_at=utcnow())

    with Session(engine) as s:
        isv.advance_after_no_accept(s, s.get(InstantOrder, oid), notify=False)

    assert _status(oid) == S.expired      # следующего кандидата нет — «рядом никого»
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).current_offer_driver_id is None


@pytest.mark.parametrize("change", ["extended", "cleared", "accepted"])
def test_lazy_offer_timeout_rechecks_stale_snapshot(client, user_factory, fake_redis, monkeypatch, change):
    """Чтение заказа не снимает обновлённое предложение по старому ORM-снимку."""
    pax = user_factory("LazyChangedPax")
    drv = user_factory("LazyChangedDrv", role=UserRole.driver)
    old = utcnow() - timedelta(minutes=5)
    future = utcnow() + timedelta(minutes=5)
    oid = _order(pax["id"], S.offered, current_offer_driver_id=drv["id"],
                 offered_at=old, offer_expires_at=old)
    transitions = []
    advance = isv.advance_after_no_accept

    def track_advance(session, order, notify=True):
        transitions.append(order.id)
        return advance(session, order, notify=notify)

    monkeypatch.setattr(isv, "advance_after_no_accept", track_advance)
    with Session(engine) as session:
        stale = session.get(InstantOrder, oid)
        with Session(engine) as other:
            changed = other.get(InstantOrder, oid)
            if change == "accepted":
                changed.status = S.accepted
                changed.driver_id = drv["id"]
            else:
                changed.offer_expires_at = future if change == "extended" else None
            other.add(changed)
            other.commit()
        result = isv.reconcile_offer(session, stale)
        assert result.status == (S.accepted if change == "accepted" else S.offered)
        assert result.offer_expires_at == (old if change == "accepted" else future if change == "extended" else None)
        assert result.current_offer_driver_id == drv["id"]
    assert transitions == []
    assert _status(oid) == (S.accepted if change == "accepted" else S.offered)


def test_lazy_offer_timeout_still_advances_expired_offer(client, user_factory, fake_redis):
    pax = user_factory("LazyExpiredPax")
    old = utcnow() - timedelta(minutes=5)
    oid = _order(pax["id"], S.offered, offered_at=old, offer_expires_at=old)
    with Session(engine) as session:
        result = isv.reconcile_offer(session, session.get(InstantOrder, oid))
        assert result.status == S.expired  # следующего водителя нет
    assert _status(oid) == S.expired


@pytest.mark.parametrize("reason", ["", "far"])
@pytest.mark.parametrize("window", ["stale", "before_update"])
def test_stale_decline_preserves_reassigned_offer(client, user_factory, monkeypatch, reason, window):
    """Запоздалый отказ прежнего водителя не снимает оффер нового получателя."""
    pax = user_factory("DeclineRacePax")
    first = user_factory("DeclineRaceFirst", role=UserRole.driver)
    second = user_factory("DeclineRaceSecond", role=UserRole.driver)
    old_expiry = utcnow() + timedelta(minutes=1)
    new_expiry = old_expiry + timedelta(minutes=1)
    oid = _order(pax["id"], S.offered, current_offer_driver_id=first["id"],
                 offer_expires_at=old_expiry)
    attempts = []
    def next_offer(session, order, notify=True):
        attempts.append(order.id)
        return order
    monkeypatch.setattr(isv, "try_offer_next", next_offer)
    with Session(engine) as session:
        stale = session.get(InstantOrder, oid)
        assert stale.current_offer_driver_id == first["id"]
        def reassign():
            with Session(engine) as other:
                changed = other.get(InstantOrder, oid)
                changed.current_offer_driver_id = second["id"]
                changed.offer_expires_at = new_expiry
                other.add(changed)
                other.commit()
        if window == "stale":
            reassign()
        else:
            execute = session.execute
            def change_before_update(statement, *args, **kwargs):
                if getattr(statement, "is_update", False):
                    monkeypatch.setattr(session, "execute", execute)
                    reassign()
                return execute(statement, *args, **kwargs)
            monkeypatch.setattr(session, "execute", change_before_update)
        isv.decline_offer(session, oid, first["id"], reason=reason)
    with Session(engine) as check:
        saved = check.get(InstantOrder, oid)
        assert saved.status == S.offered
        assert saved.current_offer_driver_id == second["id"]
        assert saved.offer_expires_at == new_expiry
    assert attempts == []


@pytest.mark.parametrize("reason", ["", "far"])
@pytest.mark.parametrize("window", ["stale", "before_update"])
@pytest.mark.parametrize("changed_field", ["offered_at", "offer_expires_at"])
def test_old_decline_preserves_new_offer_to_same_driver(client, user_factory, monkeypatch, reason, window, changed_field):
    """Новый срок того же получателя — другое предложение, не старый отказ."""
    pax = user_factory("SameOfferPax")
    driver = user_factory("SameOfferDriver", role=UserRole.driver)
    old_at = utcnow() - timedelta(minutes=1)
    new_at = utcnow()
    new_expiry = new_at + timedelta(minutes=1)
    oid = _order(pax["id"], S.offered, current_offer_driver_id=driver["id"],
                 offered_at=old_at, offer_expires_at=new_at)
    attempts = []
    def next_offer(session, order, notify=True):
        attempts.append(order.id)
        return order
    monkeypatch.setattr(isv, "try_offer_next", next_offer)
    with Session(engine) as session:
        stale = session.get(InstantOrder, oid)
        assert stale.offered_at == old_at
        def reoffer():
            with Session(engine) as other:
                changed = other.get(InstantOrder, oid)
                if changed_field == "offered_at":
                    changed.offered_at = new_at
                else:
                    changed.offer_expires_at = new_expiry
                other.add(changed)
                other.commit()
        if window == "stale":
            reoffer()
        else:
            execute = session.execute
            def change_before_update(statement, *args, **kwargs):
                if getattr(statement, "is_update", False):
                    monkeypatch.setattr(session, "execute", execute)
                    reoffer()
                return execute(statement, *args, **kwargs)
            monkeypatch.setattr(session, "execute", change_before_update)
        isv.decline_offer(session, oid, driver["id"], reason=reason)
    with Session(engine) as check:
        saved = check.get(InstantOrder, oid)
        assert saved.status == S.offered
        assert saved.current_offer_driver_id == driver["id"]
        assert saved.offered_at == (new_at if changed_field == "offered_at" else old_at)
        assert saved.offer_expires_at == (new_expiry if changed_field == "offer_expires_at" else new_at)
    assert attempts == []


@pytest.mark.parametrize("reason", ["", "far"])
@pytest.mark.parametrize("nullable_times", [False, True])
def test_decline_current_generation_still_advances(client, user_factory, monkeypatch, reason, nullable_times):
    """Старые записи без времён и обычный текущий оффер по-прежнему отклоняются."""
    pax = user_factory("CurrentGenerationPax")
    driver = user_factory("CurrentGenerationDriver", role=UserRole.driver)
    now = utcnow()
    oid = _order(pax["id"], S.offered, current_offer_driver_id=driver["id"],
                 offered_at=None if nullable_times else now,
                 offer_expires_at=None if nullable_times else now + timedelta(minutes=1))
    attempts = []
    def next_offer(session, order, notify=True):
        attempts.append(order.id)
        return order
    monkeypatch.setattr(isv, "try_offer_next", next_offer)
    with Session(engine) as session:
        result = isv.decline_offer(session, oid, driver["id"], reason=reason)
        assert result.status == S.searching
        assert result.current_offer_driver_id is None
    assert attempts == [oid]
