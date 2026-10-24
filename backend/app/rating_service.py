# -*- coding: utf-8 -*-
"""Оценка поездки — одна логика на все виды поездок (аудит 2026-08-13, волна 57).

Оценивают у нас в двух местах: попутку (`POST /bookings/{id}/rate`) и такси
(`POST /instant/orders/{id}/rate`). Ручки писались в разное время и разошлись — при том, что
человек в обоих случаях делает одно и то же: ставит звёзды и пишет пару слов о поездке.

Что показала проба:

  • **такси молча теряло отзыв.** Схема запроса принимает `text` и `tags`, приложение их шлёт,
    а сервер выбрасывал: в базе оставались только звёзды. Человек написал о водителе — и
    ничего не произошло, даже сообщения об ошибке;
  • **текст отзыва по попутке не проходил модерацию.** Публикацию он ждёт (`text_published`),
    но счётчик и журнал помеченных текстов оставались пустыми — то есть админ не узнавал,
    что в отзывах пишут телефоны и оскорбления, пока сам не открыл бы очередь;
  • **срока на оценку не было вовсе.** Поездку 400-дневной давности можно оценить сегодня.
    Это уже не отзыв о поездке, а способ достать человека спустя год.

Здесь всё сведено в одну функцию: обе ручки зовут её и потому ведут себя одинаково.
"""
import hashlib
from datetime import datetime, timedelta
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import text as sa_text, update as sa_update
from sqlmodel import Session, select

from .antifraud import moderate_open_text
from .errors import herr
from .models import DriverProfile, Rating, User
from .safety_logic import account_paused, clean_tags
from .timeutil import utcnow

# Сколько живёт право оценить поездку. Щедро: человек заходит в приложение не каждый день, и
# отобрать у него голос через неделю было бы нечестно. Но и не бесконечно — оценка через год
# говорит уже не о поездке.
RATING_WINDOW_DAYS = 60

TOO_LATE_RU = "Эту поездку оценить уже нельзя — прошло слишком много времени."
TOO_LATE_BA = "Был сәфәрҙе баһалап булмай инде — бик күп ваҡыт үтте."


def guard_rating_window(happened_at: Optional[datetime], now: Optional[datetime] = None) -> None:
    """Не поздно ли оценивать. `happened_at` неизвестно → пропускаем (данных нет — не наказываем)."""
    if happened_at is None:
        return
    now = now or utcnow()
    if now - happened_at > timedelta(days=RATING_WINDOW_DAYS):
        raise herr(409, TOO_LATE_RU, TOO_LATE_BA)


# Сколько поездка считается «свежей» для оценки на паузе. Ровно столько же, сколько после
# поездки открыта связь между попутчиками: одно событие — одно окно.
FRESH_RATING_HOURS = 48

PAUSED_RU = ("Аккаунт на паузе: сейчас можно оценить только свежую поездку. "
             "Загляни в Центр справедливости — там причина и срок.")
PAUSED_BA = ("Аккаунт паузала: хәҙер тик яңы сәфәрҙе генә баһалап була. "
             "Ғәҙеллек үҙәгенә ин — сәбәбе һәм ваҡыты шунда.")


def guard_rating_on_pause(session: Session, rater_id: int,
                          happened_at: Optional[datetime] = None,
                          now: Optional[datetime] = None) -> None:
    """Пауза лестницы и оценки: свежую поездку оценить можно, старую — нет (волна 161).

    Тут встретились два правильных правила, и грань между ними тонкая.

    **Пауза не бросает людей на полдороге** (волна 64): наказание может прийти, когда пассажир
    уже в машине. Человек обязан довести начатое — довезти, закрыть поездку и сказать о ней
    правду. Молчаливые «пять звёзд» за опасную поездку не нужны никому.

    **Но отстраняют чаще всего именно за поведение с людьми** — нахамил, обманул с ценой,
    не приехал. Получив паузу, человек шёл по списку своих поездок за два месяца и раздавал
    единицы всем подряд. Наказание, которое не мешает продолжать поведение, за которое
    назначено, — декорация.

    Грань проходит по свежести поездки, а не по факту паузы: закончил сегодня — оценивай,
    это твой голос о том, что было. Пошёл по архиву — нет.
    """
    if not account_paused(session, rater_id):
        return
    now = now or utcnow()
    свежая = (happened_at is not None
              and now - happened_at <= timedelta(hours=FRESH_RATING_HOURS))
    if not свежая:
        raise herr(403, PAUSED_RU, PAUSED_BA)


def rating_allowed_now(session: Session, user_id: int, happened_at: Optional[datetime]) -> bool:
    """Те же проверки, что у `/rate`: срок `RATING_WINDOW_DAYS` и пауза аккаунта (ревью Opus 23.10:
    человек на паузе видел звёзды и получал отказ). Звёзды на экране — только когда оценка правда пройдёт."""
    try:
        guard_rating_window(happened_at)
        guard_rating_on_pause(session, user_id, happened_at)
    except HTTPException:
        return False
    return True


def booking_rating_open(booking, ride, user_id: int) -> bool:
    """Можно ли по состоянию брони этому человеку оценить вторую сторону попутки.

    Завершённую — обе стороны. Отменённую в пути (`visibility.cancelled_in_transit`) — только пассажир
    (решение Александра 24.10.2026): иначе водитель уходил от плохого отзыва, отменив поездку перед
    прибытием. Водителю после отмены в пути оценка пассажира закрыта — решение только про пассажира.
    Остальное (обычная отмена, неявка, «посадку отменили» — `onboard_at` снят) — нельзя: оценка без
    поездки — это накрутка. Срок и паузу проверяет `rating_allowed_now` / `guard_rating_*`."""
    if booking is None or ride is None:
        return False
    from .visibility import cancelled_in_transit   # лениво: visibility тянет services
    статус = booking.status.value if hasattr(booking.status, "value") else booking.status
    if статус == "done":
        return user_id in (booking.passenger_id, ride.driver_id)
    return user_id == booking.passenger_id and cancelled_in_transit(booking)


def _lock_rating_slot(session: Session, rater_id: int, booking_id: Optional[int],
                      order_id: Optional[int]) -> None:
    """Сериализует конкурентные попытки поставить ОДНУ оценку одного человека за одну поездку.

    Было (аудит leaf-3.2): «есть ли уже оценка» и «вставить новую строку» не были одной
    операцией. Двойная отправка — два открытых тапа по звёздам, повтор после обрыва связи
    в двух вкладках — шлёт два запроса ПОЧТИ одновременно. Оба успевают прочитать «оценки ещё
    нет» раньше, чем любой из них закоммитит свою, и оба вставляют СВОЙ `Rating`. Ответ при
    этом 200 у обеих вкладок — ошибки никакой, просто у водителя задвоилась одна звезда в
    среднем и «N отзывов» выросло на единицу без второй настоящей поездки. Юнит-констрейнта
    на (rater, booking)/(rater, order) в таблице нет (models.py — не наша зона), поэтому
    защищаемся блокировкой, а не отловом конфликта уникальности.

    Тот же приём, что `routers/auth.py::_lock_otp_phone`: на PostgreSQL — транзакционный
    advisory-lock по стабильному ключу (рейтер, поездка); на SQLite (тесты/демо) — холостой
    UPDATE, который забирает писательскую блокировку базы на время транзакции.

    Лочим ПОСЛЕ модерации текста и ДО чтения `existing`: у модерации своя короткая транзакция
    со своим commit (`log_text_flag`) — захваченный РАНЬШЕ неё advisory-lock освободился бы
    тем коммитом, так и не доработав до записи оценки.
    """
    slot = f"b{booking_id}" if booking_id is not None else f"o{order_id}"
    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        # Телефон/число не несём — ключ только из id, стабилен и воспроизводим между запросами.
        key = int.from_bytes(
            hashlib.sha256(f"yuldash-rating\0{rater_id}\0{slot}".encode()).digest()[:8],
            "big", signed=True)
        session.execute(sa_text("SELECT pg_advisory_xact_lock(CAST(:key AS bigint))"), {"key": key})
    elif dialect == "sqlite":
        session.execute(sa_update(Rating).where(Rating.id == -1).values(stars=Rating.stars))
    else:
        raise RuntimeError("Rating locking requires PostgreSQL or SQLite")


def apply_rating(session: Session, rater: User, ratee_id: int, *, stars: int,
                 text: str = "", tags: str = "", booking_id: Optional[int] = None,
                 order_id: Optional[int] = None, place: str = "rating",
                 happened_at: Optional[datetime] = None, advise: bool = True) -> tuple[float, int]:
    """Поставить/обновить оценку и пересчитать витрину. Возвращает (среднее, число оценок).

    Текст идёт на модерацию: помечаем (не режем и не рвём сохранение) и публикуем только после
    решения админа. Метки — из закрытого списка, ими не оскорбить, поэтому они видны сразу.

    `advise=False` — без пуша водителю «рейтинг просел» прямо сейчас. Нужен для оценки после отмены в
    пути (проверка плана 24.10.2026): водитель, только что высадивший человека, по такому пушу поймёт,
    кто поставил единицу, а пассажир, возможно, ещё на трассе. Балл и лестница считаются как обычно.
    """
    guard_rating_on_pause(session, rater.id, happened_at)
    stars = max(1, min(5, int(stars)))
    text = (text or "").strip()[:500]
    tags = clean_tags(tags)
    if text:
        # Отзыв виден в публичном профиле — то же открытое поле, что комментарий к поездке.
        # Раньше проверки тут не было вовсе, и телефон в отзыве не попадал даже в счётчик.
        moderate_open_text(text, rater.id, place=place,
                           ref_id=booking_id or order_id, session=session)

    # Двойная отправка (аудит leaf-3.2): без лока ДВЕ одновременные транзакции обе видят
    # «оценки ещё нет» и обе вставляют свой Rating. Лочим ПРЯМО ПЕРЕД чтением «есть ли уже»,
    # чтобы проверка и запись ниже видели согласованное состояние.
    _lock_rating_slot(session, rater.id, booking_id, order_id)
    cond = (Rating.booking_id == booking_id) if booking_id is not None else (Rating.order_id == order_id)
    existing = session.exec(select(Rating).where(cond, Rating.rater_id == rater.id)).first()
    if existing:
        existing.stars = stars
        # Пустое НЕ затирает уже поставленное: первый тап уходит со звёздами, метки прилетают
        # следующим запросом — иначе человек, поправивший звёзды, терял бы метки.
        if tags:
            existing.tags = tags
        if text and text != existing.text:
            existing.text = text
            existing.text_published = False   # текст сменился → снова на модерацию
        session.add(existing)
    else:
        session.add(Rating(booking_id=booking_id, order_id=order_id, rater_id=rater.id,
                           ratee_id=ratee_id, stars=stars, text=text, tags=tags,
                           text_published=False))
    session.commit()

    # локальный импорт: services тянет модели по кругу
    from .services import driver_rating, rated_as_driver, user_rating
    avg, cnt = user_rating(session, ratee_id)     # витрина: доверие человеку одно, все роли вместе

    # Санкции — по той роли, за которую наказываем (волна 194; у курьера то же чинили в 186).
    # `DriverProfile.rating` читает matcher (`instant_service._score`): просевший балл = меньше
    # заказов, то есть меньше денег. Оценка после поездки взаимная, и раньше сюда одинаково
    # приходили обе стороны — водителю переписывали его рабочий балл по единицам, которые он
    # получил, сидя ПАССАЖИРОМ в чужой машине.
    if rated_as_driver(session, ratee_id, booking_id=booking_id, order_id=order_id):
        d_avg, d_cnt = driver_rating(session, ratee_id)
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ratee_id)).first()
        if prof and d_cnt > 0:
            prof.rating = round(d_avg, 1)
            session.add(prof)
            session.commit()
        if d_cnt > 0 and advise:
            from . import quality
            quality.maybe_low_rating_advice(session, ratee_id, d_avg)
    return avg, cnt
