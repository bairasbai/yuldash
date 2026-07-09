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
from sqlmodel import Session, select

from .. import debt as debt_mod
from ..db import get_session
from ..models import CommissionDebt, DebtStatus, User, UserRole
from ..security import current_user
from ..services import notify_admin_telegram, send_push

router = APIRouter(tags=["debt"])


def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


# ------------------------------ водитель ------------------------------
@router.get("/driver/debt")
def my_debt(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Долг водителя по комиссии за такси: сумма, срок, реквизиты СБП, блок. По СВОЕМУ токену."""
    return debt_mod.debt_summary(session, user.id)


@router.post("/driver/debt/paid")
def declare_paid(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Я оплатил»: все неоплаченные долги водителя → pending (на подтверждение админом).
    Ничего не должен → paid_kop=0. Уведомляем Александра в Telegram."""
    paid_kop = debt_mod.declare_paid(session, user.id)
    if paid_kop > 0:
        notify_admin_telegram(
            f"💸 Водитель заявил оплату долга по комиссии\n"
            f"Кто: {user.name or '—'} ({user.phone or '—'})\n"
            f"Сумма: {paid_kop // 100} ₽\n"
            f"Подтвердить: /admin/debts"
        )
    return {"ok": True, "pending_kop": paid_kop}


# ------------------------------ админ ------------------------------
@router.get("/admin/debts")
def admin_debts(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Долги на подтверждении (pending), сгруппированные по водителю. Для админа.
    У каждого водителя один representative debt_id — по нему confirm/reject подтверждает батч."""
    _require_admin(user)
    rows = session.exec(
        select(CommissionDebt).where(CommissionDebt.status == DebtStatus.pending)
        .order_by(CommissionDebt.id.desc())
    ).all()
    groups: dict[int, dict] = {}
    for d in rows:
        g = groups.get(d.driver_id)
        if g is None:
            drv = session.get(User, d.driver_id)
            g = groups[d.driver_id] = {
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
        raise HTTPException(404, "Долг не найден")
    driver_id = debt.driver_id
    paid_kop = debt_mod.admin_confirm(session, debt_id)
    if paid_kop:
        send_push(session, driver_id, "Долг подтверждён",
                  "Оплата долга по комиссии принята. Можно возить такси 🚕")
    return {"ok": True, "status": "paid", "paid_kop": paid_kop or 0}


@router.post("/admin/debts/{debt_id}/reject")
def admin_reject(debt_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить (деньги не пришли): pending этого водителя → обратно unpaid. Админ."""
    _require_admin(user)
    debt = session.get(CommissionDebt, debt_id)
    if not debt:
        raise HTTPException(404, "Долг не найден")
    driver_id = debt.driver_id
    back_kop = debt_mod.admin_reject(session, debt_id)
    if back_kop:
        send_push(session, driver_id, "Оплата не найдена",
                  "Мы не увидели перевод долга. Проверь и попробуй ещё раз.")
    return {"ok": True, "status": "unpaid", "unpaid_kop": back_kop or 0}
