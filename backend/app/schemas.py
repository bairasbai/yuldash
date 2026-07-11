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
    from_city: str = Field(..., max_length=120)   # города — короткие; отсекаем мусорную строку в БД
    to_city: str = Field(..., max_length=120)
    depart_at: datetime
    seats_total: int = 3   # диапазон 1..8 клампится в create_ride («клампим, а не падаем»)
    price: int = 0         # диапазон 0..100000 клампится в create_ride
    category: RideCategory = RideCategory.regular
    comment: str = Field("", max_length=2000)
    pickup: str = Field("", max_length=500)
    pickup_lat: Optional[float] = Field(None, ge=-90, le=90)      # валидные координаты (не NaN/мусор)
    pickup_lng: Optional[float] = Field(None, ge=-180, le=180)
    pickup_point_id: Optional[int] = None   # F14: выбрана известная точка сбора из справочника → привязать
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    only_trusted: bool = False        # «только для своих» — поездку увидят/забронируют лишь L3
    recurrence: str = "none"          # none / daily / weekdays / weekly
    receiver_name: Optional[str] = Field(None, max_length=120)   # посылка: кому отдать
    parcel_size: Optional[str] = Field(None, max_length=80)      # посылка: габарит/вес


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
    receiver_name: Optional[str] = None   # посылка: кому отдать
    parcel_size: Optional[str] = None     # посылка: габарит/вес
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    only_trusted: bool = False        # «только для своих» — клиент рисует бейдж «круг своих»
    status: RideStatus
    boosted: bool = False             # активный Boost (для подсветки/бейджа на клиенте)
    driver_name: str
    driver_rating: float
    driver_verified: bool
    driver_car: str
    driver_avatar: str = ""
    driver_online: bool = False
    # F8 «Стаж своего»: вычисляемые бейджи доверия (агрегаты, без новых таблиц).
    driver_trips: int = 0        # завершённых поездок водителя (done-брони, distinct поездок) → бейдж «N поездок»
    driver_since: str = ""       # месяц регистрации водителя "YYYY-MM" → бейдж «С нами с …»
    # Деликатный opt-in сигнал: водитель указала пол «женщина». True только для female;
    # male и «не указан» дают False (мужской пол не выпячиваем, приватность водителя).
    driver_is_woman: bool = False
