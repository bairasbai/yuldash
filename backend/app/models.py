from datetime import datetime
from enum import Enum
from typing import Optional

from sqlmodel import SQLModel, Field

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
    role: UserRole = UserRole.passenger
    language: str = "ru"
    verified: bool = False
    # OAuth / Социальные сети
    telegram_id: Optional[str] = Field(default=None, index=True, unique=True)
    vk_id: Optional[str] = Field(default=None, index=True, unique=True)
    whatsapp_verified: bool = False
    created_at: datetime = Field(default_factory=datetime.utcnow)


class OtpCode(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    phone: str = Field(index=True)
    code: str
    expires_at: datetime


class DeviceToken(SQLModel, table=True):
    """FCM-токен устройства пользователя (для push). Один пользователь — несколько устройств."""
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    token: str = Field(index=True, unique=True)
    created_at: datetime = Field(default_factory=datetime.utcnow)


class TgAuth(SQLModel, table=True):
    """Сессия входа через Telegram-бота: request_id ↔ telegram_id ↔ 4-значный код."""
    id: Optional[int] = Field(default=None, primary_key=True)
    request_id: str = Field(index=True, unique=True)
    telegram_id: Optional[str] = None
    username: str = ""
    first_name: str = ""
    code: Optional[str] = None
    status: str = "waiting"          # waiting (ждём Старт) / sent (код отправлен) / used
    attempts: int = 0                # попыток ввода кода (защита от перебора)
    created_at: datetime = Field(default_factory=datetime.utcnow)
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


class Ride(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    driver_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    depart_at: datetime
    seats_total: int = 3
    seats_left: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = ""
    pickup: str = ""                  # где водитель забирает (точка сбора, текст)
    pickup_lat: Optional[float] = None  # координаты точки сбора (пин на карте)
    pickup_lng: Optional[float] = None
    recurrence: str = "none"          # none / daily / weekdays / weekly — регулярная поездка
    # Премиум-предпочтения поездки (двусторонний фильтр водитель↔пассажир)
    pets_allowed: bool = False        # можно с животными
    child_seat: bool = False          # есть детское кресло/бустер
    women_only: bool = False          # только женщины
    smoking: bool = False             # курение разрешено
    baggage: bool = False             # есть место под багаж
    air_conditioner: bool = False     # кондиционер
    status: RideStatus = RideStatus.active
    created_at: datetime = Field(default_factory=datetime.utcnow)


class RideRequest(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    passenger_id: int = Field(index=True, foreign_key="user.id")
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    comment: str = ""
    for_relative_name: Optional[str] = None
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосовой заявки
    status: str = "active"
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Booking(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    ride_id: int = Field(index=True, foreign_key="ride.id")
    passenger_id: int = Field(index=True, foreign_key="user.id")
    seats: int = 1
    price: int = 0
    status: BookingStatus = BookingStatus.pending
    boarding_code: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


# ---- Фаза 2: чат, семья, безопасность ----

class Message(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True, foreign_key="booking.id")
    sender_id: int = Field(foreign_key="user.id")
    text: str = ""
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосового
    created_at: datetime = Field(default_factory=datetime.utcnow)


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
    created_at: datetime = Field(default_factory=datetime.utcnow)


class SosEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    booking_id: Optional[int] = Field(default=None, foreign_key="booking.id")
    category: str = "other"                 # medical / breakdown / other
    note: str = ""
    status: str = "open"                    # open / handled
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Report(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    reporter_id: int = Field(index=True, foreign_key="user.id")
    target_user_id: int = Field(foreign_key="user.id")
    reason: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Block(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, foreign_key="user.id")
    blocked_user_id: int = Field(foreign_key="user.id")
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Rating(SQLModel, table=True):
    """Оценка после поездки: rater оценил ratee (1..5 звёзд). Одна на (booking, rater)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True, foreign_key="booking.id")
    rater_id: int = Field(index=True, foreign_key="user.id")        # кто оценил
    ratee_id: int = Field(index=True, foreign_key="user.id")        # кого оценили (водитель или пассажир)
    stars: int = 5                           # 1..5
    created_at: datetime = Field(default_factory=datetime.utcnow)


class AdEvent(SQLModel, table=True):
    """Событие по рекламе: показ или клик (для реальной статистики кабинета)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    ad_id: str = Field(index=True)
    event_type: str = Field(index=True)      # impression / click
    created_at: datetime = Field(default_factory=datetime.utcnow)
