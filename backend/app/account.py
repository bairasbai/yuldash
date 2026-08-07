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
профиль безопасности (страйки/паузы).

Что ОТВЯЗЫВАЕТСЯ (не удаляем чужое, лишь убираем ссылку на юзера): у тех, кого он
пригласил, `User.referred_by` → NULL; объявления, созданные им как админом для других
(`Ad.created_by`) → NULL; жалобы НА него (3.5) и споры «Справедливости», где жива вторая
сторона (3.7-bis), — ОБЕЗЛИЧИВАЮТСЯ: ссылка на него и его текст стираются, сам разбор,
решение админа и улики второй стороны живут дальше.

Медиа: аватар + документы водителя (их ретеншен-чистка НЕ трогает) + его голосовые
стираем здесь. Чат-фото/голос и так подметает ретеншен по возрасту.
"""
import os
from urllib.parse import urlparse

from sqlalchemy import and_, delete, func, or_, update
from sqlmodel import Session, select

from .errors import herr
from .models import (
    Ad, AdEvent, AppReview, Block, Booking, BookingStatus, CommissionDebt, Consent, Coupon, OfferDecline,
    CouponRedemption, CourierApplication, CourierProfile, DebtStatus, DeviceBan, DeviceToken,
    DriverProfile, DriverSchedule, Incident, InstantOrder, InstantOrderStatus, InviteCode,
    LedgerEntry, Message,
    Notification, OtpCode, ParcelDelivery, Partner, Payment, PromoCode, PromoRedemption,
    Rating, RecentPlace, ReferralBonus, RefreshToken, Report, RequestResponse, Ride, RideRequest,
    RouteWatch, SafetyProfile, SavedPlace, SosEvent, SupportMessage, SupportTicket,
    PreTripCheck, TaxiApplication, TaxiWorkDay, TgAuth, Trust, TripShare, TrustedContact,
    UploadEvent, User,
    WaitlistEntry,
)
from .storage import get_storage

# Заказ такси ещё «живой»: он либо ищет машину, либо кто-то уже едет.
_LIVE_ORDER_STATUSES = (
    InstantOrderStatus.created, InstantOrderStatus.scheduled, InstantOrderStatus.searching,
    InstantOrderStatus.offered, InstantOrderStatus.accepted, InstantOrderStatus.arriving,
    InstantOrderStatus.onboard,
)
# Посылка физически в работе: курьер её взял и ещё не закрыл (везёт туда или обратно).
_LIVE_PARCEL_STATUSES = ("accepted", "in_transit", "returning")
# Договорённость по попутке ещё в силе: люди рассчитывают друг на друга.
_LIVE_BOOKING_STATUSES = (BookingStatus.pending, BookingStatus.confirmed, BookingStatus.onboard)


def guard_can_delete(session: Session, user: User) -> None:
    """Честные условия удаления аккаунта. Нарушено — 409 с объяснением на двух языках.

    Зачем (аудит 2026-08-03): у `/me/delete` не было ни одной проверки, а каскад ниже
    сносит долги и активные заказы. На практике это три дыры:
      • водитель с неоплаченной комиссией жал «удалить» — долг исчезал вместе с ним;
      • пассажир удалялся посреди поездки — заказ пропадал у водителя прямо в дороге;
      • курьер удалялся с чужой посылкой в руках — у посылки обнулялся курьер, и не
        оставалось даже следа, кто её вёз.
    Мы не отказываем «навсегда»: текст говорит, что именно закрыть, чтобы удалиться.
    """
    # 1) Комиссия такси (Модель А, «на доверии»). paid — закрыто; unpaid/pending — нет.
    owed_taxi = int(session.exec(
        select(func.coalesce(func.sum(CommissionDebt.amount_kop), 0)).where(
            CommissionDebt.driver_id == user.id,
            CommissionDebt.status != DebtStatus.paid,
        )
    ).one() or 0)
    if owed_taxi > 0:
        rub = owed_taxi // 100
        raise herr(409,
                   f"Сначала закрой комиссию — {rub} ₽. Оплати её в разделе «Деньги», "
                   "и аккаунт можно будет удалить.",
                   f"Башта комиссияны яп — {rub} һум. «Аҡса» бүлегендә түлә, "
                   "шунан аккаунтты юйып була.")

    # 2) Комиссия курьера — та же логика, только считается по доставленным заказам.
    # Формулу не дублируем: берём единственный источник из кабинета курьера (ленивый импорт —
    # courier.py тянет parcels.py, на уровне модуля это был бы цикл).
    from .routers.courier import _commission_owed_kop
    owed_courier = _commission_owed_kop(session, user.id)
    if owed_courier > 0:
        rub = owed_courier // 100
        raise herr(409,
                   f"Сначала оплати комиссию курьера — {rub} ₽. Она в кабинете курьера, "
                   "после оплаты аккаунт можно удалить.",
                   f"Башта курьер комиссияһын түлә — {rub} һум. Ул курьер кабинетында, "
                   "түләгәс аккаунтты юйып була.")

    # 3) Живой такси-заказ — хоть пассажиром, хоть водителем.
    live_order = session.exec(
        select(InstantOrder.id).where(
            or_(InstantOrder.passenger_id == user.id, InstantOrder.driver_id == user.id),
            InstantOrder.status.in_(_LIVE_ORDER_STATUSES),
        ).limit(1)
    ).first()
    if live_order is not None:
        raise herr(409,
                   "У тебя есть активный заказ такси (или предзаказ). Заверши или отмени его — "
                   "и возвращайся к удалению аккаунта.",
                   "Һинең әүҙем такси заказың (йәки алдан заказың) бар. Уны тамамла йәки кире ал — "
                   "шунан аккаунтты юйырға ҡайт.")

    # 4) Посылка в работе: моя (я отправитель) или чужая, которую везу я.
    live_parcel = session.exec(
        select(ParcelDelivery.sender_id, ParcelDelivery.courier_id).where(
            or_(ParcelDelivery.sender_id == user.id, ParcelDelivery.courier_id == user.id),
            ParcelDelivery.status.in_(_LIVE_PARCEL_STATUSES),
        ).limit(1)
    ).first()
    if live_parcel is not None:
        if live_parcel[1] == user.id:
            raise herr(409,
                       "Ты везёшь посылку. Доставь её или сними себя с доставки — "
                       "и возвращайся к удалению аккаунта.",
                       "Һин бандероль алып бараһың. Уны еткер йәки доставканан баш тарт — "
                       "шунан аккаунтты юйырға ҡайт.")
        raise herr(409,
                   "Твоя посылка сейчас у курьера. Дождись доставки или отмени заявку — "
                   "и возвращайся к удалению аккаунта.",
                   "Һинең бандеролең хәҙер курьерҙа. Еткереүен көт йәки заявканы кире ал — "
                   "шунан аккаунтты юйырға ҡайт.")

    # 5) Живая договорённость по ПОПУТКЕ. Аудит 2026-08-06: у такси и доставки исчезнуть посреди
    # дела было нельзя, а у попутки — можно, хотя это самый старый сценарий. Каскад ниже сносит
    # поездку водителя ВМЕСТЕ с чужими бронями на ней («Брони: … чужие на МОИХ поездках»), и
    # никто никого не предупреждает. Человек приходит к назначенному времени на трассу, машины
    # нет, а в приложении нет и самой поездки — как будто её не было. Позвонить тоже некому:
    # телефон второй стороны виден только внутри брони.
    # Отказ не «навсегда»: отмена брони и отмена рейса уже шлют уведомление второй стороне —
    # текст ведёт ровно туда.
    live_booking = session.exec(
        select(Booking.id).where(
            Booking.passenger_id == user.id,
            Booking.status.in_(_LIVE_BOOKING_STATUSES),
        ).limit(1)
    ).first()
    if live_booking is not None:
        raise herr(409,
                   "У тебя есть бронь в попутке. Отмени её — водитель получит уведомление, "
                   "и возвращайся к удалению аккаунта.",
                   "Һинең юлдаш сәфәрендә бронең бар. Уны кире ал — йөрөтөүсегә хәбәр китә, "
                   "шунан аккаунтты юйырға ҡайт.")

    my_ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == user.id)).all())
    if my_ride_ids:
        passengers_waiting = session.exec(
            select(Booking.id).where(
                Booking.ride_id.in_(my_ride_ids),
                Booking.status.in_(_LIVE_BOOKING_STATUSES),
            ).limit(1)
        ).first()
        if passengers_waiting is not None:
            raise herr(409,
                       "На твою поездку рассчитывают пассажиры. Отмени рейс — они получат "
                       "уведомление и успеют найти другую машину, — и возвращайся к удалению.",
                       "Һинең сәфәреңә юлаусылар өмөт итә. Рейсты кире ал — улар хәбәр алыр һәм "
                       "башҡа машина табып өлгөрөр, — шунан юйыуға ҡайт.")


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
    # 3.1 Сообщения: мои + в удаляемых бронях + в удаляемых такси-заказах + в удаляемых посылках
    # (чат отправитель ↔ курьер: сообщения ВТОРОЙ стороны тоже держат FK на посылку — без этой
    # строки delete(ParcelDelivery) на шаге 3.12 падал бы по внешнему ключу на Postgres,
    # и аккаунт становился неудаляемым, а это 152-ФЗ).
    msg = [Message.sender_id == uid]
    if booking_ids:
        msg.append(Message.booking_id.in_(booking_ids))
    if order_ids:
        msg.append(Message.order_id.in_(order_ids))
    if parcel_ids:
        msg.append(Message.parcel_id.in_(parcel_ids))
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
    # 3.4 SOS: мои события удаляем (это МОИ данные), но ОБЕЗЛИЧИВАЕМ те, где я фигурант
    # чужого происшествия по общей поездке — см. пояснение к 3.5.
    sos = [SosEvent.user_id == uid]
    dele(SosEvent, *sos)
    sos_unlink = []
    if booking_ids:
        sos_unlink.append(SosEvent.booking_id.in_(booking_ids))
    if order_ids:
        sos_unlink.append(SosEvent.order_id.in_(order_ids))
    if sos_unlink:
        session.execute(update(SosEvent).where(or_(*sos_unlink))
                        .values(booking_id=None, order_id=None, note=""))
    # `handled_by` — админ, принявший сигнал. Ссылку надо снять отдельно: она не про поездку,
    # а про человека, поэтому под условия выше не попадала. Без разрыва удаление АДМИНА
    # падало по внешнему ключу на Postgres, то есть аккаунт становился неудаляемым.
    # На SQLite без `PRAGMA foreign_keys=ON` это было не видно — ровно та ловушка, ради
    # которой проверку ключей включили в тестах (аудит 2026-08-07).
    session.execute(update(SosEvent).where(SosEvent.handled_by == uid).values(handled_by=None))
    # 3.5 Жалобы. МОИ (я автор) — удаляем целиком: это мои персональные данные.
    # Жалобы НА МЕНЯ — НЕ удаляем, а обезличиваем (обнуляем ссылку на меня и стираем текст).
    #
    # Почему так (аудит 2026-07-26): раньше удалялись и те, где человек — обвиняемый, и
    # нарушитель одним тапом стирал доказательства против себя: три жалобы за поведение →
    # «удалить аккаунт» → чисто. При этом факт разбора нужен платформе для защиты законного
    # интереса (ст. 6 152-ФЗ) и на случай запроса полиции/суда. Ровно тот же приём уже
    # применён к бану устройства (3.22): строка живёт, персональная ссылка снимается.
    # Обезличенная строка не содержит ни ссылки на пользователя, ни его текста — только факт,
    # дату и категорию, поэтому generic-инвариант «ноль ссылок на user.id» по-прежнему держится.
    dele(Report, Report.reporter_id == uid)
    session.execute(update(Report).where(Report.target_user_id == uid)
                    .values(target_user_id=None, reason=""))
    rep_ctx = []
    if order_ids:
        rep_ctx.append(Report.order_id.in_(order_ids))
    if parcel_ids:
        rep_ctx.append(Report.parcel_id.in_(parcel_ids))
    if rep_ctx:
        session.execute(update(Report).where(or_(*rep_ctx))
                        .values(order_id=None, parcel_id=None))
    # Жалоба может быть привязана и к БРОНИ (safety.py передаёт booking_id) — эту ссылку тоже
    # снимаем, иначе выжившая обезличенная жалоба держит FK на удаляемую бронь и delete(Booking)
    # падает на Postgres (аудит 2026-08-03: шаг закрывал только order_id/parcel_id).
    if booking_ids:
        session.execute(update(Report).where(Report.booking_id.in_(booking_ids))
                        .values(booking_id=None))
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
    # 3.7 Финансы такси: МОИ долг по комиссии и записи кошелька — удаляем, это мои данные.
    #
    # А вот по удаляемым заказам удалять нельзя (аудит 2026-08-07). `order_ids` собран по
    # «я пассажир ИЛИ я водитель», и раньше удаление шло по этому списку: пассажир, удаляя
    # свой аккаунт, стирал долг ВОДИТЕЛЯ за уже сделанную поездку и его запись заработка.
    # Это и бесплатный способ обнулить комиссию (договориться, чтобы пассажир удалился),
    # и нарушение собственного правила ledger — «только append, историю денег не удаляем»
    # (models.py: LedgerEntry). Поэтому у чужих записей снимаем ТОЛЬКО ссылку на заказ:
    # деньги остаются, персональных данных удаляемого в них нет.
    dele(CommissionDebt, CommissionDebt.driver_id == uid)
    dele(LedgerEntry, LedgerEntry.driver_id == uid)
    if order_ids:
        session.execute(update(CommissionDebt).where(CommissionDebt.order_id.in_(order_ids))
                        .values(order_id=None))
        session.execute(update(LedgerEntry).where(LedgerEntry.order_id.in_(order_ids))
                        .values(order_id=None))
    # То же самое ключом на бронь: без разрыва delete(Booking) падает по внешнему ключу
    # на Postgres, а это уже неудаляемый аккаунт (152-ФЗ даёт право на удаление).
    if booking_ids:
        session.execute(update(LedgerEntry).where(LedgerEntry.booking_id.in_(booking_ids))
                        .values(booking_id=None))
    # 3.7-bis «Справедливость»: споры, где я сторона.
    #
    # Почему НЕ удаляем (аудит 2026-08-03): раньше стиралась любая строка, где человек —
    # заявитель ИЛИ обвинённый. Обвинённый одним тапом «удалить аккаунт» уничтожал заявление
    # жертвы, её фото-улики и уже вынесенное решение админа — ровно та дыра, которую для жалоб
    # (Report) закрыли на шаге 3.5. Переносим тот же приём:
    #   • обе стороны — это я (второй уже нет) → спор бессмыслен, удаляем;
    #   • вторая сторона жива → ОБЕЗЛИЧИВАЕМ мою сторону: ссылка на меня → NULL, мой свободный
    #     текст и список МОИХ фото — пусто. Суть спора (тип, статус, решение, объяснение админа,
    #     компенсация) и улики второй стороны остаются: это её данные и её защита.
    # Мои файлы-улики стираются с диска отдельно (см. п.1) — ссылок на них больше нет; файлы
    # второй стороны, наоборот, перестают быть сиротами (раньше строка исчезала, а фото жило).
    # Обезличенная строка не содержит ни ссылки на пользователя, ни его текста, поэтому
    # generic-инвариант «ноль ссылок на user.id» по-прежнему держится.
    # appeal_text не трогаем: апелляцию подаёт любая из сторон, автор в модели не хранится —
    # это часть решения по спору (как объяснение админа), а не текст конкретной стороны.
    dele(Incident,
         and_(Incident.reporter_id == uid, Incident.respondent_id.is_(None)),
         and_(Incident.respondent_id == uid, Incident.reporter_id.is_(None)))
    session.execute(update(Incident).where(Incident.reporter_id == uid)
                    .values(reporter_id=None, description="", evidence_urls="", reporter_role=""))
    session.execute(update(Incident).where(Incident.respondent_id == uid)
                    .values(respondent_id=None, respondent_statement="",
                            respondent_evidence_urls=""))
    # Контекст спора (бронь/такси-заказ/посылка) уходит вместе с аккаунтом — на выживших строках
    # ссылку снимаем, иначе внешний ключ повиснет и delete(Booking)/delete(InstantOrder) упадёт.
    inc_ctx = []
    if booking_ids:
        inc_ctx.append(Incident.booking_id.in_(booking_ids))
    if order_ids:
        inc_ctx.append(Incident.order_id.in_(order_ids))
    if parcel_ids:
        inc_ctx.append(Incident.parcel_id.in_(parcel_ids))
    if inc_ctx:
        session.execute(update(Incident).where(or_(*inc_ctx))
                        .values(booking_id=None, order_id=None, parcel_id=None))
    # В чужих спорах, решённых мной как админом, само решение не трогаем — только отвязываем
    # ссылку (FK resolved_by). Профиль безопасности (страйки/паузы) удаляем целиком.
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
    # Промо-скидка, потраченная на удаляемом заказе, держит на него внешний ключ: без снятия
    # ссылки delete(InstantOrder) падает на Postgres, а это неудаляемый аккаунт (152-ФЗ).
    # Снимаем ТОЛЬКО ссылку: отметку used_at оставляем, иначе чужая скидка (пассажира, которого
    # вёз удаляющийся водитель) стала бы «не потраченной» и человек получил бы её второй раз.
    # Сама PromoRedemption удаляется ниже, на 3.14 (порядок дети → родители там свой).
    if order_ids:
        session.execute(update(PromoRedemption).where(PromoRedemption.used_order_id.in_(order_ids))
                        .values(used_order_id=None))
    # Журнал причин отказа ссылается на ЗАКАЗ. Чужие водители отказывались от заказов этого
    # человека — их строки нужно убрать ДО удаления самих заказов, иначе внешний ключ на боевом
    # Postgres не даст удалить аккаунт вообще (152-ФЗ: удаление обязано работать). На SQLite
    # проверки ключей выключены, поэтому тесты этого не показывали — ровно тот случай, что уже
    # записан правилом в docs/lessons.md.
    if order_ids:
        session.execute(delete(OfferDecline).where(OfferDecline.order_id.in_(order_ids)))
    session.execute(delete(OfferDecline).where(OfferDecline.driver_id == uid))
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
    # Предрейсовые подтверждения — заявления человека О САМОМ СЕБЕ. Третьей стороне они вреда
    # не наносят и уликами против кого-то не являются, поэтому стираем вместе с аккаунтом
    # (в отличие от жалоб, где мы обезличиваем, но сохраняем — там есть пострадавший).
    session.execute(delete(PreTripCheck).where(PreTripCheck.driver_id == uid))
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
