"""Pydantic-схемы, общие для нескольких роутеров/сервисов.

`RideOut` витрина поездки используется и в `services` (батч-сборка против N+1),
и в роутерах `rides`. Чтобы не плодить циклический импорт — держим её здесь.
Узко-локальные In-схемы остаются внутри своих роутеров.
"""
from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field

from .models import RideCategory, RideStatus


class RideIn(BaseModel):
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = Field("", max_length=2000)
    pickup: str = Field("", max_length=500)
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    recurrence: str = "none"          # none / daily / weekdays / weekly


class RideOut(BaseModel):
    """Поездка + витрина водителя (имя/рейтинг/авто) — чтобы приложение рисовало карточку."""
    id: int
    driver_id: int
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int
    seats_left: int
    price: int
    category: RideCategory
    comment: str
    pickup: str = ""
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    status: RideStatus
    boosted: bool = False             # активный Boost (для подсветки/бейджа на клиенте)
    driver_name: str
    driver_rating: float
    driver_verified: bool
    driver_car: str
    driver_avatar: str = ""
