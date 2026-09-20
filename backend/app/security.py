import hashlib
import hmac
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
from .errors import herr
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


def make_token(user_id: int, *, issued_after=None) -> str:
    """Короткоживущий access-токен (JWT)."""
    now = utcnow()
    if issued_after is not None and now <= issued_after:
        # Logout отзывает всё с iat <= tokens_valid_from. Часы могут вернуть тот же момент
        # при немедленном повторном входе, поэтому новый токен ставим строго за границей.
        now = issued_after + timedelta(microseconds=1)
    exp = now + timedelta(minutes=settings.access_expire_min)
    # iat — момент выпуска (с дробными секундами). По нему отсекаем токены, выпущенные
    # ≤ tokens_valid_from (logout). От гонки login→logout→login в одной миллисекунде
    # (часы Windows дают одинаковое значение соседним вызовам) защищает явная выдача
    # строго после сохранённой границы + нестрогое сравнение в _token_revoked.
    return jwt.encode(
        {"sub": str(user_id), "iat": now.timestamp(), "exp": exp},
        settings.jwt_secret, algorithm="HS256",
    )


def _hash_refresh(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


def issue_tokens(session: Session, user_id: int, *, refresh_raw: Optional[str] = None) -> dict:
    """Выдать пару access+refresh. Refresh — непрозрачный, в БД лежит ХЕШ.

    Граница последнего logout остаётся навсегда: иначе новый вход оживит все старые
    access-токены. Новую пару выпускаем строго после этой границы."""
    user = lock_refresh_user(session, user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    issued_after = user.tokens_valid_from if user else None
    if user:
        # Отметка «человек жив»: пишется при каждой выдаче пары ключей, то есть у активного —
        # минимум раз в 12 часов. По ней отличаем «уехал на сезон» от «номер перешёл к другому»
        # (волна 139). Отдельного запроса не стоит: строка уже в сессии и всё равно сохраняется.
        user.last_seen_at = utcnow()
        session.add(user)
    raw = refresh_raw if refresh_raw is not None else secrets.token_urlsafe(48)
    session.add(RefreshToken(
        user_id=user_id, token_hash=_hash_refresh(raw),
        expires_at=utcnow() + timedelta(days=settings.refresh_expire_days),
    ))
    pair = {
        "access_token": make_token(user_id, issued_after=issued_after),
        "refresh_token": raw,
        "token_type": "bearer",
    }
    session.commit()
    return pair


REFRESH_RECOVERY_SECONDS = 120


def lock_refresh_user(session: Session, user_id: int) -> Optional[User]:
    """One lock order for rotation/recovery/logout: User, then RefreshToken.

    SQLite ignores FOR UPDATE. A no-op write obtains its writer lock before any
    authorization state is read; PostgreSQL uses the row lock instead.
    """
    if session.get_bind().dialect.name == 'sqlite':
        session.execute(update(User).where(User.id == user_id).values(last_seen_at=User.last_seen_at))
    return session.exec(select(User).where(User.id == user_id).with_for_update()
                        .execution_options(populate_existing=True)).first()


def _recovery_token(raw: str, rotation_id: str) -> str:
    message = b'refresh-recovery-v1\0' + raw.encode() + b'\0' + rotation_id.encode()
    return hmac.new(settings.jwt_secret.encode(), message, hashlib.sha256).hexdigest()


def rotate_refresh(session: Session, raw: str, rotation_id: Optional[str] = None) -> dict:
    """Ротация одноразовая; тот же секрет попытки восстанавливает её результат 120с."""
    if rotation_id is not None and (
        not isinstance(rotation_id, str) or re.fullmatch(r'[0-9a-f]{64}', rotation_id) is None
    ):
        raise herr(422, "Не получилось продлить вход. Войди заново.",
                   "Инеүҙе оҙайтып булманы. Яңынан ин.")
    owner_id = session.exec(select(RefreshToken.user_id).where(
        RefreshToken.token_hash == _hash_refresh(raw))).first()
    if owner_id is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    owner = lock_refresh_user(session, owner_id)
    rt = session.exec(
        select(RefreshToken).where(RefreshToken.token_hash == _hash_refresh(raw)).with_for_update()
        .execution_options(populate_existing=True)
    ).first()
    now = utcnow()
    if not owner or not rt or rt.expires_at <= now:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    # Бан устройства — вторая половина проверки (волна 204). Первая стоит в роутере и читает
    # заголовок `X-Device-Id`; но заголовок шлёт КЛИЕНТ, и забаненному достаточно перестать
    # его слать. Поэтому смотрим ещё и на аппарат, который сервер запомнил сам при входе
    # (`User.last_device_id` — по нему же он предупреждает о входе с нового устройства).
    #
    # Бан остаётся баном АППАРАТА, а не человека: пересел на чистый телефон, вошёл заново —
    # запомненное устройство сменилось, и продление снова работает. Пожизненной блокировки
    # человека тут никто не вводил.
    from .antifraud import guard_device_not_banned   # локальный импорт: без цикла на старте
    guard_device_not_banned(session, owner.last_device_id)
    if rt.revoked:
        if (rotation_id is None or rt.rotation_id_hash is None or rt.rotated_at is None
                or not hmac.compare_digest(rt.rotation_id_hash, _hash_refresh(rotation_id))
                or not 0 <= (now - rt.rotated_at).total_seconds() <= REFRESH_RECOVERY_SECONDS):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
        child_raw = _recovery_token(raw, rotation_id)
        child = session.exec(select(RefreshToken).where(
            RefreshToken.token_hash == _hash_refresh(child_raw)).with_for_update()
            .execution_options(populate_existing=True)).first()
        if (not child or child.user_id != owner.id or child.revoked or child.expires_at <= now
                or (owner.tokens_valid_from is not None and rt.rotated_at <= owner.tokens_valid_from)):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
        # No new row, no change to either expiry or recovery window. The User
        # lock stays held until this request's session closes, excluding logout.
        return {'access_token': make_token(owner.id, issued_after=owner.tokens_valid_from),
                'refresh_token': child_raw, 'token_type': 'bearer'}
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
        .values(revoked=True, rotation_id_hash=_hash_refresh(rotation_id) if rotation_id else None,
                rotated_at=now if rotation_id else None)
    )
    if burned.rowcount == 0:
        session.rollback()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Refresh-токен недействителен")
    try:
        # Отзыв и новая пара фиксируются вместе, после успешной подписи JWT.
        if rotation_id is not None:
            return issue_tokens(session, rt.user_id, refresh_raw=_recovery_token(raw, rotation_id))
        return issue_tokens(session, rt.user_id)
    except Exception:
        session.rollback()
        raise


def revoke_all_refresh(session: Session, user_id: int, *, commit: bool = True) -> None:
    """Отозвать все refresh-токены пользователя (logout со всех устройств)."""
    for rt in session.exec(select(RefreshToken).where(
        RefreshToken.user_id == user_id, RefreshToken.revoked == False  # noqa: E712
    )).all():
        rt.revoked = True
        session.add(rt)
    if commit:
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
    тоже гасится. Свежий вход выпускает токен строго после сохранённой границы.
    Старый токен без `iat` после появления границы нельзя безопасно отнести к новой
    сессии, поэтому он тоже считается отозванным."""
    if not user.tokens_valid_from:
        return False
    iat = payload.get("iat")
    if iat is None:
        return True
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
