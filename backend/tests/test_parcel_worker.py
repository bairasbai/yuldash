# -*- coding: utf-8 -*-
"""Фоновый разбор зависших посылок (аудит 2026-08-03).

Такси уже умело закрывать заказ, у которого «сел телефон на трассе»; у доставки такого не было
вообще. Посылка, принятая пропавшим курьером, висела вечно: снять его или закрыть заявку мог
только админ руками, а отправитель (часто — бабушка, ждущая лекарство) не понимал, что делать.

Правила, которые проверяем:
  • «взял, но так и не поехал» (accepted) → курьера снимаем, посылка возвращается в общий список;
  • «уехал с коробкой» (in_transit) → возвращать в список нечего, закрываем разбор, но СЛЕД
    «кто вёз» (courier_id) сохраняем — иначе потом не с кем разбираться;
  • свежая посылка не трогается;
  • --dry-run ничего не меняет;
  • ошибка одной посылки не валит прогон;
  • обе стороны получают уведомление.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import taxi_worker as tw
from app.config import settings
from app.db import engine
from app.models import Notification, ParcelDelivery
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _quiet_push(monkeypatch):
    """Пуш вторичен и требует Firebase — в тестах молчим, запись в ленте остаётся."""
    monkeypatch.setattr("app.services.send_push", lambda *a, **kw: None)


def _parcel(sender_id: int, courier_id: int, status: str, age_hours: int) -> int:
    with Session(engine) as s:
        moment = utcnow() - timedelta(hours=age_hours)
        p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id, status=status,
                           from_city="Темясово", to_city="Сибай",
                           created_at=moment, accepted_at=moment)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _run():
    with Session(engine) as s:
        return tw.close_stuck_parcels(s)


def test_accepted_but_never_moved_releases_courier(client, user_factory):
    """Курьер взял посылку и пропал → его снимаем, заявка снова доступна другим."""
    sender = user_factory("ЖдётЛекарство")
    courier = user_factory("ПропавшийКурьер")
    pid = _parcel(sender["id"], courier["id"], "accepted", settings.parcel_stuck_hours + 5)

    assert pid in _run()

    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        assert p.status == "created", "посылка должна вернуться в общий список"
        assert p.courier_id is None and p.accepted_at is None
        assert p.return_reason == "stuck_timeout"
        # Обе стороны узнали о разборе.
        notes = s.exec(select(Notification).where(Notification.ref_id == pid,
                                                  Notification.type == "parcel")).all()
        assert {n.user_id for n in notes} == {sender["id"], courier["id"]}
        assert all(n.title_ru and n.title_ba for n in notes), "уведомления — на двух языках"


def test_in_transit_is_closed_but_keeps_who_carried_it(client, user_factory):
    """Коробка уехала с курьером → закрываем разбор, но НЕ теряем, кто её вёз."""
    sender = user_factory("ОтправительКоробки")
    courier = user_factory("УехалИПропал")
    pid = _parcel(sender["id"], courier["id"], "in_transit", settings.parcel_stuck_hours + 1)

    assert pid in _run()

    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        assert p.status == "canceled"
        assert p.courier_id == courier["id"], "след «кто вёз» обязан остаться"


def test_fresh_parcel_is_not_touched(client, user_factory):
    """Посылка в пути пару часов — нормальная работа, не трогаем."""
    sender = user_factory("ОтправительСвежий")
    courier = user_factory("КурьерВРаботе")
    pid = _parcel(sender["id"], courier["id"], "in_transit", 1)

    assert pid not in _run()
    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).status == "in_transit"


def test_returning_parcel_is_left_to_admin(client, user_factory):
    """Курьер везёт коробку ОБРАТНО — фон в это не лезет (иначе соврём обеим сторонам)."""
    sender = user_factory("ОтправительВозврат")
    courier = user_factory("ВезётОбратно")
    pid = _parcel(sender["id"], courier["id"], "returning", settings.parcel_stuck_hours + 10)

    assert pid not in _run()
    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).status == "returning"


def test_dry_run_changes_nothing(client, user_factory):
    """--dry-run показывает, что сделал бы, и не трогает ни одной строки."""
    sender = user_factory("ОтправительСухой")
    courier = user_factory("КурьерСухой")
    pid = _parcel(sender["id"], courier["id"], "accepted", settings.parcel_stuck_hours + 3)

    with Session(engine) as s:
        assert pid in tw.close_stuck_parcels(s, dry_run=True)
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        assert p.status == "accepted" and p.courier_id == courier["id"]


def test_one_broken_parcel_does_not_break_the_run(client, user_factory, monkeypatch):
    """Ошибка на одной посылке логируется, остальные разбираются как обычно."""
    sender = user_factory("ОтправительДва")
    courier = user_factory("КурьерДва")
    bad = _parcel(sender["id"], courier["id"], "accepted", settings.parcel_stuck_hours + 4)
    good = _parcel(sender["id"], courier["id"], "accepted", settings.parcel_stuck_hours + 4)

    real_notify = tw.push_notification
    calls = {"n": 0}

    def flaky(session, user_id, *a, **kw):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("канал уведомлений лёг")
        return real_notify(session, user_id, *a, **kw)

    monkeypatch.setattr(tw, "push_notification", flaky)
    handled = _run()
    assert bad in handled and good in handled, "падение уведомления не должно ронять прогон"


def test_run_once_includes_parcels(client, user_factory):
    """Сводка прогона содержит посылки — воркер действительно их разбирает."""
    with Session(engine) as s:
        res = tw.run_once(s, dry_run=True)
    assert "parcels_handled" in res
