from datetime import date as date_type, datetime
from enum import Enum
from typing import Optional

from sqlalchemy import UniqueConstraint
from sqlmodel import SQLModel, Field

from .timeutil import utcnow

# Соответствует docs/backend.md §3. Фаза 1: только ядро.


class UserRole(str, Enum):
    passenger = "passenger"
    driver = "driver"
    admin = "admin"


class RideCategory(str, Enum):
    regular = "regular"
    hospital = "hospital"
    parcel = "parcel"
    cargo = "cargo"          # грузовая (перевозка вещей/мебели, большой багажник/газель)
    urgent = "urgent"        # срочно (нужна машина срочно — экстренно, опоздание, больница)


class RideStatus(str, Enum):
    active = "active"
    done = "done"
    cancelled = "cancelled"


class BookingStatus(str, Enum):
    pending = "pending"
    confirmed = "confirmed"
    onboard = "onboard"
    done = "done"
    cancelled = "cancelled"


class PayMethod(str, Enum):
    """Как договорились платить — это ЗАПИСЬ ДОГОВОРЁННОСТИ, а не платёж и не движение денег.
    Юр-модель не меняется: деньги пассажир и водитель передают сами (СБП «на доверии»).
    Поле лишь фиксирует, о чём договорились, видно обеим сторонам (полезно в споре:
    «мы же договаривались о 400»)."""
    cash = "cash"            # наличными
    sbp = "sbp"              # переводом по СБП
    negotiate = "negotiate"  # договоримся на месте


class User(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    phone: str = Field(index=True, unique=True)
    name: str = ""
    avatar_url: str = ""        # фото профиля (публичный media-URL), необязательно
    role: UserRole = UserRole.passenger
    language: str = "ru"
    verified: bool = False
    is_advertiser: bool = False  # рекламодатель: владеет ≥1 объявлением → открывается кабинет партнёра
    # OAuth / Социальные сети
    telegram_id: Optional[str] = Field(default=None, index=True, unique=True)
    # (vk_id / whatsapp_verified удалены — мёртвые колонки, дропнуты миграцией 0002; вход через Telegram-код)
    # Выход/ревокация: токены, выпущенные ДО этого момента, считаются недействительными
    # (logout «со всех устройств», смена/угон телефона). Сравнивается с `iat` токена.
    tokens_valid_from: Optional[datetime] = None
    # Реферал «позови своего»: свой код, кто пригласил, бонусы (1 бонус = 1 бесплатное поднятие).
    referral_code: str = Field(default="", index=True)
    referred_by: Optional[int] = Field(default=None, foreign_key="user.id")
    referral_credits: int = 0
    # Анти-фрод (B8): последнее устройство входа (X-Device-Id, ANDROID_ID клиента).
    # По нему: бан устройства ловит обход бана новым номером; вход с нового устройства → сигнал.
    # Приватность: наружу не отдаём, в логи не пишем.
    last_device_id: Optional[str] = Field(default=None, index=True)
    created_at: datetime = Field(default_factory=utcnow)


class RefreshToken(SQLModel, table=True):
    """Refresh-токен (ротируемый). Храним ХЕШ (sha256), не сам токен. При каждом
    /auth/refresh старый помечается revoked и выдаётся новый. logout ревокует все."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    token_hash: str = Field(index=True, unique=True)
    revoked: bool = False
    expires_at: datetime
    created_at: datetime = Field(default_factory=utcnow)


class OtpCode(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    phone: str = Field(index=True)
    code: str
    attempts: int = 0                                              # попыток ввода (защита от перебора)
    created_at: datetime = Field(default_factory=utcnow)  # для throttle запросов кода
    expires_at: datetime


class DeviceToken(SQLModel, table=True):
    """FCM-токен устройства пользователя (для push). Один пользователь — несколько устройств."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    token: str = Field(index=True, unique=True)
    created_at: datetime = Field(default_factory=utcnow)


class TgAuth(SQLModel, table=True):
    """Сессия входа через Telegram-бота: request_id ↔ telegram_id ↔ 6-значный код."""
    id: Optional[int] = Field(default=None, primary_key=True)
    request_id: str = Field(index=True, unique=True)
    telegram_id: Optional[str] = None
    username: str = ""
    first_name: str = ""
    code: Optional[str] = None
    status: str = "waiting"          # waiting (ждём Старт) / sent (код отправлен) / used
    attempts: int = 0                # попыток ввода кода (защита от перебора)
    # Реальный номер, которым юзер поделился в боте (кнопка request_contact). Необязателен.
    # Применяется к User при верификации (если юзер ещё не создан на момент шеринга).
    shared_phone: Optional[str] = None
    created_at: datetime = Field(default_factory=utcnow)
    expires_at: datetime


class Settlement(SQLModel, table=True):
    """Справочник населённых пунктов (волна 2, география): 21 город респ. значения РБ +
    центры 54 районов + приграничные города соседних регионов. Сеется идемпотентно
    (app/geo.py, seed_settlements). kind: city | district_center | neighbor.
    name_ba — черновой башкирский (финал названий — за Александром, носителем)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    name_ru: str = Field(index=True)
    name_ba: Optional[str] = None
    region: str = ""                 # «РБ», «Челябинская обл.», «Татарстан»…
    kind: str = "city"               # city | district_center | neighbor
    lat: float = 0.0
    lng: float = 0.0
    active: bool = True


class DriverProfile(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    online: bool = False
    # Зона работы таксиста (волна 2, география). NULL = зона не выбрана → прежнее
    # поведение matcher'а (получает всё рядом). Значения: city | intercity | region.
    work_zone: Optional[str] = None
    work_city: Optional[str] = None       # для work_zone=city: «мой город» (name_ru)
    work_direction_id: Optional[int] = Field(default=None, foreign_key="settlement.id")  # intercity: закреплённое направление
    # Класс машины для такси (волна 2, §6): economy | comfort. NULL = economy (прежнее
    # поведение). Водитель заявляет в онбординге таксиста, админ подтверждает при approve.
    car_class: Optional[str] = None
    # Пол водителя — СТРОГО opt-in. "" (по умолчанию, не указан, нигде не показывается) /
    # female / male. Публично раскрываем ТОЛЬКО полезный сигнал «женщина за рулём»
    # (driver_is_woman): male и "" в витрине неразличимы (см. services.ride_out_with).
    gender: str = ""
    rating: float = 5.0
    trips_count: int = 0
    car_make: str = ""
    car_model: str = ""
    car_color: str = ""
    car_plate: str = ""
    seats: int = 4
    # Лестница качества (волна 2, §9). taxi_paused_until — пауза ТАКСИ (попутка работает):
    # авто (≥N resolved-жалоб за окно / тяжёлая категория до разбора) или вручную админом.
    # taxi_pause_reason: reports | review | admin (что показать водителю, без автора жалобы).
    taxi_paused_until: Optional[datetime] = None
    taxi_pause_reason: Optional[str] = None
    # Дедуп мягкого пуш-совета при rating < quality_advice_rating (не чаще 1/нед).
    low_rating_advice_at: Optional[datetime] = None
    # Дедуп напоминания «выдай чек в „Мой налог"» после done такси-заказа (не чаще 1/сутки, B7b-4).
    receipt_reminder_at: Optional[datetime] = None
    docs_status: str = "none"         # none / pending / verified / rejected
    license_url: str = ""             # фото водительского удостоверения
    car_photo_url: str = ""           # фото автомобиля
    verify_submitted_at: Optional[datetime] = None
    # Авто-проверка документов (OCR прав). Подсказка админу + основа для авто-решения.
    autocheck_result: str = ""        # "" (не проверяли) / pass / needs_human / reject / error
    autocheck_score: float = 0.0      # 0..1 — уверенность авто-проверки
    autocheck_data: str = ""          # JSON: распознанные поля + коды причин (для админа/клиента)
    autocheck_at: Optional[datetime] = None


class Ride(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    depart_at: datetime = Field(index=True)   # сортировка выдачи /rides по времени выезда
    seats_total: int = 3
    seats_left: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = ""
    pickup: str = ""                  # где водитель забирает (точка сбора, текст)
    pickup_lat: Optional[float] = None  # координаты точки сбора (пин на карте)
    pickup_lng: Optional[float] = None
    # Посылка (category=parcel): кому отдать на месте + габарит/вес. Только для parcel-поездок.
    receiver_name: Optional[str] = None
    parcel_size: Optional[str] = None
    # Гео-координаты концов маршрута (геокодятся из from_city/to_city при публикации).
    # Нужны для радиус-поиска: PostGIS на проде, haversine-фолбэк на sqlite/без координат.
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None
    recurrence: str = "none"          # none / daily / weekdays / weekly — регулярная поездка
    # Премиум-предпочтения поездки (двусторонний фильтр водитель↔пассажир)
    pets_allowed: bool = False        # можно с животными
    child_seat: bool = False          # есть детское кресло/бустер
    women_only: bool = False          # только женщины
    smoking: bool = False             # курение разрешено
    baggage: bool = False             # есть место под багаж
    air_conditioner: bool = False     # кондиционер
    only_trusted: bool = False        # «только для своих»: поездку видят/бронируют лишь L3 (свои); скрыта от L0–L2
    status: RideStatus = Field(default=RideStatus.active, index=True)   # /rides и /rides/near фильтруют active
    # Boost (платное поднятие): пока boosted_until > now — поездка выше в выдаче.
    boosted_until: Optional[datetime] = Field(default=None, index=True)
    boost_tier: str = ""              # quick / day / urgent (последний оплаченный тариф)
    created_at: datetime = Field(default_factory=utcnow)


class RideRequest(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    passenger_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    # Координаты концов маршрута (геокодятся при создании, как у Ride) — для карты и радиус-поиска заявок.
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    # Предпочтения/условия пассажира (что нужно/с чем едет) — водитель видит и подбирает поездку.
    women_only: bool = False        # только женщины-водители/салон
    child_seat: bool = False        # нужно детское кресло
    pets: bool = False              # еду с животным
    wheelchair: bool = False        # нужна доступность для инвалидной коляски
    non_smoking: bool = False       # некурящий салон
    air_conditioner: bool = False   # нужен кондиционер
    only_trusted: bool = False      # «только для своих»: заявку видят/берут лишь водители L3 (свои)
    comment: str = ""
    for_relative_name: Optional[str] = None
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосовой заявки
    status: str = "active"
    created_at: datetime = Field(default_factory=utcnow)


class PickupPoint(SQLModel, table=True):
    """Точка сбора по ориентиру города/села (РБ-фишка: в сёлах адресов нет —
    ориентир вроде «у мечети», «у Магнита», «автовокзал»).

    ПУБЛИЧНЫЙ справочник ориентиров (НЕ персональные данные): двуязычные названия
    RU+BA + координаты пина. Наполняется сидом популярных точек крупных городов и
    ПОПОЛНЯЕТСЯ из реально выбранных водителями точек при создании поездки
    (`usage_count` — как часто выбирают: чем выше, тем раньше в подсказках)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    city: str = Field(index=True)             # город/село, к которому относится ориентир
    title_ru: str = ""                        # «у мечети»
    title_ba: str = ""                        # «мәсет янында»
    lat: Optional[float] = None               # координаты пина ориентира
    lng: Optional[float] = None
    usage_count: int = Field(default=0, index=True)  # сколько раз выбрали → сортировка подсказок
    is_seed: bool = False                     # сидовый ориентир (курируемый) vs пользовательский
    created_at: datetime = Field(default_factory=utcnow)


class Booking(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    ride_id: int = Field(index=True, foreign_key="ride.id")
    passenger_id: int = Field(index=True, foreign_key="user.id")
    seats: int = 1
    price: int = 0
    status: BookingStatus = BookingStatus.pending
    driver_phase: str = ""           # подфаза активной поездки от водителя: "" / departed / arriving (для live-баннера пассажиру)
    boarding_code: str = ""
    # Оплата после done (Фаза 3, деньги v1). paid — факт оплаты; наличные через нас НЕ идут
    # (ledger не двигаем), безнал (ЮKassa) начисляет водителю через ledger.
    paid: bool = False
    payment_method: str = ""         # "" / cash / card / sbp / yookassa
    # Анти-фрод (B8-7/8): пометки на брони. unpaid_reported — водитель нажал «Пассажир не
    # заплатил» (жалоба unpaid, дедуп). contact_then_cancel — отмена ПОСЛЕ открытия
    # телефона/чата (паттерн «увод мимо приложения», счётчик в админ-пульсе).
    unpaid_reported: bool = False
    contact_then_cancel: bool = False
    cancelled_at: Optional[datetime] = None   # когда бронь отменили (для счётчиков за день)
    # Договорённость об оплате (НЕ платёж, деньги через приложение не идут): как решили платить.
    # Видно обеим сторонам, помогает в споре. Способ по умолчанию — «договоримся».
    pay_method: PayMethod = PayMethod.negotiate
    pay_amount: Optional[int] = None  # сумма, о которой договорились, ₽ (опц.; None = не фиксировали)
    # F12 «Зимний протокол»: авто-проверка «доехал?». sent_at — когда обеим сторонам ушёл пуш
    # «всё в порядке?»; ack_at — когда участник подтвердил, что доехал/всё хорошо (гасит эскалацию).
    winter_check_sent_at: Optional[datetime] = None
    winter_check_ack_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utcnow)


# ---- Фаза 2: чат, семья, безопасность ----

class Message(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    # Ровно ОДНА привязка: booking_id (чат брони попутки) ИЛИ order_id (чат такси-заказа, B7b-1).
    # Старые строки — все с booking_id, колонка стала nullable без потери данных.
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    sender_id: int = Field(foreign_key="user.id")
    text: str = ""
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосового
    deleted: bool = False                   # «удалено у всех» (текст очищен, видна пометка)
    edited: bool = False                    # отредактировано (пометка «изменено»)
    hidden_user_ids: str = ""               # CSV id юзеров, кто скрыл «у себя» (тред хранится на сервере)
    # Анти-фишинг (B8-6): "" — обычное; "warn" — похоже на развод (просьба кода из SMS,
    # номер карты, «переведи на другой номер»). НЕ блокируем — клиент показывает получателю
    # плашку «Никому не сообщай коды из SMS. Юлдаш никогда их не просит».
    flag: str = ""
    # Официальность (B8-9): сообщение от админа/системы → клиент рисует бейдж «Юлдаш ✓»
    # (мошенник не может прикинуться поддержкой — флаг ставит только сервер).
    from_admin: bool = False
    created_at: datetime = Field(default_factory=utcnow)


class TrustedContact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    name: str
    relation: str = ""
    phone: str = ""
    notify_by_default: bool = True


class TripShare(SQLModel, table=True):
    """Поездка, расшаренная близкому (семейный контроль). Привязка: booking_id (бронь
    попутки) ИЛИ order_id (такси-заказ, B7b-2) — ровно одна из двух."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    contact_id: int = Field(foreign_key="trustedcontact.id")
    # Live-ссылка близкому (B7c): capability-токен публичной страницы /t/{token}.
    # ≥16 случайных байт (secrets.token_urlsafe). NULL у строк до миграции w2_livelink —
    # догенерируется при следующем share. Отзыв share (DELETE) удаляет строку → токен «сгорает».
    token: Optional[str] = Field(default=None, index=True, unique=True)
    last_status: str = "shared"             # shared / sat / arrived / done
    created_at: datetime = Field(default_factory=utcnow)


class SosEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")   # SOS из такси-заказа (B7b-2)
    category: str = "other"                 # medical / breakdown / other
    note: str = ""
    status: str = "open"                    # open / handled
    created_at: datetime = Field(default_factory=utcnow)


class Report(SQLModel, table=True):
    """Жалоба (волна 2, §9 «Качество»). Анонимность — продукт: цель жалобы НИКОГДА не видит
    автора (reporter_id отдаётся только админу). category — закрытый перечень (см.
    app/quality.py); привязка к поездке (order_id/booking_id) доказывает, что стороны реально
    ехали вместе. status: new → reviewing → resolved | rejected (разбор у админа, человек в контуре)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    reporter_id: int = Field(index=True, foreign_key="user.id")
    target_user_id: int = Field(index=True, foreign_key="user.id")
    reason: str = ""                                       # свободный текст — детали (опционально)
    category: str = Field(default="other", index=True)     # перечень в quality.REPORT_CATEGORIES
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")   # быстрый заказ
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")      # бронь попутки
    status: str = Field(default="new", index=True)         # new | reviewing | resolved | rejected
    resolution: Optional[str] = None                       # решение админа (текст разбора)
    resolved_at: Optional[datetime] = None                 # когда разобрано (resolve/reject)
    created_at: datetime = Field(default_factory=utcnow)


class Block(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    blocked_user_id: int = Field(foreign_key="user.id")
    created_at: datetime = Field(default_factory=utcnow)


class Rating(SQLModel, table=True):
    """Оценка после поездки: rater оценил ratee (1..5 звёзд). Одна на (booking, rater) ИЛИ
    (order, rater) — волна 2 §9: взаимные оценки и у быстрых заказов. Ровно одна привязка:
    booking_id (попутка) или order_id (такси). Оценка анонимна: в агрегат идёт среднее,
    кто поставил — не раскрывается (rater_id не отдаём наружу).

    Звёзды идут в средний рейтинг СРАЗУ. Текстовый отзыв (`text`, необязателен, ≤500)
    появляется в публичном профиле ТОЛЬКО после модерации — как AppReview: `text_published`
    по умолчанию False, админ одобряет. Пустой текст модерации не требует."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    rater_id: int = Field(index=True, foreign_key="user.id")        # кто оценил
    ratee_id: int = Field(index=True, foreign_key="user.id")        # кого оценили (водитель или пассажир)
    stars: int = 5                           # 1..5
    text: str = ""                           # текстовый отзыв (опц., ≤500) — идёт на модерацию
    text_published: bool = Field(default=False, index=True)  # текст одобрен к показу в публичном профиле
    created_at: datetime = Field(default_factory=utcnow)


class RequestResponse(SQLModel, table=True):
    """Отклик водителя на заявку пассажира: предлагает поездку (цена/коммент).
    Пассажир принимает → создаётся Ride+Booking (обычная поездка с чатом)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    request_id: int = Field(index=True, foreign_key="riderequest.id")
    driver_id: int = Field(index=True, foreign_key="user.id")
    price: int = 0
    comment: str = ""
    status: str = "offered"                  # offered / accepted / declined
    created_at: datetime = Field(default_factory=utcnow)


# ---- Фаза 2: «Быстрый заказ» (такси-режим) — presence/тариф/заказ/matcher ----

class InstantOrderStatus(str, Enum):
    """Машина состояний быстрого заказа. Терминальные: done/cancelled/expired (выхода нет)."""
    created = "created"        # заказ создан
    searching = "searching"    # matcher ищет водителя
    offered = "offered"        # оффер отправлен водителю (ждём accept/decline/timeout)
    accepted = "accepted"      # водитель принял (телефоны раскрываются обеим сторонам)
    arriving = "arriving"      # водитель едет к пассажиру (подача)
    onboard = "onboard"        # пассажир в машине
    done = "done"              # поездка завершена
    cancelled = "cancelled"    # отменён (пассажиром или водителем)
    expired = "expired"        # никто не принял / рядом никого


class Tariff(SQLModel, table=True):
    """Тариф быстрого заказа. Цену считает СЕРВЕР (не клиент): price = max(min_price,
    base + per_km·dist + per_min·eta) · k, округление до 10 ₽. zone: city|intercity."""
    id: Optional[int] = Field(default=None, primary_key=True)
    zone: str = Field(default="city", index=True)       # city | intercity
    category: str = Field(default="standard", index=True)  # standard | ... (на будущее)
    base: int = 0                    # подача, ₽
    per_km: float = 0.0              # ₽ за км
    per_min: float = 0.0             # ₽ за минуту
    min_price: int = 0               # минимальная цена поездки, ₽
    k: float = 1.0                   # surge-коэффициент (v1 = 1.0, поле на будущее)
    active: bool = Field(default=True, index=True)
    created_at: datetime = Field(default_factory=utcnow)


class InstantOrder(SQLModel, table=True):
    """Быстрый заказ (такси-режим). Отдельный поток от плановых поездок (Ride/Booking).
    Все переходы статусов — под row-lock, идемпотентные. Координаты в БД нужны для трека
    и приватности (телефоны раскрываются только после accept)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    passenger_id: int = Field(index=True, foreign_key="user.id")
    # Точки А (откуда) и Б (куда)
    from_lat: float = 0.0
    from_lng: float = 0.0
    to_lat: float = 0.0
    to_lng: float = 0.0
    from_text: str = ""
    to_text: str = ""
    category: str = "standard"
    status: InstantOrderStatus = Field(default=InstantOrderStatus.created, index=True)
    # Цена: estimate — оценка сервера при создании; final — фактическая при завершении.
    price_estimate: int = 0
    price_final: Optional[int] = None
    tariff_id: Optional[int] = Field(default=None, foreign_key="tariff.id")
    # Применённый сурж-коэффициент (волна 2, §5): фиксируется на заказе в момент создания,
    # price_estimate уже с ним — цена не «уезжает» задним числом.
    surge_k: float = 1.0
    distance_km: float = 0.0
    eta_min: float = 0.0
    # Оплата после done (Фаза 3, деньги v1). paid — факт оплаты (нал/безнал). Наличные через
    # нас НЕ идут (ledger не двигаем), безнал (ЮKassa) начисляет водителю через ledger.
    paid: bool = False
    payment_method: str = ""         # "" / cash / card / sbp / yookassa
    # Назначенный водитель (после accept). До accept телефоны скрыты.
    driver_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")
    # Текущий оффер (кому сейчас предложено) + дедлайн ответа + счётчик кругов подбора.
    current_offer_driver_id: Optional[int] = Field(default=None, foreign_key="user.id")
    offer_expires_at: Optional[datetime] = None
    search_round: int = 0
    # Отмена: кто и почему.
    cancel_by: str = ""              # passenger | driver | system
    cancel_reason: str = ""
    # Анти-фрод (B8-7/8): пометки на заказе (см. одноимённые поля Booking).
    unpaid_reported: bool = False
    contact_then_cancel: bool = False
    # Отмены/ожидание (волна 2, §5, Модель А = страйки, денег не двигаем).
    # waiting_started_at — водитель нажал «Я на месте» (подача завершена, пошло ожидание).
    waiting_started_at: Optional[datetime] = None
    waiting_fee_kop: int = 0         # платное ожидание сверх бесплатного, копейки (фикс на onboard)
    cancel_fee_kop: int = 0          # штраф за позднюю отмену / no-show = подача, копейки (Модель А: только фиксируем)
    no_show: bool = False            # «пассажир не вышел» — отмена водителем по таймингу
    # Таймстампы переходов (пишутся машиной состояний).
    created_at: datetime = Field(default_factory=utcnow)
    searching_at: Optional[datetime] = None
    offered_at: Optional[datetime] = None
    accepted_at: Optional[datetime] = None
    arriving_at: Optional[datetime] = None
    onboard_at: Optional[datetime] = None
    done_at: Optional[datetime] = None
    cancelled_at: Optional[datetime] = None
    expired_at: Optional[datetime] = None


class RouteWatch(SQLModel, table=True):
    """Подписка «карауль поездку»: пользователь ждёт попутку по маршруту.
    При публикации подходящей поездки (rides.create_ride) сторож получает push
    + запись в ленту уведомлений. Анти-спам: не чаще 1 пуша на подписку в день
    (last_notified_at). Авто-протухание 14 дней (expires_at) — старые не матчатся."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    # Необязательная дата интереса: если задана — матчим только поездки в этот календарный день.
    watch_date: Optional[datetime] = None
    # Направление: "forward" (только from→to) / "both" (ещё и обратно to→from).
    direction: str = "forward"
    # Анти-спам: время последнего отправленного пуша по этой подписке (не чаще 1/сутки).
    last_notified_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utcnow)
    expires_at: datetime = Field(index=True)   # created_at + 14 дней; протухшие не матчим и прячем


class AdEvent(SQLModel, table=True):
    """Событие по рекламе: показ или клик (для реальной статистики кабинета)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    ad_id: int = Field(index=True, foreign_key="ad.id")
    event_type: str = Field(index=True)      # impression / click
    created_at: datetime = Field(default_factory=utcnow)


class NotificationType(str, Enum):
    booking = "booking"      # событие по броне: создана / подтверждена / отменена
    ride = "ride"            # событие по поездке: водитель выехал/подъезжает, завершена, отклик/приняли
    system = "system"        # системное: модерация, реклама и пр.
    message = "message"      # новое сообщение в чате брони


class Notification(SQLModel, table=True):
    """Уведомление Центра уведомлений. Двуязычно (RU+BA) — показываем по языку приложения.

    Пишется в тех же местах, где шлётся push (services.push_notification) → лента и пуш синхронны.
    ref_kind/ref_id — deep-link: тап открывает связанный экран (бронь/заявку). read_at — прочитано.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    type: str = Field(default=NotificationType.system.value, index=True, max_length=16)
    title_ru: str = Field(default="", max_length=140)
    title_ba: str = Field(default="", max_length=140)
    body_ru: str = Field(default="", max_length=500)
    body_ba: str = Field(default="", max_length=500)
    ref_kind: str = Field(default="", max_length=16)   # booking / request / "" — как трактовать ref_id
    ref_id: Optional[int] = Field(default=None)
    read_at: Optional[datetime] = Field(default=None)
    created_at: datetime = Field(default_factory=utcnow, index=True)


class Payment(SQLModel, table=True):
    """Платёж платформы. Два вида:
    1) СВОЯ услуга самозанятого (boost/ad/donate) — НЕ посредничество за проезд.
    2) Оплата поездки после done (purpose=ride|booking, Фаза 3) — пассажир платит за
       завершённый заказ/бронь; успех → начисление водителю через ledger (см. ledger.py).
    provider_id — id платежа в ЮKassa (или mock-id в dev)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")   # плательщик (для ride — пассажир)
    purpose: str = "boost"                       # boost | ad | donate | ride | booking
    provider_id: str = Field(default="", index=True)  # id платежа в ЮKassa
    ride_id: Optional[int] = Field(default=None, foreign_key="ride.id")  # для boost
    ad_id: Optional[int] = Field(default=None, foreign_key="ad.id")       # для оплаты рекламы (purpose=ad)
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")  # для purpose=ride (быстрый заказ)
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")     # для purpose=booking (бронь плановой поездки)
    tier: str = ""                               # quick / day / urgent (для boost)
    method: str = ""                             # cash | card | sbp | yookassa (способ оплаты поездки)
    amount_kop: int = 0                          # сумма в копейках
    status: str = "pending"                      # pending | succeeded | canceled
    created_at: datetime = Field(default_factory=utcnow, index=True)  # index — для сверки за период


class LedgerKind(str, Enum):
    """Тип записи в ledger (кошельке водителя). Append-only, историю НЕ редактируем."""
    earn = "earn"        # начисление водителю за поездку (полная сумма, +)
    fee = "fee"          # комиссия сервиса (−, вычитается из начисления)
    payout = "payout"    # выплата водителю (−, деньги ушли с баланса; v1 вручную по реестру)
    adj = "adj"          # ручная корректировка (+/−) — только админ, с note


class LedgerEntry(SQLModel, table=True):
    """Кошелёк-ledger водителя (Фаза 3, D3). ТОЛЬКО append: баланс = SUM(amount_kop).
    Историю денег НЕ редактируем и НЕ удаляем — корректировка отдельной записью kind=adj.
    amount_kop: earn > 0; fee/payout < 0; adj любой знак. Деньги — целые копейки (int)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")  # быстрый заказ
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")     # бронь плановой поездки
    kind: LedgerKind = Field(index=True)
    amount_kop: int = 0
    created_at: datetime = Field(default_factory=utcnow, index=True)  # index — для сверки за период
    note: str = ""


class DebtStatus(str, Enum):
    """Статус долга водителя по комиссии за такси-заказы (Модель А «на доверии»)."""
    unpaid = "unpaid"      # начислен, водитель ещё не переводил
    pending = "pending"    # водитель нажал «Я оплатил» — ждём подтверждения админом
    paid = "paid"          # админ подтвердил получение перевода


class CommissionDebt(SQLModel, table=True):
    """Долг водителя по комиссии сервиса за ЗАВЕРШЁННЫЙ такси-заказ (Модель А «на доверии»).

    За такси (instant) водитель получает деньги напрямую (нал / прямой СБП), а комиссию 8%
    ДОЛЖЕН платформе. Раз в неделю водитель сам переводит долг Александру по СБП и жмёт
    «Я оплатил» (unpaid → pending), Александр (админ) подтверждает (pending → paid).
    Просроченный неоплаченный долг (или сумма > порога) → режим ТАКСИ блокируется.
    ПОПУТКА (плановые Ride/Booking) этим НЕ блокируется — это отдельный поток.

    Одна строка = комиссия одного заказа (append-only, историю не редактируем).
    Деньги — только целые копейки (int amount_kop). Группируется по ISO-неделе (week)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    amount_kop: int = 0                                    # комиссия по этому заказу, копейки
    week: str = Field(default="", index=True)             # ISO-неделя начисления, напр. "2026-W28"
    status: DebtStatus = Field(default=DebtStatus.unpaid, index=True)
    created_at: datetime = Field(default_factory=utcnow, index=True)
    due_at: Optional[datetime] = None                     # срок оплаты (created_at + debt_due_days)
    paid_declared_at: Optional[datetime] = None           # когда водитель нажал «Я оплатил»
    confirmed_at: Optional[datetime] = None               # когда админ подтвердил


class TaxiCity(SQLModel, table=True):
    """Город, где включён режим такси (волна 2, гейт такси).

    Логика доступности (см. app/taxi.py): taxi_enabled=False → такси выключено везде;
    True и таблица пуста → включено везде; True и есть записи → только города с enabled=True.
    Город пользователя определяется ближайшим из CITY_COORDS (радиус taxi_city_radius_km)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    city: str = Field(index=True)
    enabled: bool = True
    created_at: datetime = Field(default_factory=utcnow)


class TaxiApplicationStatus(str, Enum):
    """Статус заявки таксиста: подал → админ одобрил/отклонил. После reject можно подать снова."""
    pending = "pending"
    approved = "approved"
    rejected = "rejected"


class TaxiApplication(SQLModel, table=True):
    """Заявка «Стать таксистом Юлдаша» (580-ФЗ, Путь Б).

    Возить ТАКСИ (instant) может только водитель с approved-заявкой; ПОПУТКА этого не требует.
    Документы: ИНН (самозанятость), № разрешения на такси, фото разрешения/ОСАГО (приватное
    хранилище /secure/docs, как license_url водителя). Требования: возраст 20+, стаж от 2 лет.
    Проверка — вручную админом (Александр). Одна заявка на пользователя (user_id unique);
    повторная подача после reject обновляет эту же строку (status → pending)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    inn: str = ""                                  # ИНН самозанятого (10-12 цифр)
    permit_number: str = ""                        # № разрешения на такси (реестр перевозчиков)
    permit_photo_url: Optional[str] = None         # фото разрешения (защищённый URL)
    osago_url: Optional[str] = None                # фото полиса ОСАГО (защищённый URL)
    birth_date: date_type = date_type(1970, 1, 1)  # для проверки «возраст 20+»
    license_since_year: int = 0                    # год получения прав (стаж от 2 лет)
    status: TaxiApplicationStatus = Field(default=TaxiApplicationStatus.pending, index=True)
    comment: Optional[str] = None                  # комментарий админа при отклонении
    created_at: datetime = Field(default_factory=utcnow)
    reviewed_at: Optional[datetime] = None         # когда админ одобрил/отклонил


class TaxiWorkDay(SQLModel, table=True):
    """Учёт такси-времени водителя за ОДИН местный день (волна 2, §8 «Отдых водителя»).

    seconds_online копится на каждом presence-heartbeat (шаг ≤ workday_step_cap_sec, чтобы
    редкие пинги не накручивали). Считается ТОЛЬКО такси-время — попутка (Ride/Booking)
    presence не шлёт и сюда не попадает. seconds_online ≥ лимита → limit_reached_at и гейт
    такси (presence/offer/accept) до разблокировки (см. app/workday.py). Активный заказ не
    рубим. return_ride_used — «один попутчик домой»: единственная разрешённая публикация
    попутки во время блока. Флаги warned_*/winter_push_sent — дедуп вежливых пушей."""
    __table_args__ = (UniqueConstraint("driver_id", "day", name="uq_taxiworkday_driver_day"),)
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    day: date_type                                    # МЕСТНЫЙ день (UTC + local_tz_offset_hours)
    seconds_online: int = 0                           # такси-время на линии за день, секунд
    limit_reached_at: Optional[datetime] = None       # момент достижения лимита (UTC) → гейт
    return_ride_used: bool = False                    # «один попутчик домой» уже опубликован
    last_heartbeat_at: Optional[datetime] = None      # последний presence (UTC) — от него отдых rest_hours
    warned_60: bool = False                           # пуш «остался час» отправлен (дедуп)
    warned_15: bool = False                           # пуш «осталось 15 минут» отправлен (дедуп)
    winter_push_sent: bool = False                    # зимний ночной совет отправлен (дедуп)


class UploadEvent(SQLModel, table=True):
    """Факт загрузки файла юзером (фото/голос) — для суточной квоты (анти disk-fill / спам).
    Лёгкая строка на каждую загрузку; считаем за последние 24ч."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    created_at: datetime = Field(default_factory=utcnow, index=True)


class AppReview(SQLModel, table=True):
    """Отзыв о приложении (отдельно от Rating за поездку). Идёт на лендинг.
    Модерация: published=False по умолчанию — на сайт попадает только одобренное."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")
    name: str = ""                           # имя для показа (по умолчанию из профиля)
    city: str = ""                           # город (опционально)
    stars: int = 5                           # 1..5
    text: str = ""                           # текст отзыва
    published: bool = Field(default=False, index=True)  # одобрено к показу на лендинге
    created_at: datetime = Field(default_factory=utcnow)


class Ad(SQLModel, table=True):
    """Рекламное объявление партнёра. Управляется админом из кабинета.
    Видно в приложении только active + в периоде. founder = место навсегда (ends_at=null), лимит 10."""
    id: Optional[int] = Field(default=None, primary_key=True)
    partner_name: str = ""                   # рекламодатель (показывается «Реклама · …»)
    partner_contact: str = ""                # tg/телефон (для админа, не публично)
    title: str = ""
    text: str = ""
    button: str = ""                         # текст кнопки
    target: str = ""                         # ссылка/deep-link при клике
    image_url: str = ""                      # картинка (опц.)
    erid: str = ""                           # маркировка рекламы (РФ закон) — обязательна
    plan: str = Field(default="standard")    # founder / standard / premium
    placements: str = ""                     # CSV: route,ridesList,profile,nearby,tripDetails,help
    cities: str = ""                         # CSV городов; пусто = все
    founder_lock: bool = False               # founder: место/цена навсегда
    priority: int = Field(default=0, index=True)  # выше = раньше (premium больше)
    starts_at: datetime = Field(default_factory=utcnow)
    ends_at: Optional[datetime] = None       # null = бессрочно (founder)
    status: str = Field(default="draft", index=True)  # draft/pending_review/active/paused/expired/archived/rejected
    reject_reason: str = ""                   # причина отказа модерации (партнёр видит), если status=rejected
    package: str = Field(default="")          # код тарифа: city/route/main (см. AD_PACKAGES); пусто = не выбран
    budget_kop: int = 0                       # стоимость размещения в копейках (из пакета, фиксируется при сабмите)
    period_days: int = 0                      # срок размещения в днях (из пакета)
    owner_id: Optional[int] = Field(default=None, foreign_key="user.id", index=True)  # партнёр-владелец; null = ничьё (видит только админ)
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")  # кто создал запись (партнёр или админ)
    submitted_at: Optional[datetime] = None   # когда отправлено на модерацию
    reviewed_at: Optional[datetime] = None    # когда админ одобрил/отклонил
    created_at: datetime = Field(default_factory=utcnow)


class ReferralBonus(SQLModel, table=True):
    """Выданный «водительский» реферальный бонус (B8-4, анти-накрутка фейк-поездками).

    Выдаётся пригласившему, когда приглашённый ВОДИТЕЛЬ реально раскатался:
    ≥3 done-поездок с ≥3 РАЗНЫМИ пассажирами, поездки «живые» (есть движение/длительность).
    Один бонус на приглашённого (invited_user_id unique) + ≤5 бонусов на пригласившего
    в календарный месяц (см. routers/referral.py)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    referrer_id: int = Field(index=True, foreign_key="user.id")
    invited_user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    kind: str = "driver"
    created_at: datetime = Field(default_factory=utcnow, index=True)


class DeviceBan(SQLModel, table=True):
    """Бан устройства (анти-фрод B8-1, обход бана новым номером).

    Клиент шлёт стабильный X-Device-Id (ANDROID_ID) со всеми запросами; забаненное
    устройство не может регистрироваться/входить, каким бы новым номером ни пытались.
    Банит ТОЛЬКО админ (человек в контуре); снять — DELETE /admin/bans/device/{device_id}.
    user_id — кому принадлежало устройство при бане (для админа, опционально)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    device_id: str = Field(index=True, unique=True, max_length=64)
    user_id: Optional[int] = Field(default=None, foreign_key="user.id")
    reason: str = ""
    created_at: datetime = Field(default_factory=utcnow)


class WaitlistEntry(SQLModel, table=True):
    """Лист ожидания раннего доступа (волна 2, §11 «Запуск»: трафик волнами).

    Публичная подача (без аккаунта): телефон + город + роль (пассажир/водитель).
    Телефон уникален — повторная подача обновляет city/role, не дублирует.
    invited_at — отметка «позван в волне» (саму рассылку админ делает вручную).
    Приватность: телефоны отдаются ТОЛЬКО админу и не пишутся в логи (152-ФЗ)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    phone: str = Field(index=True, unique=True, max_length=32)
    city: Optional[str] = Field(default=None, index=True)
    role: str = Field(default="passenger", index=True)   # passenger | driver
    created_at: datetime = Field(default_factory=utcnow)
    invited_at: Optional[datetime] = None                # когда позвали (волна); NULL = ещё ждёт


# ---- Фаза 4 (D5): формализованное доверие «между своими» ----

class Trust(SQLModel, table=True):
    """Уровень доверия «свой».

    L0..L3 в основном ВЫЧИСЛЯЮТСЯ из уже имеющихся данных (телефон → +имя/фото → +документы),
    поэтому отдельная строка здесь заводится ТОЛЬКО когда участнику дарован статус «свой» (L3)
    по инвайту от уже проверенного участника. Храним «дарованный» уровень и того, кто пригласил
    (цепочка приглашений). Итоговый уровень = max(вычисленный, дарованный).

    L0 — нормальный, полноправный пользователь: просто ещё без части привилегий. Уровни не унижают,
    а открывают доступ к «кругу своих» по мере доверия."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    level: int = 0                    # дарованный уровень (3 = «свой»); 0 = грантов нет, уровень чисто вычисляемый
    invited_by: Optional[int] = Field(default=None, foreign_key="user.id")  # кто пригласил (для цепочки доверия)
    updated_at: datetime = Field(default_factory=utcnow)


class InviteCode(SQLModel, table=True):
    """Инвайт-код «позови своего в круг доверия». Владелец — проверенный участник (L2+),
    у него ограниченный запас кодов (анти-абьюз: код не бесконечен, запас на пользователя лимитирован).
    Активация поднимает приглашённого до L3 «свой» и записывает цепочку приглашений."""
    id: Optional[int] = Field(default=None, primary_key=True)
    code: str = Field(index=True, unique=True)
    owner_id: int = Field(index=True, foreign_key="user.id")
    uses_left: int = 1                # сколько ещё активаций осталось (не бесконечный код)
    created_at: datetime = Field(default_factory=utcnow)


class Consent(SQLModel, table=True):
    """Реестр согласий (152-ФЗ): доказуемый факт и время согласия пользователя на
    оферту / политику конфиденциальности / обработку геолокации. Одна строка на вид согласия.
    Время первого согласия не перезаписываем — это юридическое доказательство."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    kind: str = Field(index=True)     # offer / privacy / geo
    granted_at: datetime = Field(default_factory=utcnow)
