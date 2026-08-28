"""Лесенка комиссии считается ПОЕЗДКАМИ, а не календарём (решение 2026-08-23).

Почему это отдельный файл. Раньше ступень зависела от даты первого заказа: водитель на двух
поездках в день сжигал льготу два месяца и не приносил почти ничего, а активный всё это время
недоплачивал с большого объёма. Теперь льгота привязана к работе: первые 30 поездок — 3%,
следующие 70 — 8%, дальше 15%.

Здесь — то, что легко сломать и незаметно:
  1) ставка заказа не зависит от него самого (иначе 31-я поездка дорожает в момент финиша);
  2) переход со старой лесенки не роняет на человека утроившуюся ставку;
  3) дашборд честно говорит, сколько поездок осталось до следующей ступени.
"""
from datetime import timedelta

from sqlmodel import Session

from app import debt as debt_mod
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, UserRole
from app.timeutil import local_date, utcnow

ORIG = (52.591, 58.317)     # Баймак
DEST = (52.712, 58.663)     # Сибай


def _seed_trips(driver_id: int, passenger_id: int, count: int, *, done_before) -> None:
    """count завершённых поездок водителя, все закрыты ДО момента done_before."""
    with Session(engine) as s:
        for i in range(count):
            s.add(InstantOrder(
                passenger_id=passenger_id, driver_id=driver_id,
                from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                status=InstantOrderStatus.done, price_estimate=100, price_final=100,
                done_at=done_before - timedelta(minutes=count - i),
            ))
        s.commit()


def _pair(user_factory, tag: str):
    d = user_factory(f"Ступень{tag}", role=UserRole.driver)
    pax = user_factory(f"СтупеньПас{tag}")
    return d["id"], pax["id"]


# ============================ 1. Ставку двигают поездки ============================
def test_the_order_itself_does_not_raise_its_own_rate(client, user_factory):
    """Поездка, которая закрывается прямо сейчас, не считается в свою же ставку.

    Водитель принял 31-й заказ, видя в оффере 3%. Если бы считали «включительно», в момент
    финиша поездка стала бы 31-й и удержали бы 8% — за работу, на которую он согласился
    по другой цене. Такое замечают один раз и уходят.
    """
    drv, pax = _pair(user_factory, "Сама")
    taken_at = utcnow() - timedelta(hours=1)
    _seed_trips(drv, pax, settings.fee_tier1_trips - 1, done_before=taken_at)   # 29 позади
    with Session(engine) as s:
        # Момент, когда он взял заказ: за спиной 29 поездок → ещё стартовая ставка.
        assert debt_mod.driver_fee_percent(s, drv, taken_at) == settings.fee_tier1_percent
    # Заказ завершился — теперь поездок 30, и СЛЕДУЮЩАЯ уже по второй ступени.
    _seed_trips(drv, pax, 1, done_before=utcnow())
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.fee_tier2_percent


def test_calendar_alone_does_not_move_the_ladder(client, user_factory):
    """Водитель год в сервисе, но сделал 3 поездки — ставка стартовая, а не верхняя.

    Это и есть смысл перехода: льготу получает тот, кто возит, а не тот, кто зарегистрировался.
    """
    drv, pax = _pair(user_factory, "Спящий")
    _seed_trips(drv, pax, 3, done_before=utcnow() - timedelta(days=365))
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.fee_tier1_percent


def test_active_driver_reaches_full_rate_within_days(client, user_factory):
    """И обратное: 100 поездок за неделю — уже полная ставка, календарь не спасает."""
    drv, pax = _pair(user_factory, "Активный")
    _seed_trips(drv, pax, settings.fee_tier2_trips, done_before=utcnow() - timedelta(days=7))
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.service_fee_percent


# ==================== 2. Переход со старой лесенки без прыжка ====================
def test_no_grandfathering_by_default(client, user_factory):
    """По умолчанию страховка выключена: живых водителей со старой лесенкой нет."""
    assert settings.fee_trips_ladder_since == ""
    drv, pax = _pair(user_factory, "Новый")
    _seed_trips(drv, pax, settings.fee_tier2_trips, done_before=utcnow() - timedelta(days=1))
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.service_fee_percent


def test_old_driver_keeps_the_kinder_tier(client, user_factory, monkeypatch):
    """Тот, кто работал ДО перехода, не просыпается с утроившейся ставкой.

    150 поездок за 5 дней: новая лесенка даёт ему 15%, старая (5-й день стажа) — 3%.
    Он выбирал сервис при старых условиях, поэтому берём ту ступень, что выгоднее ему.
    """
    drv, pax = _pair(user_factory, "Старый")
    _seed_trips(drv, pax, 150, done_before=utcnow() - timedelta(days=5))
    monkeypatch.setattr(settings, "fee_trips_ladder_since", local_date(utcnow()).isoformat())
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.fee_tier1_percent


def test_driver_who_started_after_the_switch_gets_the_new_ladder(client, user_factory, monkeypatch):
    """Пришёл уже при новых правилах — страховка на него не распространяется."""
    drv, pax = _pair(user_factory, "После")
    _seed_trips(drv, pax, settings.fee_tier2_trips, done_before=utcnow())
    monkeypatch.setattr(settings, "fee_trips_ladder_since",
                        local_date(utcnow() - timedelta(days=30)).isoformat())
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.service_fee_percent


def test_broken_switch_date_does_not_crash_money(client, user_factory, monkeypatch):
    """Опечатка в дате перехода не роняет расчёт денег — просто работает новая лесенка."""
    drv, pax = _pair(user_factory, "Опечатка")
    _seed_trips(drv, pax, 5, done_before=utcnow() - timedelta(days=1))
    monkeypatch.setattr(settings, "fee_trips_ladder_since", "не-дата")
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, drv) == settings.fee_tier1_percent


# ============================ 3. Что видит водитель ============================
def test_dashboard_counts_trips_to_next_tier(client, user_factory):
    """В кабинете честно: «осталось N поездок до следующей ставки»."""
    drv, pax = _pair(user_factory, "Кабинет")
    _seed_trips(drv, pax, 10, done_before=utcnow() - timedelta(hours=1))
    with Session(engine) as s:
        d = debt_mod.driver_dashboard(s, drv)
    assert d["trips_done"] == 10
    assert d["fee_percent"] == settings.fee_tier1_percent
    assert d["fee_next_percent"] == settings.fee_tier2_percent
    assert d["fee_trips_to_next"] == settings.fee_tier1_trips - 10
    assert d["fee_tier_trips"] == [settings.fee_tier1_trips, settings.fee_tier2_trips]


def test_dashboard_says_nothing_grows_on_the_top_tier(client, user_factory):
    """На верхней ступени не обещаем повышения, которого не будет."""
    drv, pax = _pair(user_factory, "Верх")
    _seed_trips(drv, pax, settings.fee_tier2_trips, done_before=utcnow() - timedelta(hours=1))
    with Session(engine) as s:
        d = debt_mod.driver_dashboard(s, drv)
    assert d["fee_percent"] == settings.service_fee_percent
    assert d["fee_next_percent"] is None
    assert d["fee_trips_to_next"] is None
