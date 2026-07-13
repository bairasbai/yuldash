# -*- coding: utf-8 -*-
"""Полное удаление аккаунта (152-ФЗ + требование Google Play) — НИЧЕГО не остаётся.

Ключевой инвариант: после delete_user_account ни одна строка ни в одной таблице не ссылается
на удалённого пользователя (любая колонка с FK на user.id → 0 строк с этим uid). Проверка
ГЕНЕРИЧЕСКАЯ (интроспекция метаданных) — она сама поймает НОВУЮ таблицу, если её забыли
добавить в удаление. На Postgres тест ещё и доказывает FK-безопасный порядок удаления.
"""
from datetime import date, timedelta

from sqlalchemy import text as satext
from sqlmodel import Session, select

from app import models as M
from app.account import delete_user_account
from app.db import engine
from app.models import SQLModel, User, UserRole
from app.timeutil import utcnow


def _residual_user_refs(uid: int) -> list:
    """Все (таблица.колонка) с FK на user.id, где ещё остались строки с uid."""
    hits = []
    with Session(engine) as s:
        for table in SQLModel.metadata.sorted_tables:
            for col in table.columns:
                for fk in col.foreign_keys:
                    if fk.column.table.name == "user" and fk.column.name == "id":
                        n = s.execute(
                            satext(f'SELECT COUNT(*) FROM "{table.name}" WHERE "{col.name}" = :u'),
                            {"u": uid},
                        ).scalar()
                        if n:
                            hits.append(f"{table.name}.{col.name}={n}")
    return hits


def test_delete_account_leaves_no_residual_anywhere(client, user_factory):
    """Заселяем пользователя во ВСЕ новые user-таблицы (такси/курьер/деньги/бизнес/промо),
    удаляем аккаунт → ноль остатков нигде + сам юзер удалён + чужой пользователь цел."""
    u = user_factory("ToDelete", role=UserRole.driver)
    uid = u["id"]
    other = user_factory("Other")
    oid = other["id"]

    with Session(engine) as s:
        user = s.get(User, uid)
        phone = user.phone
        # --- сенсит. заявки (ИНН/паспортные), профили, расписание ---
        # TaxiApplication уже создан user_factory для роли driver (одна на юзера) — проверяем ЕГО удаление.
        s.add(M.CourierApplication(user_id=uid, transport="car", selfie_url="secure/docs/x.jpg"))
        s.add(M.CourierProfile(user_id=uid))
        s.add(M.DriverSchedule(driver_id=uid, from_city="A", to_city="B"))
        s.add(M.TaxiWorkDay(driver_id=uid, day=date(2026, 7, 13)))
        # --- уведомления, подписки, рефералы, бан устройства ---
        s.add(M.Notification(user_id=uid, ntype="test", title="t", body="b"))
        s.add(M.RouteWatch(user_id=uid, from_city="A", to_city="B", expires_at=utcnow() + timedelta(days=7)))
        s.add(M.ReferralBonus(referrer_id=uid, invited_user_id=oid))
        s.add(M.DeviceBan(user_id=uid, device_id="dev-x"))
        # --- финансы (без заказа) ---
        s.add(M.LedgerEntry(driver_id=uid, kind="earn", amount_kop=100))
        s.add(M.CommissionDebt(driver_id=uid, amount_kop=100))
        # --- такси-заказ + дети по order_id ---
        order = M.InstantOrder(passenger_id=uid, from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1)
        contact = M.TrustedContact(user_id=uid, name="Мама", phone="+79990001177")
        s.add(order); s.add(contact); s.commit(); s.refresh(order); s.refresh(contact)
        s.add(M.CommissionDebt(driver_id=uid, order_id=order.id, amount_kop=200))
        s.add(M.LedgerEntry(driver_id=uid, order_id=order.id, kind="earn", amount_kop=200))
        s.add(M.TripShare(order_id=order.id, contact_id=contact.id, token="del-order-share-token-123456"))
        # --- посылка + рейтинг курьера по parcel_id ---
        parcel = M.ParcelDelivery(sender_id=uid, from_city="A", to_city="B")
        s.add(parcel); s.commit(); s.refresh(parcel)
        s.add(M.Rating(parcel_id=parcel.id, rater_id=oid, ratee_id=uid, stars=5))
        # --- бизнес: партнёр → купон → погашение ---
        partner = M.Partner(owner_id=uid, name="Biz", city="Уфа")
        s.add(partner); s.commit(); s.refresh(partner)
        coupon = M.Coupon(partner_id=partner.id, title="−10%")
        s.add(coupon); s.commit(); s.refresh(coupon)
        s.add(M.CouponRedemption(user_id=uid, coupon_id=coupon.id, code="CPN123"))
        # --- промокампания: код → погашение ---
        promo = M.PromoCode(owner_id=uid, code="MYCODE")
        s.add(promo); s.commit(); s.refresh(promo)
        s.add(M.PromoRedemption(user_id=uid, promo_id=promo.id))
        # --- лист ожидания по телефону (удаляется по phone юзера) ---
        s.add(M.WaitlistEntry(phone=phone))
        s.commit()

        user = s.get(User, uid)
        delete_user_account(s, user)

    # Генеральная проверка: НИГДЕ нет ссылки на удалённого пользователя.
    residual = _residual_user_refs(uid)
    assert residual == [], f"после удаления остались ссылки на юзера: {residual}"

    with Session(engine) as s:
        assert s.get(User, uid) is None                 # аккаунт удалён
        assert s.get(User, oid) is not None             # чужой пользователь цел
        # лист ожидания по телефону тоже вычищен (у него нет FK на user — проверяем отдельно)
        wl = s.exec(select(M.WaitlistEntry).where(M.WaitlistEntry.phone == phone)).first()
        assert wl is None
