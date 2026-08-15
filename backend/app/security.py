import hashlib
import re
import secrets
import string
from datetime import timedelta
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import jwt, JWTError
from sqlalchemy import update
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


def normalize_phone(raw) -> str:
    """Один номер — одна запись, как бы человек его ни набрал.

    Зачем. Вход искал пользователя по строке ТОЧНО как введено: `User.phone == body.phone`.
    Значит «+79991234567», «79991234567», «89991234567» и «+7 999 123-45-67» — четыре РАЗНЫХ
    аккаунта одного человека. У каждого своя история поездок, свой рейтинг, свои документы
    водителя, свой кошелёк и свои доверенные контакты. Заметить это человек может только по
    тому, что «всё пропало», а восстановить — никак: он не знает, каким написанием заходил.

    Хуже того, это обходило наказания: водителю поставили паузу в «Справедливости» — он
    заходит с восьмёрки вместо плюс-семи и получает чистый аккаунт. Барьер по устройству
    (`guard_device_not_banned`) остаётся, но он про устройство, а не про человека.

    Правила (Россия/Башкортостан — наши номера):
      8XXXXXXXXXX и 7XXXXXXXXXX → +7XXXXXXXXXX,  9XXXXXXXXX → +79XXXXXXXXX.
    Иностранные номера не трогаем сверх очистки: угадывать чужой план нумерации нельзя.
    Telegram-плейсхолдер `tg<id>` и пустое значение возвращаем как есть — это не телефон.
    """
    s = (raw or "").strip()
    if not s or _TG_PLACEHOLDER_RE.match(s):
        return s
    plus = s.startswith("+")
    digits = re.sub(r"\D", "", s)
    if not digits:
        return s
    if len(digits) == 11 and digits[0] in ("7", "8"):
        return "+7" + digits[1:]
    if len(digits) == 10 and digits[0] == "9":
        return "+7" + digits
    # Всё остальное — только чистим разделители, форму сохраняем.
    return ("+" if plus else "") + digits


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
    # with_for_update: блокируем строку токена → два параллельных /auth/refresh с одним
    # refresh не пройдут оба проверку (TOCTOU) и не выдадут две пары токенов.
    rt = session.exec(
        select(RefreshToken).where(RefreshToken.token_hash == _hash_refresh(raw)).with_for_update()
    ).first()
    if not rt or rt.revoked or rt.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    # Гасим токен АТОМАРНО: условие «он ещё не погашен» живёт внутри UPDATE.
    #
    # Блокировка строки выше закрывает гонку на PostgreSQL, но SQLite её игнорирует — а на нём
    # тесты, локальная разработка и демо-база. Там два параллельных /auth/refresh с ОДНИМ
    # токеном проходили проверку оба и получали по свежей паре. Смысл одноразового refresh
    # ровно в обратном: если токен утёк, второе его использование должно провалиться — по этому
    # признаку и ловят кражу. Пока условие проверялось отдельно от записи, укравший работал
    # наравне с хозяином, и следа не оставалось.
    #
    # И как везде: саму защиту не проверял ни один тест — на SQLite блокировка пустая операция.
    burned = session.execute(
        update(RefreshToken)
        .where(RefreshToken.id == rt.id, RefreshToken.revoked == False)   # noqa: E712 — SQL IS FALSE
        .values(revoked=True)
    )
    if burned.rowcount == 0:
        session.rollback()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
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


# Требования к КАЖДОМУ входящему токену. Одна точка на все четыре двери: REST (`current_user`,
# `current_user_optional`), вебсокеты (`authenticate_ws`) и служебная `verify_token`.
#
# `require: exp` — не формальность. Токен без срока жизни сервер принимал как ВЕЧНЫЙ (проба
# волны 42): подделать такой нельзя, секрет у нас, но обещание «токен протухнет» переставало
# действовать для любого токена, который однажды утёк — из бэкапа, из лога, со скриншота
# поддержки. Просроченный `exp` библиотека проверяет сама, отсутствующий раньше не проверял
# никто.
# Имена опций — от `python-jose` (не PyJWT): там это `require_exp`, а `require: [...]`
# библиотека молча игнорирует. Первая версия правки именно так и не сработала, и поймала это
# та же проба, а не тесты: правка выглядела рабочей.
_JWT_OPTIONS = {"require_exp": True, "require_sub": True}


def _decode(token: str) -> dict:
    """Разобрать и проверить токен по общим правилам. Бросает `JWTError` на любом изъяне."""
    return jwt.decode(token, settings.jwt_secret, algorithms=["HS256"], options=_JWT_OPTIONS)


def verify_token(token: str) -> int:
    """Декодирует JWT, возвращает user_id. Бросает исключение при невалидном токене.
    ВНИМАНИЕ: не проверяет ревокацию/существование юзера — для авторизации соединений
    используй `authenticate_ws` (ниже)."""
    payload = _decode(token)
    return int(payload["sub"])


def authenticate_ws(token: str, session: Session) -> User:
    """Аутентификация для WebSocket (там нет Depends/HTTPBearer).
    В отличие от `verify_token`, ПРОВЕРЯЕТ существование юзера и ревокацию сессии
    (`tokens_valid_from`/logout) — той же логикой, что REST `current_user`. Иначе токен,
    отозванный через logout, продолжал бы открывать чат до самого истечения JWT."""
    payload = _decode(token)
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
    10^6 комбинаций против 10^4 → перебор на порядки дороже.
    `secrets`, не `random`: код входа нельзя восстановить по выборке (Mersenne предсказуем)."""
    return "".join(secrets.choice(string.digits) for _ in range(6))


def gen_referral_code() -> str:
    """Короткий реферальный код (буквы+цифры, без 0/O/1/I — чтобы не путать при наборе)."""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(6))


def current_user(
    cred: HTTPAuthorizationCredentials = Depends(bearer),
    session: Session = Depends(get_session),
) -> User:
    try:
        payload = _decode(cred.credentials)
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
        payload = _decode(cred.credentials)
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        return None
    user = session.get(User, user_id)
    if not user or _token_revoked(payload, user) or is_placeholder_phone(user.phone):
        return None
    return user
