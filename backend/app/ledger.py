"""Деньги v1 (Фаза 3, D3) — ledger (кошелёк водителя) + комиссия + сверка.

Принципы (деньги — критично):
  • Только целые копейки (int amount_kop). Никаких float для сумм — округление комиссии
    через Decimal ROUND_HALF_UP (предсказуемо, без дрейфа float).
  • Ledger APPEND-ONLY: историю денег НЕ редактируем и НЕ удаляем. Баланс = SUM(amount_kop).
    Ошиблись — добавляем корректирующую запись kind=adj, а не правим старую.
  • Оплата поездки начисляет водителю ДВЕ записи: earn (+вся сумма) и fee (−комиссия).
    Баланс водителя за поездку = earn − fee (нетто ему причитается).
  • Наличные (method=cash) — деньги идут МИМО нас: помечаем заказ оплаченным, но ledger
    НЕ двигаем (комиссии/начисления через платформу нет).
  • Идемпотентность: начисление за заказ/бронь выполняется РОВНО один раз — под row-lock
    по строке заказа/брони, гейт по флагу paid. Повторный webhook не задваивает ledger.

Приватность: суммы не логируем с привязкой к персоне (телефон/имя) — только id.
"""
from decimal import ROUND_HALF_UP, Decimal
from typing import Optional

from sqlalchemy import Index, and_, func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from . import promo_ride
from .config import settings
from .models import Booking, InstantOrder, LedgerEntry, LedgerKind
from .timeutil import utcnow

# Способы оплаты, при которых деньги идут ЧЕРЕЗ нас (начисляем водителю через ledger).
# cash — мимо нас (ledger не трогаем).
_CASHLESS = ("card", "sbp", "yookassa")

# --- Барьер БД: одна компенсация промокода на заказ ------------------------------------
# Проверка «уже начисляли?» в коде и сама вставка — два разных шага, между ними успевает
# вклиниться параллельный «Завершил» (двойной тап, ретрай сети, дубль пуша): обе сессии
# видят «компенсации нет» и обе её пишут. Аудит 2026-08-07: водителю вместо 279 ₽ падало
# 558 ₽, причём кабинет показывал одну компенсацию, а дашборд — обе. Долг от той же гонки
# защищён UNIQUE(order_id) на уровне БД — компенсация такой защиты не имела.
#
# Индекс ЧАСТИЧНЫЙ, а не просто UNIQUE(ext_id): тем же ext_id помечаются выплата (kind=payout)
# и её возврат при отказе банка (kind=adj, ключ "payout:…"), а у earn/fee ext_id вообще пустой —
# сплошная уникальность запретила бы законные записи. Поэтому сужаем ровно до неймспейса
# компенсаций ("promo:{order_id}", см. promo_comp_ext_id).
#
# Индекс живёт здесь, а не в models.py, потому что смысл у него не «схема таблицы», а
# «инвариант денег этого модуля» — и объяснение обязано быть рядом с кодом, который на него
# опирается. Для прода то же самое делает миграция money_holes_20260807.
#
# substr вместо LIKE намеренно: одинаково читается на SQLite и PostgreSQL и не тащит символ
# «%» в DDL (в миграции он превратился бы в плейсхолдер драйвера).
_PROMO_COMP_WHERE = and_(
    LedgerEntry.__table__.c.kind == LedgerKind.adj,
    func.substr(LedgerEntry.__table__.c.ext_id, 1, 6) == "promo:",
)
Index(
    "uq_ledgerentry_promo_comp", LedgerEntry.__table__.c.ext_id, unique=True,
    sqlite_where=_PROMO_COMP_WHERE, postgresql_where=_PROMO_COMP_WHERE,
)


def fee_kop_for(amount_kop: int, percent: Optional[float] = None) -> int:
    """Комиссия сервиса в копейках. Детерминированно, ROUND_HALF_UP, всегда int.

    Пример: 15 000 коп × 15% = 2 250 коп. Считаем через Decimal, чтобы не ловить дрейф
    float на «некруглых» процентах (напр. 12.5%)."""
    if percent is None:
        percent = settings.service_fee_percent
    if amount_kop <= 0 or percent <= 0:
        return 0
    fee = (Decimal(amount_kop) * Decimal(str(percent)) / Decimal(100)).quantize(
        Decimal(1), rounding=ROUND_HALF_UP
    )
    return int(fee)


def driver_balance(session: Session, driver_id: int) -> int:
    """Баланс водителя в копейках = SUM(amount_kop) по всем его записям ledger.
    Никакого «изменяемого баланса» — всегда пересчёт из append-only истории."""
    # M5: считаем сумму в БД (SQL SUM), не тянем весь append-only ledger водителя в память.
    total = session.exec(
        select(func.coalesce(func.sum(LedgerEntry.amount_kop), 0)).where(LedgerEntry.driver_id == driver_id)
    ).one()
    return int(total or 0)


def owed_to_platform_kop(session: Session, driver_id: int) -> int:
    """Весь непогашенный долг человека платформе: такси + доставка, копейки.

    Долгов у одного человека может быть два вида, и живут они в разных таблицах: комиссия
    за такси — записями долга, комиссия курьера — флагом на самой доставке. Кошелёк при этом
    один. Считать «сколько он должен» по одному виду — значит недосчитать.
    """
    from . import debt as _debt
    from .routers import courier as _courier
    return _debt.taxi_owed_kop(session, driver_id) + _courier._commission_owed_kop(session, driver_id)


def payable_balance(session: Session, driver_id: int) -> int:
    """Сколько водитель реально может вывести на карту: баланс минус долг платформе.

    Без этой поправки кошелёк работал в одну сторону (аудит 2026-08-08, волна 156). Проба:
    в кошельке 600 ₽, долг по комиссии 500 ₽ — водитель выводил все 600 на карту, долг
    оставался неоплаченным, и платформа теряла деньги, которые сама же ему и доплатила.
    Особенно обидно, что доплатила она их компенсацией промо-скидки: платформа оплатила
    скидку пассажира, а водитель забрал компенсацию и остался должен комиссию.
    """
    return max(driver_balance(session, driver_id) - owed_to_platform_kop(session, driver_id), 0)


def ledger_entries(session: Session, driver_id: int, limit: int = 100) -> list[LedgerEntry]:
    """Записи ledger водителя (свежие сверху) — для экрана кошелька/истории."""
    return session.exec(
        select(LedgerEntry).where(LedgerEntry.driver_id == driver_id)
        .order_by(LedgerEntry.id.desc()).limit(max(1, min(limit, 500)))
    ).all()


class PayoutError(Exception):
    """Отказ вывода средств (границы / недостаточно баланса / провайдер).
    code — машинный (для тестов/логики), message — человеку (RU, на клиент)."""
    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


def request_payout(session: Session, driver_id: int, amount_kop: int, *,
                   payout_token: str = "", card_last4: str = "",
                   idempotency_key: str = "") -> dict:
    """Вывод с баланса водителя на карту (Модель Б). Идемпотентно; лок держим коротко.

    Инварианты денег: сумма в границах [min, max]; нельзя вывести больше баланса; списание
    пишется в ledger записью kind=payout (−сумма) РОВНО один раз на ключ идемпотентности (ext_id).
    Полный номер карты НЕ фигурирует — платим по токену, в note храним только последние 4.

    V7: ключ идемпотентности ОБЯЗАТЕЛЕН. Пустой ключ → отказ: без него проверка «уже проведён»
    не срабатывает и повтор запроса (дабл-тап / ретрай сети) провёл бы ВТОРУЮ реальную выплату.
    V8: списание резервируем под КОРОТКИМ row-lock и коммитим ДО вызова банка — сам HTTP к провайдеру
    (ЮKassa, ~30 с) идёт УЖЕ БЕЗ лока. Иначе row-lock строки водителя висел бы весь HTTP и под нагрузкой
    вычерпал бы пул соединений БД. Резерв (списание до банка) не даёт параллельному выводу увести баланс
    в минус. Явный отказ банка → компенсируем append-only записью (+сумма, kind=adj). Неоднозначный ответ
    (таймаут/сеть) — резерв НЕ трогаем: деньги могли уйти, разбирается сверкой (для реальных выплат;
    сейчас провайдер mock и такого не даёт)."""
    from .models import User
    from .payments import create_payout

    if amount_kop <= 0:
        raise PayoutError("amount", "Сумма вывода должна быть больше нуля")
    if amount_kop < settings.payout_min_kop:
        raise PayoutError("min", f"Минимальная сумма вывода — {settings.payout_min_kop // 100} ₽")
    if amount_kop > settings.payout_max_kop:
        raise PayoutError("max", f"Максимум за один вывод — {settings.payout_max_kop // 100} ₽")

    # V7: без ключа идемпотентности не выводим (иначе повтор = вторая реальная выплата).
    idempotency_key = (idempotency_key or "").strip()
    if not idempotency_key:
        raise PayoutError("idempotency", "Не получилось начать вывод. Повтори попытку.")
    # Неймспейсим ключ водителем: разные водители с одинаковым СЫРЫМ ключом (низкоэнтропийный
    # клиентский ключ / коллизия / повтор чужого) иначе схлопнулись бы в ОДНУ выплату на стороне
    # провайдера (Idempotence-Key там глобальный) → вторая реальная выплата «проглотилась» бы.
    scoped_key = f"payout:{driver_id}:{idempotency_key}"

    def _existing():
        # Матчим и НОВЫЙ scoped-ключ, и СЫРОЙ: записи выплат до этого деплоя имели ext_id=сырой
        # ключ, и ретрай той же выплаты через момент деплоя иначе не нашёл бы старую запись →
        # зарезервировал бы списание второй раз. Оба — строго в рамках этого водителя (driver_id).
        return session.exec(
            select(LedgerEntry).where(
                LedgerEntry.driver_id == driver_id,
                LedgerEntry.kind == LedgerKind.payout,
                LedgerEntry.ext_id.in_([scoped_key, idempotency_key]),
            )
        ).first()

    # --- Короткий критический участок: под row-lock проверяем идемпотентность и баланс и СРАЗУ
    #     резервируем списание (пишем payout-запись), затем commit снимает лок. Банк — уже без лока (V8).
    locked = session.exec(select(User).where(User.id == driver_id).with_for_update()).one_or_none()
    if locked is None:
        session.rollback()
        raise PayoutError("no_user", "Водитель не найден")
    prev = _existing()
    if prev is not None:                         # ключ уже проведён → второй раз НЕ списываем
        prev_id, prev_amount = prev.id, prev.amount_kop   # снимаем ДО rollback (объект протухнет)
        session.rollback()
        return {"status": "already", "entry_id": prev_id, "amount_kop": -prev_amount,
                "balance_kop": driver_balance(session, driver_id)}
    # Считаем не «сколько лежит», а «сколько СВОБОДНО»: долг платформе выводить нельзя.
    # Иначе водитель забирал компенсацию промо-скидки на карту и оставался должен комиссию
    # за ту же поездку (волна 156).
    свободно = payable_balance(session, driver_id)
    if amount_kop > свободно:
        долг = owed_to_platform_kop(session, driver_id)
        session.rollback()
        if долг > 0:
            raise PayoutError(
                "debt",
                f"Доступно к выводу {свободно // 100} ₽: {долг // 100} ₽ на балансе зарезервировано "
                "под неоплаченную комиссию",
            )
        raise PayoutError("insufficient", "Недостаточно средств на балансе")
    entry = LedgerEntry(
        driver_id=driver_id, kind=LedgerKind.payout, amount_kop=-amount_kop,
        ext_id=scoped_key,
        note=(f"Вывод на карту ····{card_last4}" if card_last4 else "Вывод на карту"),
    )
    session.add(entry)
    session.commit()                             # фиксируем резерв и СНИМАЕМ row-lock
    session.refresh(entry)
    entry_id = entry.id

    # --- Вызов банка ВНЕ лока. idempotence_key делает провайдера идемпотентным (повтор не задвоит).
    declined = False
    try:
        res = create_payout(amount_kop, payout_token, f"Юлдаш · выплата водителю #{driver_id}",
                            {"driver_id": str(driver_id)}, idempotence_key=scoped_key)
        if res.get("status") not in ("succeeded", "pending"):
            declined = True                      # банк ЯВНО отказал → деньги не ушли, резерв возвращаем
    except Exception:
        # Неоднозначно: деньги могли уйти. Резерв НЕ откатываем (безопаснее для платформы) — разберёт сверка.
        raise PayoutError("provider", "Не получилось отправить выплату. Попробуй позже")

    if declined:
        session.add(LedgerEntry(                 # компенсация append-only: +сумма (историю денег не правим)
            driver_id=driver_id, kind=LedgerKind.adj, amount_kop=amount_kop,
            ext_id=scoped_key, note="Возврат резерва: банк отклонил выплату",
        ))
        session.commit()
        raise PayoutError("provider", "Не получилось отправить выплату. Попробуй позже")

    return {"status": "ok", "entry_id": entry_id, "amount_kop": amount_kop,
            "provider_status": res["status"], "balance_kop": driver_balance(session, driver_id)}


def promo_comp_ext_id(order_id: int) -> str:
    """Ключ идемпотентности компенсации промокода по заказу (один заказ — одна компенсация)."""
    return f"promo:{order_id}"


def post_promo_compensation(session: Session, driver_id: Optional[int], order_id: Optional[int],
                            amount_kop: int) -> Optional[LedgerEntry]:
    """Доплатить водителю остаток промо-скидки, который не влез в нашу комиссию.

    Скидку пассажиру оплачивает платформа: сначала своей комиссией, а если скидка оказалась
    БОЛЬШЕ комиссии — остаток кладём водителю в кошелёк, чтобы он получил ровно столько же,
    как без промокода. Append-only запись kind=adj (+сумма), историю денег не правим.

    Идемпотентно по ext_id: повторный «done» / ретрай не начислит второй раз. Коммитит сам —
    это самостоятельный денежный эффект, он не должен зависеть от того, завёлся ли долг.

    Гонку двух «Завершил» проверка в коде не ловит (обе сессии видят «компенсации нет»), поэтому
    последнее слово за частичным UNIQUE-индексом uq_ledgerentry_promo_comp: проигравший вставку
    ловит IntegrityError и отдаёт запись победителя — деньги начисляются РОВНО один раз."""
    if driver_id is None or order_id is None or amount_kop <= 0:
        return None
    ext = promo_comp_ext_id(order_id)

    def _existing():
        return session.exec(
            select(LedgerEntry).where(LedgerEntry.ext_id == ext, LedgerEntry.kind == LedgerKind.adj)
        ).first()

    prev = _existing()
    if prev is not None:
        return prev
    entry = LedgerEntry(
        driver_id=driver_id, order_id=order_id, kind=LedgerKind.adj,
        amount_kop=int(amount_kop), ext_id=ext,
        note="Компенсация промокода пассажира",
    )
    session.add(entry)
    try:
        session.commit()
    except IntegrityError:
        # Гонку выиграл параллельный «Завершил» — он уже начислил компенсацию по этому заказу.
        # Откатываемся и отдаём его запись (как в debt.accrue_for_order при UNIQUE(order_id)).
        session.rollback()
        return _existing()
    session.refresh(entry)
    return entry


def _post_earn_and_fee(session: Session, driver_id: int, amount_kop: int, *,
                       order_id: Optional[int] = None, booking_id: Optional[int] = None,
                       note: str = "", percent: Optional[float] = None,
                       fee_kop: Optional[int] = None) -> None:
    """Добавить в ledger начисление за поездку: earn (+вся сумма) и fee (−комиссия).
    Вызывать ТОЛЬКО под уже открытой транзакцией с залоченной строкой заказа/брони.

    percent — ставка комиссии. None → плоский service_fee_percent. Для ТАКСИ передаём
    driver_fee_percent (лесенка 3/8/15 по поездкам + промо запуска 0%): иначе онлайн-оплата удержала бы
    8% в обход промо/лесенки, при этом Model-A долг с верной ставкой гасится → перебор + споры.

    fee_kop — готовая сумма комиссии (перебивает расчёт по проценту). Нужна для промокода:
    комиссия там уже уменьшена на скидку, и пересчёт по проценту от УРЕЗАННОЙ оплаты списал бы
    с водителя лишнее."""
    fee = fee_kop_for(amount_kop, percent) if fee_kop is None else max(int(fee_kop), 0)
    eff = percent if percent is not None else settings.service_fee_percent
    session.add(LedgerEntry(
        driver_id=driver_id, order_id=order_id, booking_id=booking_id,
        kind=LedgerKind.earn, amount_kop=amount_kop, note=note,
    ))
    if fee > 0:
        # Комиссия урезана скидкой пассажира → так и пишем, иначе процент в истории не сойдётся
        # с суммой и водитель решит, что его обсчитали.
        label = (f"Комиссия сервиса {eff:g}%" if fee_kop is None
                 else f"Комиссия сервиса {eff:g}% (уменьшена скидкой по промокоду)")
        session.add(LedgerEntry(
            driver_id=driver_id, order_id=order_id, booking_id=booking_id,
            kind=LedgerKind.fee, amount_kop=-fee, note=label,
        ))


def settle_instant_order(session: Session, order_id: int, method: str, amount_kop: int) -> str:
    """Провести оплату завершённого быстрого заказа. Идемпотентно, под row-lock.

    Возврат: "settled" (только что провели), "already" (было оплачено), "skip" (нельзя).
    Наличные → помечаем paid, ledger НЕ трогаем. Безнал → paid + earn/fee водителю."""
    order = session.exec(
        select(InstantOrder).where(InstantOrder.id == order_id).with_for_update()
    ).first()
    if not order or order.driver_id is None:
        return "skip"
    if order.paid:
        return "already"            # уже начислено — повторный webhook НЕ задваивает
    order.paid = True
    order.payment_method = method
    session.add(order)
    if method in _CASHLESS:
        # Комиссия по фактической ставке водителя (лесенка/промо), той же, что Model-A долг,
        # который мы тут же гасим — иначе онлайн-оплата удержит плоские 8% в обход промо/лесенки.
        # Момент фиксации ставки — created_at заказа, РОВНО как в debt.accrue_for_order: водителю
        # в оффере показали net по ставке на момент создания, и долг считается по ней же. Раньше
        # тут стоял done_at; после перехода долга на created_at пути разошлись — поездка через
        # границу ступени/конец промо давала при оплате картой один процент, а в долге другой.
        # Фолбэк done_at/сейчас — для старых заказов без created_at.
        from . import debt as _debt
        pct = _debt.driver_fee_percent(session, order.driver_id, order.created_at or order.done_at)
        # Скидка по промокоду уже вычтена из того, что заплатил пассажир (amount_kop). Комиссию
        # считаем от ПОЛНОЙ цены и гасим её скидкой — ровно как в долге Модели А. Иначе водитель
        # заплатил бы процент с урезанной суммы, а платформа не оплатила бы обещанную скидку.
        full_fee = fee_kop_for(promo_ride.price_kop(order), pct)
        fee_due, _comp = promo_ride.split_commission(full_fee, order.promo_discount_kop)
        _post_earn_and_fee(session, order.driver_id, amount_kop,
                           order_id=order.id, note=f"Быстрый заказ #{order.id}", percent=pct,
                           fee_kop=(fee_due if int(order.promo_discount_kop or 0) > 0 else None))
        # Комиссия удержана в ledger fee → снимаем долг Модели А по этому заказу, иначе
        # двойная комиссия + фантомный unpaid-долг заблокирует водителя на онлайн-оплате.
        # note важен: комиссию тут ВЗЯЛИ (записью fee), поэтому в расшифровке заработка она
        # обязана остаться — в отличие от долга, снятого по разбору жалобы.
        _debt.void_debt_for_order(session, order.id, note="Комиссия удержана при онлайн-оплате")
    session.commit()
    return "settled"


def settle_booking(session: Session, booking_id: int, method: str, amount_kop: int) -> str:
    """Провести оплату завершённой брони плановой поездки. Идемпотентно, под row-lock."""
    booking = session.exec(
        select(Booking).where(Booking.id == booking_id).with_for_update()
    ).first()
    if not booking:
        return "skip"
    if booking.paid:
        return "already"
    from .models import Ride
    ride = session.get(Ride, booking.ride_id)
    if not ride:
        return "skip"
    booking.paid = True
    booking.payment_method = method
    session.add(booking)
    if method in _CASHLESS:
        # Ставка попутки — СВОЯ (ride_service_fee_percent, по умолчанию 0%), не общая.
        # Без явного процента сюда подставлялся плоский service_fee_percent: наличными
        # водитель получал всю тысячу, картой — 920 ₽, и разницу ему нигде не объясняли
        # (аудит 2026-08-08, волна 154). Попутка бесплатна и в оферте, и в коде.
        _post_earn_and_fee(session, ride.driver_id, amount_kop,
                           booking_id=booking.id, note=f"Бронь #{booking.id}",
                           percent=settings.ride_service_fee_percent)
    session.commit()
    return "settled"


def reconcile(session: Session, date_from, date_to) -> dict:
    """Сверка за период: начисления ledger (earn) ↔ безналичные оплаты поездок (Payment).

    Инвариант: каждая успешная безналичная оплата поездки порождает РОВНО одну запись earn
    на ту же сумму → SUM(earn) должно совпасть с SUM(успешных безналичных Payment ride/booking).
    Расхождение (diff ≠ 0) = сигнал бага/пропущенного/двойного начисления → алерт (вешает Александр).
    Наличные в сверку НЕ входят (деньги мимо нас)."""
    from .models import Payment

    def _in_period(col):
        return (col >= date_from, col <= date_to)

    earn_rows = session.exec(
        select(LedgerEntry.amount_kop).where(
            LedgerEntry.kind == LedgerKind.earn, *_in_period(LedgerEntry.created_at)
        )
    ).all()
    fee_rows = session.exec(
        select(LedgerEntry.amount_kop).where(
            LedgerEntry.kind == LedgerKind.fee, *_in_period(LedgerEntry.created_at)
        )
    ).all()
    # Период считаем по МОМЕНТУ ПРИМЕНЕНИЯ платежа, а не по моменту нажатия «оплатить»:
    # начисление в ledger рождается именно тогда, когда деньги дошли. Пока сравнивали
    # по `created_at`, обычная ночная оплата (нажал в 23:58, деньги в 00:03) давала
    # −сумму вчера и +сумму сегодня при полном порядке — сверка краснела сама по себе
    # (аудит 2026-08-12, волна 27). У старых строк `settled_at` пустой → берём `created_at`:
    # для них поведение прежнее, задним числом историю не переписываем.
    settled_col = func.coalesce(Payment.settled_at, Payment.created_at)
    pay_rows = session.exec(
        select(Payment.amount_kop).where(
            Payment.purpose.in_(["ride", "booking"]),
            Payment.status == "succeeded",
            Payment.method.in_(list(_CASHLESS)),
            settled_col >= date_from, settled_col <= date_to,
        )
    ).all()

    # Сколько платформа ДОПЛАТИЛА за период — компенсации промо-скидок водителям (волна 153).
    # Раньше единственный денежный отчёт показывал доход и молчал про расход: кампания
    # «300 ₽ каждому» выглядела по нему бесплатной, хотя каждую скидку оплачиваем мы.
    adj_rows = session.exec(
        select(LedgerEntry.amount_kop).where(
            LedgerEntry.kind == LedgerKind.adj, *_in_period(LedgerEntry.created_at)
        )
    ).all()

    earn_kop = int(sum(earn_rows))
    fee_kop = int(-sum(fee_rows))               # fee хранится отрицательным → комиссия = −сумма
    promo_comp_kop = int(sum(a for a in adj_rows if a > 0))
    payments_kop = int(sum(pay_rows))
    diff_kop = earn_kop - payments_kop
    return {
        "date_from": date_from.isoformat(),
        "date_to": date_to.isoformat(),
        "earn_kop": earn_kop,                   # начислено водителям (полные суммы поездок)
        "fee_kop": fee_kop,                     # комиссия сервиса за период
        "net_drivers_kop": earn_kop - fee_kop,  # чистыми водителям
        "promo_comp_kop": promo_comp_kop,       # доплачено водителям за промо-скидки (наш расход)
        "platform_net_kop": fee_kop - promo_comp_kop,   # доход минус расход по кампаниям
        "payments_kop": payments_kop,           # прошло безналом через ЮKassa (отчёт)
        "diff_kop": diff_kop,                   # расхождение ledger↔оплаты (0 = сходится)
        "ok": diff_kop == 0,
        "earn_count": len(earn_rows),
        "payments_count": len(pay_rows),
        "checked_at": utcnow().isoformat(),
    }
