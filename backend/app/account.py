# -*- coding: utf-8 -*-
"""Полное удаление аккаунта пользователя и всех его персональных данных (необратимо).

Вызывается из POST /me/delete (auth.py). Удаляет строки во ВСЕХ таблицах, где есть
данные пользователя, в порядке, безопасном по внешним ключам (дети → родители),
затем best-effort стирает его медиа-файлы.

Почему отдельный модуль: логика объёмная и с чётким порядком удаления — держим её
в одном месте (как cleanup.py для ретеншена), auth.py остаётся тонким.

Что удаляется полностью: аккаунт, профиль водителя, его поездки/заявки/брони (и брони
других на ЕГО поездках — поездка исчезает), чат-сообщения, рейтинги (поставленные и
полученные), доверенные контакты, шеринги поездок, SOS, жалобы, блокировки, платежи,
события/объявления рекламы, FCM-токены, refresh-токены, коды входа, telegram-сессии,
события загрузок, отзывы о приложении, обращения в поддержку (тикеты + весь тред),
споры «Справедливости» (я — сторона) + профиль безопасности (страйки/паузы).

Что ОТВЯЗЫВАЕТСЯ (не удаляем чужое, лишь убираем ссылку на юзера): у тех, кого он
пригласил, `User.referred_by` → NULL; объявления, созданные им как админом для других
(`Ad.created_by`) → NULL.

Медиа: аватар + документы водителя (их ретеншен-чистка НЕ трогает) + его голосовые
стираем здесь. Чат-фото/голос и так подметает ретеншен по возрасту.
"""
import os
from urllib.parse import urlparse

from sqlalchemy import delete, or_, update
from sqlmodel import Session, select

from .models import (
    Ad, AdEvent, AppReview, Block, Booking, CommissionDebt, Consent, Coupon,
    CouponRedemption, CourierApplication, CourierProfile, DeviceBan, DeviceToken,
    DriverProfile, DriverSchedule, Incident, InstantOrder, InviteCode, LedgerEntry, Message,
    Notification, OtpCode, ParcelDelivery, Partner, Payment, PromoCode, PromoRedemption,
    Rating, RecentPlace, ReferralBonus, RefreshToken, Report, RequestResponse, Ride, RideRequest,
    RouteWatch, SafetyProfile, SavedPlace, SosEvent, SupportMessage, SupportTicket,
    TaxiApplication, TaxiWorkDay, TgAuth, Trust, TripShare, TrustedContact, UploadEvent, User,
    WaitlistEntry,
)
from .storage import get_storage


def _safe_unlink_media(url: str) -> None:
    """Best-effort удаление медиа по его URL. Берём только basename (анти path-traversal)
    и пробуем во всех областях хранилища (диск или S3). Ошибки глотаем — файла может уже
    не быть (ретеншен) или это внешний URL."""
    if not url:
        return
    name = os.path.basename(urlparse(url).path)
    if not name or name in (".", ".."):
        return
    storage = get_storage()
    # Не знаем область по URL — чистим во всех (лишние вызовы безвредны, delete идемпотентен).
    for area in ("docs", "chat", "voice", "evidence"):
        storage.delete(f"{area}/{name}")
    storage.delete(name)   # legacy: файлы прямо в корне MEDIA_DIR


def delete_user_account(session: Session, user: User) -> None:
    """Удалить аккаунт `user` и все его данные. Коммитит сам. После вызова
    объект `user` протухает (строка удалена) — не используй его дальше."""
    uid = user.id
    phone = user.phone
    tg = user.telegram_id

    # 1) Медиа-URL пользователя собираем ДО удаления строк.
    media_urls = [user.avatar_url]
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()
    if dp:
        media_urls += [dp.license_url, dp.car_photo_url]
    ca = session.exec(select(CourierApplication).where(CourierApplication.user_id == uid)).first()
    if ca:
        media_urls.append(ca.selfie_url)   # селфи с документом — чувствительное, стираем
    ta = session.exec(select(TaxiApplication).where(TaxiApplication.user_id == uid)).first()
    if ta:
        # Документы таксиста в /secure/docs (ретеншен их НЕ трогает) — самые чувствительные ПДн:
        # селфи с правами, справка о несудимости, ОСАГО, разрешение. Строка удаляется на 3.16 —
        # без этого файлы оставались бы навсегда (нарушение «необратимого удаления», 152-ФЗ).
        media_urls += [ta.selfie_url, ta.permit_photo_url, ta.osago_url, ta.criminal_record_url]
    media_urls += list(session.exec(select(Message.voice_url).where(Message.sender_id == uid)).all())
    media_urls += list(session.exec(select(RideRequest.voice_url).where(RideRequest.passenger_id == uid)).all())
    # Фото-доказательства МОИХ споров (лица/номера/травмы — чувствительное): мои как заявителя
    # и мои как обвинённого. Чужие фото в тех же спорах не трогаем (не наши данные).
    for csv_ in session.exec(select(Incident.evidence_urls).where(Incident.reporter_id == uid)).all():
        media_urls += [u for u in (csv_ or "").split(",") if u]
    for csv_ in session.exec(select(Incident.respondent_evidence_urls).where(Incident.respondent_id == uid)).all():
        media_urls += [u for u in (csv_ or "").split(",") if u]

    # 2) id связанных сущностей — для FK-безопасного каскада.
    ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == uid)).all())
    request_ids = list(session.exec(select(RideRequest.id).where(RideRequest.passenger_id == uid)).all())
    ad_ids = list(session.exec(select(Ad.id).where(Ad.owner_id == uid)).all())
    contact_ids = list(session.exec(select(TrustedContact.id).where(TrustedContact.user_id == uid)).all())
    # Такси-заказы (как пассажир ИЛИ водитель) и посылки (как отправитель) — их удаляем целиком.
    order_ids = list(session.exec(select(InstantOrder.id).where(
        or_(InstantOrder.passenger_id == uid, InstantOrder.driver_id == uid))).all())
    parcel_ids = list(session.exec(select(ParcelDelivery.id).where(ParcelDelivery.sender_id == uid)).all())
    # Обращения в поддержку (152-ФЗ: весь тред — персональные данные) — с сообщениями.
    ticket_ids = list(session.exec(select(SupportTicket.id).where(SupportTicket.user_id == uid)).all())
    # Бизнес пользователя («Скидки по пути») и его промокампании — с детьми.
    partner_ids = list(session.exec(select(Partner.id).where(Partner.owner_id == uid)).all())
    partner_coupon_ids = (list(session.exec(select(Coupon.id).where(Coupon.partner_id.in_(partner_ids))).all())
                          if partner_ids else [])
    promo_ids = list(session.exec(select(PromoCode.id).where(PromoCode.owner_id == uid)).all())
    # Брони: мои (как пассажир) + чужие на МОИХ поездках (их удаляем — поездка исчезает).
    booking_conds = [Booking.passenger_id == uid]
    if ride_ids:
        booking_conds.append(Booking.ride_id.in_(ride_ids))
    booking_ids = list(session.exec(select(Booking.id).where(or_(*booking_conds))).all())

    def dele(model, *conds) -> None:
        session.execute(delete(model).where(or_(*conds)))

    # 3) Удаляем строго дети → родители (порядок важен: Postgres проверяет внешние ключи).
    # 3.1 Сообщения: мои + в удаляемых бронях + в удаляемых такси-заказах.
    msg = [Message.sender_id == uid]
    if booking_ids:
        msg.append(Message.booking_id.in_(booking_ids))
    if order_ids:
        msg.append(Message.order_id.in_(order_ids))
    dele(Message, *msg)
    # 3.2 Рейтинги: мной поставленные/полученные + по удаляемым броням/заказам/посылкам.
    rating = [Rating.rater_id == uid, Rating.ratee_id == uid]
    if booking_ids:
        rating.append(Rating.booking_id.in_(booking_ids))
    if order_ids:
        rating.append(Rating.order_id.in_(order_ids))
    if parcel_ids:
        rating.append(Rating.parcel_id.in_(parcel_ids))
    dele(Rating, *rating)
    # 3.3 Шеринги поездок: по удаляемым броням + моим контактам + удаляемым заказам + моим посылкам
    # (G1: трекинг-ссылка получателя живёт с contact_id=NULL — ловится только по parcel_id).
    shares = []
    if booking_ids:
        shares.append(TripShare.booking_id.in_(booking_ids))
    if contact_ids:
        shares.append(TripShare.contact_id.in_(contact_ids))
    if order_ids:
        shares.append(TripShare.order_id.in_(order_ids))
    if parcel_ids:
        shares.append(TripShare.parcel_id.in_(parcel_ids))
    if shares:
        dele(TripShare, *shares)
    # 3.4 SOS: мои + по удаляемым броням/заказам.
    sos = [SosEvent.user_id == uid]
    if booking_ids:
        sos.append(SosEvent.booking_id.in_(booking_ids))
    if order_ids:
        sos.append(SosEvent.order_id.in_(order_ids))
    dele(SosEvent, *sos)
    # 3.5 Жалобы: мной поданные/на меня + по удаляемым заказам/посылкам.
    rep = [Report.reporter_id == uid, Report.target_user_id == uid]
    if order_ids:
        rep.append(Report.order_id.in_(order_ids))
    if parcel_ids:
        rep.append(Report.parcel_id.in_(parcel_ids))
    dele(Report, *rep)
    # 3.6 Платежи: мои + по моим поездкам/объявлениям/броням/заказам/подпискам бизнеса.
    pay = [Payment.user_id == uid]
    if ride_ids:
        pay.append(Payment.ride_id.in_(ride_ids))
    if ad_ids:
        pay.append(Payment.ad_id.in_(ad_ids))
    if booking_ids:
        pay.append(Payment.booking_id.in_(booking_ids))
    if order_ids:
        pay.append(Payment.order_id.in_(order_ids))
    if partner_ids:
        pay.append(Payment.partner_id.in_(partner_ids))
    dele(Payment, *pay)
    # 3.7 Финансы такси: долг по комиссии + записи ledger (мои + по удаляемым заказам).
    debt = [CommissionDebt.driver_id == uid]
    ledg = [LedgerEntry.driver_id == uid]
    if order_ids:
        debt.append(CommissionDebt.order_id.in_(order_ids))
        ledg.append(LedgerEntry.order_id.in_(order_ids))
    dele(CommissionDebt, *debt)
    dele(LedgerEntry, *ledg)
    # 3.7-bis «Справедливость»: споры, где я сторона (тексты обеих сторон = ПДн), + споры по
    # удаляемым броням (FK incident.booking_id). В чужих спорах, решённых мной как админом,
    # само решение не трогаем — только отвязываем ссылку (FK resolved_by). Профиль безопасности
    # (страйки/паузы) удаляем целиком. Без этого шага delete(Booking)/delete(User) падает по FK.
    inc = [Incident.reporter_id == uid, Incident.respondent_id == uid]
    if booking_ids:
        inc.append(Incident.booking_id.in_(booking_ids))
    dele(Incident, *inc)
    session.execute(update(Incident).where(Incident.resolved_by == uid).values(resolved_by=None))
    session.execute(delete(SafetyProfile).where(SafetyProfile.user_id == uid))
    # 3.8 Брони (после всех детей, что на них ссылаются).
    if booking_ids:
        session.execute(delete(Booking).where(Booking.id.in_(booking_ids)))
    # 3.9 Отклики на заявки: мои (как водитель) + на мои заявки.
    resp = [RequestResponse.driver_id == uid]
    if request_ids:
        resp.append(RequestResponse.request_id.in_(request_ids))
    dele(RequestResponse, *resp)
    # 3.10 Заявки + поездки.
    session.execute(delete(RideRequest).where(RideRequest.passenger_id == uid))
    session.execute(delete(Ride).where(Ride.driver_id == uid))
    # 3.11 Такси-заказы: отвязать себя как «висящий оффер» на ЧУЖИХ заказах, затем удалить свои.
    session.execute(update(InstantOrder).where(InstantOrder.current_offer_driver_id == uid)
                    .values(current_offer_driver_id=None))
    session.execute(delete(InstantOrder).where(
        or_(InstantOrder.passenger_id == uid, InstantOrder.driver_id == uid)))
    # 3.12 Посылки: отвязать себя как курьера на ЧУЖИХ, удалить свои (как отправитель).
    session.execute(update(ParcelDelivery).where(ParcelDelivery.courier_id == uid)
                    .values(courier_id=None))
    session.execute(delete(ParcelDelivery).where(ParcelDelivery.sender_id == uid))
    # 3.13 Бизнес «Скидки по пути»: погашения → купоны → сам партнёр.
    cr = [CouponRedemption.user_id == uid]
    if partner_coupon_ids:
        cr.append(CouponRedemption.coupon_id.in_(partner_coupon_ids))
    dele(CouponRedemption, *cr)
    session.execute(update(CouponRedemption).where(CouponRedemption.redeemed_by == uid)
                    .values(redeemed_by=None))
    if partner_ids:
        session.execute(delete(Coupon).where(Coupon.partner_id.in_(partner_ids)))
        session.execute(delete(Partner).where(Partner.id.in_(partner_ids)))
    # 3.14 Промокампании: погашения (мои + по моим кодам) → сами коды.
    pr = [PromoRedemption.user_id == uid]
    if promo_ids:
        pr.append(PromoRedemption.promo_id.in_(promo_ids))
    dele(PromoRedemption, *pr)
    session.execute(delete(PromoCode).where(PromoCode.owner_id == uid))
    # 3.15 Реферальные бонусы (как пригласивший и как приглашённый).
    dele(ReferralBonus, ReferralBonus.referrer_id == uid, ReferralBonus.invited_user_id == uid)
    # 3.16 Курьер/таксист: заявки (там ИНН/паспортные — стираем), профили, расписание, рабочие дни.
    session.execute(delete(CourierApplication).where(CourierApplication.user_id == uid))
    session.execute(update(CourierApplication).where(CourierApplication.invited_by == uid)
                    .values(invited_by=None))
    session.execute(delete(CourierProfile).where(CourierProfile.user_id == uid))
    session.execute(delete(TaxiApplication).where(TaxiApplication.user_id == uid))
    session.execute(delete(DriverSchedule).where(DriverSchedule.driver_id == uid))
    session.execute(delete(TaxiWorkDay).where(TaxiWorkDay.driver_id == uid))
    # 3.17 Уведомления, подписки на маршрут, сохранённые/недавние адреса (личные данные).
    session.execute(delete(Notification).where(Notification.user_id == uid))
    # Поддержка: сначала сообщения тредов (FK на тикет), затем сами тикеты (152-ФЗ — стираем всё).
    if ticket_ids:
        session.execute(delete(SupportMessage).where(SupportMessage.ticket_id.in_(ticket_ids)))
    session.execute(delete(SupportTicket).where(SupportTicket.user_id == uid))
    session.execute(delete(RouteWatch).where(RouteWatch.user_id == uid))
    session.execute(delete(SavedPlace).where(SavedPlace.user_id == uid))
    session.execute(delete(RecentPlace).where(RecentPlace.user_id == uid))
    # 3.18 Реклама: события + мои объявления; чужие, созданные мной как админом → отвязать.
    if ad_ids:
        session.execute(delete(AdEvent).where(AdEvent.ad_id.in_(ad_ids)))
        session.execute(delete(Ad).where(Ad.id.in_(ad_ids)))
    session.execute(update(Ad).where(Ad.created_by == uid).values(created_by=None))
    # 3.19 Контакты, блокировки.
    session.execute(delete(TrustedContact).where(TrustedContact.user_id == uid))
    dele(Block, Block.user_id == uid, Block.blocked_user_id == uid)
    # 3.20 Токены/коды/сессии/загрузки/отзывы/профиль водителя/лист ожидания.
    session.execute(delete(DeviceToken).where(DeviceToken.user_id == uid))
    session.execute(delete(RefreshToken).where(RefreshToken.user_id == uid))
    if phone:
        session.execute(delete(OtpCode).where(OtpCode.phone == phone))
        session.execute(delete(WaitlistEntry).where(WaitlistEntry.phone == phone))
    if tg:
        session.execute(delete(TgAuth).where(TgAuth.telegram_id == tg))
    session.execute(delete(UploadEvent).where(UploadEvent.user_id == uid))
    session.execute(delete(AppReview).where(AppReview.user_id == uid))
    session.execute(delete(DriverProfile).where(DriverProfile.user_id == uid))
    # 3.21 Доверие: мой уровень «свой», мои инвайт-коды, мои согласия (152-ФЗ — стираем всё).
    session.execute(delete(Consent).where(Consent.user_id == uid))
    session.execute(delete(InviteCode).where(InviteCode.owner_id == uid))
    session.execute(delete(Trust).where(Trust.user_id == uid))
    # Отвязать цепочку: те, кого я пригласил в круг своих, остаются «своими», но ссылку на меня убираем (FK).
    session.execute(update(Trust).where(Trust.invited_by == uid).values(invited_by=None))
    # 3.22 Бан устройства НЕ удаляем (защита от обхода бана — законное исключение 152-ФЗ),
    #      но отвязываем персональную ссылку на юзера.
    session.execute(update(DeviceBan).where(DeviceBan.user_id == uid).values(user_id=None))
    # 3.23 Отвязать рефералов, кто указал меня пригласившим (FK referred_by → user.id).
    session.execute(update(User).where(User.referred_by == uid).values(referred_by=None))
    # 3.24 Наконец — сам аккаунт.
    session.execute(delete(User).where(User.id == uid))
    session.commit()

    # 4) Best-effort стираем медиа-файлы (после успешного удаления строк).
    for url in media_urls:
        _safe_unlink_media(url)
