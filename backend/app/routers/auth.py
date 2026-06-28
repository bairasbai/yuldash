"""Авторизация: телефон+OTP, Telegram-вход (бот+код), VK/WhatsApp-заглушки,
профиль `/me`, регистрация push-токена."""
from datetime import timedelta
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import engine, get_session
from ..models import DeviceToken, OtpCode, TgAuth, User
from ..security import current_user, gen_otp, issue_tokens, revoke_all_refresh, rotate_refresh
from ..services import send_sms, user_rating
from ..timeutil import utcnow

router = APIRouter(tags=["auth"])


# ----------------------------- Телефон + OTP -----------------------------
class PhoneIn(BaseModel):
    phone: str


class VerifyIn(BaseModel):
    phone: str
    code: str
    name: str = Field("", max_length=120)


@router.post("/auth/request-code")
def request_code(body: PhoneIn, session: Session = Depends(get_session)):
    # Throttle: ≤3 кода в минуту на номер (анти-флуд: расходы на SMS + защита от забивания OtpCode).
    recent = session.exec(
        select(OtpCode).where(
            OtpCode.phone == body.phone,
            OtpCode.created_at > utcnow() - timedelta(seconds=60),
        )
    ).all()
    if len(recent) >= 3:
        raise HTTPException(429, "Слишком часто. Подожди минуту и попробуй снова.")
    code = gen_otp()
    session.add(OtpCode(
        phone=body.phone, code=code,
        expires_at=utcnow() + timedelta(seconds=settings.otp_ttl_sec),
    ))
    session.commit()
    send_sms(body.phone, code)
    resp = {"sent": True}
    if settings.env == "dev":
        resp["dev_code"] = code  # в dev возвращаем код, чтобы тестировать без SMS
    return resp


@router.post("/auth/verify")
def verify(body: VerifyIn, session: Session = Depends(get_session)):
    otp = session.exec(
        select(OtpCode).where(OtpCode.phone == body.phone).order_by(OtpCode.id.desc())
    ).first()
    if not otp or otp.expires_at < utcnow():
        raise HTTPException(400, "Неверный или просроченный код")
    if otp.attempts >= 5:                       # защита от перебора 4-значного кода
        raise HTTPException(429, "Слишком много попыток. Запроси новый код.")
    if otp.code != body.code:
        otp.attempts += 1
        session.add(otp)
        session.commit()
        raise HTTPException(400, "Неверный или просроченный код")
    user = session.exec(select(User).where(User.phone == body.phone)).first()
    if not user:
        user = User(phone=body.phone, name=body.name or "Пользователь", verified=True)
        session.add(user)
        session.commit()
        session.refresh(user)
    tokens = issue_tokens(session, user.id)   # commit внутри → user протухает
    session.refresh(user)                     # перечитываем, чтобы сериализовать в ответ
    return {**tokens, "user": user}


# ==================== TELEGRAM-ВХОД (бот, код подтверждения) ====================
# Поток как у SMS, но 4-значный код шлёт Telegram-бот:
# 1) app: POST /auth/tg/start → request_id; app открывает t.me/<bot>?start=<request_id>
# 2) юзер жмёт Старт → Telegram шлёт /start <request_id> на вебхук
# 3) вебхук привязывает ПОДТВЕРЖДЁННЫЙ Telegram'ом from.id к request_id, генерит 4-значный
#    код и присылает его юзеру в чат бота
# 4) app: POST /auth/tg/verify {request_id, code} → сервер сверяет код → JWT
# Безопасность: request_id — случайный UUID (не угадать); код 4 цифры, живёт 5 мин,
# ≤5 попыток; telegram_id подтверждён Telegram'ом. За чужого войти нельзя.

TG_CODE_TTL_SEC = 300
TG_MAX_ATTEMPTS = 5


class TgStartOut(BaseModel):
    request_id: str


@router.post("/auth/tg/start", response_model=TgStartOut)
def tg_start(session: Session = Depends(get_session)):
    req = uuid.uuid4().hex
    session.add(TgAuth(
        request_id=req, status="waiting",
        expires_at=utcnow() + timedelta(seconds=TG_CODE_TTL_SEC),
    ))
    session.commit()
    return TgStartOut(request_id=req)


@router.post("/telegram/webhook")
async def telegram_webhook(request: Request, x_telegram_bot_api_secret_token: str = Header(default="")):
    """Telegram шлёт сюда апдейты. На /start <request_id> привязываем юзера и шлём код."""
    if settings.telegram_webhook_secret and x_telegram_bot_api_secret_token != settings.telegram_webhook_secret:
        raise HTTPException(403, "bad secret")
    update = await request.json()
    msg = update.get("message") or {}
    text = msg.get("text") or ""
    frm = msg.get("from") or {}
    chat = msg.get("chat") or {}
    reply = None
    if text.startswith("/start") and frm.get("id"):
        parts = text.split(maxsplit=1)
        req = parts[1].strip() if len(parts) > 1 else ""
        with Session(engine) as s:
            row = s.exec(select(TgAuth).where(TgAuth.request_id == req)).first() if req else None
            if row and row.status in ("waiting", "sent") and row.expires_at > utcnow():
                code = gen_otp()
                row.telegram_id = str(frm["id"])
                row.username = frm.get("username", "") or ""
                row.first_name = frm.get("first_name", "") or ""
                row.code = code
                row.status = "sent"
                s.add(row)
                s.commit()
                reply = f"Твой код для входа в Юлдаш: {code}\nВведи его в приложении. Код живёт 5 минут."
            else:
                reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
    if reply is not None:
        return {"method": "sendMessage", "chat_id": chat.get("id"), "text": reply}
    return {"ok": True}


class TgVerifyIn(BaseModel):
    request_id: str
    code: str


@router.post("/auth/tg/verify")
def tg_verify(body: TgVerifyIn, session: Session = Depends(get_session)):
    # Статусы различимы клиентом для разных сообщений: 409 ещё не получен, 410 истёк,
    # 429 много попыток, 400 неверный код.
    row = session.exec(select(TgAuth).where(TgAuth.request_id == body.request_id)).first()
    if not row or row.status != "sent" or not row.telegram_id or not row.code:
        raise HTTPException(409, "Сначала получи код в Telegram")
    if row.expires_at < utcnow():
        raise HTTPException(410, "Код истёк. Получи новый.")
    if row.attempts >= TG_MAX_ATTEMPTS:
        raise HTTPException(429, "Слишком много попыток. Получи новый код.")
    if body.code.strip() != row.code:
        row.attempts += 1
        session.add(row)
        session.commit()
        raise HTTPException(400, "Неверный код")
    row.status = "used"
    session.add(row)
    user = session.exec(select(User).where(User.telegram_id == row.telegram_id)).first()
    if not user:
        user = User(
            phone=f"tg{row.telegram_id}",   # плейсхолдер (не настоящий номер), уникален по telegram_id
            name=row.first_name or row.username or "Telegram",
            telegram_id=row.telegram_id,
            verified=True,
        )
        session.add(user)
    session.commit()
    session.refresh(user)
    tokens = issue_tokens(session, user.id)   # commit внутри → user протухает
    session.refresh(user)
    return {**tokens, "user": user}


class RefreshIn(BaseModel):
    refresh_token: str


@router.post("/auth/refresh")
def refresh(body: RefreshIn, session: Session = Depends(get_session)):
    """Обновить пару токенов по refresh-токену (ротация: старый refresh гасится)."""
    if not body.refresh_token.strip():
        raise HTTPException(400, "Нужен refresh_token")
    return rotate_refresh(session, body.refresh_token.strip())


# VK / WhatsApp вход — ОТКЛЮЧЕНО до безопасной реализации.
# Прежние версии выдавали токен по непроверенному vk_id/телефону (whatsapp-callback —
# угон аккаунта: любой с чужим номером получал токен). Включим, когда будет:
#   VK  — серверный OAuth code-exchange (/auth/vk/callback) с проверкой на стороне VK;
#   WA  — WhatsApp Business API с подтверждением номера.
@router.post("/auth/vk-callback")
def vk_callback():
    raise HTTPException(501, "VK-вход ещё не подключён")


@router.post("/auth/whatsapp-callback")
def whatsapp_callback():
    raise HTTPException(501, "WhatsApp-вход ещё не подключён")


@router.get("/me")
def me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    avg, cnt = user_rating(session, user.id)
    return {**user.model_dump(), "rating": round(avg, 1) if cnt > 0 else None, "rating_count": cnt}


@router.post("/auth/logout")
def logout(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Выход со всех устройств: гасим access (метка tokens_valid_from) и все refresh-токены."""
    user.tokens_valid_from = utcnow()
    session.add(user)
    session.commit()
    revoke_all_refresh(session, user.id)
    return {"ok": True}


class PushTokenIn(BaseModel):
    token: str


@router.post("/push/register")
def push_register(body: PushTokenIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Регистрация/перепривязка FCM-токена устройства к текущему пользователю."""
    if not body.token.strip():
        raise HTTPException(400, "Пустой токен")
    existing = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
    if existing:
        existing.user_id = user.id
        session.add(existing)
    else:
        session.add(DeviceToken(user_id=user.id, token=body.token))
    session.commit()
    return {"ok": True}
