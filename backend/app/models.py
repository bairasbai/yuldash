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
    created_at: datetime = Field(default_factory=datetime.utcnow)


class OtpCode(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    phone: str = Field(index=True)
    code: str
    expires_at: datetime


class DriverProfile(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True, unique=True)
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
    driver_id: int = Field(index=True)
    from_city: str = Field(index=True)
    to_city: str = Field(index=True)
    depart_at: datetime
    seats_total: int = 3
    seats_left: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = ""
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
    passenger_id: int = Field(index=True)
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
    ride_id: int = Field(index=True)
    passenger_id: int = Field(index=True)
    seats: int = 1
    price: int = 0
    status: BookingStatus = BookingStatus.pending
    boarding_code: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


# ---- Фаза 2: чат, семья, безопасность ----

class Message(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True)
    sender_id: int
    text: str = ""
    voice_url: Optional[str] = None
    transcript: Optional[str] = None        # расшифровка голосового
    created_at: datetime = Field(default_factory=datetime.utcnow)


class TrustedContact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True)
    name: str
    relation: str = ""
    phone: str = ""
    notify_by_default: bool = True


class TripShare(SQLModel, table=True):
    """Поездка, расшаренная близкому (семейный контроль)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True)
    contact_id: int
    last_status: str = "shared"             # shared / sat / arrived / done
    created_at: datetime = Field(default_factory=datetime.utcnow)


class SosEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True)
    booking_id: Optional[int] = None
    category: str = "other"                 # medical / breakdown / other
    note: str = ""
    status: str = "open"                    # open / handled
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Report(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    reporter_id: int = Field(index=True)
    target_user_id: int
    reason: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Block(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    user_id: int = Field(index=True)
    blocked_user_id: int
    created_at: datetime = Field(default_factory=datetime.utcnow)


class Rating(SQLModel, table=True):
    """Оценка после поездки: rater оценил ratee (1..5 звёзд). Одна на (booking, rater)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    booking_id: int = Field(index=True)
    rater_id: int = Field(index=True)        # кто оценил
    ratee_id: int = Field(index=True)        # кого оценили (водитель или пассажир)
    stars: int = 5                           # 1..5
    created_at: datetime = Field(default_factory=datetime.utcnow)


class AdEvent(SQLModel, table=True):
    """Событие по рекламе: показ или клик (для реальной статистики кабинета)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    ad_id: str = Field(index=True)
    event_type: str = Field(index=True)      # impression / click
    created_at: datetime = Field(default_factory=datetime.utcnow)
