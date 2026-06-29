import hashlib
import random
import re
import secrets
import string
from datetime import timedelta
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlmodel import Session, select

from .config import settings
from .db import get_session
from .models import RefreshToken, User
from .timeutil import utcnow

bearer = HTTPBearer(auto_error=True)
bearer_optional = HTTPBearer(auto_error=False)   # для публичных списков: токен есть → знаем юзера, нет → аноним

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
    # iat — момент выпуска (с дробными секундами). По нему отсекаем токены, выпущенные
    # ≤ tokens_valid_from (logout). От гонки login→logout→login в одной миллисекунде
    # (часы Windows дают одинаковое значение соседним вызовам) защищает не точность iat,
    # а сброс метки при входе (issue_tokens) + нестрогое сравнение в _token_revoked.
    return jwt.encode(
        {"sub": str(user_id), "iat": now.timestamp(), "exp": exp},
        settings.jwt_secret, algorithm="HS256",
    )


def _hash_refresh(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


def issue_tokens(session: Session, user_id: int) -> dict:
    """Выдать пару access+refresh. Refresh — непрозрачный, в БД лежит ХЕШ.

    Свежая выдача токенов = начало валидной сессии: сбрасываем метку ревокации
    `tokens_valid_from`. Без этого токен, выпущенный в ту же миллисекунду, что и
    предыдущий logout (часы дают одинаковое значение для соседних вызовов —
    особенно на Windows), мог бы оказаться «отозванным» сразу после входа."""
    user = session.get(User, user_id)
    if user and user.tokens_valid_from is not None:
        user.tokens_valid_from = None
        session.add(user)
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
    ВНИМАНИЕ: не проверяет ревокацию/существование юзера — для авторизации соединений
    используй `authenticate_ws` (ниже)."""
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    return int(payload["sub"])


def authenticate_ws(token: str, session: Session) -> User:
    """Аутентификация для WebSocket (там нет Depends/HTTPBearer).
    В отличие от `verify_token`, ПРОВЕРЯЕТ существование юзера и ревокацию сессии
    (`tokens_valid_from`/logout) — той же логикой, что REST `current_user`. Иначе токен,
    отозванный через logout, продолжал бы открывать чат до самого истечения JWT."""
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    user = session.get(User, int(payload["sub"]))
    if not user or _token_revoked(payload, user):
        raise JWTError("token revoked or user missing")
    return user


def _token_revoked(payload: dict, user: User) -> bool:
    """Токен недействителен, если выпущен В МОМЕНТ `user.tokens_valid_from` или ДО него
    (logout/ревокация). Сравнение нестрогое (`<=`): токен, выпущенный в ту же
    миллисекунду, что и logout (часы дают одинаковое значение для соседних вызовов),
    тоже гасится. Свежий вход не страдает — он сбрасывает метку в `issue_tokens`.
    Старые токены без `iat` — пропускаем (обратная совместимость)."""
    if not user.tokens_valid_from:
        return False
    iat = payload.get("iat")
    if iat is None:
        return False
    return float(iat) <= user.tokens_valid_from.timestamp()


def gen_otp() -> str:
    """6-значный код (SMS/Telegram-вход, посадочный код брони). 6 цифр — стандарт,
    10^6 комбинаций против 10^4 → перебор на порядки дороже."""
    return "".join(random.choices(string.digits, k=6))


def gen_referral_code() -> str:
    """Короткий реферальный код (буквы+цифры, без 0/O/1/I — чтобы не путать при наборе)."""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(random.choices(alphabet, k=6))


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


def current_user_optional(
    cred: Optional[HTTPAuthorizationCredentials] = Depends(bearer_optional),
    session: Session = Depends(get_session),
) -> Optional[User]:
    """Как current_user, но без токена/при невалидном — None (не падает).
    Для публичных списков (/rides, /rides/near): залогиненному прячем заблокированных, аноним видит всё."""
    if cred is None:
        return None
    try:
        payload = jwt.decode(cred.credentials, settings.jwt_secret, algorithms=["HS256"])
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        return None
    user = session.get(User, user_id)
    if not user or _token_revoked(payload, user) or is_placeholder_phone(user.phone):
        return None
    return user
