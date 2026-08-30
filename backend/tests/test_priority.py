"""Приоритет исполнителя: кому заказ достаётся первым (решение Александра, 2026-08-28).

Идея взята у Яндекса — кто работает много и хорошо, тому заказ первым. Но их приоритет
ненавидят, и здесь мы держим ровно те отличия, ради которых всё затевалось:

  • за МАЛЫЙ объём нет минусов — иначе из приложения вылетает сосед, который подрабатывает
    вечерами после смены, то есть вся наша аудитория;
  • отказ от предложенного заказа не стоит ничего — иначе человек боится отказаться;
  • новичок получает баллы АВАНСОМ, иначе в приоритет не пробиться никогда;
  • приоритет НЕ переписывает географию: когда один явно ближе, едет он;
  • роли считаются раздельно;
  • у курьера фора включается, только если есть с кем соревноваться.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import priority as prio
from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import (DriverProfile, InstantOrder, InstantOrderStatus, TaxiApplication,
                        TaxiApplicationStatus, User, UserRole)
from app.timeutil import utcnow


def _driver(user_factory, name: str, rating: float = 5.0, approved_days_ago: int = 30):
    """Одобренный таксист с заданным рейтингом и стажем от одобрения."""
    d = user_factory(name, role=UserRole.driver)
    with Session(engine) as s:
        # Заявка у водителя уже может быть (её создаёт фабрика) — правим, а не дублируем:
        # user_id уникален, вторая строка уронила бы вставку.
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == d["id"])).first()
        if app is None:
            app = TaxiApplication(user_id=d["id"])
        app.status = TaxiApplicationStatus.approved
        app.reviewed_at = utcnow() - timedelta(days=approved_days_ago)
        s.add(app)
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        if prof is None:
            prof = DriverProfile(user_id=d["id"])
        prof.rating = rating
        s.add(prof)
        s.commit()
        # Балл за рейтинг считается по ЗАРАБОТАННЫМ оценкам, а не по числу в профиле
        # (волна 208): заводские 5.0 в профиле — это «пока не оценивали», а не заслуга.
        # Поэтому здесь же заводим настоящие оценки на нужный балл: смысл тестов ниже
        # («4.9 → балл», «4.2 → балла нет») сохраняется дословно.
        _rate_driver(d["id"], rating)
    return d


def _rate_driver(driver_id: int, rating: float, count: int = 4) -> None:
    """Настоящие оценки за поездки, где он был за рулём, со средним ≈ `rating`."""
    from app.models import Rating

    звёзды = max(1, min(5, round(rating)))
    with Session(engine) as s:
        for i in range(count):
            пассажир = User(phone=f"tg-prio-{driver_id}-{i}", name=f"Оценщик{i}",
                            telegram_id=f"prio{driver_id}x{i}", verified=True)
            s.add(пассажир)
            s.commit()
            s.refresh(пассажир)
            o = InstantOrder(passenger_id=пассажир.id, driver_id=driver_id,
                             from_lat=54.735, from_lng=55.958, to_lat=54.75, to_lng=55.97,
                             status=InstantOrderStatus.done, price_estimate=200,
                             done_at=utcnow() - timedelta(days=30))
            s.add(o)
            s.commit()
            s.refresh(o)
            s.add(Rating(order_id=o.id, rater_id=пассажир.id, ratee_id=driver_id,
                         stars=звёзды))
        s.commit()


def _done_orders(driver_id: int, count: int, **поля) -> None:
    """Столько-то завершённых заказов за последние сутки."""
    with Session(engine) as s:
        for i in range(count):
            s.add(InstantOrder(
                passenger_id=driver_id, driver_id=driver_id,
                from_lat=54.735, from_lng=55.958, to_lat=54.75, to_lng=55.97,
                status=InstantOrderStatus.done, price_estimate=200,
                done_at=utcnow() - timedelta(hours=1, minutes=i),
                created_at=utcnow() - timedelta(hours=2),
                accepted_at=utcnow() - timedelta(hours=2),
                **поля,
            ))
        s.commit()


def _points(driver_id: int) -> dict:
    with Session(engine) as s:
        return prio.taxi_points(s, driver_id)


# ============================ 1. Пряники ============================
def test_good_rating_gives_a_point(client, user_factory):
    d = _driver(user_factory, "ПриорРейтинг", rating=4.9)
    коды = {p["code"] for p in _points(d["id"])["parts"]}
    assert prio.GOOD_RATING in коды


def test_low_rating_gives_no_point_but_no_minus_either(client, user_factory):
    """Просевший рейтинг не даёт балла — но и не отнимает: за него уже есть штраф в матчере."""
    d = _driver(user_factory, "ПриорНизкийРейтинг", rating=4.2)
    расклад = _points(d["id"])
    assert prio.GOOD_RATING not in {p["code"] for p in расклад["parts"]}
    assert расклад["minus"] == 0


def test_working_a_lot_gives_a_point(client, user_factory):
    d = _driver(user_factory, "ПриорАктивный", rating=4.0)
    _done_orders(d["id"], settings.priority_orders_week)
    коды = {p["code"] for p in _points(d["id"])["parts"]}
    assert prio.ACTIVE_WEEK in коды


def test_working_a_little_costs_nothing(client, user_factory):
    """ГЛАВНОЕ отличие от Яндекса: у них меньше 25 заказов в неделю — минус четыре балла.

    Так из приложения вылетает сосед, который возит по вечерам после своей смены, — то есть
    ровно тот человек, ради которого Юлдаш и сделан. У нас за это ноль последствий.
    """
    d = _driver(user_factory, "ПриорРедкий", rating=4.0)
    _done_orders(d["id"], 1)
    расклад = _points(d["id"])
    assert расклад["minus"] == 0, "за малый объём начислили минус — это правило Яндекса, не наше"
    assert расклад["points"] >= 0


def test_hard_trips_give_a_point(client, user_factory):
    """Возил в метель — балл. Этого нет ни у одного агрегатора: они премируют объём в центре."""
    d = _driver(user_factory, "ПриорМетель", rating=4.0)
    _done_orders(d["id"], 1, weather_kind="blizzard")
    коды = {p["code"] for p in _points(d["id"])["parts"]}
    assert prio.HARD_TRIPS in коды


# ============================ 2. Кнут — только за вред другому ============================
def test_dropping_an_accepted_order_costs_points(client, user_factory):
    """Бросил заказ, который уже принял: человек ждал машину и потерял время."""
    d = _driver(user_factory, "ПриорБросил", rating=4.9)
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=d["id"], driver_id=d["id"],
            from_lat=54.735, from_lng=55.958, to_lat=54.75, to_lng=55.97,
            status=InstantOrderStatus.cancelled, cancel_by="driver", no_show=False,
            price_estimate=200, cancelled_at=utcnow() - timedelta(hours=2),
        ))
        s.commit()
    assert _points(d["id"])["minus"] == settings.priority_drop_penalty


def test_no_show_is_not_a_dropped_order(client, user_factory):
    """«Пассажир не вышел» — водитель всё сделал. Наказывать его за это нельзя."""
    d = _driver(user_factory, "ПриорНеВышел", rating=4.9)
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=d["id"], driver_id=d["id"],
            from_lat=54.735, from_lng=55.958, to_lat=54.75, to_lng=55.97,
            status=InstantOrderStatus.cancelled, cancel_by="driver", no_show=True,
            price_estimate=200, cancelled_at=utcnow() - timedelta(hours=2),
        ))
        s.commit()
    assert _points(d["id"])["minus"] == 0


def test_points_never_go_below_zero(client, user_factory):
    """Это очередь, а не долг: минус не может утащить человека в отрицательные баллы."""
    d = _driver(user_factory, "ПриорМинус", rating=4.0)
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=d["id"], driver_id=d["id"],
            from_lat=54.735, from_lng=55.958, to_lat=54.75, to_lng=55.97,
            status=InstantOrderStatus.cancelled, cancel_by="driver", no_show=False,
            price_estimate=200, cancelled_at=utcnow() - timedelta(hours=2),
        ))
        s.commit()
    assert _points(d["id"])["points"] == 0


# ============================ 3. Новичку есть чем начать ============================
def test_newbie_gets_points_in_advance(client, user_factory):
    """Ловушка агрегаторов: нет заказов → нет активности и рейтинга → нет приоритета →
    нет заказов. Аванс на первую неделю её разрывает."""
    d = _driver(user_factory, "ПриорНовичок", rating=0.0, approved_days_ago=1)
    расклад = _points(d["id"])
    assert prio.NEWBIE in {p["code"] for p in расклад["parts"]}
    assert расклад["points"] >= settings.priority_newbie_points


def test_the_advance_burns_out(client, user_factory):
    """Через неделю аванс кончается — дальше на общих основаниях."""
    d = _driver(user_factory, "ПриорБывшийНовичок", rating=0.0,
                approved_days_ago=settings.priority_newbie_days + 2)
    assert prio.NEWBIE not in {p["code"] for p in _points(d["id"])["parts"]}


# ============================ 4. Приоритет не переписывает географию ============================
def test_priority_wins_only_a_close_call(client):
    """Полный приоритет перебивает примерно полкилометра — и не больше.

    Иначе едет тот, кто дальше: пассажир ждёт дольше, водитель жжёт лишний бензин,
    проигрывают оба. Ровно за это приоритет у агрегаторов и ругают.
    """
    profs = {}
    полный = prio.score_bonus(settings.priority_max_points)
    близкий = isv._score(profs, 1, 1.0, 0.0)
    приоритетный_рядом = isv._score(profs, 2, 1.5, полный)
    приоритетный_далеко = isv._score(profs, 3, 2.0, полный)
    assert приоритетный_рядом > близкий, "приоритет не решает даже спорный случай"
    assert приоритетный_далеко < близкий, "приоритет перебил заметную разницу в расстоянии"


def test_no_points_no_bonus(client):
    assert prio.score_bonus(0) == 0.0


# ============================ 5. Роли раздельны ============================
def test_taxi_and_courier_are_counted_apart(client, user_factory):
    """Человек может отлично возить людей и плохо обращаться с посылками."""
    d = _driver(user_factory, "ПриорОбеРоли", rating=4.9)
    _done_orders(d["id"], settings.priority_orders_week)
    with Session(engine) as s:
        такси = prio.taxi_points(s, d["id"])["points"]
        курьер = prio.courier_points(s, d["id"])["points"]
    assert такси > курьер, "заслуги в такси дали приоритет в доставке"


# ============================ 6. Фора курьера ============================
def test_delay_is_zero_for_a_priority_courier(client):
    assert prio.courier_feed_delay_sec(settings.courier_priority_min_points) == 0


def test_delay_applies_to_everyone_else(client):
    assert prio.courier_feed_delay_sec(0) == settings.courier_feed_delay_sec


def test_no_priority_courier_online_means_no_delay(client):
    """Соревноваться не с кем — заказ уходит в ленту немедленно.

    Иначе фора превращается в пустую паузу: заказ полминуты не виден никому, а отправитель
    смотрит на «ищем курьера» (решение Александра).

    Линию чистим руками: база общая на весь прогон, и курьер, оставшийся онлайн от соседнего
    теста, превратил бы проверку в лотерею — зелёную или красную в зависимости от порядка.
    """
    from app.models import CourierProfile
    with Session(engine) as s:
        было = [p for p in s.exec(select(CourierProfile).where(
            CourierProfile.online == True)).all()]        # noqa: E712
        for prof in было:
            prof.online = False
            s.add(prof)
        s.commit()
        try:
            assert prio.any_priority_courier_online(s) is False
            assert prio.visible_delay_sec(s, 0) == 0
        finally:
            for prof in было:
                prof.online = True
                s.add(prof)
            s.commit()


# ============================ 7. Расклад показывается человеку ============================
def test_driver_sees_the_whole_breakdown(client, user_factory):
    """Скрытый приоритет читается как «заказы раздают по блату» — показываем целиком."""
    d = _driver(user_factory, "ПриорВитрина", rating=4.9)
    r = client.get("/driver/priority", headers=d["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["kind"] == prio.TAXI
    assert "points" in body and "parts" in body
    коды_правил = {x["code"] for x in body["rules"]}
    assert {prio.GOOD_RATING, prio.ACTIVE_WEEK, prio.HARD_TRIPS, prio.NEWBIE,
            prio.DROPPED} <= коды_правил, "человеку не показали все правила"


def test_rules_say_the_real_numbers(client, user_factory):
    """Правила на экране берут цифры из конфига, а не переписывают их руками."""
    d = _driver(user_factory, "ПриорЦифры", rating=4.9)
    правила = {x["code"]: x for x in client.get("/driver/priority", headers=d["auth"]).json()["rules"]}
    assert правила[prio.GOOD_RATING]["value"] == settings.priority_rating_min
    assert правила[prio.ACTIVE_WEEK]["value"] == settings.priority_orders_week
    assert правила[prio.DROPPED]["points"] == -settings.priority_drop_penalty
