from datetime import datetime
from enum import Enum
from typing import Optional

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


class DriverProfile(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    online: bool = False
    rating: float = 5.0
    trips_count: int = 0
    car_make: str = ""
    car_model: str = ""
    car_color: str = ""
    car_plate: str = ""
    seats: int = 4
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
    comment: str = ""
    for_relative_name: Optional[str] = None
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосовой заявки
    status: str = "active"
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
    created_at: datetime = Field(default_factory=utcnow)


# ---- Фаза 2: чат, семья, безопасность ----

class Message(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True, foreign_key="booking.id")
    sender_id: int = Field(foreign_key="user.id")
    text: str = ""
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосового
    deleted: bool = False                   # «удалено у всех» (текст очищен, видна пометка)
    edited: bool = False                    # отредактировано (пометка «изменено»)
    hidden_user_ids: str = ""               # CSV id юзеров, кто скрыл «у себя» (тред хранится на сервере)
    created_at: datetime = Field(default_factory=utcnow)


class TrustedContact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    name: str
    relation: str = ""
    phone: str = ""
    notify_by_default: bool = True


class TripShare(SQLModel, table=True):
    """Поездка, расшаренная близкому (семейный контроль)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True, foreign_key="booking.id")
    contact_id: int = Field(foreign_key="trustedcontact.id")
    last_status: str = "shared"             # shared / sat / arrived / done
    created_at: datetime = Field(default_factory=utcnow)


class SosEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")
    category: str = "other"                 # medical / breakdown / other
    note: str = ""
    status: str = "open"                    # open / handled
    created_at: datetime = Field(default_factory=utcnow)


class Report(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    reporter_id: int = Field(index=True, foreign_key="user.id")
    target_user_id: int = Field(foreign_key="user.id")
    reason: str = ""
    created_at: datetime = Field(default_factory=utcnow)


class Block(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    blocked_user_id: int = Field(foreign_key="user.id")
    created_at: datetime = Field(default_factory=utcnow)


class Rating(SQLModel, table=True):
    """Оценка после поездки: rater оценил ratee (1..5 звёзд). Одна на (booking, rater)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True, foreign_key="booking.id")
    rater_id: int = Field(index=True, foreign_key="user.id")        # кто оценил
    ratee_id: int = Field(index=True, foreign_key="user.id")        # кого оценили (водитель или пассажир)
    stars: int = 5                           # 1..5
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


class AdEvent(SQLModel, table=True):
    """Событие по рекламе: показ или клик (для реальной статистики кабинета)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    ad_id: int = Field(index=True, foreign_key="ad.id")
    event_type: str = Field(index=True)      # impression / click
    created_at: datetime = Field(default_factory=utcnow)


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
