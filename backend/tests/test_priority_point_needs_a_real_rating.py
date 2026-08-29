"""Волна 208: балл приоритета за рейтинг даётся за ЗАРАБОТАННЫЙ рейтинг, а не за заводской.

Приоритет решает, кому заказ достанется первым. В модуле прямо написано, что роли считаются
раздельно и что кнут бьёт только за вред другому человеку, — правила выписаны аккуратно.
Но у двух половин одного правила разные условия.

  * У КУРЬЕРА: балл за рейтинг даётся, только если оценок больше нуля.
  * У ТАКСИСТА: проверки на число оценок нет вовсе.

А `DriverProfile.rating` по умолчанию 5.0 — это заводской сид, «пока не оценивали», а не
заслуга. Приложение это знает и в других местах честно пишет «Новичок», пока оценок меньше
пяти. Здесь же водитель без единой оценки получает балл «рейтинг не ниже порога» и обгоняет
в очереди того, кто отвозил сотню человек и заработал настоящие 4.7.

Тот же сид приезжает и после «щита рейтинга»: разбор снял все оценки как месть — в профиль
кладётся нейтральные 5.0. Человек не виноват, но и заслуги за эти 5.0 у него нет.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app import priority
from app.config import settings
from app.db import engine
from app.models import (DriverProfile, InstantOrder, InstantOrderStatus as S, Rating,
                        TaxiApplication, TaxiApplicationStatus, UserRole)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _профиль(s: Session, uid: int, rating: float | None = None) -> DriverProfile:
    p = s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()
    if p is None:
        p = DriverProfile(user_id=uid)
        s.add(p)
        s.commit()
        s.refresh(p)
    if rating is not None:
        p.rating = rating
        s.add(p)
        s.commit()
        s.refresh(p)
    return p


def _старая_заявка(s: Session, uid: int) -> None:
    """Заявка одобрена давно — чтобы аванс новичка не мешал читать расклад."""
    давно = utcnow() - timedelta(days=int(settings.priority_newbie_days) + 5)
    app = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == uid)).first()
    if app is not None:
        app.status = TaxiApplicationStatus.approved
        app.reviewed_at = давно
        app.created_at = давно
        s.add(app)
        s.commit()


def _оценить(s: Session, driver_id: int, passenger_id: int, stars: int) -> None:
    """Настоящая оценка за поездку, где он был за рулём."""
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    s.add(Rating(order_id=o.id, rater_id=passenger_id, ratee_id=driver_id, stars=stars))
    s.commit()


def _коды(расклад: dict) -> set:
    return {p["code"] for p in расклад["parts"]}


def test_driver_without_a_single_rating_gets_no_quality_point(client, user_factory):
    """Ни одной оценки — балла за рейтинг нет. Заводские 5.0 это не заслуга.

    Иначе он обгоняет в очереди того, кто отвозил сотню человек и заработал 4.7.
    """
    новичок = user_factory("ПриоритетНовичок", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, новичок["id"])          # заводской сид 5.0, оценок нет
        _старая_заявка(s, новичок["id"])
        расклад = priority.taxi_points(s, новичок["id"])

    assert priority.GOOD_RATING not in _коды(расклад), (
        f"балл за рейтинг выдан водителю без единой оценки: {расклад['parts']}"
    )


def test_driver_with_earned_rating_gets_the_point(client, user_factory):
    """Защита не сломана: заработал оценки — балл его."""
    работяга = user_factory("ПриоритетРаботяга", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, работяга["id"])
        _старая_заявка(s, работяга["id"])
        for i in range(3):
            пас = user_factory(f"ПриоритетПас{i}")
            _оценить(s, работяга["id"], пас["id"], 5)
        расклад = priority.taxi_points(s, работяга["id"])

    assert priority.GOOD_RATING in _коды(расклад), (
        f"водитель с тремя пятёрками не получил балл за рейтинг: {расклад['parts']}"
    )


def test_low_earned_rating_gets_no_point(client, user_factory):
    """И наоборот: оценки есть, но низкие — балла нет."""
    слабый = user_factory("ПриоритетСлабый", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, слабый["id"])
        _старая_заявка(s, слабый["id"])
        for i in range(3):
            пас = user_factory(f"ПриоритетСлабыйПас{i}")
            _оценить(s, слабый["id"], пас["id"], 2)
        расклад = priority.taxi_points(s, слабый["id"])

    assert priority.GOOD_RATING not in _коды(расклад)


def test_taxi_and_courier_judge_the_rating_the_same_way(client, user_factory):
    """Два человека в одинаковом положении — одинаковый расклад.

    Правило одно («балл за рейтинг не ниже порога»), и половины одного правила не должны
    расходиться: у курьера проверка на число оценок была, у таксиста — нет.
    """
    водитель = user_factory("СверкаВодитель", role=UserRole.driver)
    курьер = user_factory("СверкаКурьер", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, водитель["id"])
        _старая_заявка(s, водитель["id"])
        такси = priority.taxi_points(s, водитель["id"])
        доставка = priority.courier_points(s, курьер["id"])

    assert (priority.GOOD_RATING in _коды(такси)) == (priority.GOOD_RATING in _коды(доставка)), (
        f"без оценок такси даёт {_коды(такси)}, доставка — {_коды(доставка)}"
    )


def test_newbie_still_gets_his_advance(client, user_factory):
    """Защита не сломана: аванс новичку — отдельный балл, он остаётся.

    Именно он и должен вытягивать новичка из ямы «нет заказов → нет рейтинга», а не
    заводской сид, притворяющийся заслугой.
    """
    новичок = user_factory("ПриоритетАванс", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, новичок["id"])
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == новичок["id"])).first()
        assert app is not None, "фабрика перестала заводить заявку — проба бессмысленна"
        app.status = TaxiApplicationStatus.approved
        app.reviewed_at = utcnow()          # одобрен только что
        s.add(app)
        s.commit()
        расклад = priority.taxi_points(s, новичок["id"])

    assert priority.NEWBIE in _коды(расклад), f"аванс новичку пропал: {расклад['parts']}"
    assert расклад["points"] > 0, "новичок остался с нулём — он никогда не получит заказ"


def test_points_never_go_below_zero(client, user_factory):
    """Опора: приоритет это очередь, а не долг — ниже нуля не опускаемся."""
    расклад = priority._pack([], 99)
    assert расклад["points"] == 0
    assert расклад["minus"] == 99



def test_unrated_driver_scores_zero_not_a_seed(client, user_factory):
    """Договор, на котором держится вся волна: у неоценённого водителя расчёт даёт 0.0 и 0.

    Проверка «оценок > 0» в приоритете сегодня избыточна ровно потому, что живой расчёт
    возвращает ноль, а ноль не проходит порог. Мутационный проход это и показал: убери
    её — ничего не сломается.

    Убирать нельзя: она держит правило словами и одинакова у такси и у доставки. Но её
    избыточность опирается на ЭТОТ договор — если однажды расчёт начнёт возвращать сид 5.0
    «чтобы новичка не обижать», проверка станет единственной защитой. Тест краснеет в тот же
    день, а не через месяц по жалобе водителя с настоящими 4.7.
    """
    from app.services import driver_rating

    новичок = user_factory("ДоговорНовичок", role=UserRole.driver)
    with Session(engine) as s:
        _профиль(s, новичок["id"])          # профиль есть, оценок нет — в профиле сид 5.0
        рейтинг, оценок = driver_rating(s, новичок["id"])

    assert (рейтинг, оценок) == (0.0, 0), (
        f"живой расчёт вернул {рейтинг} по {оценок} оценкам вместо нуля — теперь проверка "
        "«оценок > 0» в приоритете обязательна, она больше не запасная"
    )


def _отменённый(s, driver_id: int, passenger_id: int, кем: str, no_show: bool = False) -> None:
    """Отменённый заказ с отметкой, КТО его отменил."""
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.cancelled, price_estimate=200,
                     cancel_by=кем, no_show=no_show,
                     created_at=utcnow() - timedelta(hours=2),
                     accepted_at=utcnow() - timedelta(hours=2),
                     cancelled_at=utcnow() - timedelta(hours=1))
    s.add(o)
    s.commit()


def test_passenger_cancel_does_not_punish_the_driver(client, user_factory):
    """Кнут бьёт за вред ДРУГИМ. Пассажир передумал — водитель тут ни при чём.

    В модуле это записано прямо: «кнут есть, но бьёт строго за вред другим: бросил уже
    принятый заказ». Отмена пассажира не его вина, и приоритет за неё падать не должен —
    иначе водитель платит очередью за чужое решение.
    """
    водитель = user_factory("КнутВодитель", role=UserRole.driver)
    пассажир = user_factory("КнутПассажир")
    with Session(engine) as s:
        _профиль(s, водитель["id"])
        _старая_заявка(s, водитель["id"])
        _отменённый(s, водитель["id"], пассажир["id"], кем="passenger")
        расклад = priority.taxi_points(s, водитель["id"])

    assert расклад["minus"] == 0, (
        f"водителя наказали за отмену ПАССАЖИРА: минус {расклад['minus']}"
    )


def test_driver_dropping_an_accepted_order_is_punished(client, user_factory):
    """Защита не сломана: бросил принятый заказ — человек остался стоять, минус его."""
    водитель = user_factory("КнутБросил", role=UserRole.driver)
    пассажир = user_factory("КнутБросилПас")
    with Session(engine) as s:
        _профиль(s, водитель["id"])
        _старая_заявка(s, водитель["id"])
        _отменённый(s, водитель["id"], пассажир["id"], кем="driver")
        расклад = priority.taxi_points(s, водитель["id"])

    assert расклад["minus"] == int(settings.priority_drop_penalty), (
        f"бросил принятый заказ, а минуса нет: {расклад}"
    )


def test_no_show_is_not_a_dropped_order(client, user_factory):
    """«Пассажир не вышел» — не брошенный заказ: водитель доехал и отждал."""
    водитель = user_factory("КнутНеВышел", role=UserRole.driver)
    пассажир = user_factory("КнутНеВышелПас")
    with Session(engine) as s:
        _профиль(s, водитель["id"])
        _старая_заявка(s, водитель["id"])
        _отменённый(s, водитель["id"], пассажир["id"], кем="driver", no_show=True)
        расклад = priority.taxi_points(s, водитель["id"])

    assert расклад["minus"] == 0, (
        f"водителя наказали за то, что пассажир не вышел: минус {расклад['minus']}"
    )
