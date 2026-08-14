"""Обе паузы останавливают такси, а не одна.

У пассажира два разных наказания, и живут они порознь:

  • пауза «Справедливости» (§2) — идёт разбор жалобы;
  • пауза за страйки — платные отмены и неявки (§5/§9).

Ручка «вызвать машину» отвечает 403 на обе. А фоновые пути — очередь «подожду машину» и
активация предзаказа «на время» — знали только первую: волна 47 закрыла её и не заметила
вторую. Проверено запросом: воркер брал заказ, поиск реально стартовал, и машина поехала бы
к человеку, которому такси прямо сейчас закрыто (аудит 2026-08-13, волна 59).

Это тот случай, когда собственная прошлая правка оказалась половинчатой: закрыли ОДИН вид
наказания там, где их два.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app import instant_service as isv
from app import quality as quality_mod
from app import taxi_worker
from app.config import settings
from app.db import engine
from app.models import (InstantOrder, InstantOrderStatus as S, Report, SafetyProfile, UserRole)
from app.timeutil import utcnow

ORIG = (54.7388, 55.9721)
DEST = (54.7500, 55.9800)


@pytest.fixture
def retry_now(monkeypatch):
    monkeypatch.setattr(settings, "order_retry_every_min", 0, raising=False)
    yield


def _order(session: Session, passenger_id: int, **kw) -> InstantOrder:
    kw.setdefault("created_at", utcnow() - timedelta(hours=1))
    kw.setdefault("status", S.searching)
    o = InstantOrder(passenger_id=passenger_id, from_lat=ORIG[0], from_lng=ORIG[1],
                     to_lat=DEST[0], to_lng=DEST[1], from_text="Сибай", to_text="Уфа",
                     category="standard", **kw)
    session.add(o)
    session.commit()
    session.refresh(o)
    return o


def _waiting(session: Session, passenger_id: int) -> int:
    """Заказ в очереди «подожду машину» — человек нажал «подожду» после «рядом никого»."""
    return _order(session, passenger_id, status=S.expired,
                  wait_until=utcnow() + timedelta(minutes=30),
                  searching_at=utcnow() - timedelta(hours=1)).id


def _scheduled(session: Session, passenger_id: int) -> int:
    return _order(session, passenger_id, status=S.scheduled,
                  scheduled_at=utcnow() + timedelta(minutes=5)).id


def _strikes(session: Session, passenger_id: int, reporter_id: int) -> None:
    """Разобранные жалобы на неявки — ровно тот случай, за который закрывают такси."""
    for i in range(settings.strike_limit):
        r = Report(reporter_id=reporter_id, target_user_id=passenger_id,
                   category="no_show", reason=f"не вышел {i}", status="resolved")
        r.created_at = utcnow() - timedelta(hours=2)
        r.resolved_at = utcnow() - timedelta(hours=1)
        session.add(r)
    session.commit()


def test_страйки_останавливают_заказ_из_очереди(client, user_factory, retry_now):
    pax = user_factory("BothPauseQueue", role=UserRole.passenger)
    drv = user_factory("BothPauseQueueDrv", role=UserRole.driver)
    with Session(engine) as s:
        _strikes(s, pax["id"], drv["id"])
        assert quality_mod.passenger_pause_until(s, pax["id"]) is not None
        oid = _waiting(s, pax["id"])

    with Session(engine) as s:
        taxi_worker.retry_waiting_orders(s)
        assert s.get(InstantOrder, oid).status == S.cancelled


def test_страйки_не_дают_активироваться_предзаказу(client, user_factory):
    pax = user_factory("BothPauseSched", role=UserRole.passenger)
    drv = user_factory("BothPauseSchedDrv", role=UserRole.driver)
    with Session(engine) as s:
        _strikes(s, pax["id"], drv["id"])
        oid = _scheduled(s, pax["id"])
        isv.activate_scheduled(s, s.get(InstantOrder, oid))
        fresh = s.get(InstantOrder, oid)
        assert fresh.status == S.cancelled
        assert fresh.searching_at is None       # поиск даже не стартовал


def test_пауза_справедливости_по_прежнему_работает(client, user_factory, retry_now):
    """Контроль, что старая половина правила на месте (её закрыла волна 47)."""
    pax = user_factory("BothPauseSafety", role=UserRole.passenger)
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=pax["id"], suspended_until=utcnow() + timedelta(days=7)))
        s.commit()
        oid = _waiting(s, pax["id"])

    with Session(engine) as s:
        taxi_worker.retry_waiting_orders(s)
        assert s.get(InstantOrder, oid).status == S.cancelled


def test_человек_без_наказаний_машину_ждёт_спокойно(client, user_factory, retry_now):
    """Страховка от перестраховки: обычный заказ из очереди отменять нельзя."""
    pax = user_factory("BothPauseOk", role=UserRole.passenger)
    with Session(engine) as s:
        oid = _waiting(s, pax["id"])

    with Session(engine) as s:
        assert oid in taxi_worker.retry_waiting_orders(s)
        # Redis в тестах нет → водителя не нашли и заказ снова expired. Важно, что НЕ cancelled.
        assert s.get(InstantOrder, oid).status != S.cancelled


def test_человеку_объяснили_за_что(client, user_factory, retry_now):
    """Молча отменять нельзя: человек ждёт машину. И причина у двух пауз разная —
    разбор жалобы или отмены с неявками."""
    sent: list = []

    pax = user_factory("BothPauseWhy", role=UserRole.passenger)
    drv = user_factory("BothPauseWhyDrv", role=UserRole.driver)
    with Session(engine) as s:
        _strikes(s, pax["id"], drv["id"])
        oid = _waiting(s, pax["id"])

    import app.services as services_mod
    original = services_mod.push_notification

    def spy(session, user_id, ntype, title_ru, title_ba, body_ru, body_ba, **kw):
        sent.append((user_id, body_ru))
        return original(session, user_id, ntype, title_ru, title_ba, body_ru, body_ba, **kw)

    services_mod.push_notification = spy
    isv.push_notification = spy
    try:
        with Session(engine) as s:
            taxi_worker.retry_waiting_orders(s)
    finally:
        services_mod.push_notification = original
        isv.push_notification = original

    mine = [body for uid, body in sent if uid == pax["id"]]
    assert mine, "человека не предупредили, что заказ отменён"
    assert "отмен" in mine[0].lower()          # текст про страйки, а не про разбор жалобы
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).status == S.cancelled
