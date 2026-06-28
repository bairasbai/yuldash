import random
import string
from datetime import timedelta

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlmodel import Session

from .config import settings
from .db import get_session
from .models import User
from .timeutil import utcnow

bearer = HTTPBearer(auto_error=True)


def make_token(user_id: int) -> str:
    now = utcnow()
    exp = now + timedelta(minutes=settings.jwt_expire_min)
    # iat — момент выпуска (дробные секунды, чтобы не было гонки login→logout→login
    # в пределах одной секунды). По нему отсекаем токены, выпущенные до logout.
    return jwt.encode(
        {"sub": str(user_id), "iat": now.timestamp(), "exp": exp},
        settings.jwt_secret, algorithm="HS256",
    )


def verify_token(token: str) -> int:
    """Декодирует JWT, возвращает user_id. Бросает исключение при невалидном токене.
    Используется WebSocket-чатом (там нет Depends/HTTPBearer)."""
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    return int(payload["sub"])


def _token_revoked(payload: dict, user: User) -> bool:
    """Токен недействителен, если выпущен ДО `user.tokens_valid_from` (logout/ревокация).
    Старые токены без `iat` — пропускаем (обратная совместимость)."""
    if not user.tokens_valid_from:
        return False
    iat = payload.get("iat")
    if iat is None:
        return False
    return float(iat) < user.tokens_valid_from.timestamp()


def gen_otp() -> str:
    return "".join(random.choices(string.digits, k=4))


def current_user(
    cred: HTTPAuthorizationCredentials = Depends(bearer),
    session: Session = Depends(get_session),
) -> User:
    try:
        payload = jwt.decode(cred.credentials, settings.jwt_secret, algorithms=["HS256"])
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Неверный токен")
    user = session.get(User, user_id)
    if not user:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Пользователь не найден")
    if _token_revoked(payload, user):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Сессия завершена. Войди заново.")
    return user
