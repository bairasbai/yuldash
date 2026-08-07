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
