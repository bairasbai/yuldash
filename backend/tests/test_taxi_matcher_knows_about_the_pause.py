"""Подбор такси знает про паузу «Справедливости» — с обеих сторон.

История. Пауза после разбора жалобы закрывает ДЕЙСТВИЯ: отстранённый водитель не выйдет
на линию, отстранённый пассажир не вызовет машину. Но подбор в такси идёт сам, без нажатий,
и о паузе не знал ничего.

Что из этого получалось:

1. Водитель уже стоял на линии, когда разбор его отстранил. На карте он ещё есть, и подбор
   продолжал считать его годным — то есть слал ему заказы. В таком уведомлении едет **адрес
   подачи** пассажира, а отстраняют в том числе за домогательство. Заодно каждый такой заказ
   тратил круг подбора: пассажир ждал дольше, а мог и вовсе получить «рядом никого».

2. Пассажира отстранили, пока его заказ стоял в очереди «подожду машину». Фоновый воркер
   перезапускал поиск как ни в чём не бывало — и машина к нему всё-таки ехала.

Проверено запросом до правки: подбор возвращал отстранённого водителя, воркер возвращал
заказ отстранённого пассажира в поиск (аудит 2026-08-12, волна 47).
"""
from __future__ import annotations

from datetime import date, timedelta

from sqlmodel import Session

from app import instant_service as isv
from app import taxi_worker
from app.config import settings
from app.db import engine
from app.models import (DriverProfile, InstantOrder, InstantOrderStatus as S, SafetyProfile,
                        TaxiApplication, TaxiApplicationStatus, User, UserRole)
from app.timeutil import utcnow

ORIG = (54.7388, 55.9721)
DEST = (54.7500, 55.9800)


def _driver_on_line(session: Session, name: str) -> int:
    """Одобренный таксист, прямо сейчас на линии."""
    u = User(phone=f"w47-{name}", name=name, telegram_id=f"w47{name}", verified=True,
             role=UserRole.driver)
    session.add(u)
    session.commit()
    session.refresh(u)
    session.add(TaxiApplication(user_id=u.id, inn="123456789012", permit_number="Т-1",
                                birth_date=date(1990, 1, 1), license_since_year=2010,
                                status=TaxiApplicationStatus.approved))
    session.add(DriverProfile(user_id=u.id, online=True, car_classes_available="economy"))
    session.commit()
    return u.id


def _order(session: Session, passenger_id: int, **kw) -> InstantOrder:
    # created_at за окном спроса: сурж считает свежие searching-заказы, а тесты делят одну БД.
    kw.setdefault("created_at", utcnow() - timedelta(hours=1))
    kw.setdefault("status", S.searching)
    o = InstantOrder(passenger_id=passenger_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     from_text="Сибай, ул. Ленина 1", to_text="Уфа, вокзал",
                     category="standard", **kw)
    session.add(o)
    session.commit()
    session.refresh(o)
    return o


def _suspend(session: Session, user_id: int, days: int = 7) -> None:
    session.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
    session.commit()


def test_отстранённому_водителю_заказы_больше_не_летят(user_factory):
    """Он уже был на линии, когда его отстранили. Адрес пассажира ему знать незачем."""
    pax = user_factory("W47Pax")
    with Session(engine) as s:
        drv = _driver_on_line(s, "GotSuspended")
        order = _order(s, pax["id"])
        assert isv.eligible(s, [drv], order) == [drv]      # до разбора — обычный водитель

        _suspend(s, drv)
        assert isv.eligible(s, [drv], order) == []


def test_честный_водитель_рядом_заказы_получает(user_factory):
    """Страховка от перестраховки: паузы нет — подбор работает как раньше."""
    pax = user_factory("W47PaxOk")
    with Session(engine) as s:
        drv = _driver_on_line(s, "Honest")
        order = _order(s, pax["id"])
        assert isv.eligible(s, [drv], order) == [drv]


def test_отстранённого_не_подставляем_вместо_честного(user_factory):
    """Рядом двое: один отстранён, другой нет. Заказ уходит только честному."""
    pax = user_factory("W47PaxTwo")
    with Session(engine) as s:
        banned = _driver_on_line(s, "Banned2")
        honest = _driver_on_line(s, "Honest2")
        order = _order(s, pax["id"])
        _suspend(s, banned)
        assert isv.eligible(s, [banned, honest], order) == [honest]


def test_заказ_отстранённого_пассажира_машину_не_ищет(user_factory, monkeypatch):
    """Он нажал «подожду машину», и в это время разбор его отстранил.
    Заказ отменяется честно — а не едет к нему водитель."""
    monkeypatch.setattr(settings, "order_retry_every_min", 0, raising=False)
    pax = user_factory("W47Waiting")
    with Session(engine) as s:
        order = _order(s, pax["id"], status=S.expired,
                       wait_until=utcnow() + timedelta(minutes=30),
                       searching_at=utcnow() - timedelta(hours=1))
        oid = order.id
        _suspend(s, pax["id"])

    with Session(engine) as s:
        taxi_worker.retry_waiting_orders(s)
        assert s.get(InstantOrder, oid).status == S.cancelled


def test_предзаказ_отстранённого_к_сроку_не_активируется(user_factory):
    """Тот же случай, но у предзаказа «на время»: правило и текст — общие."""
    pax = user_factory("W47Scheduled")
    with Session(engine) as s:
        order = _order(s, pax["id"], status=S.scheduled,
                       scheduled_at=utcnow() + timedelta(minutes=5))
        _suspend(s, pax["id"])

        assert isv.activate_scheduled(s, order).status == S.cancelled


def test_заказ_здорового_человека_из_очереди_возвращается_в_поиск(user_factory, monkeypatch):
    """Контрольный: паузы нет — воркер по-прежнему перезапускает поиск, заказ не отменяется."""
    monkeypatch.setattr(settings, "order_retry_every_min", 0, raising=False)
    pax = user_factory("W47WaitingOk")
    with Session(engine) as s:
        order = _order(s, pax["id"], status=S.expired,
                       wait_until=utcnow() + timedelta(minutes=30),
                       searching_at=utcnow() - timedelta(hours=1))
        oid = order.id

    with Session(engine) as s:
        assert oid in taxi_worker.retry_waiting_orders(s)
        # Redis в тестах нет → водителей не находим и заказ снова expired. Важно, что НЕ cancelled.
        assert s.get(InstantOrder, oid).status != S.cancelled
