"""Деньги v1 (Фаза 3, D3): оплата поездки после done + кошелёк-ledger водителя + сверка.

Оплата ТОЛЬКО завершённой (done) поездки/брони — пассажир платит картой/СБП (ЮKassa) или
отмечает «наличными». Безнал начисляет водителю через ledger (earn − комиссия); наличные
идут мимо нас (ledger не двигаем). Начисление идемпотентно (флаг paid + row-lock в ledger.py).

Права (анти-IDOR): платить может ТОЛЬКО пассажир-владелец заказа; кошелёк/историю ledger
видит ТОЛЬКО сам водитель (по своему токену); сверка — только админ.

`POST /instant/orders/{id}/pay`  — оплатить завершённый быстрый заказ.
`POST /bookings/{id}/pay`        — оплатить завершённую бронь плановой поездки.
`GET  /wallet/balance`           — баланс водителя (SUM ledger).
`GET  /wallet/ledger`            — записи ledger водителя (свои).
`GET  /admin/ledger/reconcile`   — сверка ledger↔оплаты за период (админ).
"""
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..ledger import (PayoutError, driver_balance, ledger_entries, owed_to_platform_kop,
                      payable_balance, reconcile, request_payout, _reversal_ext_id)
from ..models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus,
    LedgerEntry, LedgerKind, Payment, User, UserRole,
)
from ..security import current_user
from ..timeutil import utcnow
from .payments import _activate_payment, _start_yookassa, _sync_provider_status

router = APIRouter(tags=["wallet"])

# Способы оплаты, которые принимает пассажир на экране «Оплата».
_METHODS = ("cash", "card", "sbp")


class PayIn(BaseModel):
    method: str = Field("card", max_length=16)   # cash | card | sbp


def _guard_method(method: str) -> None:
    if method not in _METHODS:
        raise herr(400, "Неизвестный способ оплаты", "Билдәһеҙ түләү ысулы")


def _cancel_own_pending_cashless(session: Session, user_id: int, *, order_id=None, booking_id=None) -> None:
    """Оплата налом закрывает СВОЙ висящий безнал-платёж на тот же заказ/бронь. Иначе живая
    ссылка ЮKassa переживает нал: пассажир «передумал → нал → позже открыл старую ссылку» платит
    дважды (нал водителю + карта платформе), а settle_* отбивает вебхук как "already" без денег
    водителю. Локальный canceled убирает платёж из дедупа/поллинга; сама ссылка у провайдера
    протухает по его TTL (API отмены неоплаченного pending у ЮKassa нет)."""
    q = select(Payment).where(
        Payment.user_id == user_id, Payment.status == "pending",
        Payment.purpose == ("ride" if order_id is not None else "booking"),
    )
    q = q.where(Payment.order_id == order_id) if order_id is not None else q.where(Payment.booking_id == booking_id)
    rows = session.exec(q).all()
    if rows:
        for p in rows:
            p.status = "canceled"
            session.add(p)
        session.commit()


def _pay_cashless(session: Session, payer: User, *, purpose: str, amount_kop: int,
                  method: str, description: str, order_id=None, booking_id=None) -> dict:
    """Общий безналичный поток через ЮKassa (карта/СБП). mock/dev → succeeded сразу
    (активируем и начисляем); yookassa → confirmation_url, начисление придёт по webhook."""
    # Онлайн-оплата поездки идёт ТОЛЬКО через реальный yookassa. В проде любой другой провайдер
    # (mock/sbp_manual) вернул бы «succeeded» без денег → начисление фантома. Блокируем 503:
    # клиент по 503 прячет карту (OnlinePayGate), остаётся нал / перевод «на доверии».
    if settings.is_prod and settings.payments_provider != "yookassa":
        raise herr(503, "Оплата скоро будет доступна", "Түләү оҙаҡламай мөмкин буласаҡ")
    # Дедуп pending: уже есть висящий платёж на этот заказ/бронь → возвращаем его, НЕ создаём второй
    # (иначе два тапа «Оплатить» / ретрай при задержке вебхука = два реальных списания). Образец — debt.py.
    dq = select(Payment).where(
        Payment.user_id == payer.id, Payment.purpose == purpose, Payment.status == "pending",
    )
    dq = dq.where(Payment.order_id == order_id) if order_id is not None else dq.where(Payment.booking_id == booking_id)
    existing = session.exec(dq.order_by(Payment.id.desc())).first()
    if existing and (existing.provider_id or existing.method == "yookassa"):
        if existing.provider_id:
            existing, info = _sync_provider_status(session, existing)
            if existing.status == "succeeded":
                return {"status": "succeeded", "method": "yookassa", "payment_id": existing.id}
            if existing.status == "pending":
                return {"status": "pending", "method": "yookassa", "payment_id": existing.id,
                        "confirmation_url": (info or {}).get("confirmation_url", "")}
            existing = None  # provider canceled окончательно: ниже создадим новую строку и ключ
        if existing is not None:
            # Неизвестный исход первого обращения: повторяем ту же локальную строку, поэтому
            # _start_yookassa отправит провайдеру тот же Idempotence-Key и не создаст второе списание.
            res = _start_yookassa(session, existing, description, payer.phone)
            existing.provider_id = res["provider_id"]
            session.add(existing)
            session.commit()
            if res["status"] == "succeeded":
                _activate_payment(session, existing)
                return {"status": "succeeded", "method": "yookassa", "payment_id": existing.id}
            return {"status": "pending", "method": "yookassa", "payment_id": existing.id,
                    "confirmation_url": res["confirmation_url"]}
    payment = Payment(
        user_id=payer.id, purpose=purpose, amount_kop=amount_kop, method=method,
        order_id=order_id, booking_id=booking_id,
    )
    session.add(payment)
    session.commit()
    session.refresh(payment)
    res = _start_yookassa(session, payment, description, payer.phone)
    payment.provider_id = res["provider_id"]
    session.add(payment)
    session.commit()
    if res["status"] == "succeeded":              # mock/dev — оплачено сразу → начисляем
        _activate_payment(session, payment)
        return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
    return {"status": "pending", "method": "yookassa", "payment_id": payment.id,
            "confirmation_url": res["confirmation_url"]}


# ------------------------------ оплата быстрого заказа ------------------------------
@router.post("/instant/orders/{order_id}/pay")
def pay_instant_order(order_id: int, body: PayIn, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """Пассажир оплачивает ЗАВЕРШЁННЫЙ быстрый заказ. Только владелец, только статус done."""
    order = session.get(InstantOrder, order_id)
    if not order:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if order.passenger_id != user.id:                     # анти-IDOR: чужой заказ не оплатить
        raise herr(403, "Это не твой заказ", "Был һинең заказың түгел")
    if order.status != InstantOrderStatus.done:
        raise herr(409, "Оплатить можно только завершённую поездку", "Тик тамамланған сәфәр өсөн түләп була")
    if order.driver_id is None:
        raise herr(409, "У заказа нет водителя", "Заказдың водителе юҡ")
    if order.paid:                                        # идемпотентно: повторная оплата не начисляет второй раз
        return {"status": "already_paid", "method": order.payment_method}
    _guard_method(body.method)
    # Пассажир платит цену МИНУС скидку по промокоду (её оплачивает платформа своей комиссией,
    # см. app/promo_ride.py) — без этого промокод молча пропадал бы при оплате картой.
    from .. import promo_ride
    amount_kop = promo_ride.payable_kop(order)
    if body.method == "cash":
        from .. import ledger
        ledger.settle_instant_order(session, order.id, "cash", amount_kop)   # paid=True, ledger НЕ трогаем
        _cancel_own_pending_cashless(session, user.id, order_id=order.id)
        return {"status": "paid", "method": "cash"}
    return _pay_cashless(session, user, purpose="ride", amount_kop=amount_kop, method=body.method,
                         description=f"Юлдаш · поездка #{order.id}", order_id=order.id)


# ------------------------------ оплата брони плановой поездки ------------------------------
@router.post("/bookings/{booking_id}/pay")
def pay_booking(booking_id: int, body: PayIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Пассажир оплачивает ЗАВЕРШЁННУЮ бронь плановой поездки. Только владелец, только done."""
    booking = session.get(Booking, booking_id)
    if not booking:
        raise herr(404, "Бронь не найдена", "Бронь табылманы")
    if booking.passenger_id != user.id:                   # анти-IDOR
        raise herr(403, "Это не твоя бронь", "Был һинең броның түгел")
    if booking.status != BookingStatus.done:
        raise herr(409, "Оплатить можно только завершённую поездку", "Тик тамамланған сәфәр өсөн түләп була")
    if booking.paid:
        return {"status": "already_paid", "method": booking.payment_method}
    _guard_method(body.method)
    amount_kop = int(booking.price) * 100                 # цена брони в ₽ → копейки
    if amount_kop <= 0:
        raise herr(409, "У брони нет суммы к оплате", "Брондә түләргә сумма юҡ")
    if body.method == "cash":
        from .. import ledger
        ledger.settle_booking(session, booking.id, "cash", amount_kop)
        _cancel_own_pending_cashless(session, user.id, booking_id=booking.id)
        return {"status": "paid", "method": "cash"}
    return _pay_cashless(session, user, purpose="booking", amount_kop=amount_kop, method=body.method,
                         description=f"Юлдаш · поездка #{booking.id}", booking_id=booking.id)


# ------------------------------ кошелёк водителя ------------------------------
@router.get("/wallet/balance")
def wallet_balance(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Баланс кошелька — сумма всех записей ledger по СВОЕМУ id (нельзя запросить чужой).

    Рядом с балансом отдаём, сколько из него зарезервировано под неоплаченную комиссию и
    сколько реально свободно. Без этих двух чисел человек видит 600 ₽, жмёт «Вывести» и
    получает отказ, не понимая причины (волна 156)."""
    bal = driver_balance(session, user.id)
    долг = owed_to_platform_kop(session, user.id)
    свободно = max(bal - долг, 0)
    return {"balance_kop": bal, "balance_rub": bal // 100,
            "reserved_kop": min(долг, max(bal, 0)),   # больше, чем лежит, зарезервировать нельзя
            "payable_kop": свободно, "payable_rub": свободно // 100}


@router.get("/wallet/ledger")
def wallet_ledger(limit: int = 100, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """История начислений/комиссий/выплат водителя. Всегда по СВОЕМУ id — чужую не видно."""
    rows = ledger_entries(session, user.id, limit)
    return [
        {
            "id": e.id, "kind": e.kind.value if hasattr(e.kind, "value") else e.kind,
            "amount_kop": e.amount_kop, "amount_rub": e.amount_kop / 100.0,
            "order_id": e.order_id, "booking_id": e.booking_id,
            "note": e.note, "created_at": e.created_at,
        }
        for e in rows
    ]


# ------------------------------ сверка (админ) ------------------------------
@router.get("/admin/ledger/reconcile")
def admin_ledger_reconcile(days: int = 1, date_from: str = "", date_to: str = "",
                           user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сверка: SUM(earn ledger) ↔ SUM(успешных безналичных оплат) за период. Только админ.
    По умолчанию — сутки. diff ≠ 0 → расхождение (алерт вешает Александр)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    try:
        end = datetime.fromisoformat(date_to) if date_to else utcnow()
        start = datetime.fromisoformat(date_from) if date_from else end - timedelta(days=max(1, min(days, 366)))
    except ValueError:
        raise herr(400, "Неверный формат даты (нужен ISO 8601)", "Дата форматы дөрөҫ түгел (ISO 8601 кәрәк)")
    return reconcile(session, start, end)


# ============================ Выплаты водителям (Модель Б, ВЫКЛ по умолчанию) ============================
# ГОТОВНОСТЬ. Режим доступен, только когда Александр оформит ИП + бизнес-ЮKassa + ключи выплат
# и выставит PAYOUTS_ENABLED=true. Выключено → «Выплаты скоро» (не 500), Модель А остаётся рабочей.
_PAYOUT_SOON = "Выплаты на карту скоро будут доступны"
_PAYOUT_SOON_BA = "Картаға түләүҙәр тиҙҙән эшләй башлай"


def _payout_profile(session: Session, user_id: int) -> DriverProfile | None:
    return session.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()


@router.get("/wallet/payout/status")
def wallet_payout_status(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Доступны ли выплаты + баланс и сохранённые реквизиты. Клиент по `enabled` рисует
    активную кнопку «Вывести на карту» либо заглушку «Скоро». Границы — с сервера, не хардкод клиента."""
    dp = _payout_profile(session, user.id)
    return {
        "enabled": settings.payouts_ready,          # реально ли можно выводить (флаг + ключи в проде)
        "balance_kop": driver_balance(session, user.id),
        # Сколько из баланса свободно: долг платформе выводить нельзя (волна 156).
        "payable_kop": payable_balance(session, user.id),
        "owed_kop": owed_to_platform_kop(session, user.id),
        "has_requisite": bool(dp and dp.payout_card_last4),
        "card_last4": (dp.payout_card_last4 if dp else ""),
        "min_kop": settings.payout_min_kop,
        "max_kop": settings.payout_max_kop,
    }


class PayoutRequisiteIn(BaseModel):
    # Номер карты вводится в UI и идёт ТРАНЗИТОМ: сервер берёт только последние 4 и забывает
    # остальное (полный PAN не логируем и не храним). `card_last4`/`payout_token` — путь виджета
    # провайдера (prod), когда PAN на сервер вообще не попадает.
    card_number: str = Field("", max_length=32)
    card_last4: str = Field("", max_length=4)
    payout_token: str = Field("", max_length=128)


@router.post("/wallet/payout/requisite")
def save_payout_requisite(body: PayoutRequisiteIn, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Сохранить карту для выплат. Храним ТОЛЬКО последние 4 цифры + токен провайдера — НЕ полный номер."""
    digits = "".join(c for c in body.card_number if c.isdigit())
    last4 = (digits[-4:] if len(digits) >= 4 else "") or "".join(c for c in body.card_last4 if c.isdigit())[-4:]
    if not last4 or (digits and len(digits) < 12):
        raise herr(400, "Проверь номер карты для вывода", "Сығарыу өсөн карта номерын тикшер")
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        dp = DriverProfile(user_id=user.id)
    dp.payout_card_last4 = last4                 # только 4 цифры — не PAN
    dp.payout_token = body.payout_token.strip()  # токен провайдера (не PAN); в dev может быть пустым
    dp.payout_card_at = utcnow()
    session.add(dp)
    session.commit()
    # digits/card_number намеренно НЕ сохраняем и не логируем — уходят из памяти с концом запроса.
    return {"ok": True, "card_last4": last4}


class PayoutIn(BaseModel):
    amount_kop: int
    # Ключ идемпотентности: клиент шлёт один и тот же при ретрае → повтор не спишет баланс дважды.
    idempotency_key: str = Field("", max_length=64)


@router.post("/wallet/payout")
def wallet_payout(body: PayoutIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Вывести деньги с баланса на карту водителя (Модель Б). ВЫКЛ по умолчанию.

    payouts_ready=False → 503 «Выплаты скоро» (не падаем). Иначе: нужны сохранённые реквизиты,
    сумма в границах и ≤ баланса; списание идёт в ledger записью payout (−сумма), идемпотентно."""
    if not settings.payouts_ready:                          # выключено → мягко «скоро», не 500
        raise herr(503, _PAYOUT_SOON, _PAYOUT_SOON_BA)
    dp = _payout_profile(session, user.id)
    if not dp or not dp.payout_card_last4:
        raise herr(400, "Сначала добавь карту для вывода", "Башта сығарыу өсөн карта өҫтә")
    try:
        res = request_payout(
            session, user.id, int(body.amount_kop),
            payout_token=dp.payout_token, card_last4=dp.payout_card_last4,
            idempotency_key=body.idempotency_key.strip(),
        )
    except PayoutError as e:
        # Оба языка + машинный код. Код нужен клиенту: `provider` означает «попытка закрыта,
        # начни новую» — с прежним ключом ретрай упрётся в тот же отказ (волна 219).
        raise HTTPException(400, {"ru": e.message, "ba": e.message_ba, "code": e.code})
    return res


# ------------------------------ выплаты (админ) ------------------------------
@router.get("/admin/payouts")
def admin_payouts(limit: int = 100, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Реестр выплат (ledger kind=payout), свежие сверху. Только админ. Пока выплаты авто
    (ЮKassa Payout), реестр — для сверки/контроля; ручное подтверждение появится, если авто нет."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    rows = session.exec(
        select(LedgerEntry).where(LedgerEntry.kind == LedgerKind.payout)
        .order_by(LedgerEntry.id.desc()).limit(max(1, min(limit, 500)))
    ).all()
    # Отклонённая банком выплата остаётся в реестре записью payout — деньги мы не переписываем
    # задним числом. Но в сводке для сверки она обязана быть ПОМЕЧЕНА: без пометки админ считает
    # её отправленной, хотя резерв вернулся человеку на баланс (волна 219).
    отменённые = set(session.exec(
        select(LedgerEntry.ext_id).where(
            LedgerEntry.kind == LedgerKind.adj,
            LedgerEntry.ext_id.in_([_reversal_ext_id(e.ext_id) for e in rows] or [""]),
        )
    ).all())
    return [
        {"id": e.id, "driver_id": e.driver_id, "amount_kop": -e.amount_kop,
         "ext_id": e.ext_id, "note": e.note, "created_at": e.created_at,
         # true = банк отказал, резерв вернули; денег по этой строке НЕ уходило.
         "reversed": _reversal_ext_id(e.ext_id) in отменённые}
        for e in rows
    ]
