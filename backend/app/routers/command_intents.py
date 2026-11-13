"""Сверка намерения с сервером и закрытие команды, которая ещё не исполнилась."""

from typing import Annotated

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, Field, model_validator
from sqlmodel import Session

from ..db import get_session
from ..idempotency import Command, Scope, close_intent, read_intent, validate_key
from ..models import User
from ..security import current_user


router = APIRouter(prefix="/command-intents", tags=["command-intents"])


class CloseIn(BaseModel):
    scope: Scope
    target_id: Annotated[int, Field(gt=0, le=2_147_483_647)] | None = None
    payload_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]

    @model_validator(mode="after")
    def validate_target(self) -> "CloseIn":
        if (self.target_id is None) != (self.scope is Scope.RIDE_CREATE):
            raise ValueError("target_id must be absent only for ride.create")
        return self


@router.get("/{key}")
def get_intent(key: str, response: Response, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    response.headers["Cache-Control"] = "no-store"
    return read_intent(session, user.id, key)


@router.post("/{key}/close")
def close(key: str, body: CloseIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    command = Command(validate_key(key), body.scope, body.target_id, body.payload_hash)
    return close_intent(session, user.id, command)
