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
события загрузок, отзывы о приложении.

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
    Ad, AdEvent, AppReview, Block, Booking, DeviceToken, DriverProfile, Message,
    OtpCode, Payment, Rating, RefreshToken, Report, RequestResponse, Ride,
    RideRequest, SosEvent, TgAuth, TripShare, TrustedContact, UploadEvent, User,
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
    for area in ("docs", "chat", "voice"):
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
    media_urls += list(session.exec(select(Message.voice_url).where(Message.sender_id == uid)).all())
    media_urls += list(session.exec(select(RideRequest.voice_url).where(RideRequest.passenger_id == uid)).all())

    # 2) id связанных сущностей — для FK-безопасного каскада.
    ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == uid)).all())
    request_ids = list(session.exec(select(RideRequest.id).where(RideRequest.passenger_id == uid)).all())
    ad_ids = list(session.exec(select(Ad.id).where(Ad.owner_id == uid)).all())
    contact_ids = list(session.exec(select(TrustedContact.id).where(TrustedContact.user_id == uid)).all())
    # Брони: мои (как пассажир) + чужие на МОИХ поездках (их удаляем — поездка исчезает).
    booking_conds = [Booking.passenger_id == uid]
    if ride_ids:
        booking_conds.append(Booking.ride_id.in_(ride_ids))
    booking_ids = list(session.exec(select(Booking.id).where(or_(*booking_conds))).all())

    def dele(model, *conds) -> None:
        session.execute(delete(model).where(or_(*conds)))

    # 3) Удаляем строго дети → родители.
    # 3.1 Сообщения: мои + в удаляемых бронях.
    msg = [Message.sender_id == uid]
    if booking_ids:
        msg.append(Message.booking_id.in_(booking_ids))
    dele(Message, *msg)
    # 3.2 Рейтинги: поставленные/полученные мной + по удаляемым броням.
    rating = [Rating.rater_id == uid, Rating.ratee_id == uid]
    if booking_ids:
        rating.append(Rating.booking_id.in_(booking_ids))
    dele(Rating, *rating)
    # 3.3 Шеринги поездок: по удаляемым броням + по моим контактам.
    shares = []
    if booking_ids:
        shares.append(TripShare.booking_id.in_(booking_ids))
    if contact_ids:
        shares.append(TripShare.contact_id.in_(contact_ids))
    if shares:
        dele(TripShare, *shares)
    # 3.4 SOS: мои + по удаляемым броням.
    sos = [SosEvent.user_id == uid]
    if booking_ids:
        sos.append(SosEvent.booking_id.in_(booking_ids))
    dele(SosEvent, *sos)
    # 3.5 Платежи: мои + по моим поездкам/объявлениям.
    pay = [Payment.user_id == uid]
    if ride_ids:
        pay.append(Payment.ride_id.in_(ride_ids))
    if ad_ids:
        pay.append(Payment.ad_id.in_(ad_ids))
    dele(Payment, *pay)
    # 3.6 Брони.
    if booking_ids:
        session.execute(delete(Booking).where(Booking.id.in_(booking_ids)))
    # 3.7 Отклики на заявки: мои (как водитель) + на мои заявки.
    resp = [RequestResponse.driver_id == uid]
    if request_ids:
        resp.append(RequestResponse.request_id.in_(request_ids))
    dele(RequestResponse, *resp)
    # 3.8 Заявки + 3.9 Поездки.
    session.execute(delete(RideRequest).where(RideRequest.passenger_id == uid))
    session.execute(delete(Ride).where(Ride.driver_id == uid))
    # 3.10 Реклама: события + мои объявления; чужие, созданные мной как админом → отвязать.
    if ad_ids:
        session.execute(delete(AdEvent).where(AdEvent.ad_id.in_(ad_ids)))
        session.execute(delete(Ad).where(Ad.id.in_(ad_ids)))
    session.execute(update(Ad).where(Ad.created_by == uid).values(created_by=None))
    # 3.11 Контакты, жалобы, блокировки.
    session.execute(delete(TrustedContact).where(TrustedContact.user_id == uid))
    dele(Report, Report.reporter_id == uid, Report.target_user_id == uid)
    dele(Block, Block.user_id == uid, Block.blocked_user_id == uid)
    # 3.12 Токены/коды/сессии/загрузки/отзывы/профиль водителя.
    session.execute(delete(DeviceToken).where(DeviceToken.user_id == uid))
    session.execute(delete(RefreshToken).where(RefreshToken.user_id == uid))
    if phone:
        session.execute(delete(OtpCode).where(OtpCode.phone == phone))
    if tg:
        session.execute(delete(TgAuth).where(TgAuth.telegram_id == tg))
    session.execute(delete(UploadEvent).where(UploadEvent.user_id == uid))
    session.execute(delete(AppReview).where(AppReview.user_id == uid))
    session.execute(delete(DriverProfile).where(DriverProfile.user_id == uid))
    # 3.13 Отвязать рефералов, кто указал меня пригласившим (FK referred_by → user.id).
    session.execute(update(User).where(User.referred_by == uid).values(referred_by=None))
    # 3.14 Наконец — сам аккаунт.
    session.execute(delete(User).where(User.id == uid))
    session.commit()

    # 4) Best-effort стираем медиа-файлы (после успешного удаления строк).
    for url in media_urls:
        _safe_unlink_media(url)
