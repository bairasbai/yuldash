"""Заявки пассажира + матчинг заявки с поездками (см. backend.md §4)."""
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import Ride, RideCategory, RideRequest, RideStatus, User
from ..security import current_user

router = APIRouter(tags=["requests"])


class RequestIn(BaseModel):
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    comment: str = Field("", max_length=2000)
    for_relative_name: Optional[str] = Field(None, max_length=120)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


@router.post("/requests", response_model=RideRequest)
def create_request(body: RequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = RideRequest(passenger_id=user.id, **body.model_dump())
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


@router.get("/requests/mine", response_model=List[RideRequest])
def my_requests(
    limit: Optional[int] = None,        # пагинация (опц., None = все — обратносовместимо)
    offset: int = 0,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    q = select(RideRequest).where(RideRequest.passenger_id == user.id)
    if limit is not None:   # порядок добавляем только при пагинации (дефолт — как было)
        q = q.order_by(RideRequest.id.desc()).offset(max(0, offset)).limit(max(1, min(limit, 200)))
    return session.exec(q).all()


@router.get("/match/rides", response_model=List[Ride])
def match_rides(request_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id:
        raise HTTPException(403, "Нет доступа к этой заявке")
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.from_city.contains(req.from_city),
        Ride.to_city.contains(req.to_city),
        Ride.seats_left >= req.seats,
        Ride.category == req.category,
    )
    return session.exec(q.order_by(Ride.depart_at)).all()
