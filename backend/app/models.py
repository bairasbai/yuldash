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
    docs_status: str = "pending"


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
