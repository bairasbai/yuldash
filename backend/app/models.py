from datetime import date as date_type, datetime
from enum import Enum
from typing import Optional

from sqlalchemy import BigInteger, Index, UniqueConstraint
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
    # Время выезда прошло, а поездку так и не забронировали. Раньше такого состояния не было,
    # и поездка оставалась active НАВСЕГДА: из ленты её прятал фильтр по времени, а у водителя
    # в «моих поездках» она висела активной вечно (найдено на проде 2026-08-03 — три штуки
    # от 5, 6 и 15 июля). Ни туда ни сюда: пассажир не видит, водитель не может закрыть.
    expired = "expired"


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
    # Родной город (name_ru из справочника Settlement). Пусто по умолчанию — заполняется в профиле.
    # Нужен, чтобы витрина купонов/посылок сразу показывала «в моём городе» без ручного выбора каждый раз.
    city: str = Field(default="", index=True)
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
    # Пол — СТРОГО по желанию: "" (не указан, по умолчанию) / female / male.
    #
    # Живёт на User, а не на профиле водителя, потому что это свойство ЧЕЛОВЕКА, а не роли:
    # проверка «только женщины» на попутке касается и пассажира, у которого профиля водителя
    # нет вовсе. Раньше поле было только у водителя (`DriverProfile.gender`) — из-за этого
    # отметку «только женщины» проверить было нечем, и она оставалась пустым обещанием
    # (аудит 2026-08-08).
    #
    # Наружу НИКОГДА не отдаём сам пол. Публично раскрывается только полезный сигнал
    # «женщина за рулём» (`driver_is_woman`), где male и "" неразличимы.
    gender: str = Field(default="", max_length=8)
    # Реферал «позови своего»: свой код, кто пригласил, бонусы (1 бонус = 1 бесплатное поднятие).
    referral_code: str = Field(default="", index=True)
    referred_by: Optional[int] = Field(default=None, foreign_key="user.id")
    referral_credits: int = 0
    # Сколько реферальных бонусов человек получил ЗА ЖИЗНЬ как пригласивший (аудит 2026-08-12,
    # волна 25). `referral_credits` — это ОСТАТОК: потратил бонусы, и потолок 20 освобождается
    # заново. Проба показала: 50 приглашённых аккаунтов = 40 бонусов, цикл бесконечен, хотя
    # комментарий рядом обещал обратное. Пожизненный счётчик переживает и трату бонусов,
    # и удаление приглашённых аккаунтов (счётчик живёт у пригласившего, а не считает их).
    referral_bonus_lifetime: int = 0
    # Анти-фрод (B8): последнее устройство входа (X-Device-Id, ANDROID_ID клиента).
    # По нему: бан устройства ловит обход бана новым номером; вход с нового устройства → сигнал.
    # Приватность: наружу не отдаём, в логи не пишем.
    last_device_id: Optional[str] = Field(default=None, index=True)
    # Когда человек последний раз пользовался приложением (обновляется при выдаче/обновлении
    # ключей входа, то есть минимум раз в 12 часов у активного).
    #
    # Зачем понадобилось (аудит 2026-08-08, волна 139). Вход у нас по номеру телефона, а номер
    # человеку не принадлежит: операторы забирают неиспользуемый номер и через полгода-год
    # продают другому. Новый владелец ставит Юлдаш, входит по SMS — и получает ЧУЖОЙ аккаунт
    # целиком: имя, историю поездок, переписку, доверенные контакты. Без отметки активности
    # отличить «человек сменил телефон» от «номер перешёл к другому» невозможно.
    last_seen_at: Optional[datetime] = Field(default=None, index=True)
    # Номер отвязан от этого аккаунта: он перешёл к другому человеку. Данные не удаляем —
    # прежний владелец может вернуть доступ через поддержку, если это был он.
    phone_released_at: Optional[datetime] = None
    # F19 «Позови водителя»: приглашённый стал водителем и опубликовал первый рейс →
    # пригласивший получил бонус (бесплатный Boost). Флаг гарантирует начисление РОВНО раз.
    driver_referral_rewarded: bool = False
    # Тестовый аккаунт модерации сторов (B9b-4): вход review_phone+review_code из env,
    # реальная SMS не шлётся. Обычный пассажир БЕЗ прав (не админ, не водитель).
    is_reviewer: bool = False
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
    # С какого телефона эта запись. Нужна, чтобы отличить законную смену человека на общем
    # телефоне от попытки забрать чужой приём уведомлений (аудит 2026-08-08, волна 147):
    # раньше перепривязать запись мог кто угодно, знающий строку токена, и жертва переставала
    # получать всё — сообщения, «водитель подъехал», напоминание по сигналу SOS.
    device_id: str = Field(default="", index=True)
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
    kind: str = "city"               # city | district_center | neighbor | village
    district: Optional[str] = None   # район (для village: различать тёзок «Берёзовка, Иглинский р-н»)
    lat: float = 0.0
    lng: float = 0.0
    active: bool = True


class DriverProfile(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    online: bool = False
    # Зона работы таксиста (волна 2, география). NULL = зона не выбрана → прежнее
    # поведение matcher'а (получает всё рядом). Значения: city | intercity | region.
    # Зона работы (география). База: city — один НП (work_city), district — весь
    # муниципальный район (work_district). Плюс два согласия: work_intercity — беру заказы
    # с выездом за базу («загород»), work_regions — готов и в соседние регионы.
    # Старые значения work_zone (intercity/region) продолжают работать: миграция переводит их
    # в базу + тумблеры, см. alembic/versions/zone_district.py.
    work_zone: Optional[str] = None       # city | district (legacy: intercity | region)
    work_city: Optional[str] = None       # для work_zone=city: «мой город/село» (name_ru)
    work_district: Optional[str] = None   # для work_zone=district: «Абзелиловский р-н»
    work_intercity: bool = False          # выезд загород (заказы, где вторая точка вне базы)
    work_regions: bool = False            # готов в соседние регионы (Челябинская, Оренбургская…)
    work_direction_id: Optional[int] = Field(default=None, foreign_key="settlement.id")  # закреплённое направление загорода
    # Класс машины для такси: economy | comfort | business | minivan. NULL = economy.
    # ⚠️ Начиная с 2026-08-08 класс НЕ заявляется водителем, а СЧИТАЕТСЯ по характеристикам
    # машины (app/car_class.py) — иначе любой ставил себе «Бизнес». Поле осталось как
    # «основной класс» (высший из доступных) для витрины и обратной совместимости;
    # подбор смотрит на car_classes_available ∩ car_classes_enabled.
    car_class: Optional[str] = None
    # Что насчитал классификатор (CSV) и что из этого водитель включил сам (CSV).
    # Пусто в enabled = берёт все доступные: человек прошёл модерацию и вышел на линию,
    # он ждёт заказы, а не пустой экран из-за незаполненной галочки.
    car_classes_available: str = ""
    car_classes_enabled: str = ""
    # Опции салона (CSV, app/car_class.OPTIONS): детские кресла по группам, бустер, помощь
    # с коляской, собака-проводник, детская коляска, животные, большой багаж. Не класс —
    # галочка поверх любого класса: машина не может стоять в двух классах, а кресло возить
    # может любая.
    car_options: str = ""
    # ⚠️ УСТАРЕЛО (2026-08-08): САМ ПОЛ переехал на `User.gender` — он нужен и пассажиру,
    # у которого этого профиля нет вовсе (проверка «только женщины» на попутке). Колонку не
    # удаляем (данные), но НЕ читаем и НЕ пишем: два источника правды разъезжаются.
    # Значения перенесены миграцией `ag_user_gender`.
    gender: str = ""
    # Пол ПОДТВЕРДИЛ МОДЕРАТОР по фото прав, а не сам водитель. Публичный бейдж «женщина за
    # рулём» и фильтр показывают только подтверждённых: иначе любой мужчина ставит себе галочку
    # и попадает в выдачу «женщина за рулём» — ровно та жалоба, что копится у Uber («заказала
    # женщину-водителя, приехал муж»). Фича существует ради безопасности женщин, и держать её
    # на честном слове нельзя. Меняет пол → подтверждение слетает, нужен новый просмотр.
    #
    # ⚠️ Остаётся ЗДЕСЬ, хотя сам пол уехал на User (слияние веток 2026-08-12). Это два разных
    # факта: `User.gender` — приватное свойство человека (нужно и пассажирке для фильтра
    # «только женщины»), а `gender_verified` — публичное право показывать бейдж водителя,
    # и подтверждается оно по ВОДИТЕЛЬСКИМ документам. Ветка аудита безопасности переносила
    # только первое и это поле потеряла; при слиянии защита восстановлена.
    gender_verified: bool = False
    rating: float = 5.0
    trips_count: int = 0
    car_make: str = ""
    car_model: str = ""
    car_color: str = ""
    car_plate: str = ""
    seats: int = 4
    # Характеристики для расчёта класса. Год — из СТС, без него Комфорт и выше недоступны.
    # Потолок мест — 8 пассажиров (категория M1): больше — это автобус, там категория D
    # у водителя и лицензия на перевозки у службы заказа.
    car_year: Optional[int] = None
    car_ac: bool = False              # рабочий кондиционер
    car_sedan: bool = False           # кузов седан (нужно Бизнесу)
    car_leather: bool = False         # кожа или комбинированный салон (нужно Бизнесу)
    # Ставит МОДЕРАТОР при очном допуске (видеозвонок + осмотр машины), не водитель.
    # Без него в Бизнес не пускаем: там и цена выше, и ожидания пассажира другие.
    car_premium_verified: bool = False
    # По умолчанию True — человеку верим; модератор снимает по фото, если салон в чехлах
    # или кузов битый. Снятая галочка роняет класс до Эконома, а не блокирует работу.
    car_clean: bool = True            # салон целый, без чехлов и накидок, без запаха
    car_body_ok: bool = True          # кузов без крупных вмятин, ржавчины, «разных» деталей
    # «Сказать рәхмәт» (чаевые «на доверии»): реквизит СБП водителя для добровольных чаевых.
    # OPT-IN: пусто = водитель НЕ принимает денежные чаевые. Показывается пассажиру только
    # после завершённой поездки И при settings.tips_money_enabled (по умолчанию выключено).
    tips_sbp: str = ""
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
    # --- Реквизиты для выплат (Модель Б, Фаза 3 v2). Полный номер карты НЕ храним. ---
    payout_card_last4: str = ""       # последние 4 цифры карты (для показа «карта ····1234»)
    payout_token: str = ""            # токен привязанной карты у провайдера (НЕ PAN) — по нему шлём выплату
    payout_card_at: Optional[datetime] = None  # когда реквизиты добавлены/обновлены


class MedicalPartner(SQLModel, table=True):
    """Партнёр-медцентр (F22) — клиника в справочнике как точка назначения поездки «в больницу».

    ДЕЛИКАТНО: это B2B-логистика («доехать до клиники»), НЕ медуслуга. Здесь — только
    публичные данные организации (название, город, адрес, координаты, описание маршрута).
    Никаких мед.данных, диагнозов, приёмов, обещаний лечения. Пациент нигде не привязывается —
    клиника это лишь пункт на карте, к которому пассажиры из районов могут подъехать вместе.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str = Field(index=True)            # название клиники (напр. «РКБ им. Куватова»)
    city: str = Field(index=True)            # город, где находится клиника
    address: str = ""                        # адрес (публичный, как на вывеске)
    lat: Optional[float] = None              # координаты для пина на карте
    lng: Optional[float] = None
    description: str = ""                     # описание: как доехать / что рядом (без обещаний медуслуг)
    active: bool = Field(default=True, index=True)   # показывать ли в справочнике
    created_at: datetime = Field(default_factory=utcnow)   # согласовано с миграцией f22 (server_default now())
class DriverSchedule(SQLModel, table=True):
    """Постоянный (регулярный) маршрут водителя: «езжу Баймаҡ→Уфа по пятницам в 8:00».

    Показывается в профиле водителя и в поиске (публично). Пассажиры могут «следить»
    за направлением — связка с route-watch (F13); саму подписку эта таблица НЕ хранит.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    # Дни недели через запятую, ISO 1=Пн … 7=Вс. Напр. "5" (пятница) или "1,3,5".
    weekdays: str = ""
    time: str = ""            # время выезда "HH:MM" (местное), напр. "08:00"; "" — без точного времени
    comment: str = Field(default="", max_length=200)
    active: bool = Field(default=True, index=True)
    created_at: datetime = Field(default_factory=utcnow)


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
    # Клиника-назначение (F22): поездка «в больницу»/«на приём» (category=hospital) может быть
    # привязана к партнёру-медцентру из справочника. Это ТОЛЬКО логистика — точка назначения,
    # как обычный пункт маршрута. НИКАКИХ мед.данных пациента (диагнозы/услуги/приёмы) не храним.
    partner_id: Optional[int] = Field(default=None, foreign_key="medicalpartner.id", index=True)
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
    quiet: bool = False               # тихая поездка: без лишних разговоров/громкой музыки
    # «Без подростков без сопровождения» — выбор водителя, не запрет сервиса. Многие честно
    # не хотят брать на себя ответственность за чужого ребёнка, и это нормально: лучше сказать
    # заранее, чем отказывать на месте, когда подросток уже стоит у дороги.
    no_minors: bool = False
    waypoints: str = ""               # остановки по пути (названия НП через " | "), A→точки→B
    status: RideStatus = Field(default=RideStatus.active, index=True)   # /rides и /rides/near фильтруют active
    # Boost (платное поднятие): пока boosted_until > now — поездка выше в выдаче.
    boosted_until: Optional[datetime] = Field(default=None, index=True)
    boost_tier: str = ""              # quick / day / urgent (последний оплаченный тариф)
    created_at: datetime = Field(default_factory=utcnow)
    # Композитный индекс под горячую выдачу /rides (фильтр status='active' + сортировка по depart_at):
    # точечный план вместо bitmap-AND двух отдельных индексов на большом объёме active-поездок.
    __table_args__ = (Index("ix_ride_status_depart", "status", "depart_at"),)


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
    status: str = Field(default="active", index=True)   # горячий фильтр: /requests, авто-подбор (WHERE status='active') каждую минуту
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


class SavedPlaceKind(str, Enum):
    home = "home"
    work = "work"
    custom = "custom"


class SavedPlace(SQLModel, table=True):
    """Сохранённое место пользователя (дом/работа/произвольное) для быстрого выбора
    в форме заказа. ПРИВАТНО — только владелец (адрес/координаты не показываем чужим).
    home/work — по одному на человека (upsert по kind), custom — до лимита (см. router).
    Координаты нужны, чтобы сразу подставить пин на карту без повторного геокодинга."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    label: str = Field(default="", max_length=120)      # человекочит. имя («Дом», «Офис», «Мама»)
    kind: str = Field(default=SavedPlaceKind.custom.value, max_length=16, index=True)  # home/work/custom
    address: str = Field(default="", max_length=500)
    lat: Optional[float] = None
    lng: Optional[float] = None
    created_at: datetime = Field(default_factory=utcnow)
    # Когда этим адресом воспользовались в последний раз. По нему сортируется быстрый список
    # в форме заказа: наверху то, куда ездят, а не то, что завели последним. У нового места
    # равно моменту создания — человек завёл адрес, значит, скорее всего, сейчас туда и поедет.
    used_at: datetime = Field(default_factory=utcnow, index=True)
    # Когда этим адресом воспользовались в последний раз. По нему сортируется быстрый список
    # в форме заказа: наверху то, куда ездят, а не то, что завели последним. У нового места
    # равно моменту создания — человек завёл адрес, значит, скорее всего, сейчас туда и поедет.
    used_at: datetime = Field(default_factory=utcnow, index=True)


class RecentPlace(SQLModel, table=True):
    """Недавняя точка (куда/откуда заказывали) — быстрый повтор адреса. ПРИВАТНО, только
    владелец. Дедуп по (user_id, address): повторный заказ обновляет used_at, а не плодит
    дубли. Держим последние ~10 на человека (старые вычищаются при добавлении)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    address: str = Field(default="", max_length=500)
    lat: Optional[float] = None
    lng: Optional[float] = None
    used_at: datetime = Field(default_factory=utcnow, index=True)  # свежесть → сортировка/чистка


class Booking(SQLModel, table=True):
    # Композитный индекс под скан rate-reminder'а (status=done AND rate_reminded=false);
    # на проде добавляется миграцией m_audit_hardening, здесь — паритет для свежих БД (create_all).
    __table_args__ = (Index("ix_booking_status_rate_reminded", "status", "rate_reminded"),)
    id: Optional[int] = Field(default=None, primary_key=True)
    ride_id: int = Field(index=True, foreign_key="ride.id")
    passenger_id: int = Field(index=True, foreign_key="user.id")
    seats: int = 1
    price: int = 0
    status: BookingStatus = BookingStatus.pending
    driver_phase: str = ""           # подфаза активной поездки от водителя: "" / departed / arriving (для live-баннера пассажиру)
    # Едет несовершеннолетний (2026-08-07). До этого возраст не спрашивался нигде: подросток
    # регистрировался и садился к незнакомому человеку, а водитель об этом не знал и отвечал бы
    # за него в случае чего. При этом сайт прямо обещает «школьник доберётся» — запрещать нельзя,
    # в районе это реальная нужда. Поэтому не запрет, а согласие взрослого + честная видимость:
    # взрослый назван поимённо с телефоном (это и есть запись согласия), водитель видит пометку
    # ДО подтверждения брони и вправе отказаться.
    minor_passenger: bool = False
    minor_guardian_name: str = ""     # кто из взрослых отвечает за поездку подростка
    minor_guardian_phone: str = ""    # ПДн: наружу отдаём только водителю этой брони
    # Сервер сам сверил «подъезжаю» с живым GPS водителя и убедился, что он рядом с точкой подачи.
    # Пассажир видит это как «подтверждено по GPS» — слово водителя перестаёт быть единственным
    # доказательством (разбор конкурентов 2026-08-07: у inDrive «Я приехал» жмут за километры).
    arrival_verified: bool = False
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
    thanked: bool = False                     # «Сказать рәхмәт»: пассажир поблагодарил за поездку (дедуп)
    rate_reminded: bool = False               # фоновая задача уже слала «оцените поездку» по этой броне (дедуп, без спама)
    # Когда ВОДИТЕЛЬ принял эту бронь. Это единственное доказательство, что стороны реально
    # имеют дело друг с другом: нажать «Забронировать» может кто угодно, не спрашивая водителя,
    # а подтверждение — встречный шаг. По статусу это не восстановить: отменённая после
    # подтверждения и отменённая из ожидания выглядят одинаково (аудит 2026-08-08, волна 158).
    confirmed_at: Optional[datetime] = None
    cancelled_at: Optional[datetime] = None   # когда бронь отменили (для счётчиков за день)
    # Причина отмены (код: changed_mind/found_other/plans_changed/driver_no_response/car_problem/no_show/other)
    # и флаг неявки — сигнал доверия «между своими» и аргумент в споре. Пусто = причину не указали.
    cancel_reason: Optional[str] = None
    no_show: bool = False                     # пассажир не явился (ставит водитель) — отдельно от обычной отмены
    cancelled_by: Optional[int] = None        # кто выполнил отмену (для «Надёжности»: поздняя отмена бьёт по инициатору)
    # Забытые вещи: «забыл вещь в машине» → чат брони снова открыт на запись до этого времени.
    # У такси такой выход был, у попутки нет — а чат попутки после поездки мы закрыли
    # (аудит 2026-08-06). Телефон, забытый на заднем сиденье, терялся бы навсегда.
    lost_item_until: Optional[datetime] = None
    # Сколько раз по этой поездке уже нажимали «забыл вещь». Кнопка открывает чат заново,
    # и без счётчика её можно жать бесконечно: раз в двое суток — и переписка с человеком
    # держится открытой сколько угодно, с уведомлением на каждое нажатие (волна 151).
    lost_item_calls: int = 0
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
    # Ровно ОДНА привязка из трёх: booking_id (чат брони попутки), order_id (чат такси-заказа,
    # B7b-1) ИЛИ parcel_id (чат отправитель ↔ курьер по посылке). Старые строки — все с
    # booking_id, колонка стала nullable без потери данных.
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    parcel_id: Optional[int] = Field(default=None, index=True, foreign_key="parceldelivery.id")
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
    """Публичная трекинг-ссылка на «движение» (капабилити-токен → /t/{token}).
    Привязка — РОВНО ОДНА из трёх:
      • booking_id — бронь попутки (семейный контроль, близкому);
      • order_id   — такси-заказ (B7b-2, близкому);
      • parcel_id  — доставка/посылка (G1): ссылку получает ПОЛУЧАТЕЛЬ, следит за
        курьером в браузере без приложения (самая любимая фича отправителей).
    Поэтому contact_id опционален: у брони/заказа он есть (доверенный контакт),
    у посылки — нет (ссылку отправитель отдаёт получателю сам / SMS на его номер)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    parcel_id: Optional[int] = Field(default=None, index=True, foreign_key="parceldelivery.id")
    contact_id: Optional[int] = Field(default=None, foreign_key="trustedcontact.id")
    # Live-ссылка близкому (B7c): capability-токен публичной страницы /t/{token}.
    # ≥16 случайных байт (secrets.token_urlsafe). NULL у строк до миграции w2_livelink —
    # догенерируется при следующем share. Отзыв share (DELETE) удаляет строку → токен «сгорает».
    token: Optional[str] = Field(default=None, index=True, unique=True)
    last_status: str = "shared"             # shared / sat / arrived / done
    created_at: datetime = Field(default_factory=utcnow)
    # Срок жизни live-ссылки (приватность): если поездка «зависла» в живом статусе (водитель не
    # нажал «Завершить»), ссылка иначе показывала бы гео бессрочно. Ставим щедрый TTL (24ч — дольше
    # любой реальной поездки). NULL у строк до миграции = бессрочно, скрытие — по статусу поездки.
    expires_at: Optional[datetime] = Field(default=None)


class SosEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")   # SOS из такси-заказа (B7b-2)
    category: str = "other"                 # medical / breakdown / accident / threat / other
    note: str = ""
    status: str = Field(default="open", index=True)     # open / handled
    created_at: datetime = Field(default_factory=utcnow, index=True)
    # Кто и когда принял сигнал (аудит 2026-07-26). Раньше SOS уходил ОДНИМ сообщением в Telegram
    # и всё: списка сигналов у админа не было, статус не менялся никогда — уснул, и следа нет.
    handled_at: Optional[datetime] = None
    handled_by: Optional[int] = Field(default=None, foreign_key="user.id")
    handled_note: str = ""                  # что сделали (для истории/полиции)
    escalated_at: Optional[datetime] = None # когда ушёл повторный сигнал (не принят вовремя)


class Report(SQLModel, table=True):
    """Жалоба (волна 2, §9 «Качество»). Анонимность — продукт: цель жалобы НИКОГДА не видит
    автора (reporter_id отдаётся только админу). category — закрытый перечень (см.
    app/quality.py); привязка к поездке (order_id/booking_id) доказывает, что стороны реально
    ехали вместе. status: new → reviewing → resolved | rejected (разбор у админа, человек в контуре)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    reporter_id: int = Field(index=True, foreign_key="user.id")
    # NULL = обвиняемый удалил аккаунт: строку НЕ стираем, а обезличиваем (см. account.py, шаг 3.5).
    # Иначе нарушитель одним тапом уничтожал доказательства против себя — три жалобы за поведение,
    # «удалить аккаунт», и разбирать нечего (аудит 2026-07-26).
    target_user_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")
    reason: str = ""                                       # свободный текст — детали (опционально)
    category: str = Field(default="other", index=True)     # перечень в quality.REPORT_CATEGORIES
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")   # быстрый заказ
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")      # бронь попутки
    parcel_id: Optional[int] = Field(default=None, foreign_key="parceldelivery.id")  # C2: спор по доставке
    status: str = Field(default="new", index=True)         # new | reviewing | resolved | rejected
    resolution: Optional[str] = None                       # решение админа (текст разбора)
    resolved_at: Optional[datetime] = None                 # когда разобрано (resolve/reject)
    created_at: datetime = Field(default_factory=utcnow)


class Incident(SQLModel, table=True):
    """Система «Справедливость»: спор по поездке с ДВУСТОРОННИМ разбором (в отличие от анонимной
    Report). Обе стороны слышимы (due process): заявитель описывает, обвинённый объясняется, админ
    решает соразмерно по лестнице и объясняет обеим. Дополняет Report, не заменяет. См. docs/trust-safety.md."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: Optional[int] = Field(default=None, index=True, foreign_key="booking.id")
    # Спор по доставке (аудит 2026-07-26): «груз разбит/потерян» — та же машина разбора, что
    # и по поездке. Без этого поля типы parcel_damage/parcel_lost были заведены, но недостижимы:
    # код требовал booking_id и отвечал 400. Ровно один из двух контекстов заполнен.
    parcel_id: Optional[int] = Field(default=None, index=True, foreign_key="parceldelivery.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    # NULL у любой из сторон = эта сторона удалила аккаунт: строку НЕ стираем, а обезличиваем
    # (см. account.py, шаг 3.7-bis). Иначе обвинённый одним тапом уничтожал заявление жертвы
    # вместе с её фото-уликами и решением админа (аудит 2026-08-03). Тот же приём, что у Report.
    reporter_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")     # кто заявил
    respondent_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")   # на кого (обвиняемый)
    type: str = Field(index=True)            # код (passenger_no_show, harassment, parcel_damage, …)
    reporter_role: str = ""                  # passenger/driver/courier/sender/recipient
    description: str = ""                    # версия заявителя (≤2000)
    status: str = Field(default="open", index=True)  # open/awaiting_response/under_review/resolved/appealed/closed
    suspected_bump: bool = False             # авто-детект «бампинга» (детект отложен — см. decisions 2026-07-25)
    # Фото-доказательства (порт из pr88): CSV своих URL (/secure/evidence/...), ≤10 шт.
    # Спор «слово против слова» без фото нерешаем; файлы приватны (лица/номера/травмы).
    evidence_urls: str = ""                  # доказательства заявителя
    respondent_evidence_urls: str = ""       # доказательства обвинённого (право на защиту)
    respondent_statement: str = ""           # объяснение обвинённого (≤2000)
    responded_at: Optional[datetime] = None
    resolution: str = ""                     # none/dismissed/warning/strike/suspend/ban/mutual_resolved
    fault: str = ""                          # none/reporter/respondent/both/unclear
    resolution_note: str = ""                # объяснение админа обеим сторонам (≤2000)
    compensation_kop: int = 0                # предложенная компенсация (не списываем автоматически)
    appeal_text: str = ""                    # текст апелляции (≤2000)
    appeal_status: str = ""                  # ""/requested/upheld/overturned
    # Что РЕАЛЬНО применено к профилю обвинённого этим спором (для честного отката при пере-
    # решении после апелляции: «оставить в силе» не должно наказывать второй раз, «отменить» —
    # снимает именно то, что наложил этот спор, не трогая чужие).
    applied_warning: bool = False
    applied_strike: bool = False
    applied_suspended_until: Optional[datetime] = None
    resolved_by: Optional[int] = Field(default=None, foreign_key="user.id")
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)
    resolved_at: Optional[datetime] = None


class SafetyProfile(SQLModel, table=True):
    """Состояние «справедливости» пользователя (1:1 с User, ленивое создание). Лестница §2:
    страйки/замечания → standing, пауза с сроком, затухание страйков за окно (без «клейма навсегда»)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    strikes: int = 0
    warnings: int = 0
    standing: str = "good"                   # good/warned/limited/suspended
    suspended_until: Optional[datetime] = None
    suspend_reason: str = ""
    last_strike_at: Optional[datetime] = None
    rating_shield: bool = False              # админ защитил оболганного (щит рейтинга)
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)


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
    parcel_id: Optional[int] = Field(default=None, index=True, foreign_key="parceldelivery.id")  # C3: оценка доставки курьера
    rater_id: int = Field(index=True, foreign_key="user.id")        # кто оценил
    ratee_id: int = Field(index=True, foreign_key="user.id")        # кого оценили (водитель или пассажир)
    stars: int = 5                           # 1..5
    text: str = ""                           # текстовый отзыв (опц., ≤500) — идёт на модерацию
    text_published: bool = Field(default=False, index=True)  # текст одобрен к показу в публичном профиле
    # Быстрые метки, CSV: polite,ontime,clean,safe,comfortable,helpful,late,rude,unsafe,dirty,detour.
    # Список закрытый (safety_logic.RATING_TAGS), чистятся через clean_tags — произвольная строка
    # от клиента сюда не попадает. Модерации НЕ требуют: выбор из закрытого списка оскорбить нельзя,
    # в отличие от свободного текста.
    tags: str = Field(default="", max_length=200)
    # «Щит рейтинга» (Справедливость): админ пометил оценку спорной/накрученной → НЕ входит в средний
    # рейтинг (агрегат фильтрует excluded=False). Защита оболганного: месть-оценка не рушит рейтинг.
    excluded: bool = False
    created_at: datetime = Field(default_factory=utcnow)


class RequestResponse(SQLModel, table=True):
    """Отклик водителя на заявку пассажира: предлагает поездку (цена/коммент).
    Пассажир принимает → создаётся Ride+Booking (обычная поездка с чатом)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    request_id: int = Field(index=True, foreign_key="riderequest.id")
    driver_id: int = Field(index=True, foreign_key="user.id")
    price: int = 0                           # ПЕРВАЯ цена водителя (не меняется — история торга)
    comment: str = ""
    status: str = "offered"                  # offered / accepted / declined
    created_at: datetime = Field(default_factory=utcnow)
    # --- Торг о цене, второй круг (2026-07-27) ---
    # Раньше отклик был «бери или уходи»: водитель назвал цену, пассажир мог только принять или
    # молча уйти. В деревне торговаться — привычка, а не неудобство, и половина сделок гибла
    # на разнице в 50 ₽, которую обе стороны были готовы пройти навстречу.
    current_price: int = 0                   # цена, которая сейчас НА СТОЛЕ (0 у старых строк → берём price)
    last_offer_by: str = Field(default="driver", max_length=16)   # чей ход был последним: driver | passenger
    bargain_rounds: int = 0                  # сколько встречных сделано всего (лимит — см. requests.py)
    # Компактная история «d:500,p:400,d:450» — видят ОБЕ стороны. Отдельная таблица под 2-3 хода
    # не нужна, а без истории торг превращается в «я же говорил другую цену» (CSV — как evidence_urls).
    bargain_history: str = Field(default="", max_length=200)


# ---- Фаза 2: «Быстрый заказ» (такси-режим) — presence/тариф/заказ/matcher ----

class InstantOrderStatus(str, Enum):
    """Машина состояний быстрого заказа. Терминальные: done/cancelled/expired (выхода нет)."""
    scheduled = "scheduled"    # предзаказ «на время»: ждёт активации ко времени подачи (не ищет водителя)
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
    # Ночной/утренний коэффициент (аудит 2026-07-26): в −30 в 5 утра по дневной цене никто не
    # поедет. Окно задаётся часами по местному времени (может переходить через полночь: 22→6).
    # night_k = 1.0 → надбавки нет. Применяется ПОСЛЕ суржа, общий потолок — surge_max_k.
    night_k: float = 1.0
    night_from_hour: int = 22        # с какого часа действует ночной коэффициент
    night_to_hour: int = 6           # до какого часа (не включая)
    # --- Дальняя подача: отдельная строка счёта, а не множитель (разбор 2026-08-23) ---
    # Раньше дорога водителя К пассажиру оплачивалась множителем ≤ ×1,12 ко всей цене. На
    # короткой поездке это +20 ₽ за 20 км порожняка — водитель ехал в минус и не ехал вовсе,
    # а если рядом не было НИКОГО, множитель вообще равнялся 1,0: надбавки не было ровно там,
    # где она нужнее всего. В Башкирии между сёлами 20–40 км, это не редкий случай, а норма.
    #
    # Теперь это деньги за бензин отдельной строкой: пассажир видит «машина едет издалека,
    # 18 км, +190 ₽», водитель получает их целиком (комиссия с компенсации не берётся).
    # Ставка ≈ себестоимость километра (бензин 65 ₽/л × 7 л/100 км + износ ≈ 3,5 ₽/км ≈ 8 ₽/км),
    # взята с запасом: порожний километр не должен приносить прибыль, он должен не приносить убыток.
    #
    # pickup_per_km = 0 → строка выключена. Так ведут себя базы, куда миграция ещё не дошла:
    # молча начать брать с людей новые деньги хуже, чем день поработать по-старому.
    pickup_free_km: float = 3.0      # столько километров подачи входит в цену
    pickup_per_km: float = 0.0       # ₽ за километр подачи сверх бесплатных (0 = выключено)
    pickup_max_rub: int = 0          # потолок строки, ₽ (0 = выключено)
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
    # Как найти пассажира: в селе «Ленина 12» — это пять домов без табличек. Комментарий и
    # подъезд уходят водителю ВМЕСТЕ с оффером (до этого чат недоступен — его нет до accept).
    comment: str = ""                # «за магазином, синие ворота», «позвони — выйду»
    entrance: str = ""               # подъезд/квартира/этаж
    # Заказ ДЛЯ ДРУГОГО человека (сын из Уфы вызывает такси маме в Баймаке). Если заполнено —
    # водителю в карточке показываем это имя и ЭТОТ телефон (иначе он звонит заказчику в другой город).
    for_name: str = ""
    for_phone: str = ""
    # «Только женщина за рулём» (opt-in пассажира). В попутках выбор был всегда, в такси
    # появился аудитом 2026-08-06. Фильтр жёсткий: подобрать мужчину «раз никого нет» —
    # значит обмануть в том единственном, ради чего галочку и ставили.
    women_only: bool = False
    category: str = "standard"        # standard | comfort | business | minivan
    # Что пассажир попросил в салоне (CSV, app/car_class.OPTIONS): детское кресло по группе,
    # бустер, коляска, собака-проводник, животное, большой багаж. Фильтр ЖЁСТКИЙ — машине без
    # кресла такой заказ не предлагаем вообще. Подобрать «раз никого нет» значит обмануть
    # в том единственном, ради чего галочку и ставили.
    options: str = ""
    # Классы, которые пассажир СОГЛАСИЛСЯ добавить к поиску, когда в выбранном никого не было
    # (CSV). Молча класс не подменяем никогда: «заказал Комфорт — приехал Логан» это главный
    # источник скандалов у Яндекса. Цена при согласии пересчитывается, см. instant_service.
    fallback_categories: str = ""
    status: InstantOrderStatus = Field(default=InstantOrderStatus.created, index=True)
    # Предзаказ «на время» (MVP): если задан и в будущем — заказ создаётся в статусе `scheduled`
    # и НЕ уходит в поиск сразу. Активация ко времени — клиент-инициируемая/ленивая (см. instant.py).
    # Индекс — под будущий фоновый диспетчер и выборку «мои будущие предзаказы».
    scheduled_at: Optional[datetime] = Field(default=None, index=True)
    # Круговой рейс (только межгород): водитель везёт туда, ждёт на месте и везёт обратно.
    # Обратная дорога со скидкой round_trip_discount_percent — рынок так и делает, и это
    # честный ответ на пустой возврат: водитель едет назад не порожняком, а с тем же
    # пассажиром. `return_wait_min` — сколько он ждёт на месте (0 = не круговой).
    round_trip: bool = False
    return_wait_min: int = 0
    # Цена: estimate — оценка сервера при создании; final — фактическая при завершении.
    # ВАЖНО: `price_estimate` — это ВСЯ сумма, которую платит человек (поездка + компенсации
    # ниже). Так его читают все экраны и старые версии приложения: одно число «сколько отдать».
    price_estimate: int = 0
    price_final: Optional[int] = None
    # Сколько из этой суммы — сама поездка (км и минуты по тарифу), ₽. Нужен для пересчётов:
    # смена адреса, добавленный класс, активация предзаказа сравнивают и переписывают именно
    # поездку. Без отдельного поля пересчёт затирал бы компенсации — а водитель к пассажиру
    # уже съездил, и эти деньги у него забирать не за что. 0 у старых заказов → читаем
    # `price_estimate` (там компенсаций и не было).
    ride_price: int = 0
    # --- Компенсации водителю (не наценка, комиссия с них не берётся) ---
    # Дорога К пассажиру: фиксируется при создании по ближайшей живой машине, а когда рядом
    # никого — после accept по настоящей позиции согласившегося водителя (см. instant_service).
    pickup_fee_kop: int = Field(default=0, sa_type=BigInteger)
    pickup_km: float = 0.0           # сколько километров подачи учтено в строке
    # Водителю и так было по пути (его зона / он ехал сюда) → подача вдвое дешевле.
    # Храним факт, а не только сумму: иначе в чеке не объяснить, почему у соседа дороже.
    pickup_enroute: bool = False
    # Опции салона деньгами: детское кресло 150 ₽, животное и большой багаж по 100 ₽
    # (`car_class.options_fee_rub`). Уходит водителю целиком, комиссия не берётся.
    # Инвалидная коляска и собака-проводник — всегда 0 ₽, это не настройка.
    options_fee_kop: int = Field(default=0, sa_type=BigInteger)
    # Зимняя дорога: компенсация водителю за гололёд, метель, сильный снег или мороз.
    # Тоже вне наценки и без комиссии — зимой у него реально выше расход и износ.
    # `weather_kind` нужен чеку: «Гололёд +45 ₽» объясняет, а «погода +45 ₽» — нет.
    weather_fee_kop: int = Field(default=0, sa_type=BigInteger)
    weather_kind: str = Field(default="", max_length=16)
    # Цена поездки БЕЗ наценки (тот же расчёт при коэффициенте 1,0), ₽. Нужна ровно для
    # одного: показать в чеке строку «наценка за спрос +40 ₽». Без неё честного чека нет —
    # сумму наценки не восстановить: цена округляется до 10 ₽, и делением на коэффициент
    # обратно она не получается. 0 у старых заказов → строку просто не показываем.
    ride_base_price: int = 0
    # Рядом машин не было → честную цифру взять неоткуда. Пассажиру говорим «добавится до N ₽»,
    # сумму фиксируем при accept. Отменить в первые cancel_free_minutes можно бесплатно.
    pickup_pending: bool = False
    tariff_id: Optional[int] = Field(default=None, foreign_key="tariff.id")
    # Применённый сурж-коэффициент (волна 2, §5): фиксируется на заказе в момент создания,
    # price_estimate уже с ним — цена не «уезжает» задним числом.
    surge_k: float = 1.0
    # Сколько машина реально прошла С ПАССАЖИРОМ на борту, км. Копится по её же координатам,
    # которые и так летят на экран пассажира. Нужен, когда пассажир меняет адрес на полпути:
    # цена тогда = проеденное + остаток до нового адреса. Считать «новую поездку от текущей
    # точки» нельзя — крюк, который водитель уже сделал, пропал бы бесплатно.
    #
    # Путь ДО посадки сюда не идёт: он оплачен подачей, и при смене адреса до посадки цена
    # просто считается заново.
    driven_km: float = 0.0
    # Когда последний раз меняли адрес. Нужно, чтобы не принимать двойные нажатия и
    # подвисшую сеть за две настоящие смены подряд.
    destination_changed_at: Optional[datetime] = None
    # Сколько раз адрес меняли за поездку. Не ограничиваем (решение Александра), но в чеке
    # человек должен видеть, почему цена не та, что была при заказе.
    destination_changes: int = 0
    # Водитель ПОДТВЕРДИЛ, что видел смену («Понял»). Пусто = ещё не видел, и пассажиру
    # через минуту покажем «позвони ему». Мы не можем заставить человека посмотреть в
    # телефон за рулём, но можем честно сказать пассажиру, что происходит.
    destination_ack_at: Optional[datetime] = None
    # --- Предложенная, но ещё не применённая смена ---
    # Заполняется ТОЛЬКО когда нужно согласие водителя: поездка стала межгородной или цена
    # выросла втрое. Пять часов за руль и ночёвка в чужом городе — это не «поменял адрес»,
    # это другая работа, и соглашаться на неё водитель должен сам.
    # Во всех остальных случаях адрес меняется сразу и эти поля не используются.
    pending_to_lat: Optional[float] = None
    pending_to_lng: Optional[float] = None
    pending_to_text: str = ""
    pending_price: Optional[int] = None
    pending_asked_at: Optional[datetime] = None
    # Почему спрашиваем: zone (стала межгородной) | price (выросла втрое). Для текста водителю.
    pending_reason: str = ""
    # Остановки по пути: JSON-массив [{"lat","lng","text","done"}], A → точки → B.
    #
    # Почему не как у попутки. Там остановки лежат просто НАЗВАНИЯМИ через разделитель и
    # на цену не влияют вообще — водитель попутки сам ставит цену. В такси считает сервер,
    # и без координат остановку нельзя ни построить в маршрут, ни оплатить. А смысл
    # остановки ровно в том, что за неё платят: иначе водитель везёт лишние километры даром.
    #
    # `done` — остановку уже проехали. Нужно при смене конечного адреса: проеденные остаются
    # в истории, будущие сохраняются (человек едет в другое место, но за ребёнком заехать
    # всё ещё надо).
    waypoints_json: str = ""
    # Водитель нажал «Стоим» на остановке — с этого момента тикает ожидание по общим
    # правилам. Кнопкой, а не автоматом: машина, застрявшая в пробке у светофора рядом
    # с остановкой, начала бы «зарабатывать» сама, а разбираться пришлось бы пассажиру.
    stop_started_at: Optional[datetime] = None
    # Водитель завершил поездку досрочно («Не смогу ехать по новому адресу») и почему:
    # shift_end | out_of_zone | no_fuel | other. Пустое = обычное завершение.
    # Пассажиру показываем причину словами: «просто завершено» посреди поездки звучит как
    # произвол, а названная причина превращает отказ в понятную ситуацию.
    early_finish_reason: str = ""
    # ПОЛНЫЙ множитель, по которому реально посчитана цена: спрос × ночь × погода × дальняя
    # подача. `surge_k` выше — только спрос, он показывается пассажиру как «наценка за спрос»
    # и менять его смысл нельзя. А для ЛЮБОГО пересчёта цены на живом заказе нужен именно
    # полный: без него ночной пересчёт давал цену ниже настоящей, и водитель ночью получал
    # как днём (аудит 2026-08-21). Ноль/единица у старых заказов → фолбэк на surge_k.
    pricing_k: float = 1.0
    # Скидка по промокоду (M2, kind="taxi_ride"), копейки. Фиксируется при СОЗДАНИИ заказа:
    # человек видел цену со скидкой до заказа — она и должна остаться. Пассажир платит
    # price − promo_discount_kop, а водитель НЕ теряет ни копейки: скидку оплачивает платформа
    # (комиссия за поездку уменьшается, остаток идёт водителю в кошелёк). См. app/promo_ride.py.
    promo_discount_kop: int = Field(default=0, sa_type=BigInteger)
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
    waiting_fee_kop: int = Field(default=0, sa_type=BigInteger)         # платное ожидание сверх бесплатного, копейки (фикс на onboard)
    cancel_fee_kop: int = Field(default=0, sa_type=BigInteger)          # штраф за позднюю отмену / no-show = подача, копейки (Модель А: только фиксируем)
    no_show: bool = False            # «пассажир не вышел» — отмена водителем по таймингу
    # Таймстампы переходов (пишутся машиной состояний). created_at индексируем — растущая таблица:
    # сортировка/дневная сводка/будущая чистка по дате (иначе seq-scan по мере роста заказов).
    created_at: datetime = Field(default_factory=utcnow, index=True)
    searching_at: Optional[datetime] = None
    offered_at: Optional[datetime] = None
    accepted_at: Optional[datetime] = None
    arriving_at: Optional[datetime] = None
    onboard_at: Optional[datetime] = None
    # ❄️ Зимний протокол («ты доехал?»). Раньше жил только у попутки, хотя трасса
    # Сибай–Уфа зимой одинаково опасна во всех трёх сценариях (аудит 2026-08-06).
    winter_check_sent_at: Optional[datetime] = None
    winter_check_ack_at: Optional[datetime] = None
    done_at: Optional[datetime] = None
    cancelled_at: Optional[datetime] = None
    expired_at: Optional[datetime] = None
    # «Рядом никого»: заказ не нашёл машину, но пассажир согласился подождать — фоновый воркер
    # (app/taxi_worker.py) перезапустит поиск, пока не истечёт wait_until. Без этого в райцентре
    # ночью заказ умирает за 2 секунды («Рядом никого») и человек уходит к конкуренту.
    wait_until: Optional[datetime] = Field(default=None, index=True)
    retry_count: int = 0             # сколько раз воркер перезапускал поиск (для лога/потолка)
    # Забытые вещи: пассажир нажал «забыл вещь» → чат заказа снова открыт до этого времени.
    lost_item_until: Optional[datetime] = None
    # «Сказать рәхмәт» после такси-заказа (дедуп). Раньше благодарность была только у попуток —
    # водителю такси, который помог занести коляску, сказать спасибо было нечем.
    thanked: bool = False


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
    # G3 — что караулим: "rides" (пассажир ждёт поездки водителей — дефолт, прежнее поведение) /
    # "requests" (водитель ждёт заявки пассажиров по своему направлению) / "both" (и то, и то).
    # rides матчатся в create_ride (notify_route_watchers), requests — в create_request
    # (notify_request_watchers). Один механизм — обе стороны попутки.
    watch_kind: str = "rides"
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


class SupportTicketStatus(str, Enum):
    open = "open"        # обращение в работе (пользователь или админ ещё может писать)
    closed = "closed"    # закрыто; новое сообщение пользователя переоткрывает (см. support.py)


class SupportSender(str, Enum):
    user = "user"        # написал сам пользователь
    admin = "admin"      # ответила поддержка


class SupportTicket(SQLModel, table=True):
    """Обращение в поддержку внутри приложения (замена ссылки в Telegram).
    Тред = SupportTicket + его SupportMessage. Приватность: тикет видит ТОЛЬКО автор
    (по user_id) и админ. Персональные данные (весь тред) стираются при удалении аккаунта."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    subject: str = Field(default="", max_length=200)
    status: SupportTicketStatus = Field(default=SupportTicketStatus.open, index=True)
    created_at: datetime = Field(default_factory=utcnow, index=True)
    updated_at: datetime = Field(default_factory=utcnow, index=True)  # последнее сообщение — сорт «свежие сверху»


class SupportMessage(SQLModel, table=True):
    """Сообщение в треде поддержки. sender — user|admin (не FK на конкретного админа:
    для пользователя это единый голос «Поддержка Юлдаш»). Удаляется вместе с тикетом."""
    id: Optional[int] = Field(default=None, primary_key=True)
    ticket_id: int = Field(index=True, foreign_key="supportticket.id")
    sender: SupportSender = Field(default=SupportSender.user, index=True)
    body: str = Field(default="", max_length=4000)
    created_at: datetime = Field(default_factory=utcnow, index=True)


class Payment(SQLModel, table=True):
    """Платёж платформы. Два вида:
    1) СВОЯ услуга самозанятого (boost/ad/donate) — НЕ посредничество за проезд.
    2) Оплата поездки после done (purpose=ride|booking, Фаза 3) — пассажир платит за
       завершённый заказ/бронь; успех → начисление водителю через ledger (см. ledger.py).
    provider_id — id платежа в ЮKassa (или mock-id в dev)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")   # плательщик (для ride — пассажир)
    purpose: str = "boost"                       # boost | ad | donate | ride | booking | partner_sub | support | courier_commission
    provider_id: str = Field(default="", index=True)  # id платежа в ЮKassa
    ride_id: Optional[int] = Field(default=None, foreign_key="ride.id")  # для boost
    ad_id: Optional[int] = Field(default=None, foreign_key="ad.id")       # для оплаты рекламы (purpose=ad)
    order_id: Optional[int] = Field(default=None, foreign_key="instantorder.id")  # для purpose=ride (быстрый заказ)
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")     # для purpose=booking (бронь плановой поездки)
    partner_id: Optional[int] = Field(default=None, foreign_key="partner.id")     # для purpose=partner_sub (подписка бизнеса в «Скидки по пути», M1)
    tier: str = ""                               # quick / day / urgent (boost) | код тарифа PARTNER_PLANS (partner_sub)
    method: str = ""                             # cash | card | sbp | yookassa (способ оплаты поездки)
    amount_kop: int = Field(default=0, sa_type=BigInteger)                          # сумма в копейках
    status: str = "pending"                      # pending | succeeded | canceled | refund_due
    created_at: datetime = Field(default_factory=utcnow, index=True)  # когда НАЖАЛИ «оплатить»
    # Когда платёж РЕАЛЬНО применён (деньги дошли). Разные моменты: человек жмёт «оплатить»
    # в 23:58, деньги приходят в 00:03. Сверка ledger↔оплаты обязана сравнивать по этому полю,
    # иначе обычная ночная оплата даёт красный diff два дня подряд (аудит 2026-08-12, волна 27).
    settled_at: Optional[datetime] = Field(default=None, index=True)


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
    amount_kop: int = Field(default=0, sa_type=BigInteger)
    created_at: datetime = Field(default_factory=utcnow, index=True)  # index — для сверки за период
    note: str = ""
    # payout: ключ идемпотентности выплаты / id выплаты у провайдера (для earn/fee/adj пусто).
    # Гарантирует, что повторный запрос вывода с тем же ключом НЕ спишет баланс дважды.
    ext_id: str = Field(default="", index=True)


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
    amount_kop: int = Field(default=0, sa_type=BigInteger)                                    # комиссия по этому заказу, копейки
    week: str = Field(default="", index=True)             # ISO-неделя начисления, напр. "2026-W28"
    status: DebtStatus = Field(default=DebtStatus.unpaid, index=True)
    created_at: datetime = Field(default_factory=utcnow, index=True)
    due_at: Optional[datetime] = None                     # срок оплаты (created_at + debt_due_days)
    paid_declared_at: Optional[datetime] = None           # когда водитель нажал «Я оплатил»
    # Сколько раз заявляли оплату по этому долгу (аудит 2026-07-26). Раньше кнопку «Я оплатил»
    # можно было жать бесконечно: pending снимает блок, админ отклонил → нажал снова → работает.
    # После DEBT_MAX_DECLARES отклонённых заявок «слово» больше не снимает блокировку.
    declare_count: int = 0
    # Момент, когда предупредили водителя, что доверие вот-вот кончится (волна 176).
    # Нужно, чтобы предупреждение ушло ОДИН раз: каждую ночь — спам, который перестают
    # читать ровно к тому дню, когда он важен.
    declare_reminded_at: Optional[datetime] = None
    note: str = ""                                        # почему списан вручную (история для админа)
    confirmed_at: Optional[datetime] = None               # когда админ подтвердил
    # Один заказ = максимум одна запись долга. DB-барьер против гонки двойного «done»
    # (двойной тап/ретрай): check-then-insert без него мог создать две записи на один order_id.
    # order_id nullable → NULL-строки (если появятся) уникальностью не связаны (NULL≠NULL в SQL).
    __table_args__ = (UniqueConstraint("order_id", name="uq_commissiondebt_order_id"),)


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
    хранилище /secure/docs, как license_url водителя). Требования: возраст 20+, стаж от 3 лет (580-ФЗ).
    Проверка — вручную админом (Александр). Одна заявка на пользователя (user_id unique);
    повторная подача после reject обновляет эту же строку (status → pending)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    inn: str = ""                                  # ИНН самозанятого (10-12 цифр)
    permit_number: str = ""                        # № разрешения на такси (реестр перевозчиков)
    permit_photo_url: Optional[str] = None         # фото разрешения (защищённый URL)
    osago_url: Optional[str] = None                # фото полиса ОСАГО (защищённый URL)
    # Проверки водителя, Уровень 1 (сверка «между своими», ручная модерация админом):
    selfie_url: Optional[str] = None               # селфи с правами в руках — сверка лица с документом
    criminal_record_url: Optional[str] = None      # справка о несудимости (Госуслуги/МВД) — опц., рекомендуется
    birth_date: date_type = date_type(1970, 1, 1)  # для проверки «возраст 20+»
    license_since_year: int = 0                    # год получения прав (стаж от 3 лет, 580-ФЗ)
    # Точная дата выдачи прав (аудит 2026-08-03). Год в одиночку врал: права от 31.12.2023
    # проходили 01.01.2026 как «3 года стажа», хотя реального стажа 2 года и 1 день. Поле
    # опциональное — старые клиенты шлют только год, и для них стаж считаем от 31 декабря
    # этого года (консервативно, в пользу безопасности пассажира).
    license_since_date: Optional[date_type] = None
    # Сроки документов (аудит 2026-07-26). Раньше документы были ТОЛЬКО картинками: одобрили
    # в июле — человек возит с просроченным ОСАГО в декабре, а мы «проверенная служба».
    # Фоновая проверка (app/doc_check.py) напоминает за 14/3 дня и снимает допуск к такси.
    osago_until: Optional[date_type] = Field(default=None, index=True)
    permit_until: Optional[date_type] = Field(default=None, index=True)
    # Диагностическая карта (техосмотр) — 580-ФЗ требует исправную машину, а в коде техосмотра
    # не было вообще. Дата хранится рядом с остальными сроками, контроль — тот же doc_check.
    inspection_until: Optional[date_type] = Field(default=None, index=True)
    # ОСГОП — страхование ответственности перевозчика. Обязателен для ВСЕХ, включая самозанятых,
    # с 01.09.2024 (67-ФЗ). Стоит 1 300–5 400 ₽/год, штраф за отсутствие с 08.03.2026 — 5 000 ₽
    # самозанятому. Дешёвый документ, но без него служба заказа не имеет права давать заказы.
    osgop_url: Optional[str] = None
    osgop_until: Optional[date_type] = Field(default=None, index=True)
    docs_expired: bool = Field(default=False, index=True)   # допуск снят до обновления документов
    docs_warned_at: Optional[datetime] = None               # когда слали последнее напоминание (анти-спам)
    # До какого момента водитель работает по СЛОВУ: новую дату документа он вписал сам, фото
    # не приложил (волна 170). Не принёс до этого срока — ночной робот снимает допуск обратно.
    # None = подтверждать нечего (фото есть или дата не менялась).
    docs_photo_due_at: Optional[datetime] = Field(default=None, index=True)
    status: TaxiApplicationStatus = Field(default=TaxiApplicationStatus.pending, index=True)
    comment: Optional[str] = None                  # комментарий админа при отклонении
    created_at: datetime = Field(default_factory=utcnow)
    reviewed_at: Optional[datetime] = None         # когда админ одобрил/отклонил


class PreTripCheck(SQLModel, table=True):
    """Предрейсовое подтверждение таксиста за ОДИН местный день (580-ФЗ, честный минимум).

    Закон требует предрейсовый медосмотр и контроль исправности машины. Медцентра у нас нет
    и не будет — но «ничего» тоже неправильный ответ: раньше в коде не было ни одного
    упоминания осмотра (аудит 2026-07-26). Поэтому перед первым выходом на линию водитель
    один раз в день подтверждает три вещи ЯВНО, своим действием, и это остаётся записью:
    самочувствие, исправность машины, отсутствие алкоголя.

    Это самодекларация, а не медосмотр — так и написано водителю в интерфейсе, и так же
    честно должно звучать в любом отчёте. Ценность в двух вещах: человек осознанно ставит
    галочку (а не «как-нибудь доеду»), и при разборе ДТП видно, что он в этот день заявил.

    Один день = одна строка (UniqueConstraint). Отозвать нельзя: запись — след, а не тумблер.
    Попутки это не касается — она не такси и предрейсового контроля не требует.
    """
    __table_args__ = (UniqueConstraint("driver_id", "day", name="uq_pretripcheck_driver_day"),)
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    day: date_type = Field(index=True)      # МЕСТНЫЙ день (UTC + local_tz_offset_hours), как TaxiWorkDay
    health_ok: bool = False                 # «чувствую себя хорошо, могу вести»
    car_ok: bool = False                    # «машина исправна: тормоза, свет, резина»
    no_alcohol: bool = False                # «алкоголь и лекарства, влияющие на реакцию, не принимал»
    note: str = Field(default="", max_length=300)   # что-то заметил, но всё же выехал (для разбора)
    created_at: datetime = Field(default_factory=utcnow)


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


class DailyDigestLog(SQLModel, table=True):
    """Замок дневной сводки админу (B9b-3): одна строка = сводка за МЕСТНЫЙ день отправлена.
    UNIQUE(day) решает гонку воркеров gunicorn: второй insert падает → второй раз не шлём.
    Без внешнего cron — триггерит первый запрос после daily_digest_hour (см. app/digest.py)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    day: date_type = Field(unique=True)               # МЕСТНЫЙ день сводки (UTC + local_tz_offset_hours)
    sent_at: datetime = Field(default_factory=utcnow)


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
    budget_kop: int = Field(default=0, sa_type=BigInteger)                       # стоимость размещения в копейках (из пакета, фиксируется при сабмите)
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


class FamilySmsLog(SQLModel, table=True):
    """Журнал SMS близким, отправленных ЗА СЧЁТ ПЛАТФОРМЫ (аудит 2026-08-12, волна 48).

    Зачем понадобился. «Поделиться поездкой с близким» шлёт SMS на номер, который человек ввёл
    сам — номер ничем не подтверждён. Потолок стоял на ЧИСЛЕ близких (10), а не на числе
    сообщений: контакт удаляется, слот освобождается, и следующий номер получает новую SMS.
    Проба: 60 сообщений на 60 разных номеров подряд, ни одного отказа. То же самое, что было
    с реферальными бонусами в волне 25 — лимит на остаток вместо лимита на жизнь.

    Считать по `TripShare` нельзя: удаление контакта уносит и его строки шаринга, то есть
    счётчик обнулялся бы вместе с уликой. Поэтому журнал живёт у ОТПРАВИТЕЛЯ и переживает
    удаление контактов.

    Приватность: номер получателя сюда НЕ пишем — он и так лежит в своей таблице (доверенный
    контакт, посылка), а второй копии чужого телефона в базе быть не должно. Здесь только
    «кто, какого рода сообщение и когда» — этого хватает и для потолка, и чтобы видеть расход.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")     # кто инициировал отправку
    kind: str = Field(max_length=24)                            # share_ride / share_taxi / parcel / status
    created_at: datetime = Field(default_factory=utcnow, index=True)


class TextFlag(SQLModel, table=True):
    """Помеченный открытый текст — журнал для админа (модерация текста, 2026-08-08).

    Зачем понадобился. Пометки ставились и раньше, но ложились только в счётчик Redis: в пульсе
    админ видел ЧИСЛО помеченных за сегодня и не мог посмотреть, кто и за что. Помечать и не
    показывать — работа впустую: среагировать не на что.

    Приватность (§8 и принцип модуля antifraud). Сам текст сюда НЕ копируем — храним ССЫЛКУ:
    место (`place`) и id записи (`ref_id`). Текст и так лежит в своей таблице, админ откроет
    объект и увидит его в контексте. Второй копии личных данных в базе не появляется, а
    сообщение с чужим телефоном не расползается по журналам.

    Принцип модуля не меняется: это ПОМЕТКА, а не наказание. Ничего не блокируется, текст
    сохраняется и доставляется; решение принимает человек.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")     # кто написал
    kind: str = Field(index=True, max_length=16)                # warn (фишинг) / contact / abuse
    place: str = Field(max_length=32)                           # где: review, response, name, pickup, …
    ref_id: Optional[int] = None                                # id записи в её таблице (может быть null)
    created_at: datetime = Field(default_factory=utcnow, index=True)


class DeviceBan(SQLModel, table=True):
    """Бан устройства (анти-фрод B8-1, барьер от «нового номера на том же телефоне»).

    Клиент шлёт стабильный X-Device-Id (ANDROID_ID) со всеми запросами; забаненное
    устройство не входит по своему id. Заголовок контролирует клиент → целевой обход
    сменой X-Device-Id возможен (усиление — Play Integrity, бэклог); честная оценка, не «закрыто».
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


class OfferDecline(SQLModel, table=True):
    """Почему водитель не взял предложенный заказ (разбор №2, 2026-08-03).

    До этого отказ был безмолвным: платформа видела «заказ не берут» и продолжала слать
    такие же заказы тем же людям. Причина превращает это в диагноз — далеко подавать,
    мало денег, неудобное направление, водитель на перерыве. Из этого видно, что чинить:
    радиус подачи, тариф или расписание.

    Это ЖУРНАЛ, а не наказание: за отказ санкций нет и не планируется. Иначе водитель
    перестанет отказываться честно и просто уйдёт в офлайн — а это хуже и для пассажира,
    и для нас (машина есть, но её не видно).

    Персональных данных нет: id заказа, id водителя, слово-причина. Чистится ретеншеном
    (`cleanup.py`) — поштучные отказы нужны недолго, важна статистика.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    order_id: int = Field(index=True, foreign_key="instantorder.id")
    driver_id: int = Field(index=True, foreign_key="user.id")
    reason: str = Field(default="", max_length=32)   # far / cheap / direction / busy / break / other
    created_at: datetime = Field(default_factory=utcnow, index=True)


class Consent(SQLModel, table=True):
    """Реестр согласий (152-ФЗ): доказуемый факт и время согласия пользователя на
    оферту / политику конфиденциальности / обработку геолокации. Одна строка на вид согласия.
    Время первого согласия не перезаписываем — это юридическое доказательство."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    kind: str = Field(index=True)     # offer / privacy / geo
    granted_at: datetime = Field(default_factory=utcnow)


# ---- M1 (монетизация): партнёрский слой + купонный маркетплейс «Скидки по пути» ----

class Partner(SQLModel, table=True):
    """Бизнес-партнёр (кафе/АЗС/шиномонтаж/магазин/аптека/сервис) в разделе «Скидки по пути».

    Философия M1: зарабатываем на БИЗНЕСЕ (подписка за место + оплата за погашённый купон),
    НЕ на пассажирах. Купон = реальная скидка от бизнеса, честно и прозрачно.
    Приватность: при погашении бизнес видит только код и максимум имя — НЕ телефон, НЕ гео.

    Модерация бизнеса — вручную админом (status). Место в выдаче даёт активная подписка
    (subscription_until > now) — точный аналог платного гейта у рекламы (F20)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    owner_id: int = Field(index=True, foreign_key="user.id")   # владелец бизнеса (один owner = один партнёр)
    name: str = Field(index=True)                              # название бизнеса (публичное)
    category: str = Field(default="other", max_length=20)     # cafe|azs|tire|store|pharmacy|service|other
    city: str = Field(default="", index=True)                 # город бизнеса (фильтр витрины)
    address: str = ""                                          # адрес (публичный)
    lat: Optional[float] = None                               # координаты пина на карте
    lng: Optional[float] = None
    phone: str = ""                                           # публичный контакт бизнеса (НЕ телефон пользователя)
    description: str = ""                                     # описание бизнеса
    status: str = Field(default="pending", max_length=16, index=True)    # pending|active|paused|rejected|archived (модерация)
    subscription_until: Optional[datetime] = None            # до какой даты оплачено место в «Скидки по пути»
    subscription_plan: str = Field(default="", max_length=20)  # код тарифа из PARTNER_PLANS (зафиксирован при оплате)
    reject_reason: str = ""
    created_at: datetime = Field(default_factory=utcnow)
    reviewed_at: Optional[datetime] = None


class Coupon(SQLModel, table=True):
    """Купон бизнеса — честная скидка «по пути». Виден в витрине, только пока партнёр active
    и подписка оплачена (гейт как у платной рекламы). Срок/лимит/текст скидки — на виду."""
    id: Optional[int] = Field(default=None, primary_key=True)
    partner_id: int = Field(index=True, foreign_key="partner.id")
    title: str = Field(index=True)                            # заголовок купона
    description: str = ""                                     # подробности предложения
    discount_text: str = Field(default="", max_length=80)    # честный текст скидки («−20%», «2 по цене 1»)
    city: str = Field(default="", index=True)                # денормализовано от партнёра (фильтр витрины)
    route_hint: str = ""                                      # CSV городов «по пути» (опц.)
    valid_from: Optional[datetime] = None                    # окно действия (null = без нижней границы)
    valid_until: Optional[datetime] = None                   # окно действия (null = бессрочно)
    limit_total: int = 0                                     # общий лимит погашений (0 = без лимита)
    limit_per_user: int = 1                                  # лимит на одного пользователя
    redeemed_count: int = 0                                  # денормализованный счётчик погашений
    premium: bool = False                                    # выделенная метка на карте (фича premium-подписки)
    status: str = Field(default="draft", max_length=16, index=True)     # draft|active|paused|archived
    # --- Проверка текста (2026-08-08). Отдельно от `status`, потому что это РАЗНЫЕ вещи:
    # status — чего хочет партнёр («показывай»), review — что решила проверка («можно»).
    # Пока они были одним полем, чистый купон не видел никто и никогда: автопроверка ищет
    # телефоны/ссылки/ругань по шаблонам и пропускает «скидка 90% при предоплате на карту».
    #   held     — автопроверка пометила текст → в витрине НЕ виден, ждёт человека;
    #   pending  — текст чистый → виден СРАЗУ (бизнес не тормозим), но лежит в очереди админа;
    #   approved — админ посмотрел, всё в порядке;
    #   blocked  — админ снял с витрины, партнёр видит причину в review_note.
    review: str = Field(default="pending", max_length=16, index=True)
    review_flag: str = Field(default="", max_length=16)      # метка автопроверки: ""|warn|contact|abuse
    review_note: str = ""                                    # причина снятия (её видит партнёр)
    reviewed_at: Optional[datetime] = None
    reports_count: int = 0                                   # сколько живых людей пожаловались
    created_at: datetime = Field(default_factory=utcnow)


class CouponReport(SQLModel, table=True):
    """Жалоба пользователя на купон: «обещали не то» / «обман» / «нет такой скидки».

    Жалоба НЕ снимает купон с витрины — только возвращает его в очередь админа. Иначе
    конкурент выключал бы чужую скидку одной кнопкой. UNIQUE(coupon_id, user_id): один
    человек — одна жалоба, повторными нажатиями очередь не засыпать."""
    __table_args__ = (UniqueConstraint("coupon_id", "user_id", name="uq_couponreport_coupon_user"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    coupon_id: int = Field(index=True, foreign_key="coupon.id")
    user_id: int = Field(index=True, foreign_key="user.id")
    reason: str = Field(default="", max_length=500)
    created_at: datetime = Field(default_factory=utcnow)


class CouponRedemption(SQLModel, table=True):
    """Бронь/погашение купона. Пользователь активирует → получает короткий код; бизнес
    гасит код у себя. Приватность: код не привязан ни к телефону, ни к координатам."""
    id: Optional[int] = Field(default=None, primary_key=True)
    coupon_id: int = Field(index=True, foreign_key="coupon.id")
    user_id: int = Field(index=True, foreign_key="user.id")
    code: str = Field(index=True, max_length=12)             # короткий уникальный код погашения (6 симв)
    status: str = Field(default="reserved", max_length=16)  # reserved|redeemed|canceled|expired
    reserved_at: datetime = Field(default_factory=utcnow)
    redeemed_at: Optional[datetime] = None
    redeemed_by: Optional[int] = Field(default=None, foreign_key="user.id")  # сотрудник партнёра, подтвердивший


# ---- M2 (монетизация): промокоды и кампании (рычаг роста) ----

class PromoCode(SQLModel, table=True):
    """Именной промокод / кампания (блогер, партнёр, общая акция Юлдаша).

    Философия M2 (красные линии): промокод — инструмент ПРИВЛЕЧЕНИЯ, а не прямой доход и НЕ
    штраф за отказ. Попутка остаётся бесплатной — код НЕ вводит плату. Бонус пользователю только
    приятный (бесплатные поднятия поездки водителю) либо чистая атрибуция (welcome). Блогеру платим
    «на результат» — за РЕАЛЬНО активных приведённых (критерий «живой поездки», как в реферале B8).

    owner_id=None → общая акция Юлдаша; иначе — владелец кампании (блогер/партнёр), видит статистику
    только по своему коду. Один код на всю жизнь аккаунта (анти-абуз: у юзера максимум одна PromoRedemption)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    code: str = Field(index=True, unique=True, max_length=32)   # ХРАНИМ в верхнем регистре
    title: str = ""
    description: str = ""
    owner_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")  # None = общая акция Юлдаша
    campaign: str = Field(default="", max_length=80)            # метка кампании для группировки
    kind: str = Field(default="welcome", max_length=16)         # welcome (атрибуция) | boost (perk_value поднятий)
    perk_value: int = 0                                         # число boost-кредитов (для kind=boost)
    limit_total: int = 0                                        # общий лимит активаций (0 = без лимита)
    limit_per_user: int = 1                                     # лимит на пользователя (де-факто 1: один код на жизнь)
    redeemed_count: int = 0                                     # денормализованный счётчик активаций
    valid_from: Optional[datetime] = None                      # окно действия (null = без нижней границы)
    valid_until: Optional[datetime] = None                     # окно действия (null = бессрочно)
    active: bool = Field(default=True, index=True)             # вкл/выкл кампании
    created_at: datetime = Field(default_factory=utcnow)


class PromoRedemption(SQLModel, table=True):
    """Факт применения промокода пользователем. Уникальность «один промокод на юзера ВСЕГО»
    (ввёл код один раз в жизни аккаунта). БД-барьер UNIQUE(user_id): прикладная проверка «уже
    активировал» не сериализует гонку ДВУХ РАЗНЫХ кодов (лочатся разные строки promocode) →
    без констрейнта юзер получал двойной бонус, а блогеру засчитывался результат дважды."""
    __table_args__ = (UniqueConstraint("user_id", name="uq_promoredemption_user"),)
    id: Optional[int] = Field(default=None, primary_key=True)
    promo_id: int = Field(index=True, foreign_key="promocode.id")
    user_id: int = Field(index=True, foreign_key="user.id")
    redeemed_at: datetime = Field(default_factory=utcnow)
    # --- Скидка на поездку в такси (kind="taxi_ride"), копейки ---
    # Сумма фиксируется В МОМЕНТ АКТИВАЦИИ: правка кампании задним числом не меняет обещание,
    # которое человек уже получил. 0 = у кода нет денежной скидки (welcome/boost).
    discount_kop: int = Field(default=0, sa_type=BigInteger)
    # На каком заказе скидка потрачена (NULL = ещё цела). Захват — под row-lock + атомарный
    # CAS-UPDATE, иначе два параллельных заказа потратили бы одну скидку дважды.
    # Заказ не состоялся (отменён / «рядом никого») → ссылка снимается, скидка не сгорает.
    used_order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    used_at: Optional[datetime] = None


# ---- M3: доставка посылок между сёлами/городами (реальная боль села) ----

class ParcelDelivery(SQLModel, table=True):
    """Заявка на доставку посылки попутным курьером (лекарство/документы/вещи между сёлами).

    Философия M3 (красные линии):
    - Попутка ЛЮДЕЙ остаётся бесплатной; символический сбор берём только за ВЕЩЬ-доставку —
      за реальную услугу «свели отправителя и попутного курьера» (fee_kop из конфига по размеру).
    - Приватность: телефон получателя (receiver_phone) виден курьеру ТОЛЬКО после того, как он
      принял посылку. До принятия — скрыт из /available. Телефоны не логируем.
    - Безопасность: отправитель принимает правила «не возим запрещённое» при создании (флаг
      rules_accepted). Мы — логистика между своими, не перевозчик запрещёнки.

    Это ОТДЕЛЬНАЯ от Ride.parcel сущность (у Ride.parcel — плановая попутка-посылка). Здесь —
    самостоятельный флоу заявки: отправитель создаёт → курьер принимает → везёт → отдаёт по коду.
    confirm_code отправитель передаёт получателю ВНЕ приложения; получатель называет код курьеру
    при передаче — курьер вводит код, статус → delivered (подтверждение вручения без раскрытия ПДн)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    sender_id: int = Field(index=True, foreign_key="user.id")                 # отправитель посылки
    courier_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")  # курьер, взявший посылку
    from_city: str = Field(default="", index=True)                           # откуда (город/село отправления)
    to_city: str = Field(default="", index=True)                             # куда (город/село назначения)
    # «Куда именно», а не только город: раньше курьер брал заказ и ехал «в Баймак» — ни дома, ни
    # квартиры, ни ориентира. У заказа ТАКСИ это давно решено полями comment/entrance; здесь та же
    # идиома для посылок. СВОБОДНЫЙ текст, а не улица/дом/квартира: в селе адрес чаще ориентир
    # («у мечети», «синие ворота», «за магазином»), чем табличка с номером.
    # ПРИВАТНО — правило ровно как у receiver_phone: в открытом списке заявок адресов нет вообще,
    # отдаём принявшему курьеру, отправителю (его собственные данные) и админу; получателю по
    # трекинг-ссылке — только его to_address (см. routers/parcels._addresses, share._parcel_state).
    from_address: str = Field(default="", max_length=200)                    # где забрать (дом, квартира, ориентир)
    to_address: str = Field(default="", max_length=200)                      # где вручить
    from_lat: Optional[float] = None                                         # координаты точки забора (опц.)
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None                                           # координаты точки вручения (опц.)
    to_lng: Optional[float] = None
    size: str = Field(default="small", max_length=16)                        # small|medium|large (сбор зависит от размера)
    description: str = ""                                                     # что за посылка (без запрещёнки)
    # ЧТО ИМЕННО ВЕЗЁМ (разбор №2, 2026-08-03). Раньше был только «размер» — курьер соглашался
    # на «большую» и находил у подъезда мешок картошки на 40 кг, который не поднимет и не увезёт
    # на легковой. Размер отвечает на «влезет ли», вес — на «унесу ли», а это разные вопросы.
    # Всё три поля УСЛОВИЯ заказа, а не персональные данные: курьер видит их ДО принятия,
    # в том числе в открытом списке (в отличие от телефона и адреса).
    weight_kg: float = Field(default=0.0)                                    # 0 = не указан (не обязателен)
    cargo_type: str = Field(default="", max_length=16)                       # documents|medicine|food|clothes|tech|other
    fragile: bool = False                                                     # «хрупкое»: везти аккуратно, не класть под низ
    receiver_name: str = ""                                                   # имя получателя (публично курьеру)
    receiver_phone: str = ""                                                  # ПРИВАТНО: отдаём только принявшему курьеру
    fee_kop: int = Field(default=0, sa_type=BigInteger)                                                          # символический сервисный сбор платформы (коп), фиксируется при создании
    status: str = Field(default="created", max_length=16, index=True)        # created|accepted|in_transit|delivered|canceled|returning|returned
    confirm_code: str = Field(default="", index=True, max_length=12)         # короткий код вручения (получатель называет курьеру)
    created_at: datetime = Field(default_factory=utcnow, index=True)          # растущая таблица: индекс под сорт/чистку по дате
    accepted_at: Optional[datetime] = None
    # ❄️ Зимний протокол («ты доехал?»). Раньше жил только у попутки, хотя трасса
    # Сибай–Уфа зимой одинаково опасна во всех трёх сценариях (аудит 2026-08-06).
    winter_check_sent_at: Optional[datetime] = None
    winter_check_ack_at: Optional[datetime] = None
    delivered_at: Optional[datetime] = None
    # --- C1: профессиональный курьер (гибрид «по пути» + режим «Курьер») ---
    # Тип доставки: poputka = по пути (текущий M3, любой попутчик, без гейта) |
    # courier = заказать курьера (только одобренным курьерам на линии) |
    # buy_bring = «купи и привези» (курьер тратит свои на товар, получатель возвращает).
    delivery_type: str = Field(default="poputka", max_length=16)
    # Объявленная ценность посылки (коп) — для ответственности при споре. 0 = не объявлено.
    declared_value_kop: int = Field(default=0, sa_type=BigInteger)
    # «Купи и привези»: стоимость товара (наложка), которую курьер тратит и получатель возвращает.
    # Ограничена потолком COURIER_COD_CAP_KOP (защита курьера от больших авансов). 0 = не применяется.
    cod_amount_kop: int = Field(default=0, sa_type=BigInteger)
    # Комиссия платформы с доставки (коп) — фиксируется при создании (прозрачно, «на доверии»).
    commission_kop: int = Field(default=0, sa_type=BigInteger)
    # C3: комиссия по этой доставке уже оплачена курьером платформе (биллинг «на доверии»).
    # False + status=delivered → входит в «к оплате сейчас» (/courier/pay-commission).
    commission_paid: bool = Field(default=False, index=True)
    # Срочность: bypath = в ближайший рейс/по пути | now = нужен курьер сейчас (надбавка к цене).
    urgency: str = Field(default="bypath", max_length=16)
    # СРОК: «нужно доставить не позже этого дня» (местный день, Уфа UTC+5). None = «не срочно,
    # когда получится». urgency — это про СПОСОБ (в ближайший рейс / курьера сейчас), а не про
    # срок: у «по пути» доставка зависит от того, поедет ли кто-то в ту сторону, и могла тянуться
    # неделю. Отправитель не понимал, когда посылка доедет, а курьер, глядя на заявку, не знал,
    # ждут ли её к завтрашнему утру. Дата, а не datetime: человек мыслит днями («к пятнице»).
    deliver_by: Optional[date_type] = None
    # --- C2: расчёт «купи и привези» с получателем + объявленная ценность ---
    # Цена доставки (коп), зафиксированная при создании (без комиссии платформы) — сколько
    # получатель платит за саму доставку. Для buy_bring: получатель платит товар + доставку.
    delivery_price_kop: int = Field(default=0, sa_type=BigInteger)
    # «Купи и привези»: сколько курьер ФАКТИЧЕСКИ потратил на товар в магазине (может отличаться
    # от cod_amount_kop, заявленного при заказе). Получатель возвращает именно эту сумму. 0 = не задано.
    goods_actual_kop: int = Field(default=0, sa_type=BigInteger)
    # Получатель рассчитался с курьером (товар + доставка). Ставится при вручении buy_bring.
    settled: bool = False
    settled_at: Optional[datetime] = None
    # --- Ветка «что-то пошло не так» (аудит 2026-07-26) ---
    # Возврат: получателя нет / отказался / не выходит на связь. Курьер везёт посылку обратно.
    # Статусы: ... | returning (везу обратно) | returned (вернул отправителю).
    return_reason: str = Field(default="", max_length=200)   # почему возвращаем (видят обе стороны)
    returned_at: Optional[datetime] = None
    delivery_attempts: int = 0                                # сколько раз пытались вручить
    # Фото-фиксация на границах ответственности: «взял целой» / «отдал целой». Приватные URL
    # (/secure/evidence) — без них спор «ты разбил» ↔ «оно уже было» нерешаем ни для кого.
    pickup_photo_url: str = ""                                # фото при заборе у отправителя
    delivery_photo_url: str = ""                              # фото при вручении получателю
    # Компенсация курьеру за отмену «на полпути» (Модель А: только фиксируем сумму, деньги мимо нас).
    cancel_fee_kop: int = Field(default=0, sa_type=BigInteger)


class CourierApplication(SQLModel, table=True):
    """Заявка «Стать курьером Юлдаша» (профессия, как таксист; проверка Уровень 1).

    Режим «Курьер» — отдельная от «по пути» роль: одобренный курьер видит заказы courier/buy_bring
    и берёт их. Проверка L1 «между своими»: селфи с документом (сверка лица) + «кто пригласил»
    (invited_by по User.referred_by — доверие). Модерация вручную админом. Одна активная заявка
    на пользователя; после reject можно подать снова (обновляем строку в pending)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    transport: str = Field(default="car", max_length=16)     # car (легковой) | cargo (грузовой/каблук)
    status: str = Field(default="pending", index=True, max_length=16)  # pending | approved | rejected
    selfie_url: str = ""                                     # селфи с документом — сверка лица (L1)
    # Кто и на чём везёт (аудит 2026-07-26). Раньше «стать курьером» = селфи + выбор «легковой/
    # грузовой»: ни ФИО, ни номера машины, ни согласия с правилами. Человеку доверяли чужую
    # посылку, зная о нём меньше, чем о попутчике.
    full_name: str = Field(default="", max_length=120)        # ФИО как в документе (сверка с селфи)
    car_plate: str = Field(default="", max_length=16)         # госномер — по нему узнают машину
    rules_accepted: bool = False                              # согласие с правилами доставки
    rules_accepted_at: Optional[datetime] = None
    invited_by: Optional[int] = Field(default=None, foreign_key="user.id")  # кто пригласил (доверие «между своими»)
    reject_reason: str = ""                                  # причина отклонения (курьер увидит, подаст снова)
    created_at: datetime = Field(default_factory=utcnow)
    reviewed_at: Optional[datetime] = None


class CourierProfile(SQLModel, table=True):
    """Профиль курьера на линии (создаётся при approve заявки). Аналог DriverProfile для такси.

    online — на линии/не на линии. zone — где работает (city/intercity/region, как у таксиста).
    car_class — тип транспорта (car|cargo, из заявки). work_city/work_direction_id — привязка зоны."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True, foreign_key="user.id")
    online: bool = Field(default=False, index=True)
    car_class: str = Field(default="car", max_length=16)     # car | cargo
    zone: str = Field(default="city", max_length=16)         # city | district (legacy: intercity | region)
    work_city: str = Field(default="", max_length=80)        # zone=city: «мой город/село» (name_ru)
    work_district: Optional[str] = Field(default=None, max_length=80)   # zone=district: «Абзелиловский р-н»
    work_intercity: bool = False          # выезд загород (заказы, где вторая точка вне базы)
    work_regions: bool = False            # готов в соседние регионы
    work_direction_id: Optional[int] = Field(default=None, foreign_key="settlement.id")  # закреплённое направление
    updated_at: datetime = Field(default_factory=utcnow)
    # --- C3: мягкая лестница качества (без жёстких авто-блоков, «по-соседски») ---
    # Дедуп тёплого пуш-совета при просевшем рейтинге (не чаще раза в неделю).
    low_rating_advice_at: Optional[datetime] = None
    # Мягкая пауза курьера (очень низкий рейтинг/тяжёлые споры) — курьер не берёт заказы до даты.
    # None = не на паузе. Ставится мягко и на короткий срок; аккаунт остаётся (вечен).
    paused_until: Optional[datetime] = None


class AnalyticsEvent(SQLModel, table=True):
    """Анонимное продуктовое событие (воронка/метрики веб-версии). БЕЗ ЛИЧНОСТИ:
    ни user_id, ни телефона, ни имени, ни точных координат. client_id — случайный id
    браузера (генерит сам клиент), к аккаунту не привязан.

    Приём — POST /events (без обязательной авторизации: веб шлёт события до логина).
    context_json — безопасные props в JSON: сервер САМ вырезает потенциально
    чувствительные ключи (телефон/имя/координаты/токены/…) и обрезает длинные строки
    ДО записи (см. routers/events.sanitize_props). Эфемерно по смыслу — чистится
    ретеншеном (app/cleanup.py), аккаунты и репутация этим не затрагиваются."""
    id: Optional[int] = Field(default=None, primary_key=True)
    event: str = Field(default="", max_length=64)              # имя события (напр. "web_open", "role_pick")
    client_id: str = Field(default="", index=True, max_length=64)  # анонимный id браузера
    ts: Optional[int] = Field(default=None, sa_type=BigInteger)  # клиентское время события (epoch ms), опц. BigInteger — не влезает в int4 Postgres
    context_json: str = Field(default="")                     # безопасные props (JSON, после чистки)
    created_at: datetime = Field(default_factory=utcnow, index=True)
    # Сводка воронки для админа: WHERE created_at >= since GROUP BY event — композит покрывает.
    __table_args__ = (Index("ix_analyticsevent_created_event", "created_at", "event"),)


class PriceComplaint(SQLModel, table=True):
    """«Что-то не так с ценой» — жалоба человека на сумму, с самой суммой внутри.

    Зачем отдельная таблица, а не общий разбор (`Report`). Разбор — про ЛЮДЕЙ: нахамил,
    не заплатил, опасно вёл. Здесь второй стороны нет вообще: человек спорит с нашим
    расчётом. Свести это в жалобу на водителя значит обвинить того, кто ни при чём.

    Жалоба принимается и ДО заказа — обычно именно там она и рождается: человек увидел
    450 ₽ и закрыл приложение. Поэтому `order_id` необязательный.

    Приватность (§8): координат тут нет и быть не может. Храним только числа расчёта
    (`breakdown_json` — то же, что человек видел на экране: поездка, подача, опции, наценка)
    и выбранную причину. Адрес, откуда он ехал, для разбора цены не нужен.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    order_id: Optional[int] = Field(default=None, index=True, foreign_key="instantorder.id")
    price: int = 0                                    # сумма, на которую жалуются, ₽
    reason: str = Field(default="other", max_length=32)   # перечень — instant.PRICE_COMPLAINT_REASONS
    comment: str = Field(default="", max_length=500)
    breakdown_json: str = Field(default="")           # строки счёта, как их видел человек
    created_at: datetime = Field(default_factory=utcnow, index=True)
    # Админ посмотрел и закрыл. Отдельного статуса не заводим: жалоба на цену — это сигнал,
    # а не дело с участниками; её либо учли, либо нет.
    handled_at: Optional[datetime] = None


class PromoClaimLog(SQLModel, table=True):
    """След «этот номер уже брал промокод» — переживает удаление аккаунта.

    Зачем (аудит 2026-08-08, волна 149). «Один код на всю жизнь аккаунта» защищено уникальным
    индексом по человеку. Но удаление аккаунта уносит и эту запись — а номер остаётся тем же.
    Проверено пробой: вошёл по SMS, применил код на 300 ₽, съездил, удалил аккаунт, вошёл снова
    с ТЕМ ЖЕ номером и с того же телефона — и код принялся заново. Три круга подряд.

    Каждый круг — прямые деньги: скидку по промокоду оплачивает платформа, водитель получает
    своё полностью. Если водитель в сговоре, это готовый канал обналички с одного номера.

    Что храним: ключ телефона (только цифры, как в `_phone_key`) и отметку устройства — этого
    хватает, чтобы узнать повтор. Самого номера здесь нет: вторая копия чужого телефона в базе
    не нужна, а по ключу человека не найти, если не знать номер заранее.

    Живёт дольше аккаунта намеренно — в этом весь смысл. Чистится по сроку кампании
    (`cleanup.py`), потому что вечно помнить незачем: кампания заканчивается.
    """
    id: Optional[int] = Field(default=None, primary_key=True)
    promo_id: int = Field(index=True, foreign_key="promocode.id")
    phone_key: str = Field(default="", index=True)
    device_id: str = Field(default="", index=True)
    created_at: datetime = Field(default_factory=utcnow, index=True)
