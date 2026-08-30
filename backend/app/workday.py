"""8-часовой лимит смены такси + отдых водителя (волна 2, §8 «Отдых водителя»).

Суть: безопасность = продукт. Водитель, отработавший taxi_shift_limit_hours (8ч) на линии
ТАКСИ за местный день, отправляется отдыхать — гейт (в стиле долгового, app/debt.py)
закрывает presence/offer/accept до разблокировки. АКТИВНЫЙ заказ не рубим: переходы
arrived/onboard/done текущего заказа работают (они и не ходят через водительский гейт).

Учёт: на каждом POST /instant/presence прибавляем интервал от прошлого heartbeat с кэпом
workday_step_cap_sec (редкие пинги не накручивают часы). Считается ТОЛЬКО такси-время —
попутка (Ride/Booking) presence не шлёт.

Разблокировка (все условия сразу): наступил СЛЕДУЮЩИЙ местный день И местное время
≥ rest_unlock_hour (06:00) И прошло ≥ rest_hours (8ч) от последнего heartbeat дня лимита.
Часовой пояс — конфиг local_tz_offset_hours (Уфа UTC+5), БД везде наивный UTC (timeutil).

«Один попутчик домой»: во время блока водитель может опубликовать ОДНУ попутку
(смена кончилась в другом городе — машина всё равно едет назад). Вторая публикация или
отклик на заявку до разблокировки → мягкий 403. ВНЕ блока попутка не ограничена вообще.

Вежливость (≤2 пуша за период отдыха, дедуп флагами): «хорошо поработал» при лимите +
зимней ночью (ноя–мар, 20:00–07:00 местного) совет про тепло и заряд. Плюс предупреждения
об остатке ≤60 и ≤15 минут — по одному разу за смену.
"""
from datetime import date as date_type, datetime, time as time_type, timedelta
from typing import Optional

from fastapi import HTTPException
from sqlmodel import Session, select

from .config import settings
from .models import TaxiWorkDay
from .services import push_bilingual
from .timeutil import utcnow


# ------------------------------ местное время ------------------------------
def _tz() -> timedelta:
    return timedelta(hours=settings.local_tz_offset_hours)


def local_now(now: Optional[datetime] = None) -> datetime:
    """Местное «сейчас» (наивное): UTC + local_tz_offset_hours."""
    return (now or utcnow()) + _tz()


def local_day(now: Optional[datetime] = None) -> date_type:
    """Местный календарный день — граница смены проходит по местной полуночи."""
    return local_now(now).date()


def shift_limit_sec() -> int:
    return settings.taxi_shift_limit_hours * 3600


def is_winter_night(now: Optional[datetime] = None) -> bool:
    """Зимняя ночь по-местному: месяц ноя–мар, 20:00–07:00."""
    ln = local_now(now)
    return (ln.month >= 11 or ln.month <= 3) and (ln.hour >= 20 or ln.hour < 7)


# ------------------------------ сообщения (RU + черновой BA, финал — за Александром) ------------------------------
def rest_block_message() -> str:
    h = settings.taxi_shift_limit_hours
    u = settings.rest_unlock_hour
    return (f"Ты сегодня за рулём {h} часов — отдохни 🌙 Завтра с {u} утра снова на линию."
            f" · Һин бөгөн {h} сәғәт руль артында — ял ит 🌙 Иртәгә иртәнге {u}-нан йәнә линияға.")


def return_ride_message() -> str:
    return ("Возьми одного попутчика домой и отдохни 🌙 Завтра попутка снова без ограничений."
            " · Бер юлдашты өйгә алып ҡайт та ял ит 🌙 Иртәгә юлдаш йәнә сикләүһеҙ.")


# ------------------------------ строки учёта ------------------------------
def _get_day(session: Session, driver_id: int, day: date_type) -> Optional[TaxiWorkDay]:
    return session.exec(
        select(TaxiWorkDay).where(TaxiWorkDay.driver_id == driver_id, TaxiWorkDay.day == day)
    ).first()


def _get_or_create_today(session: Session, driver_id: int, now: datetime) -> TaxiWorkDay:
    day = local_day(now)
    wd = _get_day(session, driver_id, day)
    if wd is not None:
        return wd
    wd = TaxiWorkDay(driver_id=driver_id, day=day)
    session.add(wd)
    try:
        session.commit()
    except Exception:  # noqa: BLE001 — гонка двух heartbeat на unique(driver_id, day)
        session.rollback()
        wd = _get_day(session, driver_id, day)
        if wd is None:
            raise
        return wd
    session.refresh(wd)
    return wd


def _last_limited(session: Session, driver_id: int) -> Optional[TaxiWorkDay]:
    """Последний день, в который водитель упёрся в лимит (кандидат на действующий блок)."""
    return session.exec(
        select(TaxiWorkDay).where(
            TaxiWorkDay.driver_id == driver_id,
            TaxiWorkDay.limit_reached_at.is_not(None),   # noqa: E711
        ).order_by(TaxiWorkDay.day.desc())
    ).first()


# ------------------------------ блок / разблокировка ------------------------------
def unlock_at(wd: TaxiWorkDay) -> datetime:
    """Момент снятия блока (наивный UTC): max(следующий местный день rest_unlock_hour;
    последний heartbeat дня лимита + rest_hours). Так выполняются все три условия §8:
    следующий день, ≥06:00 местного, ≥8ч отдыха."""
    morning_local = datetime.combine(wd.day + timedelta(days=1), time_type(hour=settings.rest_unlock_hour))
    morning_utc = morning_local - _tz()
    rest_from = wd.last_heartbeat_at or wd.limit_reached_at or morning_utc
    return max(morning_utc, rest_from + timedelta(hours=settings.rest_hours))


def blocking_workday(session: Session, driver_id: int, now: Optional[datetime] = None) -> Optional[TaxiWorkDay]:
    """Действующий блок отдыха: строка дня лимита, пока now < unlock_at. Нет блока → None."""
    now = now or utcnow()
    wd = _last_limited(session, driver_id)
    if wd is None:
        return None
    return wd if now < unlock_at(wd) else None


# ------------------------------ недельный лимит ------------------------------
def week_seconds(session: Session, driver_id: int, now: Optional[datetime] = None) -> int:
    """Сколько такси-времени водитель наездил за последние 7 местных дней.

    Зачем (разбор №2, 2026-08-03): дневной лимит был, недельного — нет. Восемь часов в день
    семь дней подряд — это 56 часов за руль без единого выходного, и формально всё в порядке.
    Именно так и накапливается усталость, из-за которой случаются ночные аварии на трассе
    Сибай–Уфа: каждый отдельный день выглядит нормальным.
    """
    now = now or utcnow()
    today = local_day(now)
    since = today - timedelta(days=6)      # сегодня + шесть предыдущих = неделя
    rows = session.exec(
        select(TaxiWorkDay).where(
            TaxiWorkDay.driver_id == driver_id,
            TaxiWorkDay.day >= since,
            TaxiWorkDay.day <= today,
        )
    ).all()
    присутствие = {r.day: int(r.seconds_online or 0) for r in rows}
    # Каждый день недели меряем ТЕМ ЖЕ прибором, что и текущую смену: большее из присутствия
    # и фактических рейсов (волны 171 и 211). Раньше суммировалось только присутствие, и
    # сорок часов за неделю не набирались у двоих сразу: у курьера, который на такси-линию
    # не выходит и строк дня не имеет вовсе, и у таксиста, ездившего там, где нет сети.
    # Максимум берём ПО ДНЯМ, а не по всему окну: неделя из дня «только присутствие» и дня
    # «только рейсы» иначе схлопнулась бы в один из них.
    фактические = _work_seconds_by_day(session, driver_id, since, today, now)
    return sum(max(присутствие.get(д, 0), фактические.get(д, 0))
               for д in set(присутствие) | set(фактические))


def _work_seconds_by_day(session: Session, driver_id: int, с_дня: date_type,
                         по_день: date_type, now: datetime) -> dict:
    """Фактическая работа по местным дням окна — ОДНИМ запросом на весь диапазон.

    Семь отдельных вызовов `work_seconds_today` дали бы четырнадцать запросов на каждом
    presence-пинге; здесь заказы и доставки читаются один раз и раскладываются по дням.
    """
    начало = datetime.combine(с_дня, time_type()) - _tz()
    конец = datetime.combine(по_день + timedelta(days=1), time_type()) - _tz()
    интервалы = _work_intervals(session, driver_id, начало, конец, now)
    по_дням: dict = {}
    for a, b in интервалы:
        день = local_day(a)
        while день <= по_день:
            граница_с = datetime.combine(день, time_type()) - _tz()
            граница_по = граница_с + timedelta(days=1)
            кусок = (max(a, граница_с), min(b, граница_по))
            if кусок[1] > кусок[0]:
                по_дням.setdefault(день, []).append(кусок)
            if b <= граница_по:
                break
            день += timedelta(days=1)
    return {д: _union_seconds(куски) for д, куски in по_дням.items()}


def week_limit_sec() -> int:
    return settings.taxi_week_limit_hours * 3600


def week_block_until(session: Session, driver_id: int, now: Optional[datetime] = None) -> Optional[datetime]:
    """Достигнут недельный потолок → до какого момента водитель отдыхает.

    Отдых считаем до начала следующего местного дня: неделя — скользящая, и завтра самый
    старый день выпадет из окна, освободив часы. Это мягче фиксированной «недели с
    понедельника»: водитель не оказывается заблокированным на пять суток подряд.
    """
    if settings.taxi_week_limit_hours <= 0:
        return None                                   # 0 = лимит выключен
    now = now or utcnow()
    if week_seconds(session, driver_id, now) < week_limit_sec():
        return None
    tomorrow_local = datetime.combine(local_day(now) + timedelta(days=1), time_type(hour=0))
    return tomorrow_local - _tz()


def week_block_message() -> str:
    h = settings.taxi_week_limit_hours
    # Не обещаем «завтра снова на линию»: окно скользящее, и завтра часы освободятся только
    # если самый старый день выпал из недели. Пообещать и не пустить — хуже, чем не обещать.
    return (f"За неделю уже {h} часов за рулём — сегодня отдыхай 🌙 Линия откроется, "
            f"как только за последние 7 дней станет меньше {h} часов."
            f" · Аҙнаға {h} сәғәт руль артында — бөгөн ял ит 🌙 Һуңғы 7 көндә {h} сәғәттән "
            f"аҙыраҡ булыу менән линия асыла.")


def guard_rested(session: Session, driver_id: int, now: Optional[datetime] = None) -> None:
    """Гейт отдыха: блок по дневной смене или неделе → 403 с тёплым текстом.

    Стоит на такси (presence/offer/accept) И на приёме курьерских заказов (2026-08-29).
    Раньше был только на такси, и лимит получался наполовину декоративным: отработал смену
    таксистом — и весь вечер вози посылки. Усталость не спрашивает, человек в машине или
    коробка; ночная трасса Сибай–Акъяр одинаковая в обоих случаях.

    Переходы уже принятого заказа НЕ трогает: заказ, начатый до лимита, надо довезти —
    бросить пассажира или посылку посреди дороги хуже, чем доработать полчаса.
    """
    now = now or utcnow()
    _stamp_limit_if_reached(session, driver_id, now)
    wd = blocking_workday(session, driver_id, now)
    if wd is None:
        # Дневной лимит не сработал — проверяем недельный. Порядок именно такой: дневной
        # конкретнее и его текст понятнее («ты сегодня 8 часов за рулём»).
        if week_block_until(session, driver_id, now) is not None:
            raise HTTPException(403, week_block_message())
        return
    _maybe_winter_advice(session, driver_id, wd, now)
    raise HTTPException(403, rest_block_message())


def _stamp_limit_if_reached(session: Session, driver_id: int, now: datetime) -> None:
    """Отметить выработанную смену, если её ещё никто не отметил (волна 212).

    Флаг `limit_reached_at` писал ровно один вызов — `record_heartbeat`, а зовут его только
    из `POST /instant/presence`, куда пускают лишь водителя с включённым «Я на линии»
    в ТАКСИ. Человек, который весь день возит посылки и такси не касался, туда не заходит
    ни разу — и гейт отдыха, поставленный волной 194 на приём курьерского заказа, честно
    срабатывал на флаг, которого никогда не было.

    Считаем живьём тем же прибором, что и смену таксиста, и ставим ту же отметку: дальше
    работают ровно те же `unlock_at`, текст и пуш. Одно место решает «смена выработана»,
    а не два — иначе двери разъедутся, как уже было с витриной поездки (волна 210).

    Строку дня заводим ТОЛЬКО когда лимит действительно достигнут: иначе у каждого, кто
    просто открыл приложение, появлялась бы пустая строка — и попадала в недельный счёт.
    """
    if blocking_workday(session, driver_id, now) is not None:
        # Блок уже идёт — второй поверх него ставить НЕЛЬЗЯ. Человек, которому осталось
        # доспать час, каждой отклонённой попыткой продлевал бы себе отдых на новые сутки,
        # и выйти на линию он не смог бы уже никогда. Поймано соседним тестом про неполный
        # отдых: в 06:30 отказ, в 07:35 обязан быть допуск.
        return
    if shift_seconds(session, driver_id, None, now) < shift_limit_sec():
        return
    wd = _get_or_create_today(session, driver_id, now)
    if wd.limit_reached_at is not None:
        return
    wd.limit_reached_at = now
    session.add(wd)
    session.commit()
    _push_limit_reached(session, driver_id, wd, now)


# Старое имя. Гейт перестал быть «только про такси», но зовут его из нескольких мест —
# переименование одним махом ничего не улучшило бы, а шанс пропустить вызов есть.
guard_taxi_rested = guard_rested


# ------------------------------ вежливые пуши (дедуп флагами) ------------------------------
def _maybe_winter_advice(session: Session, driver_id: int, wd: TaxiWorkDay, now: datetime) -> None:
    """Блок зимней ночью → один (дедуп) совет про тепло и заряд. Коммитим флаг ДО пуша."""
    if not is_winter_night(now) or wd.winter_push_sent:
        return
    wd.winter_push_sent = True
    session.add(wd)
    session.commit()
    push_bilingual(session, driver_id,
                   "Береги себя ❄️", "Үҙеңде һаҡла ❄️",
                   "Зимняя ночь — прогрей машину, проверь заряд телефона и одевайся теплее.",
                   "Ҡышҡы төн — машинаңды йылыт, телефондың зарядын тикшер, йылыраҡ кейен.")


def _push_limit_reached(session: Session, driver_id: int, wd: TaxiWorkDay, now: datetime) -> None:
    h = settings.taxi_shift_limit_hours
    u = settings.rest_unlock_hour
    push_bilingual(session, driver_id,
                   "Хорошо поработал 👏", "Яҡшы эшләнең 👏",
                   f"{h} часов на линии позади. Отдохни — завтра с {u} утра снова в путь 🌙",
                   f"{h} сәғәт линияла үтте. Ял ит — иртәгә иртәнге {u}-нан йәнә юлға 🌙")
    _maybe_winter_advice(session, driver_id, wd, now)


def _maybe_warn(session: Session, driver_id: int, wd: TaxiWorkDay, remaining_sec: int) -> None:
    """Предупреждения об остатке смены: ≤60 мин и ≤15 мин, по одному разу (флаги-дедуп).
    Флаги ставит вызывающий record_heartbeat (коммит общий) — тут только отправка."""
    if remaining_sec <= 15 * 60 and not wd.warned_15:
        wd.warned_15 = True
        wd.warned_60 = True          # часовое уже неактуально — не шлём два подряд
        push_bilingual(session, driver_id,
                       "Осталось 15 минут смены", "Сменаға 15 минут ҡалды",
                       "Скоро отдых — спокойно заверши дела на линии 🌙",
                       "Оҙаҡламай ял — линиялағы эштәреңде тыныс ҡына тамамла 🌙")
    elif remaining_sec <= 60 * 60 and not wd.warned_60:
        wd.warned_60 = True
        push_bilingual(session, driver_id,
                       "Остался час смены", "Сменаға бер сәғәт ҡалды",
                       "Через час — заслуженный отдых. Планируй последние заказы 🌙",
                       "Бер сәғәттән — лайыҡлы ял. Һуңғы заказдарҙы планлаштыр 🌙")


# ------------------------------ учёт heartbeat ------------------------------
def carried_over_seconds(session: Session, driver_id: int, now: Optional[datetime] = None) -> int:
    """Сколько времени тянется в сегодняшний день из НЕЗАКОНЧЕННОЙ вчерашней смены.

    Смена не обрывается полуночью. Учёт вёлся по календарным суткам, и водитель, работавший
    «впритык», обходил правило об усталости, ни разу его не нарушив: 7,8 часа до полуночи
    (в лимит не упёрся → блока нет), в 00:01 счётчик с нуля — и ещё 8 часов. Итого почти
    шестнадцать часов за рулём подряд, и формально всё в порядке. Недельный лимит поймает это
    через несколько дней, а ночная трасса Сибай–Уфа — сегодня (проверено запросом, аудит
    2026-08-12, волна 54).

    Ровно та же слепота, из-за которой заводили недельный лимит: «каждый отдельный день
    выглядит нормальным». Только здесь она внутри суток.

    Смена считается продолжающейся, пока между последним выходом на линию и текущим моментом
    не прошёл положенный отдых (`rest_hours`). Поспал — начинаешь с нуля, и это ровно то,
    ради чего правило и написано."""
    now = now or utcnow()
    вчера = local_day(now) - timedelta(days=1)
    начало = datetime.combine(вчера, time_type()) - _tz()
    prev = _get_day(session, driver_id, вчера)
    интервалы = _work_intervals(session, driver_id, начало, начало + timedelta(days=1), now)

    # Хвост мерим ТЕМ ЖЕ прибором, что и текущий день: большее из присутствия и фактических
    # рейсов (волна 211). Раньше тут стояло только присутствие, а у него потолок на редкий
    # пинг — и три часа ночной трассы Сибай–Акъяр без сотовой сети переносились через
    # полночь как четыре минуты. Человек получал почти полные новые восемь часов.
    работа = max(int(prev.seconds_online or 0) if prev is not None else 0,
                 _union_seconds(интервалы))
    if работа <= 0:
        return 0

    # Момент, когда человек последний раз работал. Строки рабочего дня может не быть вовсе:
    # её заводит только такси-пинг, а курьер на такси-линию не выходит (волна 212). Тогда
    # «последний раз за рулём» — это конец последней вчерашней доставки.
    моменты = [b for _, b in интервалы]
    if prev is not None and prev.last_heartbeat_at:
        моменты.append(prev.last_heartbeat_at)
    if not моменты:
        # Работа в строке есть, а КОГДА она была — неизвестно: ни пинга, ни заказа. Такого
        # состояния живой код не создаёт (пинг всегда пишет время), это остаётся от прямого
        # посева. Не зная момента, отдых не отсчитать, поэтому и хвост не тянем. Попытка
        # подставить сюда «конец вчерашних суток» делала результат зависимым от часа
        # запуска — та самая ошибка, от которой в проекте отдельный урок.
        return 0
    if (now - max(моменты)) >= timedelta(hours=settings.rest_hours):
        return 0                      # отдых был — вчерашнее не тянем
    return работа


def resting_driver_ids(session: Session, driver_ids: list, now: Optional[datetime] = None) -> set:
    """Кто из этих водителей сейчас в блоке отдыха — ОДНИМ запросом на весь круг.

    Нужна подбору такси (волна 60): гейт `guard_taxi_rested` спрашивает про одного, а в круге
    кандидатов полтора десятка. Решение принимает тот же `unlock_at`, что и одиночный гейт,
    поэтому разъехаться они не могут."""
    if not driver_ids:
        return set()
    now = now or utcnow()
    rows = session.exec(
        select(TaxiWorkDay).where(
            TaxiWorkDay.driver_id.in_(list(driver_ids)),
            TaxiWorkDay.limit_reached_at.is_not(None),
        ).order_by(TaxiWorkDay.day.desc())
    ).all()
    latest: dict = {}
    for wd in rows:                      # строки отсортированы: первая на водителя — самая свежая
        latest.setdefault(wd.driver_id, wd)
    return {did for did, wd in latest.items() if now < unlock_at(wd)}


def last_online_at(session: Session, driver_id: int, now: Optional[datetime] = None):
    """Когда водитель последний раз был на линии (наивный UTC) или None, если ни разу.

    Смотрим сегодня и вчера: дальше в прошлое заглядывать незачем — любой перерыв длиннее
    суток заведомо больше отдыха. Нужен тем, кто отличает «идущую смену» от «новой»
    (предрейсовая самопроверка, волна 55)."""
    now = now or utcnow()
    today = local_day(now)
    stamps = []
    for day in (today, today - timedelta(days=1)):
        wd = _get_day(session, driver_id, day)
        if wd is not None and wd.last_heartbeat_at is not None:
            stamps.append(wd.last_heartbeat_at)
    return max(stamps) if stamps else None


def _union_seconds(интервалы) -> int:
    """Длина ОБЪЕДИНЕНИЯ интервалов, а не их сумма.

    Человек может везти пассажира и посылку одновременно — и это один час за рулём, а не
    два. Складывать интервалы значило бы наказывать ровно за то, ради чего он совмещает
    режимы: он не устал вдвое от того, что в багажнике коробка.

    Обратное тоже важно: час такси и час доставки ПОСЛЕ него — это два часа, и максимум
    из двух чисел (как считалось раньше между присутствием и заказами) их бы потерял.
    Объединение отвечает правильно в обоих случаях.
    """
    отрезки = sorted((с, по) for с, по in интервалы if по > с)
    всего = 0
    конец = None
    начало = None
    for с, по in отрезки:
        if начало is None:
            начало, конец = с, по
        elif с <= конец:                 # пересекается или примыкает — растягиваем текущий
            конец = max(конец, по)
        else:
            всего += int((конец - начало).total_seconds())
            начало, конец = с, по
    if начало is not None:
        всего += int((конец - начало).total_seconds())
    return всего


def _courier_intervals_today(session: Session, courier_id: int, начало_дня, конец_дня, now):
    """Отрезки, когда человек вёз ПОСЫЛКИ, обрезанные границами местного дня.

    Доставка — такая же работа за рулём, как поездка с пассажиром: те же километры, та же
    ночная трасса, та же усталость. До 29.08 она не считалась никуда, и водитель, упёршийся
    в восьмичасовой лимит такси, мог весь вечер возить коробки — формально отдыхая.
    """
    from .models import ParcelDelivery
    строки = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.courier_id == courier_id,
            ParcelDelivery.accepted_at.is_not(None),
            ParcelDelivery.accepted_at < конец_дня,
        )
    ).all()
    интервалы = []
    for p in строки:
        # Конец работы: вручил, вернул отправителю — или он всё ещё в пути.
        конец = p.delivered_at or getattr(p, "returned_at", None)
        if конец is None:
            if p.status in ("delivered", "canceled", "returned"):
                continue        # дело закрыто, а метки нет — честно посчитать нечем
            конец = now
        интервалы.append((max(p.accepted_at, начало_дня), min(конец, конец_дня, now)))
    return интервалы


def work_seconds_today(session: Session, driver_id: int,
                       now: Optional[datetime] = None,
                       день: Optional[date_type] = None) -> int:
    """Сколько человек фактически работал за рулём за местный день — по самим заказам.

    `день` — какие местные сутки считаем; по умолчанию сегодняшние. Вчерашние нужны хвосту
    смены через полночь (`carried_over_seconds`, волна 211): он раньше тянул только сигналы
    присутствия, и ночной рейс без связи обнулялся ровно на полуночном шве.

    Считаем ОБА режима: поездки с пассажирами и доставки. Раньше считались только такси-
    заказы, и это делало восьмичасовой лимит наполовину декоративным: отработал смену
    таксистом — и весь вечер вози посылки, счётчик их не видит.

    Зачем (аудит 2026-08-08, волна 171). Рабочее время копилось ТОЛЬКО из сигналов присутствия,
    и у каждого сигнала стоит кэп: редкий пинг добавляет не больше минуты. Кэп нужен — иначе
    один пинг раз в три часа записал бы три часа «работы». Но он же теряет настоящую работу
    там, где её больше всего.

    Проба: водитель час вёз пассажира по трассе, где связи нет. Пингов не было, вернулась
    связь — засчиталась **одна минута вместо часа**. Восьмичасовой лимит превращается
    в двенадцатичасовой ровно там, где усталость опаснее всего: ночная дорога между сёлами,
    где и сотовой сети нет, и встречный свет слепит.

    Считаем по фактам: заказ принят в 21:40, завершён в 22:50 — значит семьдесят минут человек
    был за рулём, что бы ни думала об этом сотовая сеть. Берём заказы, ЗАДЕТЫЕ текущим местным
    днём, и обрезаем их границами дня: рейс через полночь не должен целиком падать в один день.
    """
    now = now or utcnow()
    начало_дня = datetime.combine(день or local_day(now), time_type()) - _tz()
    return _union_seconds(_work_intervals(session, driver_id, начало_дня,
                                          начало_дня + timedelta(days=1), now))


def _work_intervals(session: Session, driver_id: int, начало, конец, now):
    """Отрезки работы за рулём в окне [начало, конец): такси-заказы и доставки одним списком.

    Час с пассажиром и час с посылкой — это два часа за рулём; если он вёз их одновременно —
    один. Объединение отрезков отвечает верно в обоих случаях, а сумма или максимум ошиблись
    бы в одном из них.
    """
    from .models import InstantOrder, InstantOrderStatus
    заказы = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.accepted_at.is_not(None),
            InstantOrder.accepted_at < конец,
            InstantOrder.status.in_([InstantOrderStatus.accepted, InstantOrderStatus.arriving,
                                     InstantOrderStatus.onboard, InstantOrderStatus.done]),
        )
    ).all()
    интервалы = []
    for o in заказы:
        конец_рейса = o.done_at or now           # заказ ещё идёт — считаем до сих пор
        интервалы.append((max(o.accepted_at, начало), min(конец_рейса, конец, now)))
    интервалы += _courier_intervals_today(session, driver_id, начало, конец, now)
    return [(a, b) for a, b in интервалы if b > a]


def shift_seconds(session: Session, driver_id: int, wd: Optional[TaxiWorkDay] = None,
                  now: Optional[datetime] = None) -> int:
    """Сколько водитель за рулём в ТЕКУЩЕЙ смене: сегодня + хвост незаконченной вчерашней.

    `wd` можно не передавать: строку сегодняшнего дня возьмём сами, а если её нет — сочтём
    присутствие нулём и обопрёмся на фактические рейсы. Строку при этом НЕ заводим: у чистого
    курьера её нет вовсе, а лимит к нему применяется тот же (волна 212).

    «Сегодня» — БОЛЬШЕЕ из двух измерений, а не их сумма (волна 171): время присутствия
    на линии и время фактических поездок. Максимум, потому что эти два числа описывают одно
    и то же время разными приборами, и складывать их значило бы считать один час дважды.
    Прибор, который видит больше, и ближе к правде: сеть могла молчать, а руль — нет.
    """
    now = now or utcnow()
    if wd is None:
        wd = _get_day(session, driver_id, local_day(now))
    присутствие = int(wd.seconds_online or 0) if wd is not None else 0
    сегодня = max(присутствие, work_seconds_today(session, driver_id, now))
    return сегодня + carried_over_seconds(session, driver_id, now)


def record_heartbeat(session: Session, driver_id: int, now: Optional[datetime] = None) -> TaxiWorkDay:
    """Учесть presence-heartbeat такси: +интервал от прошлого пинга (кэп
    workday_step_cap_sec — редкие heartbeat не накручивают). Первый пинг дня время не даёт.
    Достигли лимита → limit_reached_at + пуш «хорошо поработал» (один раз)."""
    now = now or utcnow()
    wd = _get_or_create_today(session, driver_id, now)
    if wd.last_heartbeat_at is not None:
        step = (now - wd.last_heartbeat_at).total_seconds()
        if step > 0:
            wd.seconds_online += int(min(step, settings.workday_step_cap_sec))
    wd.last_heartbeat_at = now
    # Лимит считаем по СМЕНЕ, а не по календарным суткам (см. carried_over_seconds).
    remaining = shift_limit_sec() - shift_seconds(session, driver_id, wd, now)
    if remaining <= 0 and wd.limit_reached_at is None:
        wd.limit_reached_at = now
        session.add(wd)
        session.commit()
        _push_limit_reached(session, driver_id, wd, now)
    else:
        _maybe_warn(session, driver_id, wd, remaining)
        session.add(wd)
        session.commit()
    session.refresh(wd)
    return wd


# ------------------------------ «один попутчик домой» ------------------------------
def guard_publish_ride(session: Session, driver_id: int, now: Optional[datetime] = None) -> None:
    """POST /rides во время блока отдыха: первая публикация — «один попутчик домой»
    (пропускаем и помечаем return_ride_used; флаг закоммитит сам эндпоинт вместе с
    поездкой — не съедаем попытку, если публикация упала). Вторая → мягкий 403.
    ВНЕ блока попутка не ограничена вообще — обычное поведение."""
    wd = blocking_workday(session, driver_id, now)
    if wd is None:
        return
    if not wd.return_ride_used:
        wd.return_ride_used = True
        session.add(wd)
        return
    raise HTTPException(403, return_ride_message())


def guard_respond_request(session: Session, driver_id: int, now: Optional[datetime] = None) -> None:
    """Отклик на заявку пассажира во время блока отдыха → мягкий 403 («домой — один
    попутчик из своей публикации, а не новые обязательства»). Вне блока — без ограничений."""
    if blocking_workday(session, driver_id, now) is not None:
        raise HTTPException(403, return_ride_message())


# ------------------------------ сводка водителю ------------------------------
def summary(session: Session, driver_id: int, now: Optional[datetime] = None) -> dict:
    """GET /instant/workday: прогресс смены для кабинета таксиста. Во время блока
    показываем день лимита (там же return_ride_used), иначе — сегодняшний местный день."""
    now = now or utcnow()
    blocked_wd = blocking_workday(session, driver_id, now)
    wd = blocked_wd or _get_day(session, driver_id, local_day(now))
    # Показываем ту же цифру, по которой считается лимит: со «хвостом» вчерашней смены, если
    # отдыха между ними не было (волна 54). Иначе в полночь прогресс визуально обнулялся бы,
    # а блок приходил бы «неожиданно» — хуже всего, когда правило кажется случайным.
    # Считаем и без строки дня: у курьера её может не быть вовсе, а часы за рулём есть
    # (волна 212). Раньше кабинет показывал такому человеку ноль и «осталось 8 часов».
    seconds = shift_seconds(session, driver_id, wd, now)
    limit = shift_limit_sec()
    return {
        "day": (wd.day if wd else local_day(now)).isoformat(),
        "seconds_online": seconds,
        "limit_sec": limit,
        "remaining_sec": max(0, limit - seconds),
        "limit_hours": settings.taxi_shift_limit_hours,
        "blocked": blocked_wd is not None,
        "unlock_at": unlock_at(blocked_wd).isoformat() if blocked_wd else None,
        "return_ride_used": bool(blocked_wd.return_ride_used) if blocked_wd else False,
    }
