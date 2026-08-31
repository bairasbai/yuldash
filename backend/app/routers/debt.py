"""Долг по комиссии за ТАКСИ (Модель А «на доверии», Фаза 3).

Водитель:
`GET  /driver/debt`        — сколько должен, до какой даты, реквизиты СБП Александра, блок.
`POST /driver/debt/paid`   — «Я оплатил» → долг в pending (ждёт подтверждения админом).

Админ (Александр):
`GET  /admin/debts`               — водители с долгом на подтверждении (pending), группировкой.
`POST /admin/debts/{id}/confirm`  — подтвердить перевод → paid, блок такси снят.
`POST /admin/debts/{id}/reject`   — деньги не пришли → долг обратно в unpaid.

Права (анти-IDOR): свой долг водитель видит только по своему токену; админ-эндпоинты — только
для роли admin. Блокируется ТОЛЬКО такси (instant); ПОПУТКА (Ride/Booking) не трогается.
"""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from .. import debt as debt_mod
from ..db import get_session
from ..errors import herr
from ..logs import admin_action
from ..models import CommissionDebt, DebtStatus, User, UserRole
from ..security import current_user
from ..services import notify_admin_telegram, push_notification
from ..timeutil import utcnow

router = APIRouter(tags=["debt"])


def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


# ------------------------------ водитель ------------------------------
@router.get("/driver/debt")
def my_debt(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Долг водителя по комиссии за такси: сумма, срок, реквизиты СБП, блок. По СВОЕМУ токену."""
    # Сначала зачитываем кошелёк: деньги платформы, лежащие у водителя (компенсация промо-скидки),
    # гасят его долг платформе. Иначе экран показал бы «должен 18,60 ₽» человеку, у которого тут
    # же в кошельке лежит 281,40 ₽ (аудит 2026-08-08, волна 154).
    debt_mod.settle_debt_from_wallet(session, user.id)
    return debt_mod.debt_summary(session, user.id)


@router.post("/driver/debt/paid")
def declare_paid(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оплата долга по комиссии за такси. По флажку payments_provider:
    yookassa → оплата картой (confirmation_url, авто-чек; вебхук/поллинг гасит весь долг);
    иначе → «Я оплатил» по СБП «на доверии» (долг → pending, админ подтверждает в /admin/debts)."""
    from ..config import settings
    from ..models import Payment
    from ..payments import fetch_payment
    from .payments import _activate_payment, _start_yookassa

    if settings.is_prod and settings.payments_provider == "mock":
        raise herr(503, "Оплата скоро будет доступна", "Түләү оҙаҡламай мөмкин буласаҡ")

    # Кошелёк гасит долг раньше карты: платить картой то, что уже покрыто своими деньгами,
    # человек не должен (волна 154).
    debt_mod.settle_debt_from_wallet(session, user.id)

    if settings.payments_provider == "yookassa":
        summary = debt_mod.debt_summary(session, user.id)
        owed_kop = int(summary["unpaid_kop"]) + int(summary["pending_kop"])   # всё, что ещё не paid
        if owed_kop <= 0:
            return {"ok": True, "method": "yookassa", "status": "succeeded", "amount_kop": 0}
        # Дедуп: висящий pending-платёж долга — возвращаем его с актуальным confirmation_url.
        existing = session.exec(
            select(Payment).where(
                Payment.user_id == user.id, Payment.purpose == "taxi_debt", Payment.status == "pending",
            ).order_by(Payment.id.desc())
        ).first()
        if existing and existing.provider_id:
            try:
                info = fetch_payment(existing.provider_id)
            except Exception:
                info = None
            if info and info["status"] == "succeeded":
                _activate_payment(session, existing)
                return {"ok": True, "method": "yookassa", "status": "succeeded", "payment_id": existing.id}
            return {"ok": True, "method": "yookassa", "status": "pending", "payment_id": existing.id,
                    "amount_kop": existing.amount_kop, "confirmation_url": (info or {}).get("confirmation_url", "")}
        payment = Payment(user_id=user.id, purpose="taxi_debt", amount_kop=owed_kop, method="yookassa", status="pending")
        session.add(payment)
        session.commit()
        session.refresh(payment)
        res = _start_yookassa(session, payment, "Юлдаш · комиссия такси", user.phone)
        payment.provider_id = res["provider_id"]
        session.add(payment)
        session.commit()
        if res["status"] == "succeeded":          # mock/dev — оплачено сразу
            _activate_payment(session, payment)
            return {"ok": True, "method": "yookassa", "status": "succeeded", "payment_id": payment.id}
        return {"ok": True, "method": "yookassa", "status": "pending", "payment_id": payment.id,
                "amount_kop": owed_kop, "confirmation_url": res["confirmation_url"]}

    # СБП «на доверии» (по умолчанию): долг → pending, админ подтверждает.
    paid_kop = debt_mod.declare_paid(session, user.id)
    if paid_kop > 0:
        notify_admin_telegram(
            f"💸 Водитель заявил оплату долга по комиссии\n"
            f"Кто: {user.name or '—'} ({user.phone or '—'})\n"
            f"Сумма: {paid_kop // 100} ₽\n"
            f"Подтвердить: /admin/debts"
        )
    return {"ok": True, "method": "sbp_manual", "pending_kop": paid_kop}


# ------------------------------ админ ------------------------------
@router.get("/admin/debts")
def admin_debts(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Долги на подтверждении (pending), сгруппированные по ЗАЯВЛЕНИЮ «Я оплатил». Для админа.
    У каждого заявления свой representative debt_id — по нему confirm/reject закрывает батч.

    Группируем по (водитель, момент заявления), а не по водителю (волна 220): одно нажатие
    «Я оплатил» = один перевод = одна строка. Пока группировали по водителю, сумма в строке
    росла от второго заявления, поданного уже после того, как админ на неё посмотрел, —
    и кнопка гасила больше, чем он проверил в банке."""
    _require_admin(user)
    rows = session.exec(
        select(CommissionDebt).where(CommissionDebt.status == DebtStatus.pending)
        .order_by(CommissionDebt.id.desc())
    ).all()
    groups: dict[tuple, dict] = {}
    for d in rows:
        ключ = (d.driver_id, d.paid_declared_at)
        g = groups.get(ключ)
        if g is None:
            drv = session.get(User, d.driver_id)
            g = groups[ключ] = {
                "debt_id": d.id,                       # representative id для confirm/reject
                "driver_id": d.driver_id,
                "driver_name": (drv.name if drv else ""),
                "driver_phone": (drv.phone if drv else ""),
                "amount_kop": 0,
                "amount": 0,
                "weeks": [],
                "declared_at": d.paid_declared_at,
            }
        g["amount_kop"] += d.amount_kop
        g["amount"] = g["amount_kop"] // 100
        if d.week and d.week not in g["weeks"]:
            g["weeks"].append(d.week)
    return list(groups.values())


@router.post("/admin/debts/{debt_id}/confirm")
def admin_confirm(debt_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Подтвердить перевод долга: весь pending этого водителя → paid, блок такси снят. Админ."""
    _require_admin(user)
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        raise herr(404, "Долг не найден", "Бурыс табылманы")
    driver_id = debt.driver_id
    paid_kop = debt_mod.admin_confirm(session, debt_id)
    admin_action(user.id, "debt.confirm", debt_id=debt_id, driver=driver_id, amount_kop=paid_kop)
    if paid_kop:
        # Запись, а не голый пуш: это снятие блокировки — человек должен узнать о нём даже
        # если пуш не дошёл, иначе будет думать, что всё ещё не может работать
        # (аудит 2026-08-08, волна 20).
        push_notification(
            session, driver_id, "money",
            "Долг подтверждён", "Бурыс раҫланды",
            "Оплата долга по комиссии принята. Можно возить такси 🚕",
            "Комиссия бурысы түләүе ҡабул ителде. Такси йөрөтөргә була 🚕",
            ref_kind="debt", ref_id=debt_id,
        )
    return {"ok": True, "status": "paid", "paid_kop": paid_kop or 0}


@router.get("/driver/taxi-rides")
def my_taxi_rides(limit: int = 100, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои завершённые ТАКСИ-заказы с расшифровкой: цена, комиссия, чистыми. Только СВОИ (по токену).
    Закрывает вопрос «почему сумма не сходится» — раньше водитель видел только итог за день.

    Путь именно taxi-rides: `/driver/rides` уже занят списком плановых поездок-попуток
    (rides.py) — одинаковый путь молча перехватывался бы первым зарегистрированным роутером."""
    return debt_mod.driver_rides(session, user.id, limit)


class ForgiveIn(BaseModel):
    reason: str = Field("", max_length=300)


@router.post("/admin/debts/{debt_id}/forgive")
def admin_forgive(debt_id: int, body: ForgiveIn | None = None,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Списать долг по-человечески: пассажир не заплатил, поездка сорвалась, спорная ситуация.

    Раньше у админа было только «подтвердить» и «отклонить» — простить было НЕЧЕМ, и водитель
    оставался должен комиссию за поездку, где ему не заплатили (аудит 2026-07-26). Технически
    можно было «подтвердить» оплату, которой не было, но это врало бы в отчётах о собранной
    комиссии. Здесь долг гасится честно и с причиной."""
    _require_admin(user)
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        raise herr(404, "Долг не найден", "Бурыс табылманы")
    if debt.status == DebtStatus.paid:
        return {"ok": True, "status": "paid", "already": True}
    reason = ((body.reason if body else "") or "").strip()[:300]
    debt.status = DebtStatus.paid
    debt.confirmed_at = utcnow()
    debt.note = (f"Списан админом: {reason}" if reason else "Списан админом")[:300]
    session.add(debt)
    session.commit()
    admin_action(user.id, "debt.forgive", debt_id=debt_id, driver=debt.driver_id,
                 amount_kop=debt.amount_kop)
    push_notification(
        session, debt.driver_id, "money",
        "Долг списан", "Бурыс алып ташланды",
        (f"Комиссия списана: {reason}" if reason else "Комиссия по этой поездке списана."),
        (f"Комиссия алып ташланды: {reason}" if reason else "Был сәфәр өсөн комиссия алып ташланды."),
        ref_kind="debt", ref_id=debt_id,
    )
    return {"ok": True, "status": "paid", "forgiven_kop": debt.amount_kop, "reason": reason}


@router.post("/admin/debts/{debt_id}/reject")
def admin_reject(debt_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить (деньги не пришли): pending этого водителя → обратно unpaid. Админ."""
    _require_admin(user)
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        raise herr(404, "Долг не найден", "Бурыс табылманы")
    driver_id = debt.driver_id
    back_kop = debt_mod.admin_reject(session, debt_id)
    admin_action(user.id, "debt.reject", debt_id=debt_id, driver=driver_id, amount_kop=back_kop)
    if back_kop:
        # Самое важное из трёх: долг вернулся в неоплаченные, такси снова закрыто. Проверено
        # пробой — записей у водителя было НОЛЬ, он узнавал об этом, упершись в блокировку.
        push_notification(
            session, driver_id, "money",
            "Оплата не найдена", "Түләү табылманы",
            "Мы не увидели перевод долга. Долг снова числится неоплаченным — "
            "проверь платёж и заяви оплату ещё раз.",
            "Беҙ бурыс күсереүен күрмәнек. Бурыс тағы түләнмәгән булып тора — "
            "түләүҙе тикшер һәм яңынан белдер.",
            ref_kind="debt", ref_id=debt_id,
        )
    return {"ok": True, "status": "unpaid", "unpaid_kop": back_kop or 0}
