"""Долг по комиссии за ТАКСИ (Модель А «на доверии», Фаза 3).

Суть: за завершённый быстрый заказ (instant) водитель получает деньги напрямую
(нал / прямой СБП), а комиссию 8% ДОЛЖЕН платформе. Раз в неделю водитель сам переводит
долг Александру по СБП и жмёт «Я оплатил» (unpaid → pending); Александр (админ) подтверждает
(pending → paid) или отклоняет (pending → unpaid). Просроченный неоплаченный долг (или сумма
неоплаченного > порога) → режим ТАКСИ блокируется, пока не погасит.

ВАЖНО: блокируется ТОЛЬКО такси (instant). ПОПУТКА (плановые Ride/Booking) — отдельный поток,
её долг по комиссии НЕ трогает.

Принципы (деньги — критично):
  • Только целые копейки (int amount_kop). Комиссия считается тем же fee_kop_for, что и ledger
    (Decimal ROUND_HALF_UP — без float-дрейфа).
  • Начисление идемпотентно: на один заказ — не больше одной записи долга (гейт по order_id).
  • Долг — append-запись на заказ; статус меняем, историю сумм не переписываем.

Приватность: суммы не логируем с привязкой к персоне — только id.
"""
from datetime import date, datetime, timedelta
from typing import Optional

from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from . import promo_ride
from .config import settings
from .ledger import driver_balance, fee_kop_for, post_promo_compensation, promo_comp_ext_id
from .models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, LedgerEntry, LedgerKind,
    Report, TaxiApplication, TaxiApplicationStatus, User,
)
from .timeutil import local_date, utcnow


def _week_key(dt) -> str:
    """ISO-неделя начисления, напр. '2026-W28' — по ней группируем долг для оплаты."""
    y, w, _ = dt.isocalendar()
    return f"{y}-W{w:02d}"


def _launch_promo_percent(session: Session, driver_id: int, now) -> Optional[float]:
    """Промо запуска «первым водителям — 0%»: если заявка таксиста одобрена ДО даты
    launch_promo_until (ISO, из конфига) и с одобрения прошло ≤ launch_promo_days — водитель
    платит launch_promo_percent. Пустая дата → промо выключено (None = промо не действует)."""
    raw = settings.launch_promo_until.strip()
    if not raw:
        return None
    try:
        promo_until = date.fromisoformat(raw)
    except ValueError:
        return None                     # кривая дата в конфиге → промо не применяем, не падаем
    app = session.exec(select(TaxiApplication).where(
        TaxiApplication.user_id == driver_id,
        TaxiApplication.status == TaxiApplicationStatus.approved,
    )).first()
    if app is None:
        return None
    approved_at = app.reviewed_at or app.created_at
    # Дата одобрения — по Уфе, как и дата окончания набора в конфиге. По мировому времени
    # водитель, одобренный 1 сентября в 2 часа ночи, попадал в августовский набор и получал
    # 0% комиссии, которого не заслужил: пять часов каждые сутки платформа раздавала промо
    # опоздавшим (аудит 2026-08-08, волна 155).
    if approved_at is None or local_date(approved_at) > promo_until:
        return None                     # одобрен после окна набора — промо не для него
    if now > approved_at + timedelta(days=settings.launch_promo_days):
        return None                     # промо-период истёк — дальше обычная лесенка
    return settings.launch_promo_percent


def driver_fee_percent(session: Session, driver_id: int, now=None) -> float:
    """Процент комиссии для водителя (лесенка 3% → 5% → 8%, §5 Деньги).

    Стаж = дни с ПЕРВОГО его завершённого (done) быстрого заказа:
    ≤ fee_tier1_days → fee_tier1_percent; ≤ fee_tier2_days → fee_tier2_percent;
    дальше — service_fee_percent (навсегда). Промо запуска (одобрен до launch_promo_until)
    перекрывает лесенку на первые launch_promo_days дней."""
    now = now or utcnow()
    promo = _launch_promo_percent(session, driver_id, now)
    if promo is not None:
        return promo
    first_done = session.exec(
        select(InstantOrder.done_at).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at.is_not(None),                     # noqa: E711
        ).order_by(InstantOrder.done_at)
    ).first()
    if first_done is None:
        return settings.fee_tier1_percent      # первый заказ — стаж 0 дней
    days = (now - first_done).days
    if days <= settings.fee_tier1_days:
        return settings.fee_tier1_percent
    if days <= settings.fee_tier2_days:
        return settings.fee_tier2_percent
    return settings.service_fee_percent


def _promo_comp_kop(session: Session, order_ids: list) -> int:
    """Сколько платформа доплатила водителю в кошелёк по этим заказам (компенсация промокода).
    Читаем ФАКТ из ledger, а не пересчитываем формулу — деньги должны сходиться с историей."""
    if not order_ids:
        return 0
    total = session.exec(
        select(func.coalesce(func.sum(LedgerEntry.amount_kop), 0)).where(
            LedgerEntry.kind == LedgerKind.adj,
            LedgerEntry.ext_id.in_([promo_comp_ext_id(i) for i in order_ids]),
        )
    ).one()
    return int(total or 0)


# Приставка в note у долга, которого водитель фактически НЕ платит: списан админом или снят
# по разбору жалобы. Отдельного статуса под это в DebtStatus нет (там только unpaid/pending/paid),
# поэтому «почему paid» хранится в note — и обе стороны договорённости держатся за эту константу.
WRITTEN_OFF_PREFIX = "Списан"


def fee_charged_kop(d: Optional[CommissionDebt]) -> int:
    """Сколько комиссии по этому долгу реально осталось на водителе, копейки.

    Долга нет (промо 0% / грошовый заказ) → ноль. Долг списан админом или снят по разбору →
    тоже ноль: водителю пришёл пуш «комиссию сняли», и если после этого экран расшифровки
    продолжает её вычитать, мы врём в минус тому самому человеку, ради спора с которым
    экран и делали (аудит 2026-08-07 — раньше тут стоял вырожденный тернарник, обе ветки
    которого возвращали одно и то же).

    А вот долг, погашенный ОНЛАЙН-оплатой, показываем: комиссия там реально удержана, просто
    не долгом, а записью fee в кошельке. Спрятать её — значит завысить заработок."""
    if d is None:
        return 0
    if d.status == DebtStatus.paid and (d.note or "").startswith(WRITTEN_OFF_PREFIX):
        return 0
    return max(int(d.amount_kop or 0), 0)


def driver_dashboard(session: Session, driver_id: int, now: Optional[datetime] = None) -> dict:
    """Данные дашборда таксиста для кабинета: заработок и заказы ЗА СЕГОДНЯ + текущая ступень
    комиссии. Лесенка комиссии — по СТАЖУ (дни с первого done-заказа), не по деньгам:
    первый месяц дешевле, потом растёт. Показываем честно, когда ступень поднимется.

    earnings_today — сумма фактических цен (price_final, ₽) завершённых такси-заказов за
    местный день; fee_percent — сколько платформа берёт сейчас (с учётом промо запуска)."""
    now = now or utcnow()
    tz = timedelta(hours=settings.local_tz_offset_hours)
    ln = now + tz                                        # местное «сейчас»
    start_utc = datetime(ln.year, ln.month, ln.day) - tz  # местная полночь → обратно в UTC
    end_utc = start_utc + timedelta(days=1)
    done_today = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at >= start_utc,
            InstantOrder.done_at < end_utc,
        )
    ).all()
    earnings = sum(int(o.price_final if o.price_final is not None else o.price_estimate) for o in done_today)
    gross_today_kop = earnings * 100
    promo_disc_kop = promo_comp_kop = 0
    if done_today:
        order_ids = [o.id for o in done_today if o.id is not None]
        debts_today = session.exec(
            select(CommissionDebt).where(CommissionDebt.order_id.in_(order_ids))
        ).all()
        fee_today_kop = sum(fee_charged_kop(d) for d in debts_today)
        # Промокод пассажира: на руки водитель получил меньше на размер скидки, зато комиссия
        # уменьшена, а остаток пришёл в кошелёк. Без этих двух слагаемых «чистыми» врало бы.
        promo_disc_kop = sum(max(int(o.promo_discount_kop or 0), 0) for o in done_today)
        promo_comp_kop = _promo_comp_kop(session, order_ids) if promo_disc_kop else 0
    else:
        fee_today_kop = 0
    net_today_kop = max(gross_today_kop - promo_disc_kop - fee_today_kop + promo_comp_kop, 0)

    percent = driver_fee_percent(session, driver_id, now)
    first_done = session.exec(
        select(InstantOrder.done_at).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
            InstantOrder.done_at.is_not(None),
        ).order_by(InstantOrder.done_at.asc()).limit(1)
    ).first()
    tenure_days = (now - first_done).days if first_done else 0
    if tenure_days <= settings.fee_tier1_days:
        next_percent, days_to_next = settings.fee_tier2_percent, settings.fee_tier1_days - tenure_days
    elif tenure_days <= settings.fee_tier2_days:
        next_percent, days_to_next = settings.service_fee_percent, settings.fee_tier2_days - tenure_days
    else:
        next_percent, days_to_next = None, None       # верхняя ступень — дальше не растёт
    return {
        # Backward compatibility: earnings_today остаётся валовой суммой в ₽.
        # Новые поля — точная денежная расшифровка в целых копейках.
        "earnings_today": earnings,
        "gross_today_kop": gross_today_kop,
        "fee_today_kop": fee_today_kop,
        # Скидки пассажиров по промокодам за сегодня и компенсация их платформой — чтобы в
        # кабинете было видно, откуда разница между ценой поездки и деньгами в руках.
        "promo_discount_today_kop": promo_disc_kop,
        "promo_comp_today_kop": promo_comp_kop,
        "net_today_kop": net_today_kop,
        "orders_today": len(done_today),
        "fee_percent": percent,
        "tenure_days": tenure_days,
        "fee_tiers": [settings.fee_tier1_percent, settings.fee_tier2_percent, settings.service_fee_percent],
        "fee_tier_days": [settings.fee_tier1_days, settings.fee_tier2_days],
        "fee_next_percent": next_percent,
        "fee_days_to_next": days_to_next,
    }


def _local_day_expr(session: Session, column):
    """SQL-выражение «локальный день» (строка YYYY-MM-DD) с учётом пояса — для GROUP BY.
    Портируемо: SQLite (strftime + модификатор часов) и PostgreSQL (сдвиг на interval).
    Смещение — наш собственный int-конфиг (не пользовательский ввод), инъекции нет."""
    offset_h = int(settings.local_tz_offset_hours)
    dialect = session.get_bind().dialect.name if session.get_bind() is not None else "sqlite"
    if dialect.startswith("postgres"):
        return func.to_char(column + timedelta(hours=offset_h), "YYYY-MM-DD")
    return func.strftime("%Y-%m-%d", column, f"{offset_h:+d} hours")


def unpaid_confirmed_order_ids(session: Session, driver_id: int) -> set:
    """Заказы этого водителя, по которым РАЗБОР подтвердил «пассажир не заплатил».

    Зачем (аудит 2026-08-08, волна 190). Водителя кинули на 620 ₽, он нажал «пассажир
    не заплатил», админ жалобу подтвердил и снял с него комиссию за эту поездку — всё
    правильно. А в заработке она осталась. Мало того: комиссия снялась, и «чистыми» по ней
    стало БОЛЬШЕ, чем по честной поездке. Приложение показывало человеку, что он заработал
    ровно ту сумму, которую ему не отдали, и выглядело это лучше обычной поездки.

    Водитель планирует по этому числу бензин, платёж и продукты. Число, которое он читает
    как факт, обязано быть фактом.

    Считаем по подтверждённой жалобе, а не по метке `unpaid_reported`: метку ставит сам
    водитель нажатием кнопки, и до разбора это его слово, а не установленный факт.
    """
    rows = session.exec(
        select(Report.order_id).where(
            Report.category == "unpaid",
            Report.status == "resolved",
            Report.order_id.is_not(None),
            Report.reporter_id == driver_id,
        )
    ).all()
    return {int(r) for r in rows if r}


def unpaid_confirmed_parcel_ids(session: Session, courier_id: int) -> set:
    """Доставки этого курьера, по которым РАЗБОР подтвердил, что ему не заплатили.

    Пара к `unpaid_confirmed_order_ids` (такси). Правило одно, наборы разные: там заказы,
    здесь доставки. Держим рядом, чтобы не разъехались, — у курьера цена ошибки выше:
    в «купи и привези» он тратит на товар СВОИ деньги (волна 191).
    """
    rows = session.exec(
        select(Report.parcel_id).where(
            Report.category == "unpaid",
            Report.status == "resolved",
            Report.parcel_id.is_not(None),
            Report.reporter_id == courier_id,
        )
    ).all()
    return {int(r) for r in rows if r}


def driver_earnings(session: Session, driver_id: int, period: str = "week",
                    now: Optional[datetime] = None) -> dict:
    """История заработка водителя за период (week|month|all): суммарно + разбивка по дням.

    База суммы — та же, что «заработок за сегодня» в driver_dashboard: фактическая цена
    завершённого такси-заказа (price_final, иначе price_estimate), ₽. Считаем SQL-агрегатом
    (SUM/COUNT и GROUP BY по локальному дню), НЕ тянем заказы в память.

    Только СВОИ данные (фильтр по driver_id). period: week — последние 7 локальных дней,
    month — 30, all — за всё время. Пустой период → total=0, trips=0, by_day=[]."""
    now = now or utcnow()
    period = period if period in ("week", "month", "all") else "week"
    conds = [
        InstantOrder.driver_id == driver_id,
        InstantOrder.status == InstantOrderStatus.done,
        InstantOrder.done_at.is_not(None),
    ]
    if period != "all":
        tz = timedelta(hours=settings.local_tz_offset_hours)
        ln = now + tz                                        # местное «сейчас»
        days_back = 6 if period == "week" else 29            # включая сегодня → 7 / 30 дней
        start_local = datetime(ln.year, ln.month, ln.day) - timedelta(days=days_back)
        conds.append(InstantOrder.done_at >= start_local - tz)   # местная полночь → обратно в UTC

    price_expr = func.coalesce(InstantOrder.price_final, InstantOrder.price_estimate)
    # Поездки, по которым разбор признал, что денег водитель не получил, в заработок
    # не идут — но и не исчезают: их сумма называется отдельно (волна 190). Спрятать
    # совсем было бы вторым обманом: работу он сделал, и она должна быть видна.
    кинутые = unpaid_confirmed_order_ids(session, driver_id)
    if кинутые:
        conds.append(InstantOrder.id.not_in(кинутые))
    total_row = session.exec(
        select(func.coalesce(func.sum(price_expr), 0), func.count()).where(*conds)
    ).one()
    total_sum = int(total_row[0] or 0)
    total_trips = int(total_row[1] or 0)

    day_expr = _local_day_expr(session, InstantOrder.done_at)
    rows = session.exec(
        select(day_expr.label("day"), func.coalesce(func.sum(price_expr), 0), func.count())
        .where(*conds).group_by(day_expr).order_by(day_expr)
    ).all()
    by_day = [{"date": str(r[0]), "sum": int(r[1] or 0), "trips": int(r[2] or 0)} for r in rows]
    # Сколько за тот же период не отдали. Считаем теми же условиями, но по обратному набору.
    неоплачено_сумма = неоплачено_поездок = 0
    if кинутые:
        строка = session.exec(
            select(func.coalesce(func.sum(price_expr), 0), func.count()).where(
                *[c for c in conds if c is not conds[-1]], InstantOrder.id.in_(кинутые))
        ).one()
        неоплачено_сумма = int(строка[0] or 0)
        неоплачено_поездок = int(строка[1] or 0)
    return {"period": period, "total": total_sum, "trips": total_trips, "by_day": by_day,
            # Разбор подтвердил, что по этим поездкам не заплатили (волна 190).
            "unpaid_total": неоплачено_сумма, "unpaid_trips": неоплачено_поездок}


def driver_rides(session: Session, driver_id: int, limit: int = 100) -> dict:
    """Список ЗАВЕРШЁННЫХ поездок водителя с расшифровкой денег: цена, комиссия, чистыми.

    Раньше такого списка не существовало вообще: /instant/orders/mine фильтрует только по
    пассажиру, а заработок отдавался одной суммой за день. Спор «Юлдаш говорит 4200, я
    насчитал 4600 — где мои 400?» было нечем закрыть, и в таксопарке это причина №1 ухода
    водителя (аудит 2026-07-26). Комиссию берём фактическую — из начисленного долга по
    заказу (та самая сумма, которую он реально должен), а не пересчитываем задним числом."""
    limit = max(1, min(int(limit or 100), 200))
    orders = session.exec(
        select(InstantOrder).where(
            InstantOrder.driver_id == driver_id,
            InstantOrder.status == InstantOrderStatus.done,
        ).order_by(InstantOrder.done_at.desc(), InstantOrder.id.desc()).limit(limit)
    ).all()
    if not orders:
        return {"rides": [], "total_price": 0, "total_fee_kop": 0, "total_net_kop": 0}
    order_ids = [o.id for o in orders if o.id is not None]
    debts = {d.order_id: d for d in session.exec(
        select(CommissionDebt).where(CommissionDebt.order_id.in_(order_ids))
    ).all() if d.order_id is not None}
    # Компенсации промокодов по этим заказам (обычно пусто — лишний запрос не делаем).
    comps: dict = {}
    if any(int(o.promo_discount_kop or 0) > 0 for o in orders):
        comps = {e.order_id: int(e.amount_kop) for e in session.exec(
            select(LedgerEntry).where(
                LedgerEntry.kind == LedgerKind.adj,
                LedgerEntry.ext_id.in_([promo_comp_ext_id(i) for i in order_ids]),
            )
        ).all() if e.order_id is not None}
    кинутые = unpaid_confirmed_order_ids(session, driver_id)
    rides, total_price, total_fee, total_net = [], 0, 0, 0
    for o in orders:
        price_rub = int(o.price_final if o.price_final is not None else o.price_estimate)
        d = debts.get(o.id)
        # Долга нет (промо 0% / грошовый заказ / списан) → комиссия по этой поездке ноль.
        fee_kop = fee_charged_kop(d)
        # Промокод: на руки водитель взял меньше на скидку, зато комиссия уже уменьшена, а
        # остаток скидки платформа вернула в кошелёк. «Чистыми» = как будто промокода не было.
        disc_kop = max(int(o.promo_discount_kop or 0), 0)
        comp_kop = comps.get(o.id, 0)
        net_kop = price_rub * 100 - disc_kop - fee_kop + comp_kop
        # По подтверждённой жалобе «пассажир не заплатил» на руки не пришло ничего.
        # Строку оставляем — работа была, и в споре её надо видеть, — но «чистыми» ноль,
        # и в итоги она не идёт (волна 190).
        не_заплатили = o.id in кинутые
        if не_заплатили:
            net_kop = 0
        else:
            total_price += price_rub
            total_fee += fee_kop
            total_net += net_kop
        rides.append({
            "order_id": o.id,
            "done_at": o.done_at,
            "from": o.from_text or "", "to": o.to_text or "",
            "price": price_rub,                       # ₽, как показываем пассажиру
            "promo_discount_kop": disc_kop,           # скидка пассажира (её оплатила платформа)
            "promo_comp_kop": comp_kop,               # доплата платформы в кошелёк
            "fee_kop": fee_kop,                       # комиссия платформы, копейки
            "net_kop": net_kop,                       # «чистыми» водителю
            "paid": bool(o.paid),
            "unpaid_confirmed": не_заплатили,     # разбор признал: денег не было (волна 190)
            "payment_method": o.payment_method or "",
            "fee_status": (d.status.value if d and hasattr(d.status, "value") else
                           (d.status if d else "none")),
        })
    return {"rides": rides, "total_price": total_price, "total_fee_kop": total_fee,
            "total_net_kop": total_net}


def order_commission_kop(order: InstantOrder, percent: Optional[float] = None) -> int:
    """Комиссия платформы по завершённому такси-заказу, копейки.
    База = финальная цена (или оценка) в ₽ → копейки; процент — лесенка по стажу
    (driver_fee_percent) либо service_fee_percent, если процент не передан."""
    price_rub = int(order.price_final if order.price_final is not None else order.price_estimate)
    if price_rub <= 0:
        return 0
    return fee_kop_for(price_rub * 100, percent)


def _clock_starts(now: datetime) -> datetime:
    """С какого момента считать часы на оплату: ночью — с утра, днём — прямо сейчас.

    Поездка, законченная в час ночи, деньгами не поможет: банк спит вместе с человеком.
    Отсчитывать от неё три часа — значит заблокировать такси к четырём утра за то, что
    водитель не сделал перевод посреди ночи. Границу берём ту же, что у тихих часов пушей,
    и по башкирскому времени: сервер живёт в UTC, а спит человек по своим часам."""
    end = int(settings.quiet_hours_to or 0)
    if end <= 0:
        return now                       # тихие часы выключены — сдвигать нечего
    start = int(settings.quiet_hours_from or 0)
    local = now + timedelta(hours=settings.local_tz_offset_hours)
    quiet = (local.hour >= start or local.hour < end) if start > end else (start <= local.hour < end)
    if not quiet:
        return now
    morning = local.replace(hour=end, minute=0, second=0, microsecond=0)
    if local.hour >= end:                # ночь ДО полуночи → утро уже следующего дня
        morning += timedelta(days=1)
    return morning - timedelta(hours=settings.local_tz_offset_hours)


def _due_at(now: datetime, amount_kop: int, pay_now_allowed: bool = True) -> datetime:
    """Когда наступает срок оплаты этой комиссии.

    Обычный городской заказ идёт в недельный цикл (debt_due_days). Крупная комиссия —
    дальний межгород — гасится сразу после поездки: у водителя на руках только что
    полученные за неё деньги, и просить в этот момент честнее и легче, чем через неделю.
    Порог и льготные часы — в конфиге, правятся без пересборки.

    `pay_now_allowed=False` — заказ закрыл не водитель, а ночной автомат. Тогда короткий срок
    ставить нельзя: момент окончания поездки мы не знаем, водитель в этот час спит и пуша не
    видел, а к утру он был бы уже заблокирован за просрочку, о которой его не предупреждали.
    Такой долг идёт в обычный недельный цикл."""
    if not (pay_now_allowed and amount_kop >= settings.debt_now_threshold_kop):
        return now + timedelta(days=settings.debt_due_days)
    return _clock_starts(now) + timedelta(hours=settings.debt_now_grace_hours)


def is_pay_now(d: CommissionDebt) -> bool:
    """Это долг «оплатить сразу после поездки», а не из недельного цикла?

    Смотрим на окно самой записи (due_at − created_at), а НЕ на текущий порог в конфиге:
    иначе смена порога задним числом переписала бы историю и водитель увидел бы «срочно»
    там, где ему обещали неделю.

    Окно должно быть ПОЛОЖИТЕЛЬНЫМ. Срок, оказавшийся раньше начисления, окна не задаёт —
    это долг, которому дату сдвинули руками (админка, миграция, тестовая эмуляция просрочки),
    и «меньше суток» там получается из отрицательного числа. Без этой проверки любой такой
    долг выглядел бы «оплати сегодня» — а по нему теперь ещё и решается, блокировать ли
    водителя, который о сроке не знал."""
    if d.due_at is None or d.created_at is None:
        return False
    return timedelta(0) <= (d.due_at - d.created_at) < timedelta(days=1)


def accrue_for_order(session: Session, order: InstantOrder,
                     pay_now_allowed: bool = True) -> Optional[CommissionDebt]:
    """Начислить долг по комиссии за завершённый такси-заказ. Идемпотентно.

    `pay_now_allowed=False` зовёт ночная чистка за водителя, который не нажал «Завершил»:
    крупную комиссию в этом случае в «оплатить сразу» не переводим (см. `_due_after`).

    Вызывать ПОСЛЕ перехода в done. На один order_id заводим не больше одной записи долга —
    повторный тап «done» (идемпотентный переход) не задваивает долг. Нулевая комиссия
    (бесплатный/грошовый заказ) долг не создаёт."""
    if order.driver_id is None:
        return None
    existing = session.exec(
        select(CommissionDebt).where(CommissionDebt.order_id == order.id)
    ).first()
    if existing:
        return existing                       # уже начислено — не задваиваем
    now = utcnow()
    # Ступень фиксируем на момент создания: upfront net в оффере и фактический долг совпадут,
    # даже если короткая поездка пересекла календарную границу тарифной ступени.
    percent = driver_fee_percent(session, order.driver_id, order.created_at or now)
    full = order_commission_kop(order, percent)
    # Скидку пассажира по промокоду оплачивает ПЛАТФОРМА, а не водитель: сначала гасим её своей
    # комиссией (вплоть до нуля), остаток кладём водителю в кошелёк. Водитель в любом случае
    # получает столько же, как без промокода (см. app/promo_ride.py).
    amount, comp = promo_ride.split_commission(full, order.promo_discount_kop)
    if comp > 0:
        post_promo_compensation(session, order.driver_id, order.id, comp)   # идемпотентно по ext_id
    if amount <= 0:
        # Долга по этому заказу нет, но компенсация могла пополнить кошелёк — и ею закрывается
        # долг за прошлые поездки (волна 154).
        settle_debt_from_wallet(session, order.driver_id, now)
        return None                           # нулевая комиссия (промо 0% / грошовый заказ) — долг не заводим
    debt = CommissionDebt(
        driver_id=order.driver_id,
        order_id=order.id,
        amount_kop=amount,
        week=_week_key(now),
        status=DebtStatus.unpaid,
        created_at=now,
        due_at=_due_at(now, amount, pay_now_allowed),
    )
    session.add(debt)
    try:
        session.commit()
    except IntegrityError:
        # Гонка: параллельный «done» успел вставить долг по этому order_id первым (UNIQUE order_id).
        # Не задваиваем — откатываемся и возвращаем уже существующую запись.
        session.rollback()
        return session.exec(
            select(CommissionDebt).where(CommissionDebt.order_id == order.id)
        ).first()
    # Деньги платформы, уже лежащие у водителя в кошельке (компенсация промо-скидки), гасят
    # свежий долг сразу — иначе он видит недоступную сумму и одновременно долг за ту же поездку.
    settle_debt_from_wallet(session, order.driver_id, now)
    session.refresh(debt)
    return debt


def void_debt_for_order(session: Session, order_id: int, note: str = "") -> bool:
    """B2: снять долг по комиссии за заказ, оплаченный ОНЛАЙН (Модель Б).

    На done заказа всегда заводится долг Модели А («водитель взял нал напрямую, должен комиссию»).
    Если пассажир затем оплатил заказ картой/СБП через платформу, комиссия уже удержана в ledger
    (fee), а деньги получила платформа — значит долг Модели А фиктивен. Помечаем его paid, иначе
    водитель обложен комиссией дважды, а фантомный unpaid-долг блокирует ему такси.

    Идемпотентно. НЕ коммитит — вызывается внутри транзакции settle_* (та и коммитит).

    note — ПОЧЕМУ долг стал paid. Это не косметика: от причины зависит расшифровка заработка
    (см. fee_charged_kop). По умолчанию — «сняли по разбору», то есть комиссию с водителя НЕ
    взяли (так зовёт жалоба «пассажир не заплатил»). Онлайн-оплата передаёт свой note: там
    комиссия удержана, просто записью fee в кошельке."""
    debt = session.exec(
        select(CommissionDebt).where(CommissionDebt.order_id == order_id)
    ).first()
    if debt is None or debt.status == DebtStatus.paid:
        return False
    debt.status = DebtStatus.paid
    debt.confirmed_at = utcnow()
    if not debt.note:                      # свой note (напр. от админского «простить») не трогаем
        debt.note = note or f"{WRITTEN_OFF_PREFIX}: снят по разбору"
    session.add(debt)
    return True


# Причина, по которой долг стал paid без перевода по СБП: его закрыли деньгами, которые уже
# лежали у водителя в кошельке. Комиссию тут ВЗЯЛИ (записью fee), поэтому приставка намеренно
# не WRITTEN_OFF_PREFIX — иначе расшифровка заработка спрятала бы её и завысила «чистыми».
WALLET_PAID_NOTE = "Оплачен из кошелька"


def taxi_owed_kop(session: Session, driver_id: Optional[int]) -> int:
    """Сколько водитель ещё должен платформе за такси (unpaid + pending), копейки.

    Лёгкая сумма для денежных проверок: `debt_summary` тянет разбивку по неделям и реквизиты,
    а здесь нужно одно число.
    """
    if driver_id is None:
        return 0
    total = session.exec(
        select(func.coalesce(func.sum(CommissionDebt.amount_kop), 0)).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status != DebtStatus.paid,
        )
    ).one()
    return max(int(total or 0), 0)


def settle_debt_from_wallet(session: Session, driver_id: Optional[int],
                            now: Optional[datetime] = None) -> int:
    """Погасить долг по комиссии тем, что уже лежит у водителя в кошельке. Возврат — копейки.

    Зачем (решение Александра, аудит 2026-08-08, волна 154). Компенсацию промо-скидки платформа
    кладёт водителю в кошелёк (post_promo_compensation), но вывести её нельзя — выплаты на карту
    выключены до оформления ИП. Получалось смешное: пассажир поехал по промокоду, водитель видит
    «281,40 ₽» и не может их тронуть, и при этом ДОЛЖЕН платформе 18,60 ₽ комиссии за ту же
    поездку. Деньги платформы у водителя и долг водителя платформе гасят друг друга — так это
    и должно работать, без банковских договоров.

    Правила:
      • Гасим строго по старшинству (FIFO, старые долги первыми) и ТОЛЬКО целиком: суммы долга
        не переписываем — долг append-запись на заказ, у него меняется статус, а не цифра.
        Не хватило на самый старый — останавливаемся, деньги ждут. Иначе свежий мелкий долг
        погасился бы, а старый просроченный продолжал блокировать такси.
      • Списание из кошелька — отдельная запись fee (−сумма). Без неё долг исчезал бы, а число
        в кошельке оставалось прежним, то есть деньги брались бы из воздуха.
      • Идемпотентно: paid-долги не трогаем, повторный вызов при пустом балансе возвращает 0.
      • Только по ОПЛАЧЕННОМУ заказу. Долг заводится в момент «Завершил», а способ оплаты
        выясняется позже: если пассажир потом заплатит картой, комиссия удержится записью fee,
        а долг Модели А снимется как фиктивный (void_debt_for_order). Погаси мы его кошельком
        раньше — водитель заплатил бы комиссию дважды: один раз из кошелька, второй при оплате.
        Ровно это и поймал прогон на промо-заказе. Пока заказ не оплачен, долг не окончателен.
    """
    if driver_id is None:
        return 0
    balance = driver_balance(session, driver_id)
    if balance <= 0:
        return 0
    rows = session.exec(
        select(CommissionDebt).join(
            InstantOrder, InstantOrder.id == CommissionDebt.order_id
        ).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status != DebtStatus.paid,
            InstantOrder.paid == True,          # noqa: E712 — способ оплаты уже известен
        ).order_by(CommissionDebt.created_at, CommissionDebt.id)
    ).all()
    now = now or utcnow()
    total = 0
    for d in rows:
        amount = max(int(d.amount_kop or 0), 0)
        if amount == 0:
            continue
        if total + amount > balance:
            break                              # на старейший долг не хватило — дальше не идём
        d.status = DebtStatus.paid
        d.confirmed_at = now
        if not d.note:                         # свой note (напр. админское «простить») не трогаем
            d.note = WALLET_PAID_NOTE
        session.add(d)
        total += amount
    if total <= 0:
        return 0
    session.add(LedgerEntry(
        driver_id=driver_id, kind=LedgerKind.fee, amount_kop=-total,
        note="Комиссия за такси удержана из кошелька",
    ))
    session.commit()
    return total


def _unpaid(session: Session, driver_id: int) -> list[CommissionDebt]:
    return session.exec(
        select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status == DebtStatus.unpaid,
        )
    ).all()


def _pending(session: Session, driver_id: int) -> list[CommissionDebt]:
    return session.exec(
        select(CommissionDebt).where(
            CommissionDebt.driver_id == driver_id,
            CommissionDebt.status == DebtStatus.pending,
        )
    ).all()


# Сколько дней «слово» водителя («Я оплатил») снимает блокировку такси БЕЗ подтверждения
# деньгами. Отдельной настройки в конфиге сознательно нет: это не тариф, который крутят под
# город, а срок доверия — один для всех и заметный в коде.
#
# Зачем срок вообще (аудит 2026-08-07). Раньше pending не блокировал и не протухал: раз в
# неделю водитель жал «Я оплатил», весь долг уходил в pending, блок снимался — и так до
# бесконечности. Защита declare_abuse включалась только если админ РУКАМИ отклонит заявку:
# счётчик обещаний растёт лишь при новом «Я оплатил», а долг, зависший в pending, второй раз
# не заявляют. Александр один, отклонять каждую заявку вручную он не может — значит комиссию,
# единственный доход платформы, можно было не платить вообще.
#
# Три дня, а не семь: обычно перевод подтверждается за вечер; неделя = ещё одна бесплатная
# неделя работы на каждое нажатие кнопки.
DECLARE_TRUST_DAYS = 3


def crossed_warn_line(session: Session, driver_id: int, just_added_kop: int) -> Optional[int]:
    """Долг ЭТИМ заказом дорос до предупредительной линии? Вернёт сумму долга или None.

    Считаем «до» вычитанием только что начисленного, а не отдельным запросом до начисления:
    так предупреждение уходит РОВНО ОДИН раз — в тот заказ, которым линия пересечена. Иначе
    водитель получал бы его после каждой следующей поездки, а повторяющееся предупреждение
    перестают читать ровно к тому моменту, когда оно становится важным.

    Уже перешагнувших порог блокировки не трогаем: у них такси и так закрыто, и «скоро
    закроется» было бы враньём — им нужен другой разговор, не этот."""
    if settings.debt_warn_ratio <= 0 or just_added_kop <= 0:
        return None
    line = int(settings.debt_block_threshold_kop * settings.debt_warn_ratio)
    if line <= 0:
        return None
    after = sum(d.amount_kop for d in _unpaid(session, driver_id))
    if after > settings.debt_block_threshold_kop:
        return None
    before = after - just_added_kop
    return after if before < line <= after else None


def _stale_declares(pending: list[CommissionDebt], now) -> list[CommissionDebt]:
    """Долги, где «слово» протухло: заявили оплату, а деньги так и не подтвердились.
    Фолбэк на created_at — для строк, где заявления не было (ручной pending из админки/миграции):
    у них отсчёт идёт от начисления, «вечного доверия» не остаётся ни у кого."""
    edge = now - timedelta(days=DECLARE_TRUST_DAYS)
    return [d for d in pending if (d.paid_declared_at or d.created_at) < edge]


def taxi_block_reason(session: Session, driver_id: int, now=None) -> Optional[str]:
    """Причина блокировки такси для водителя или None (можно возить).

    Блокируем, если есть ПРОСРОЧЕННЫЙ неоплаченный долг (due_at < now) ИЛИ сумма неоплаченного
    долга превысила порог debt_block_threshold_kop. Долг в статусе pending (водитель заявил
    оплату, ждём админа) НЕ блокирует — работаем «на доверии». Ничего не должен → None.

    ⚠️ Доверяем, но не бесконечно (аудит 2026-07-26). Раньше кнопку «Я оплатил» можно было
    жать без счёта: заявил → pending → блок снят; админ отклонил → долг вернулся в unpaid →
    нажал снова → снова работает. Комиссию можно было не платить вообще, а это вся выручка
    платформы. Теперь долг, по которому «слово» давали больше debt_max_declares раз, в
    pending блокировку НЕ снимает — ждём подтверждения деньгами.

    ⚠️ И у самого «слова» есть срок (аудит 2026-08-07): pending старше DECLARE_TRUST_DAYS
    блокировку тоже не снимает — иначе долг висел бы в pending вечно и счётчик обещаний не
    рос бы никогда. Выход из этого состояния честный: чистка возвращает протухший pending в
    unpaid (cleanup.expire_stale_declares), водитель может заявить оплату снова — но уже под
    счётчик, то есть ограниченное число раз."""
    now = now or utcnow()
    driver = session.get(User, driver_id)
    return _reason_from(_unpaid(session, driver_id), _pending(session, driver_id), now,
                        last_seen=getattr(driver, "last_seen_at", None))


def _had_a_chance_to_know(d: CommissionDebt, last_seen) -> bool:
    """Мог ли водитель вообще узнать про этот долг: заходил ли он в приложение после начисления.

    Нужно только короткому сроку «дальней поездки». Обычный недельный водитель не пропустит:
    неделю он в приложение зайдёт. А три часа — пропустит запросто, и виноват в этом будет
    не он: пуш уходит молча в никуда, если уведомления выключены в системе, если у аккаунта
    нет ни одного устройства, если Firebase не настроен или если антишторм проглотил событие
    (services.push_notification: ни одна из этих веток наружу не сообщает).

    Без этой проверки короткий срок стал бы тихой блокировкой: человек возит людей, ничего
    не видел и не слышал, а через три часа такси у него отключено. Это ровно та ловушка,
    ради которой ночные поездки и автозакрытие короткого срока не получают (см. `_due_at`);
    здесь закрыта третья её дверь — «предупреждение не дошло».

    Лазейки не открывает: недельный срок и порог блокировки по сумме остаются в силе, так
    что «не заходить в приложение» от долга не спасает — только оттягивает до общих правил."""
    if d.created_at is None:
        return True                    # нечего сравнивать — ведём себя как раньше
    return last_seen is not None and last_seen >= d.created_at


def _reason_from(unpaid: list, pending: list, now, last_seen=None) -> Optional[str]:
    """Решение по уже собранным долгам одного водителя. Вынесено, чтобы ТОЧНО ТА ЖЕ логика
    работала и в пакетной проверке круга подбора (волна 60) — иначе гейт на ручке и фильтр
    в подборе разъедутся, а разъезжаются такие пары всегда."""
    # Долги, где доверие исчерпано: обещали оплату N+ раз, подтверждения так и нет.
    abused = [d for d in pending if (d.declare_count or 0) > settings.debt_max_declares]
    if abused:
        return "declare_abuse"
    if _stale_declares(pending, now):
        return "declare_stale"        # слово дали, деньги не пришли — доверие на паузе
    if not unpaid:
        return None
    overdue = [d for d in unpaid if d.due_at is not None and d.due_at < now]
    # Короткий срок блокирует только того, у кого был шанс о нём узнать (см. выше).
    overdue = [d for d in overdue if not is_pay_now(d) or _had_a_chance_to_know(d, last_seen)]
    if overdue:
        return "overdue"
    if sum(d.amount_kop for d in unpaid) > settings.debt_block_threshold_kop:
        return "over_threshold"
    return None


def blocked_driver_ids(session: Session, driver_ids: list, now=None) -> set:
    """Кто из этих водителей заблокирован долгом — ОДНИМ запросом на весь круг.

    Нужна подбору такси: там до полутора десятков кандидатов, и спрашивать про каждого
    отдельно значило бы три десятка запросов на каждый заказ. Решение принимает та же
    `_reason_from`, что и одиночный гейт."""
    if not driver_ids:
        return set()
    now = now or utcnow()
    rows = session.exec(
        select(CommissionDebt).where(
            CommissionDebt.driver_id.in_(list(driver_ids)),
            CommissionDebt.status.in_([DebtStatus.unpaid, DebtStatus.pending]),
        )
    ).all()
    # «Заходил ли в приложение» — тем же одним запросом на весь круг: `_reason_from` смотрит
    # на это для короткого срока, и без него пакетная проверка судила бы строже одиночной.
    seen = dict(session.exec(
        select(User.id, User.last_seen_at).where(User.id.in_(list(driver_ids)))
    ).all())
    by_driver: dict = {}
    for d in rows:
        u, p = by_driver.setdefault(d.driver_id, ([], []))
        (u if d.status == DebtStatus.unpaid else p).append(d)
    return {did for did, (u, p) in by_driver.items()
            if _reason_from(u, p, now, last_seen=seen.get(did)) is not None}


# Понятная ошибка блокировки такси (RU — серверная строка; UI локализует через appText).
# Оба языка (волна 177): башкироязычный водитель видел «нет доступа» вместо причины —
# и не понимал, что дело в долге и что с этим делать.
TAXI_BLOCKED_MSG = "Оплати долг сервису, чтобы возить такси"
TAXI_BLOCKED_MSG_BA = "Такси йөрөтөр өсөн сервисҡа бурысты түлә"


def debt_summary(session: Session, driver_id: int) -> dict:
    """Сводка долга для кабинета водителя: сколько должен, до какой даты, реквизиты СБП,
    блокировка. Разбивка по неделям — для наглядности."""
    now = utcnow()
    unpaid = _unpaid(session, driver_id)
    pending = _pending(session, driver_id)
    unpaid_kop = sum(d.amount_kop for d in unpaid)
    pending_kop = sum(d.amount_kop for d in pending)
    due_dates = [d.due_at for d in unpaid if d.due_at is not None]
    earliest_due = min(due_dates) if due_dates else None
    reason = taxi_block_reason(session, driver_id, now)

    # Разбивка по неделям (unpaid + pending) — свежие сверху.
    by_week: dict[str, dict] = {}
    for d in unpaid + pending:
        w = by_week.setdefault(d.week, {"week": d.week, "amount_kop": 0, "status": d.status.value})
        w["amount_kop"] += d.amount_kop
        if d.status == DebtStatus.pending:
            w["status"] = DebtStatus.pending.value   # ждёт подтверждения — важнее показать
    weeks = sorted(by_week.values(), key=lambda x: x["week"], reverse=True)

    # Сколько из неоплаченного — «сразу после поездки» (дальний межгород). Клиент по этой
    # сумме поднимает экран оплаты не дожидаясь, пока водитель сам зайдёт в кабинет.
    pay_now_kop = sum(d.amount_kop for d in unpaid if is_pay_now(d))
    pay_now_due = min([d.due_at for d in unpaid if is_pay_now(d) and d.due_at], default=None)

    return {
        "unpaid_kop": unpaid_kop,
        "pending_kop": pending_kop,
        "due_at": earliest_due.isoformat() if earliest_due else None,
        "overdue": reason == "overdue",
        "blocked": reason is not None,
        "block_reason": reason,
        "threshold_kop": settings.debt_block_threshold_kop,
        "sbp": {"phone": settings.owner_sbp_phone, "name": settings.owner_sbp_name},
        "weeks": weeks,
        # Аддитивные поля (старый клиент их просто не читает).
        "pay_now_kop": pay_now_kop,
        "pay_now_due_at": pay_now_due.isoformat() if pay_now_due else None,
    }


def declare_paid(session: Session, driver_id: int) -> int:
    """Водитель заявил оплату: все его unpaid-долги → pending (ждут подтверждения админом).
    Возврат: сумма переведённого в pending (копейки). Ничего не должен → 0.

    Считаем, сколько раз по каждому долгу давали «слово»: после debt_max_declares отказов
    заявка больше не снимает блокировку (см. taxi_block_reason) — иначе комиссию можно было
    не платить вообще, бесконечно нажимая кнопку."""
    now = utcnow()
    unpaid = _unpaid(session, driver_id)
    total = 0
    for d in unpaid:
        d.status = DebtStatus.pending
        d.paid_declared_at = now
        d.declare_count = (d.declare_count or 0) + 1
        session.add(d)
        total += d.amount_kop
    if unpaid:
        session.commit()
    return total


def mark_all_paid(session: Session, driver_id: int, up_to: Optional[datetime] = None) -> int:
    """Погасить долг водителя (unpaid + pending) → paid. Используется при оплате картой
    (ЮKassa): подтверждение приходит вебхуком, деньги уже у платформы, админ не нужен.
    Идемпотентно (уже paid не трогаем). Возврат: погашенная сумма (копейки).

    up_to (граница снапшота): гасим только долг, начисленный ДО момента создания платежа
    (created_at <= up_to). Иначе долг, накопленный в окне между «жму оплатить» и подтверждением,
    погасился бы бесплатно. None → без границы (весь долг)."""
    now = utcnow()
    conds = [
        CommissionDebt.driver_id == driver_id,
        CommissionDebt.status != DebtStatus.paid,
    ]
    if up_to is not None:
        conds.append(CommissionDebt.created_at <= up_to)
    rows = session.exec(select(CommissionDebt).where(*conds)).all()
    total = 0
    for d in rows:
        d.status = DebtStatus.paid
        d.confirmed_at = now
        session.add(d)
        total += d.amount_kop
    if rows:
        session.commit()
    return total


def admin_confirm(session: Session, debt_id: int) -> Optional[int]:
    """Админ подтвердил перевод: ВЕСЬ pending-долг этого водителя → paid (блок снят).
    debt_id — любая запись из батча водителя (в /admin/debts группируем по водителю).
    Возврат: подтверждённая сумma (копейки) или None, если долг не найден."""
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        return None
    now = utcnow()
    pending = _pending(session, debt.driver_id)
    total = 0
    for d in pending:
        d.status = DebtStatus.paid
        d.confirmed_at = now
        session.add(d)
        total += d.amount_kop
    if pending:
        session.commit()
    return total


def admin_reject(session: Session, debt_id: int) -> Optional[int]:
    """Админ отклонил (деньги не пришли): pending-долг водителя → обратно unpaid.
    Возврат: сумма возвращённого в unpaid (копейки) или None, если долг не найден."""
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        return None
    pending = _pending(session, debt.driver_id)
    total = 0
    for d in pending:
        d.status = DebtStatus.unpaid
        d.paid_declared_at = None
        session.add(d)
        total += d.amount_kop
    if pending:
        session.commit()
    return total
