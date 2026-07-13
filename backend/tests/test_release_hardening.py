"""Прод-доводка (релиз): деньги без утечки + приватность гео.

Закрываем находки аудита перед прод-релизом:
- HIGH-1: UNIQUE(commissiondebt.order_id) — двойной «done» не задваивает комиссию таксисту.
- HIGH-2: долг/комиссия, накопленные в окне между «жму оплатить» и подтверждением, НЕ гасятся
  бесплатно (гасим только то, что вошло в снапшот суммы: created_at/delivered_at <= момент платежа).
- Приватность: точные адреса посылки в открытом списке округлены; точную видит только принявший курьер.
- Приватность: точка подачи пассажира округлена водителю до accept.
- Приватность: TTL live-ссылки поездки — «зависшая» поездка не отдаёт гео бессрочно.

FK на Postgres обязателен (CI-джоба backend-tests-postgres): все id ссылаются на реальные строки.
"""
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, Notification, ParcelDelivery,
    Payment, TripShare, TrustedContact, User, UserRole,
)
from app.timeutil import utcnow


def _order(passenger_id: int, **ov) -> int:
    """Минимальный реальный InstantOrder (нужен лишь passenger_id) → его id."""
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, **ov)
        s.add(o); s.commit(); s.refresh(o)
        return o.id


# ---------------- HIGH-1: UNIQUE order_id у долга ----------------

def test_commissiondebt_order_id_unique(client, user_factory):
    """DB-барьер: две записи долга на один order_id невозможны (гонка двойного «done»)."""
    drv = user_factory("UqDrv", role=UserRole.driver)["id"]
    oid = _order(drv)
    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv, order_id=oid, amount_kop=1000, status=DebtStatus.unpaid))
        s.commit()
    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv, order_id=oid, amount_kop=1000, status=DebtStatus.unpaid))
        with pytest.raises(IntegrityError):
            s.commit()


def test_commissiondebt_null_order_id_allowed(client, user_factory):
    """NULL order_id уникальностью не связан (NULL≠NULL) — несколько таких строк допустимы."""
    drv = user_factory("UqNullDrv", role=UserRole.driver)["id"]
    with Session(engine) as s:
        s.add(CommissionDebt(driver_id=drv, order_id=None, amount_kop=100, status=DebtStatus.unpaid))
        s.add(CommissionDebt(driver_id=drv, order_id=None, amount_kop=200, status=DebtStatus.unpaid))
        s.commit()   # не должно падать


# ---------------- HIGH-2: не прощаем долг/комиссию, накопленные в окне оплаты ----------------

def test_taxi_debt_window_not_forgiven(client, user_factory):
    """mark_all_paid(up_to) гасит только долг ДО момента создания платежа; новый долг остаётся."""
    from app import debt as debt_mod
    drv = user_factory("WinDrv", role=UserRole.driver)["id"]
    boundary = utcnow()
    with Session(engine) as s:
        old = CommissionDebt(driver_id=drv, order_id=None, amount_kop=500,
                             status=DebtStatus.unpaid, created_at=boundary - timedelta(minutes=5))
        new = CommissionDebt(driver_id=drv, order_id=None, amount_kop=500,
                             status=DebtStatus.unpaid, created_at=boundary + timedelta(minutes=5))
        s.add(old); s.add(new); s.commit()
        old_id, new_id = old.id, new.id
    with Session(engine) as s:
        paid = debt_mod.mark_all_paid(s, drv, up_to=boundary)
        assert paid == 500                                      # погашен только «старый» долг
    with Session(engine) as s:
        assert s.get(CommissionDebt, old_id).status == DebtStatus.paid
        assert s.get(CommissionDebt, new_id).status == DebtStatus.unpaid   # накопленный в окне — не прощён


def test_courier_commission_window_not_forgiven(client, user_factory):
    """_activate_payment гасит комиссию только по доставкам ДО создания платежа; новая — остаётся."""
    from app.routers.payments import _activate_payment
    courier = user_factory("WinCourier")["id"]
    sender = user_factory("WinSender")["id"]
    boundary = utcnow()
    with Session(engine) as s:
        pay = Payment(user_id=courier, purpose="courier_commission", amount_kop=3000,
                      method="sbp", status="pending", created_at=boundary)
        s.add(pay); s.commit(); s.refresh(pay)
        pid = pay.id
        old = ParcelDelivery(sender_id=sender, courier_id=courier, delivery_type="courier",
                             status="delivered", commission_kop=3000, commission_paid=False,
                             delivered_at=boundary - timedelta(minutes=5))
        new = ParcelDelivery(sender_id=sender, courier_id=courier, delivery_type="courier",
                             status="delivered", commission_kop=1500, commission_paid=False,
                             delivered_at=boundary + timedelta(minutes=5))
        s.add(old); s.add(new); s.commit()
        old_id, new_id = old.id, new.id
    with Session(engine) as s:
        _activate_payment(s, s.get(Payment, pid))
    with Session(engine) as s:
        assert s.get(ParcelDelivery, old_id).commission_paid is True
        assert s.get(ParcelDelivery, new_id).commission_paid is False      # доставка в окне — не прощена
        assert s.get(Payment, pid).status == "succeeded"


# ---------------- Приватность: координаты посылки ----------------

def test_parcel_available_blurs_coords_exact_after_accept():
    """Открытый список заявок отдаёт округлённые (~1 км) координаты; принявшему курьеру — точные."""
    from app.routers.parcels import _parcel_available, _parcel_for_courier
    p = ParcelDelivery(id=1, sender_id=1, from_lat=54.73512, from_lng=55.95841,
                       to_lat=53.63099, to_lng=55.95012, status="created")
    av = _parcel_available(p)
    assert (av["from_lat"], av["from_lng"]) == (54.74, 55.96)   # округлено до 2 знаков
    assert (av["to_lat"], av["to_lng"]) == (53.63, 55.95)
    cr = _parcel_for_courier(p)
    assert (cr["from_lat"], cr["to_lng"]) == (54.73512, 55.95012)   # принявшему — точный адрес


# ---------------- Приватность: точка подачи пассажира до accept ----------------

def test_driver_offer_pickup_blurred_before_accept(client, user_factory):
    """Водителю до accept точку ПОДАЧИ отдаём округлённой (как телефоны); направление — как есть."""
    from app.instant_service import order_payload
    drv = user_factory("OfDrv", role=UserRole.driver)
    pax = user_factory("OfPax")
    oid = _order(pax["id"], from_lat=54.73512, from_lng=55.95841, to_lat=53.63099, to_lng=55.95012,
                 status=InstantOrderStatus.offered, current_offer_driver_id=drv["id"],
                 from_text="A", to_text="B")
    with Session(engine) as s:
        payload = order_payload(s, s.get(InstantOrder, oid), s.get(User, drv["id"]))
    assert payload["role"] == "driver"
    assert (payload["from_lat"], payload["from_lng"]) == (54.74, 55.96)   # подача округлена до accept
    assert (payload["to_lat"], payload["to_lng"]) == (53.63099, 55.95012)  # направление не прячем


# ---------------- Приватность: TTL live-ссылки поездки ----------------

def test_trip_share_expired_returns_404(client, user_factory):
    """Просроченная live-ссылка гео не отдаёт (иначе «зависшая» поездка светила бы гео бессрочно)."""
    from app.routers.share import _resolve_share
    owner = user_factory("ShareOwner")["id"]
    oid = _order(owner)
    token = "expired-share-token-1234567890"
    with Session(engine) as s:
        contact = TrustedContact(user_id=owner, name="Мама", phone="+79990001199")
        s.add(contact); s.commit(); s.refresh(contact)
        s.add(TripShare(order_id=oid, contact_id=contact.id, token=token,
                        expires_at=utcnow() - timedelta(hours=1)))
        s.commit()
    with Session(engine) as s:
        with pytest.raises(HTTPException) as ei:
            _resolve_share(s, token)
        assert ei.value.status_code == 404


def test_trip_share_live_within_ttl(client, user_factory):
    """Живая (не истёкшая) ссылка резолвится — TTL не ломает нормальный сценарий."""
    from app.routers.share import _resolve_share
    owner = user_factory("ShareOwner2")["id"]
    oid = _order(owner)
    token = "live-share-token-1234567890ab"
    with Session(engine) as s:
        contact = TrustedContact(user_id=owner, name="Папа", phone="+79990001188")
        s.add(contact); s.commit(); s.refresh(contact)
        s.add(TripShare(order_id=oid, contact_id=contact.id, token=token,
                        expires_at=utcnow() + timedelta(hours=24)))
        s.commit()
    with Session(engine) as s:
        assert _resolve_share(s, token).token == token


# ---------------- Устойчивость к росту: чистка уведомлений ----------------

def test_notification_cleanup_purges_old_keeps_fresh(client, user_factory):
    """Быстрорастущая таблица notification: старые (>90д) чистятся, свежие остаются."""
    from app import cleanup
    uid = user_factory("NotifCleanup")["id"]
    with Session(engine) as s:
        s.add(Notification(user_id=uid, title_ru="старое-90", body_ru="b",
                           created_at=utcnow() - timedelta(days=200)))
        s.add(Notification(user_id=uid, title_ru="свежее-90", body_ru="b",
                           created_at=utcnow()))
        s.commit()
    cleanup.main()   # реальная чистка
    with Session(engine) as s:
        titles = [n.title_ru for n in s.exec(select(Notification).where(Notification.user_id == uid)).all()]
    assert "старое-90" not in titles    # старое уведомление вычищено
    assert "свежее-90" in titles         # свежее осталось
