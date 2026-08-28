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
from datetime import datetime, timedelta
from typing import Optional

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


def apply_rating(session: Session, rater: User, ratee_id: int, *, stars: int,
                 text: str = "", tags: str = "", booking_id: Optional[int] = None,
                 order_id: Optional[int] = None, place: str = "rating",
                 happened_at: Optional[datetime] = None) -> tuple[float, int]:
    """Поставить/обновить оценку и пересчитать витрину. Возвращает (среднее, число оценок).

    Текст идёт на модерацию: помечаем (не режем и не рвём сохранение) и публикуем только после
    решения админа. Метки — из закрытого списка, ими не оскорбить, поэтому они видны сразу.
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
        if d_cnt > 0:
            from . import quality
            quality.maybe_low_rating_advice(session, ratee_id, d_avg)
    return avg, cnt
