"""Подбор такси знает все четыре наказания водителя, а не два.

Водителю могут закрыть такси по четырём разным причинам:

  1. пауза «Справедливости» (§2) — идёт разбор жалобы;
  2. пауза качества — бросал принятые заказы;
  3. отдых (§8) — восемь часов за рулём, дальше нельзя;
  4. долг по комиссии — просрочка или превышенный порог.

На выходе на линию и на приёме заказа проверяются все четыре. А подбор — тот, что решает,
кому вообще отправить заказ, — знал только первые два: третью и четвёртую туда не донесли
(проверено запросом: подбор возвращал и уставшего, и должника; аудит 2026-08-13, волна 60).

Цена не только в потерянном круге подбора. В уведомлении о заказе едет адрес подачи пассажира,
а главное — система предлагала работу человеку, которому сама же её запретила из-за усталости.
Ответить он не мог (приём заказа закрыт), но соблазн создавался на ровном месте.

Проверки в подборе пакетные — один запрос на весь круг, — но решение принимают ТЕ ЖЕ функции,
что и одиночные гейты. Иначе гейт и фильтр разъедутся, а такие пары разъезжаются всегда.
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest
from sqlmodel import Session

from app import debt as debt_mod
from app import instant_service as isv
from app import workday as wd_mod
from app.config import settings
from app.db import engine
from app.models import (CommissionDebt, DebtStatus, DriverProfile, InstantOrder,
                        InstantOrderStatus as S, SafetyProfile, TaxiApplication,
                        TaxiApplicationStatus, TaxiWorkDay, User, UserRole)
from app.timeutil import utcnow

ORIG = (54.7388, 55.9721)
DEST = (54.7500, 55.9800)


@pytest.fixture
def order_and_driver(user_factory):
    """Пассажир с заказом и водитель на линии — исходная точка всех историй."""
    pax = user_factory("FourPunPax", role=UserRole.passenger)

    def make(name: str):
        with Session(engine) as s:
            u = User(phone=f"w60-{name}", name=name, telegram_id=f"w60{name}",
                     verified=True, role=UserRole.driver)
            s.add(u)
            s.commit()
            s.refresh(u)
            s.add(TaxiApplication(user_id=u.id, inn="123456789012", permit_number="Т-1",
                                  birth_date=date(1990, 1, 1), license_since_year=2010,
                                  status=TaxiApplicationStatus.approved))
            s.add(DriverProfile(user_id=u.id, online=True, car_classes_available="economy"))
            s.commit()
            o = InstantOrder(passenger_id=pax["id"], status=S.searching,
                             from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                             from_text="Сибай, ул. Ленина 1", to_text="Уфа",
                             category="standard", created_at=utcnow() - timedelta(hours=1))
            s.add(o)
            s.commit()
            s.refresh(o)
            return u.id, o.id

    return make


def _eligible(driver_id: int, order_id: int) -> list:
    with Session(engine) as s:
        return isv.eligible(s, [driver_id], s.get(InstantOrder, order_id))


def test_уставшему_водителю_заказы_не_шлём(order_and_driver):
    """Восемь часов за рулём — система сама запретила ему работать. Предлагать нельзя."""
    drv, order_id = order_and_driver("Tired")
    assert _eligible(drv, order_id) == [drv]        # до лимита — обычный водитель

    now = utcnow()
    with Session(engine) as s:
        s.add(TaxiWorkDay(driver_id=drv, day=wd_mod.local_day(now),
                          seconds_online=settings.taxi_shift_limit_hours * 3600,
                          limit_reached_at=now - timedelta(minutes=5),
                          last_heartbeat_at=now - timedelta(minutes=5)))
        s.commit()
        assert wd_mod.blocking_workday(s, drv) is not None

    assert _eligible(drv, order_id) == []


def test_должнику_заказы_не_шлём(order_and_driver):
    drv, order_id = order_and_driver("InDebt")
    assert _eligible(drv, order_id) == [drv]

    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv, amount_kop=50_000, status=DebtStatus.unpaid,
                             due_at=utcnow() - timedelta(days=2)))
        s.commit()
        assert debt_mod.taxi_block_reason(s, drv) == "overdue"

    assert _eligible(drv, order_id) == []


def test_отстранённому_разбором_тоже_не_шлём(order_and_driver):
    """Контроль первой половины правила — её закрыла волна 47."""
    drv, order_id = order_and_driver("Suspended")
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=drv, suspended_until=utcnow() + timedelta(days=7)))
        s.commit()

    assert _eligible(drv, order_id) == []


def test_честный_водитель_заказы_получает(order_and_driver):
    """Страховка от перестраховки: без наказаний подбор работает как раньше."""
    drv, order_id = order_and_driver("Honest")
    assert _eligible(drv, order_id) == [drv]


def test_оплаченный_долг_возвращает_в_подбор(order_and_driver):
    """Наказание не вечное: рассчитался — снова в деле, и ждать пересчёта не нужно."""
    drv, order_id = order_and_driver("Paid")
    with Session(engine) as s:
        d = CommissionDebt(driver_id=drv, amount_kop=50_000, status=DebtStatus.unpaid,
                           due_at=utcnow() - timedelta(days=2))
        s.add(d)
        s.commit()
        s.refresh(d)
    assert _eligible(drv, order_id) == []

    with Session(engine) as s:
        d = s.exec(
            __import__("sqlmodel").select(CommissionDebt).where(CommissionDebt.driver_id == drv)
        ).first()
        d.status = DebtStatus.paid
        s.add(d)
        s.commit()

    assert _eligible(drv, order_id) == [drv]


def test_подбор_и_гейт_решают_одинаково(order_and_driver):
    """Сторож на расхождение: фильтр в подборе и гейт на ручке должны давать один ответ.
    Пакетная проверка существует ради скорости, а не ради второй, отдельной правды."""
    drv, order_id = order_and_driver("SameAnswer")
    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv, amount_kop=1, status=DebtStatus.pending,
                             declare_count=settings.debt_max_declares + 1))
        s.commit()

        single = debt_mod.taxi_block_reason(s, drv) is not None
        batch = drv in debt_mod.blocked_driver_ids(s, [drv])
        assert single == batch is True
