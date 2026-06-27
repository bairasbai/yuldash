import hashlib
import hmac
import random
import string
import time
from datetime import datetime, timedelta

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlmodel import Session

from .config import settings
from .db import get_session
from .models import User

bearer = HTTPBearer(auto_error=True)


def make_token(user_id: int) -> str:
    exp = datetime.utcnow() + timedelta(minutes=settings.jwt_expire_min)
    return jwt.encode({"sub": str(user_id), "exp": exp}, settings.jwt_secret, algorithm="HS256")


def verify_token(token: str) -> int:
    """Декодирует JWT, возвращает user_id. Бросает исключение при невалидном токене.
    Используется WebSocket-чатом (там нет Depends/HTTPBearer)."""
    payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
    return int(payload["sub"])


def gen_otp() -> str:
    return "".join(random.choices(string.digits, k=4))


# ---- Подпись Telegram-данных (бот-вебхук подписывает, callback проверяет) ----
# Защита от подделки: только сервер знает jwt_secret, значит только сервер (наш бот)
# может выпустить валидную подпись для пары (telegram_id, время). Клиент подделать не может.

def sign_telegram(uid: str, auth_date: int) -> str:
    msg = f"{uid}:{auth_date}".encode()
    return hmac.new(settings.jwt_secret.encode(), msg, hashlib.sha256).hexdigest()


def verify_telegram(uid: str, auth_date: int, sig: str, max_age_sec: int = 300) -> bool:
    if not uid or not sig or not auth_date:
        return False
    if not hmac.compare_digest(sign_telegram(uid, auth_date), sig):
        return False
    return abs(int(time.time()) - int(auth_date)) <= max_age_sec


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
    return user
