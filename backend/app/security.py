import hashlib
import random
import re
import secrets
import string
from datetime import timedelta

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlmodel import Session, select

from .config import settings
from .db import get_session
from .models import RefreshToken, User
from .timeutil import utcnow

bearer = HTTPBearer(auto_error=True)

# Telegram-плейсхолдер tg<id>: ставится при входе, пока юзер не поделился реальным
# номером. Реальный номер обязателен (безопасность / защита от мошенников).
_TG_PLACEHOLDER_RE = re.compile(r"^tg\d+$")


def is_placeholder_phone(phone) -> bool:
    """True, если номер ещё не задан или это Telegram-плейсхолдер tg<id> (не реальный)."""
    return not phone or bool(_TG_PLACEHOLDER_RE.match(phone))


def make_token(user_id: int) -> str:
    """Короткоживущий access-токен (JWT)."""
    now = utcnow()
    exp = now + timedelta(minutes=settings.access_expire_min)
    # iat — момент выпуска (дробные секунды, чтобы не было гонки login→logout→login
    # в пределах одной секунды). По нему отсекаем токены, выпущенные до logout.
    return jwt.encode(
        {"sub": str(user_id), "iat": now.timestamp(), "exp": exp},
        settings.jwt_secret, algorithm="HS256",
    )


def _hash_refresh(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


def issue_tokens(session: Session, user_id: int) -> dict:
    """Выдать пару access+refresh. Refresh — непрозрачный, в БД лежит ХЕШ."""
    raw = secrets.token_urlsafe(48)
    session.add(RefreshToken(
        user_id=user_id, token_hash=_hash_refresh(raw),
        expires_at=utcnow() + timedelta(days=settings.refresh_expire_days),
    ))
    session.commit()
    return {"access_token": make_token(user_id), "refresh_token": raw, "token_type": "bearer"}


def rotate_refresh(session: Session, raw: str) -> dict:
    """Проверить refresh, ОТОЗВАТЬ его (one-time) и выдать новую пару. Иначе 401."""
    rt = session.exec(select(RefreshToken).where(RefreshToken.token_hash == _hash_refresh(raw))).first()
    if not rt or rt.revoked or rt.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    rt.revoked = True                       # ротация: старый refresh больше не работает
    session.add(rt)
    session.commit()
    return issue_tokens(session, rt.user_id)


def revoke_all_refresh(session: Session, user_id: int) -> None:
    """Отозвать все refresh-токены пользователя (logout со всех устройств)."""
    for rt in session.exec(select(RefreshToken).where(
        RefreshToken.user_id == user_id, RefreshToken.revoked == False  # noqa: E712
    )).all():
        rt.revoked = True
        session.add(rt)
    session.commit()


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
    # Номер обязателен: без реального номера приложение не работает (защита от мошенников).
    if is_placeholder_phone(user.phone):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "phone_required")
    return user
