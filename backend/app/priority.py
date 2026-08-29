"""Приоритет исполнителя: кому заказ достаётся первым (решение Александра, 2026-08-28).

Зачем вообще. У Яндекса приоритет — главный рычаг мотивации: процент комиссии там один для
всех (22–25%), а кто работает много и хорошо, тому первому падают заказы. Мы повторяем идею,
но не повторяем её болезни.

Чем их приоритет плох и что мы делаем иначе:

  • **Непрозрачен.** Водитель не знает, почему ему не падают заказы.
    → У нас расклад показывается целиком: сколько баллов и за что.

  • **Наказывает за малый объём.** Меньше 25 заказов в неделю — минус четыре балла, человек
    падает в самый низ. Это выкидывает из приложения соседа, который подрабатывает вечерами
    после смены, — то есть ровно ту аудиторию, ради которой Юлдаш и сделан.
    → У нас за малый объём НЕТ минусов. Только плюс за большой.

  • **Считает долю принятых офферов.** Водитель боится отказаться от неудобного заказа.
    → У нас отказ не стоит ничего. Отказ — это право.

  • **Богатые богатеют.** Новичку не пробиться: нет заказов → нет активности → нет заказов.
    → У нас новичок получает баллы АВАНСОМ на первую неделю.

  • **Перебивает географию.** Едет тот, кто дальше: пассажир ждёт, водитель жжёт бензин,
    проигрывают оба.
    → У нас приоритет решает спорные случаи (разница ~500 м), дальше решает близость.

Кнут есть, но бьёт строго за вред ДРУГИМ: бросил уже принятый заказ — человек ждал машину
и потерял время. Мало возить — вред только своему кошельку, и это не наше дело.

Роли считаются РАЗДЕЛЬНО: человек может отлично возить людей и плохо обращаться с посылками.
Общий счёт дал бы ему фору в том, чего он ещё не умеет, за счёт того, кто именно это и делает.
"""
from datetime import timedelta

from sqlalchemy import func
from sqlmodel import Session, select

from .config import settings
from .models import (CourierApplication, CourierProfile, InstantOrder,
                     InstantOrderStatus, ParcelDelivery, TaxiApplication, TaxiApplicationStatus)
from .timeutil import utcnow

TAXI, COURIER = "taxi", "courier"

# Причины баллов — коды, чтобы клиенты показывали их на двух языках сами.
GOOD_RATING = "rating"          # рейтинг не ниже порога
ACTIVE_WEEK = "active"          # много выполненных заказов за неделю
HARD_TRIPS = "hard_trips"       # возит туда, куда не хотят: ночь, метель, село
NEWBIE = "newbie"               # аванс новичку на первую неделю
DROPPED = "dropped"             # бросил принятый заказ (минус)


def _window_start(now):
    return now - timedelta(days=7)


def _newbie_bonus(approved_at, now) -> int:
    """Аванс новичку: баллы просто так на первую неделю.

    Без него в приоритет не пробиться никогда: нет заказов → нет активности и рейтинга →
    нет приоритета → нет заказов. Именно на этом у агрегаторов и застревают новые люди.
    """
    if approved_at is None:
        return 0
    if now - approved_at > timedelta(days=int(settings.priority_newbie_days)):
        return 0
    return int(settings.priority_newbie_points)


def _pack(parts: list, minus: int) -> dict:
    plus = sum(p["points"] for p in parts)
    return {
        "points": max(plus - minus, 0),      # ниже нуля не опускаем: это очередь, а не долг
        "plus": plus,
        "minus": minus,
        "parts": parts,
        "max_points": int(settings.priority_max_points),
    }


# ============================ Такси ============================
def _taxi_approved_at(session: Session, driver_id: int):
    app = session.exec(select(TaxiApplication).where(
        TaxiApplication.user_id == driver_id,
        TaxiApplication.status == TaxiApplicationStatus.approved,
    )).first()
    return (app.reviewed_at or app.created_at) if app else None


def _hard_trip_done(session: Session, driver_id: int, since, now) -> bool:
    """Возил ли он за неделю туда, куда не хотят: ночью, в метель или с подачей в селе.

    Это наш собственный балл, которого нет ни у одного агрегатора. Яндекс премирует объём
    в центре города, где и так очередь из машин. Мы премируем того, кто поехал в село
    в метель в шесть утра, — то есть закрываем ровно ту дыру, из-за которой человек
    в деревне не может уехать вообще.
    """
    from . import geo as geo_mod
    from .instant_service import in_night_window, local_hour

    rows = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at >= since,
        ).limit(100)
    ).all()
    for o in rows:
        if (getattr(o, "weather_kind", "") or "").strip():
            return True                                  # вёз в гололёд/метель
        когда = o.accepted_at or o.created_at or o.done_at
        if когда is not None and in_night_window(
            local_hour(когда), int(settings.night_from_hour_default),
            int(settings.night_to_hour_default),
        ):
            return True                                  # вёз ночью
        if o.from_lat is not None and o.from_lng is not None:
            место = geo_mod.nearest_settlement(session, o.from_lat, o.from_lng)
            if место is not None and (место.kind or "") == "village":
                return True                              # подача в селе
    return False


def taxi_points(session: Session, driver_id: int, now=None) -> dict:
    """Расклад приоритета таксиста. Показывается ему целиком — скрытый приоритет читается
    как «заказы раздают по блату», а это ровно та боль Яндекса, против которой мы строимся."""
    now = now or utcnow()
    since = _window_start(now)
    parts, minus = [], 0

    # Рейтинг считаем ЖИВЬЁМ и требуем хотя бы одну оценку — как у курьера ниже
    # (аудит 2026-08-08, волна 208).
    #
    # Читали `DriverProfile.rating`, а у него по умолчанию 5.0 — это заводской сид
    # «пока не оценивали», а не заслуга. Водитель без единой оценки получал балл
    # «рейтинг не ниже порога» и обгонял в очереди того, кто отвозил сотню человек
    # и заработал настоящие 4.7. Тот же сид кладётся в профиль после «щита рейтинга»,
    # когда разбор снял все оценки как месть.
    #
    # Половины одного правила разошлись молча: у курьера проверка `оценок > 0` была
    # с самого начала, у таксиста её не написали. Теперь обе половины зовут свой
    # ролевой расчёт и обе требуют настоящих оценок.
    from .services import driver_rating

    рейтинг, оценок = driver_rating(session, driver_id)
    if оценок > 0 and рейтинг >= float(settings.priority_rating_min):
        parts.append({"code": GOOD_RATING, "points": 1, "value": round(рейтинг, 2)})

    сделано = int(session.exec(
        select(func.count()).select_from(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at >= since,
        )
    ).one() or 0)
    if сделано >= int(settings.priority_orders_week):
        parts.append({"code": ACTIVE_WEEK, "points": 1, "value": сделано})

    if _hard_trip_done(session, driver_id, since, now):
        parts.append({"code": HARD_TRIPS, "points": 1, "value": 1})

    аванс = _newbie_bonus(_taxi_approved_at(session, driver_id), now)
    if аванс > 0:
        parts.append({"code": NEWBIE, "points": аванс, "value": int(settings.priority_newbie_days)})

    # Кнут — только за вред другому человеку: он бросил заказ, который уже принял, и кто-то
    # остался стоять. Отказ от предложенного заказа не наказывается никогда.
    брошено = int(session.exec(
        select(func.count()).select_from(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.cancelled,
            InstantOrder.cancel_by == "driver",
            InstantOrder.no_show == False,          # noqa: E712 — «не вышел» это не брошенный заказ
            InstantOrder.cancelled_at >= since,
        )
    ).one() or 0)
    if брошено > 0:
        minus = int(settings.priority_drop_penalty)
    return _pack(parts, minus)


def score_bonus(points: int) -> float:
    """Добавка к очереди офферов. Подобрана так, чтобы приоритет решал спорные случаи
    (разница около полукилометра), но не переписывал географию: когда один водитель явно
    ближе, едет он — и это честно перед пассажиром, который ждёт машину."""
    return max(int(points), 0) * float(settings.priority_weight)


# ============================ Курьер ============================
def _courier_approved_at(session: Session, courier_id: int):
    app = session.exec(select(CourierApplication).where(
        CourierApplication.user_id == courier_id,
        CourierApplication.status == "approved",
    )).first()
    return (app.reviewed_at or app.created_at) if app else None


def courier_points(session: Session, courier_id: int, now=None) -> dict:
    """Расклад приоритета курьера. Считается отдельно от такси: работа разная."""
    now = now or utcnow()
    since = _window_start(now)
    parts, minus = [], 0

    # Рейтинг курьера считается по его доставкам (в профиле его нет — только у водителя).
    from .routers.courier import courier_rating
    рейтинг, оценок = courier_rating(session, courier_id)
    if оценок > 0 and рейтинг >= float(settings.priority_rating_min):
        parts.append({"code": GOOD_RATING, "points": 1, "value": round(рейтинг, 2)})

    сделано = int(session.exec(
        select(func.count()).select_from(ParcelDelivery).where(
            ParcelDelivery.courier_id == courier_id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivered_at >= since,
        )
    ).one() or 0)
    if сделано >= int(settings.priority_orders_week):
        parts.append({"code": ACTIVE_WEEK, "points": 1, "value": сделано})

    трудные = int(session.exec(
        select(func.count()).select_from(ParcelDelivery).where(
            ParcelDelivery.courier_id == courier_id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivered_at >= since,
            ParcelDelivery.pickup_fee_kop > 0,     # ездил за посылкой далеко
        )
    ).one() or 0)
    if трудные > 0:
        parts.append({"code": HARD_TRIPS, "points": 1, "value": трудные})

    аванс = _newbie_bonus(_courier_approved_at(session, courier_id), now)
    if аванс > 0:
        parts.append({"code": NEWBIE, "points": аванс, "value": int(settings.priority_newbie_days)})
    return _pack(parts, minus)


def courier_feed_delay_sec(points: int) -> int:
    """Через сколько секунд заказ увидит НЕприоритетный курьер. 0 — видит сразу.

    Почему фора по времени, а не сортировка ленты: лента короткая, все заказы и так на экране,
    и выигрывает тот, кто быстрее нажал. Тридцати секунд хватает, чтобы фора была настоящей,
    и никто при этом заказа не теряет — через полминуты он у всех.
    """
    if int(points) >= int(settings.courier_priority_min_points):
        return 0
    return max(int(settings.courier_feed_delay_sec), 0)


def any_priority_courier_online(session: Session, now=None) -> bool:
    """Есть ли сейчас на линии хоть один приоритетный курьер.

    Если соревноваться не с кем, фора превращается в пустую паузу: заказ полминуты не виден
    никому, отправитель смотрит на «ищем курьера», а заказ просто лежит (решение Александра).
    """
    now = now or utcnow()
    online = session.exec(select(CourierProfile).where(
        CourierProfile.online == True)).all()        # noqa: E712
    порог = int(settings.courier_priority_min_points)
    for prof in online:
        if courier_points(session, prof.user_id, now)["points"] >= порог:
            return True
    return False


def visible_delay_sec(session: Session, points: int, now=None) -> int:
    """Сколько секунд заказ прячется от ЭТОГО курьера с учётом того, есть ли конкуренты."""
    задержка = courier_feed_delay_sec(points)
    if задержка <= 0:
        return 0
    return задержка if any_priority_courier_online(session, now) else 0


def points_of(session: Session, user_id: int, kind: str = TAXI, now=None) -> dict:
    return taxi_points(session, user_id, now) if kind == TAXI else courier_points(session, user_id, now)


def explain(kind: str = TAXI) -> list[dict]:
    """Из чего складывается приоритет — для экрана «почему у меня столько».

    Тексты клиент показывает сам на двух языках; сервер даёт коды и цифры, чтобы правило
    и его объяснение не разъехались (тот же урок, что с компенсациями в чеке).
    """
    сделано = int(settings.priority_orders_week)
    return [
        {"code": GOOD_RATING, "points": 1, "value": float(settings.priority_rating_min)},
        {"code": ACTIVE_WEEK, "points": 1, "value": сделано},
        {"code": HARD_TRIPS, "points": 1, "value": 1},
        {"code": NEWBIE, "points": int(settings.priority_newbie_points),
         "value": int(settings.priority_newbie_days)},
        {"code": DROPPED, "points": -int(settings.priority_drop_penalty), "value": 1},
    ]


def payload(session: Session, user_id: int, kind: str = TAXI, now=None) -> dict:
    """То, что уходит на экран: сколько баллов, за что и что их отнимает."""
    расклад = points_of(session, user_id, kind, now)
    расклад["kind"] = kind
    расклад["rules"] = explain(kind)
    if kind == COURIER:
        расклад["feed_delay_sec"] = courier_feed_delay_sec(расклад["points"])
    return расклад
