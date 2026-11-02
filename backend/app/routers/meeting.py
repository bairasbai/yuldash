"""RS-03 Б: смена точного места встречи/высадки — три команды с CAS по версии.

Пассажир предлагает (pending — применяется сразу; confirmed/onboard — предложение, пока
действует прежняя точка), водитель отвечает («да» — точка применяется; «нет» — прежняя
остаётся), пассажир может отозвать своё предложение. Каждая запись — условный UPDATE по
`meet_version` + актуальному статусу + прежнему JSON предложения: 0 строк → 409
`meeting_point_stale`, поздний ответ чужого предложения ничего не портит. Повтор того же
`proposal_id` с тем же содержимым — без побочек (`already: true`), с другим — 409.
Уведомления — только после commit победившего перехода, без координат и пояснений.
"""
from __future__ import annotations

import json
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import update
from sqlmodel import Session, select

from .. import meeting_points
from ..config import settings
from ..db import get_session
from ..errors import herr
from ..meeting_points import MeetingIn
from ..models import Booking, BookingStatus, Ride, User
from ..security import current_user
from ..services import booking_and_ride_for_user, push_notification
from ..timeutil import utcnow

router = APIRouter(tags=["meeting"])

LIVE = (BookingStatus.confirmed, BookingStatus.onboard)


class ProposeIn(BaseModel):
    kind: str                          # "pickup" | "dropoff"
    point: Optional[MeetingIn] = None  # None — снять свою точку (как clear)
    base_version: int
    # Повтор своего же — без побочек; клиент без id — сервер даст. UUID hex — 32, потолок с запасом.
    proposal_id: Optional[str] = Field(None, max_length=40)


class AnswerIn(BaseModel):
    proposal_id: str = Field(max_length=40)
    base_version: int
    accept: bool


class WithdrawIn(BaseModel):
    proposal_id: str = Field(max_length=40)
    base_version: int


def _dto(session, booking_id: int, ride: Ride, viewer_id: int, **extra):
    booking = session.get(Booking, booking_id)   # свежее из базы, не старый ORM
    return {**meeting_points.booking_public_dto(session, booking, ride, viewer_id), **extra}


def _relock(session, booking_id: int, ride_id: int) -> tuple[Booking, Ride]:
    """Строки под замок после модерации (она коммитит сессию и снимает FOR UPDATE).
    Порядок Ride → Booking — единый с остальными операциями брони (V4, bookings.py)."""
    ride = session.exec(select(Ride).where(Ride.id == ride_id)
                        .with_for_update().execution_options(populate_existing=True)).first()
    booking = session.exec(select(Booking).where(Booking.id == booking_id)
                           .with_for_update().execution_options(populate_existing=True)).first()
    return booking, ride


def _push(session, user_id: int, title_ru: str, title_ba: str, body_ru: str, body_ba: str,
          booking_id: int):
    push_notification(session, user_id, "booking", title_ru, title_ba, body_ru, body_ba,
                      ref_kind="booking", ref_id=booking_id)


@router.post("/bookings/{booking_id}/meeting-point")
def propose(body: ProposeIn, booking_id: int,
            user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Менять место может только пассажир", "Урынды тик пассажир үҙгәртә ала")
    status = booking.status
    if status in (BookingStatus.done, BookingStatus.cancelled):
        raise herr(409, "Поездка закрыта — место уже не меняется",
                   "Сәфәр ябыҡ — урын инде үҙгәртелмәй", code="meeting_point_closed")
    if body.kind not in ("pickup", "dropoff"):
        raise herr(400, "Место бывает «посадка» или «высадка»", "Урын «ултырыу» йәки «төшөү» була")
    if status == BookingStatus.onboard and body.kind == "pickup":
        raise herr(409, "Ты уже в машине — место посадки не меняется",
                   "Һин инде машиналаһың — ултырыу урыны үҙгәртелмәй", code="meeting_point_onboard")
    # Валидация точки (модерация пояснения может закоммитить сессию — перезахват ниже).
    stop_name = (booking.pickup_stop or "") if body.kind == "pickup" else (booking.dropoff_stop or "")
    point = meeting_points.validate_meeting(session, ride, body.kind, body.point,
                                            stop_name=stop_name, user_id=user.id, booking_id=booking.id)
    booking, ride = _relock(session, booking_id, ride.id)
    status = booking.status
    if status in (BookingStatus.done, BookingStatus.cancelled):
        raise herr(409, "Поездка закрыта — место уже не меняется",
                   "Сәфәр ябыҡ — урын инде үҙгәртелмәй", code="meeting_point_closed")
    if status == BookingStatus.onboard and body.kind == "pickup":
        raise herr(409, "Ты уже в машине — место посадки не меняется",
                   "Һин инде машиналаһың — ултырыу урыны үҙгәртелмәй", code="meeting_point_onboard")
    stop_name = (booking.pickup_stop or "") if body.kind == "pickup" else (booking.dropoff_stop or "")
    point = meeting_points.validate_meeting(session, ride, body.kind, body.point,
                                            stop_name=stop_name, user_id=user.id,
                                            booking_id=booking.id, skip_moderation=True)
    # Повтор своего же предложения: тот же id и то же содержимое — без побочек.
    if status in LIVE and booking.meet_proposal:
        try:
            old = json.loads(booking.meet_proposal)
        except (ValueError, TypeError):
            old = None
        if old and body.proposal_id and old.get("proposal_id") == body.proposal_id:
            new_body = _proposal_body(body.kind, body.point, point)
            if old.get("point") == new_body["point"] and old.get("kind") == body.kind:
                return _dto(session, booking_id, ride, user.id, already=True)
            raise herr(409, "Место только что изменилось — посмотри ещё раз",
                       "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    if status == BookingStatus.pending:
        prefix = "meet_" if body.kind == "pickup" else "alight_"
        stored = tuple(getattr(booking, prefix + field) for field in ("lat", "lng", "note", "landmark_id"))
        if meeting_points.same_point(stored, point):
            return _dto(session, booking_id, ride, user.id, already=True)
    if booking.meet_changes >= settings.meeting_point_max_changes and not (body.point and body.point.clear):
        raise herr(409, "Место уже меняли много раз — договорись в чате",
                   "Урын күп тапҡыр үҙгәртелде инде — чатта килеш", code="meeting_point_changes_limit")
    now = utcnow().isoformat()
    if status == BookingStatus.pending:
        # Ждёт подтверждения — применяем сразу (водитель ещё ничего не обещал).
        values = _apply_values(body.kind, point)
        values["meet_version"] = booking.meet_version + 1
        values["meet_changes"] = booking.meet_changes + 1
        values["meet_proposal"] = ""
        result = session.execute(
            update(Booking).where(Booking.id == booking.id,
                                  Booking.meet_version == body.base_version,
                                  Booking.status == BookingStatus.pending)
            .values(**values))
        if result.rowcount == 0:
            raise herr(409, "Место только что изменилось — посмотри ещё раз",
                       "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
        session.commit()
        return _dto(session, booking_id, ride, user.id)
    # confirmed/onboard — предложение; действует прежняя точка.
    proposal_id = body.proposal_id or meeting_points.new_proposal_id()
    proposal = _proposal_body(body.kind, body.point, point)
    proposal.update({"proposal_id": proposal_id, "kind": body.kind,
                     "created_at": now, "author_id": user.id})
    result = session.execute(
        update(Booking).where(Booking.id == booking.id,
                              Booking.meet_version == body.base_version,
                              Booking.status.in_((BookingStatus.confirmed,) if body.kind == "pickup" else LIVE),
                              Booking.meet_proposal == booking.meet_proposal)
        .values(meet_proposal=json.dumps(proposal, ensure_ascii=False, sort_keys=True),
                meet_version=booking.meet_version + 1,
                meet_changes=booking.meet_changes + 1))
    if result.rowcount == 0:
        raise herr(409, "Место только что изменилось — посмотри ещё раз",
                   "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    session.commit()
    _push(session, ride.driver_id, "Новое предложение", "Яңы тәҡдим",
          "Пассажир предлагает другое место встречи", "Пассажир башҡа осрашыу урынын тәҡдим итә", booking_id)
    return _dto(session, booking_id, ride, user.id)


@router.post("/bookings/{booking_id}/meeting-point/answer")
def answer(body: AnswerIn, booking_id: int,
           user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise herr(403, "Отвечать на предложение может только водитель",
                   "Тәҡдимгә тик йөрөтөүсе яуап бирә ала")
    booking, ride = _relock(session, booking_id, ride.id)
    if not booking.meet_proposal:
        return _dto(session, booking_id, ride, user.id, already=True)
    try:
        proposal = json.loads(booking.meet_proposal)
    except (ValueError, TypeError):
        proposal = None
    if not proposal or proposal.get("proposal_id") != body.proposal_id:
        raise herr(409, "Место только что изменилось — посмотри ещё раз",
                   "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    kind = proposal.get("kind")
    if booking.status == BookingStatus.onboard and kind != "dropoff":
        raise herr(409, "Пассажир уже в машине — меняется только высадка",
                   "Пассажир инде машинала — тик төшөү урыны үҙгәрә", code="meeting_point_onboard")
    if booking.status not in LIVE:
        raise herr(409, "Поездка закрыта — место уже не меняется",
                   "Сәфәр ябыҡ — урын инде үҙгәртелмәй", code="meeting_point_closed")
    values = {"meet_version": booking.meet_version + 1, "meet_proposal": ""}
    if body.accept:
        values.update(_apply_values_from_proposal(kind, proposal))
    result = session.execute(
        update(Booking).where(Booking.id == booking.id,
                              Booking.meet_version == body.base_version,
                              Booking.status.in_(LIVE),
                              Booking.meet_proposal == booking.meet_proposal)
        .values(**values))
    if result.rowcount == 0:
        raise herr(409, "Место только что изменилось — посмотри ещё раз",
                   "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    session.commit()
    if body.accept:
        _push(session, booking.passenger_id, "Ответ водителя", "Йөрөтөүсөнөң яуабы",
              "Водитель согласился на новое место", "Йөрөтөүсе яңы урынға риҙа", booking_id)
    else:
        _push(session, booking.passenger_id, "Ответ водителя", "Йөрөтөүсөнөң яуабы",
              "Водитель оставил прежнее место — договоритесь в чате", "Йөрөтөүсе иҫке урынды ҡалдырҙы — чатта килешегеҙ", booking_id)
    return _dto(session, booking_id, ride, user.id)


@router.post("/bookings/{booking_id}/meeting-point/withdraw")
def withdraw(body: WithdrawIn, booking_id: int,
             user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise herr(403, "Отозвать предложение может только пассажир",
                   "Тәҡдимде тик пассажир кире ала ала")
    booking, ride = _relock(session, booking_id, ride.id)
    if booking.status not in LIVE:
        raise herr(409, "Поездка закрыта — место уже не меняется",
                   "Сәфәр ябыҡ — урын инде үҙгәртелмәй", code="meeting_point_closed")
    if not booking.meet_proposal:
        return _dto(session, booking_id, ride, user.id, already=True)
    try:
        proposal = json.loads(booking.meet_proposal)
    except (ValueError, TypeError):
        proposal = None
    if not proposal or proposal.get("proposal_id") != body.proposal_id:
        raise herr(409, "Место только что изменилось — посмотри ещё раз",
                   "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    if booking.status == BookingStatus.onboard and proposal.get("kind") != "dropoff":
        raise herr(409, "Ты уже в машине — отзывается только высадка",
                   "Һин инде машиналаһың — тик төшөү урыны кире алына", code="meeting_point_onboard")
    result = session.execute(
        update(Booking).where(Booking.id == booking.id,
                              Booking.meet_version == body.base_version,
                              Booking.status.in_(LIVE),
                              Booking.meet_proposal == booking.meet_proposal)
        .values(meet_proposal="", meet_version=booking.meet_version + 1))
    if result.rowcount == 0:
        raise herr(409, "Место только что изменилось — посмотри ещё раз",
                   "Урын хәҙер генә үҙгәрҙе — тағы ҡара", code="meeting_point_stale")
    session.commit()
    _push(session, ride.driver_id, "Предложение отозвано", "Тәҡдим кире алынды",
          "Пассажир отозвал предложение о месте", "Пассажир урын тәҡдимен кире алды", booking_id)
    return _dto(session, booking_id, ride, user.id)


def _proposal_body(kind: str, raw: Optional[MeetingIn], validated: Optional[tuple]) -> dict:
    """Публичное тело предложения: точка уже проверена; clear — «снять свою точку»."""
    if raw is not None and raw.clear:
        return {"point": {"clear": True}}
    if validated is None:
        return {"point": {"clear": True}}
    lat, lng, note, landmark_id = validated
    return {"point": {"lat": lat, "lng": lng, "note": note, "landmark_id": landmark_id, "clear": False}}


def _apply_values(kind: str, validated: Optional[tuple]) -> dict:
    """Колонки точки для прямого применения (pending или «да» водителя)."""
    prefix = "meet_" if kind == "pickup" else "alight_"
    if validated is None:
        return {prefix + "lat": None, prefix + "lng": None, prefix + "note": "", prefix + "landmark_id": None}
    lat, lng, note, landmark_id = validated
    return {prefix + "lat": lat, prefix + "lng": lng, prefix + "note": note, prefix + "landmark_id": landmark_id}


def _apply_values_from_proposal(kind: str, proposal: dict) -> dict:
    p = proposal.get("point") or {}
    if p.get("clear"):
        return _apply_values(kind, None)
    return _apply_values(kind, (p.get("lat"), p.get("lng"), p.get("note") or "", p.get("landmark_id")))
