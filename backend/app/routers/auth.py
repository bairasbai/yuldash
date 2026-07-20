"""Авторизация: телефон+OTP, Telegram-вход (бот+код), VK/WhatsApp-заглушки,
профиль `/me`, регистрация push-токена."""
from datetime import timedelta
from typing import Optional
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..account import delete_user_account
from ..config import settings
from ..db import engine, get_session
from ..models import Ad, DeviceToken, DriverProfile, OtpCode, Payment, RequestResponse, Ride, TgAuth, User, UserRole
from ..payments import BOOST_PLANS
from ..security import current_user, gen_otp, is_placeholder_phone, issue_tokens, revoke_all_refresh, rotate_refresh
from ..services import send_push_bi, send_sms, user_rating
from ..timeutil import utcnow

router = APIRouter(tags=["auth"])


def _maybe_promote_admin(session: Session, user: User) -> None:
    """Автоадмин: вход с Telegram-id владельца ИЛИ с админ-телефона (config) → роль admin.
    Реюз admin_telegram_chat_id + список admin_phones. Кабинет админа появляется сам."""
    admin_phones = {p.strip() for p in (settings.admin_phones or "").split(",") if p.strip()}
    by_tg = bool(settings.admin_telegram_chat_id) and user.telegram_id == settings.admin_telegram_chat_id
    by_phone = bool(user.phone) and user.phone in admin_phones
    if (by_tg or by_phone) and user.role != UserRole.admin:
        user.role = UserRole.admin
        session.add(user)


def _norm_phone(raw: str) -> str:
    """Нормализуем номер из Telegram-контакта: только цифры, ведущий +."""
    d = "".join(c for c in (raw or "") if c.isdigit())
    return ("+" + d) if d else ""


def _set_user_phone(session: Session, user: User, phone: str) -> None:
    """Сохранить реальный номер юзеру. Не перезаписываем, если номер уже занят
    другим юзером (User.phone unique) — тогда тихо оставляем как есть."""
    if not phone or user.phone == phone:
        return
    clash = session.exec(select(User).where(User.phone == phone, User.id != user.id)).first()
    if clash:
        return
    user.phone = phone
    session.add(user)
    session.commit()


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
    if otp.attempts >= 5:                       # защита от перебора 6-значного кода
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
    _maybe_promote_admin(session, user)   # автоадмин по телефону (SMS-вход)
    tokens = issue_tokens(session, user.id)   # commit внутри → user протухает
    session.refresh(user)                     # перечитываем, чтобы сериализовать в ответ
    return {**tokens, "user": user}


# ==================== TELEGRAM-ВХОД (бот, код подтверждения) ====================
# Поток как у SMS, но 6-значный код шлёт Telegram-бот:
# 1) app: POST /auth/tg/start → request_id; app открывает t.me/<bot>?start=<request_id>
# 2) юзер жмёт Старт → Telegram шлёт /start <request_id> на вебхук
# 3) вебхук привязывает ПОДТВЕРЖДЁННЫЙ Telegram'ом from.id к request_id, генерит 6-значный
#    код и присылает его юзеру в чат бота
# 4) app: POST /auth/tg/verify {request_id, code} → сервер сверяет код → JWT
# Безопасность: request_id — случайный UUID (не угадать); код 6 цифр, живёт 5 мин,
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
    callback = update.get("callback_query") or {}
    if callback:
        return _handle_admin_callback(callback)
    msg = update.get("message") or {}
    text = msg.get("text") or ""
    frm = msg.get("from") or {}
    chat = msg.get("chat") or {}
    contact = msg.get("contact") or {}

    # Юзер поделился номером кнопкой request_contact. Принимаем ТОЛЬКО свой номер
    # (contact.user_id == отправитель) — не пересланный чужой контакт.
    if contact and frm.get("id"):
        tid = str(frm["id"])
        if str(contact.get("user_id")) == tid and contact.get("phone_number"):
            phone = _norm_phone(contact["phone_number"])
            with Session(engine) as s:
                u = s.exec(select(User).where(User.telegram_id == tid)).first()
                if u:
                    _set_user_phone(s, u, phone)               # юзер уже есть → сразу пишем
                else:
                    # ещё не верифицировался → запомним на сессии входа, применим при verify
                    row = s.exec(
                        select(TgAuth).where(TgAuth.telegram_id == tid).order_by(TgAuth.id.desc())
                    ).first()
                    if row:
                        row.shared_phone = phone
                        s.add(row)
                        s.commit()
            return {"method": "sendMessage", "chat_id": chat.get("id"),
                    "text": "Спасибо! Номер сохранён ✅ Вернись в приложение и введи код.",
                    "reply_markup": {"remove_keyboard": True}}
        return {"method": "sendMessage", "chat_id": chat.get("id"),
                "text": "Поделись своим номером кнопкой ниже 🙏"}

    reply = None
    reply_markup = None
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
                reply = (
                    f"Твой код для входа в Юлдаш: {code}\n"
                    "Код живёт 5 минут.\n\n"
                    "Для безопасности нажми «📱 Поделиться номером» ниже — без номера "
                    "вход не завершится. Затем введи код в приложении."
                )
                reply_markup = {
                    "keyboard": [[{"text": "📱 Поделиться номером", "request_contact": True}]],
                    "resize_keyboard": True, "one_time_keyboard": True,
                }
            else:
                reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
            # Гигиена: чистим просроченные строки входа (TgAuth/OtpCode растут на каждую попытку).
            # Делаем здесь — частоту вебхука Telegram сам ограничивает (~30/с), клиентский спайк не грузим.
            now = utcnow()
            s.execute(delete(TgAuth).where(TgAuth.expires_at < now))
            s.execute(delete(OtpCode).where(OtpCode.expires_at < now))
            s.commit()
    if reply is not None:
        out = {"method": "sendMessage", "chat_id": chat.get("id"), "text": reply}
        if reply_markup is not None:
            out["reply_markup"] = reply_markup
        return out
    # Админ пишет боту текстом («Одобрить», «/Одобрить», «#1») — раньше бот молчал, и это путало.
    # Подсказываем, где реально одобрять (кнопка под откликом / Кабинет админа), вместо тишины.
    low = text.strip().lower()
    if str(frm.get("id")) == str(settings.admin_telegram_chat_id) and (
        low.startswith(("/", "#")) or "одобр" in low or "принят" in low or "прими" in low
    ):
        return {"method": "sendMessage", "chat_id": chat.get("id"),
                "text": "Заявку нельзя одобрить текстом. Отклик водителя принимается так:\n"
                        "• кнопкой ✅ Принять прямо под сообщением «🚗 Отклик на заявку»;\n"
                        "• или в приложении: Профиль → Кабинет админа → «Отклики по заявке» → введи № заявки → «Принять за пользователя».\n"
                        "Кнопка появляется после того, как на заявку откликнется водитель."}
    return {"ok": True}


def _telegram_api(method: str, payload: dict) -> None:
    if not settings.telegram_bot_token:
        return
    try:
        import httpx
        httpx.post(f"https://api.telegram.org/bot{settings.telegram_bot_token}/{method}", json=payload, timeout=8)
    except Exception:
        pass


def _activate_manual_payment(session: Session, payment: Payment) -> None:
    """Apply a manually confirmed SBP payment from Telegram. Same effect as admin confirm."""
    if payment.status == "succeeded":
        return
    payment.status = "succeeded"
    session.add(payment)
    if payment.purpose == "boost" and payment.ride_id is not None:
        ride = session.get(Ride, payment.ride_id)
        plan = BOOST_PLANS.get(payment.tier)
        if ride and plan:
            ride.boosted_until = utcnow() + timedelta(hours=plan[2])
            ride.boost_tier = payment.tier
            session.add(ride)
    elif payment.purpose == "ad" and payment.ad_id is not None:
        ad = session.get(Ad, payment.ad_id)
        if ad:
            ad.status = "active"
            if ad.period_days > 0:
                ad.starts_at = utcnow()
                ad.ends_at = utcnow() + timedelta(days=ad.period_days)
            session.add(ad)
    session.commit()
    # Плательщику: оплата подтверждена (буст/донат). Рекламу ведёт её собственный поток.
    if payment.purpose == "boost":
        send_push_bi(session, payment.user_id, "Платёж подтверждён", "Түләү раҫланды",
                     "Твоя поездка поднята в топ.", "Сәфәрең өҫкә күтәрелде.")
    elif payment.purpose == "donate":
        send_push_bi(session, payment.user_id, "Спасибо за поддержку!", "Ярҙамың өсөн рәхмәт!",
                     "Твой донат получен. Спасибо, что поддерживаешь Юлдаш 💚",
                     "Донатың ҡабул ителде. Юлдашты яҡлағаның өсөн рәхмәт 💚")


def _handle_admin_callback(callback: dict):
    """Inline-кнопки админа в Telegram. Сейчас поддерживает модерацию водителей."""
    cb_id = callback.get("id")
    frm = callback.get("from") or {}
    data = callback.get("data") or ""
    msg = callback.get("message") or {}
    chat = msg.get("chat") or {}
    chat_id = chat.get("id")
    message_id = msg.get("message_id")

    if str(frm.get("id")) != str(settings.admin_telegram_chat_id):
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Нет доступа", "show_alert": True})
        return {"ok": True}

    parts = data.split(":")
    if len(parts) != 3 or parts[0] not in ("drv", "ad", "pay", "resp") or parts[1] not in ("ok", "no"):
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Неизвестная команда"})
        return {"ok": True}

    try:
        user_id = int(parts[2])
    except ValueError:
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Некорректный ID"})
        return {"ok": True}

    approve = parts[1] == "ok"
    with Session(engine) as s:
        if parts[0] == "drv":
            target = s.get(User, user_id)
            if not target:
                text = f"Пользователь #{user_id} не найден"
            else:
                dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
                if not dp:
                    dp = DriverProfile(user_id=user_id)
                target.verified = approve
                dp.docs_status = "verified" if approve else "rejected"
                s.add(target)
                s.add(dp)
                s.commit()
                if approve:
                    send_push_bi(s, user_id, "Проверка пройдена", "Тикшереү үтелде",
                                 "Теперь ты можешь публиковать поездки.", "Хәҙер һин сәфәрҙәр баҫтыра алаһың.")
                else:
                    send_push_bi(s, user_id, "Проверка не пройдена", "Тикшереү үтелмәне",
                                 "Проверь фото и отправь снова.", "Фотоларҙы тикшереп, ҡабат ебәр.")
                text = f"{'Одобрен' if approve else 'Отклонён'} водитель #{user_id}: {target.name or target.phone}"
        elif parts[0] == "ad":
            ad = s.get(Ad, user_id)
            if not ad:
                text = f"Объявление #{user_id} не найдено"
            elif approve:
                ad.status = "active"
                ad.reject_reason = ""
                ad.reviewed_at = utcnow()
                if ad.period_days > 0:
                    ad.starts_at = utcnow()
                    ad.ends_at = utcnow() + timedelta(days=ad.period_days)
                s.add(ad)
                s.commit()
                if ad.owner_id:
                    send_push_bi(
                        s, ad.owner_id,
                        "Реклама одобрена", "Реклама раҫланды",
                        f"«{ad.title}» прошла модерацию. Осталось оплатить размещение.",
                        f"«{ad.title}» модерацияны үтте. Урынлаштырыуҙы түләргә ҡалды.",
                    )
                text = f"Одобрена реклама #{ad.id}: «{ad.title}»"
            else:
                ad.status = "rejected"
                ad.reject_reason = "Отклонено администратором в Telegram"
                ad.reviewed_at = utcnow()
                s.add(ad)
                s.commit()
                if ad.owner_id:
                    send_push_bi(s, ad.owner_id, "Реклама отклонена", "Реклама кире ҡағылды", ad.reject_reason)
                text = f"Отклонена реклама #{ad.id}: «{ad.title}»"
        elif parts[0] == "resp":
            resp = s.get(RequestResponse, user_id)   # user_id здесь = id отклика
            if not resp:
                text = f"Отклик #{user_id} не найден"
            elif resp.status == "accepted":
                text = f"Отклик #{resp.id} уже принят"
            elif not approve:
                resp.status = "declined"
                s.add(resp)
                s.commit()
                send_push_bi(s, resp.driver_id, "Отклик отклонён", "Яуап кире ҡағылды",
                             "По этой заявке выбрали другого попутчика.", "Был заявкаға башҡа юлдаш һайланылар.")
                text = f"Отклонён отклик #{resp.id}"
            else:
                from .requests import accept_request_response   # локальный импорт — без цикла на старте
                try:
                    accept_request_response(s, resp)
                    text = f"Принят отклик #{resp.id} — поездка создана. Перезвони пассажиру и водителю."
                except HTTPException as e:
                    text = f"Не удалось принять отклик #{resp.id}: {e.detail}"
        else:
            payment = s.get(Payment, user_id)
            if not payment:
                text = f"Платёж #{user_id} не найден"
            elif approve:
                if payment.status == "succeeded":
                    text = f"Платёж #{payment.id} уже подтверждён"
                else:
                    _activate_manual_payment(s, payment)
                    text = f"Подтверждена оплата #{payment.id}: {payment.purpose} {payment.amount_kop // 100} ₽"
            else:
                if payment.status == "pending":
                    payment.status = "canceled"
                    s.add(payment)
                    s.commit()
                text = f"Отклонена оплата #{payment.id}: {payment.purpose} {payment.amount_kop // 100} ₽"

    if cb_id:
        _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": text})
    if chat_id and message_id:
        _telegram_api("editMessageReplyMarkup", {"chat_id": chat_id, "message_id": message_id, "reply_markup": {"inline_keyboard": []}})
        _telegram_api("sendMessage", {"chat_id": chat_id, "text": text})
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
    user = session.exec(select(User).where(User.telegram_id == row.telegram_id)).first()
    if not user and row.shared_phone:
        existing_by_phone = session.exec(select(User).where(User.phone == row.shared_phone)).first()
        if existing_by_phone and not existing_by_phone.telegram_id:
            existing_by_phone.telegram_id = row.telegram_id
            existing_by_phone.verified = True
            if not existing_by_phone.name:
                existing_by_phone.name = row.first_name or row.username or "Telegram"
            session.add(existing_by_phone)
            session.commit()
            session.refresh(existing_by_phone)
            user = existing_by_phone
    if not user:
        user = User(
            phone=f"tg{row.telegram_id}",   # плейсхолдер, пока юзер не поделился реальным номером
            name=row.first_name or row.username or "Telegram",
            telegram_id=row.telegram_id,
            verified=True,
        )
        session.add(user)
        session.commit()
        session.refresh(user)
    # Реальный номер из бота (кнопка «Поделиться номером») — подставляем, если есть.
    if row.shared_phone:
        _set_user_phone(session, user, row.shared_phone)
        session.refresh(user)
    # ⛔ Номер ОБЯЗАТЕЛЕН (безопасность / защита от мошенников). Без реального номера вход
    # не завершаем: код НЕ помечаем used (status='sent') → юзер делится номером в боте и
    # повторяет ввод того же кода. Клиент по 403 phone_required показывает экран-подсказку.
    if is_placeholder_phone(user.phone):
        raise HTTPException(403, "phone_required")
    _maybe_promote_admin(session, user)   # автоадмин по telegram_id или телефону
    row.status = "used"
    session.add(row)
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


class MeUpdateIn(BaseModel):
    name: Optional[str] = Field(None, max_length=120)
    avatar_url: Optional[str] = Field(None, max_length=500)


@router.post("/me/update")
def update_me(body: MeUpdateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Редактирование профиля: имя и/или аватар. Телефон не меняем. Поля опциональны."""
    if body.name is not None:
        n = body.name.strip()
        if n:
            user.name = n[:120]
    if body.avatar_url is not None:
        user.avatar_url = body.avatar_url.strip()[:500]
    session.add(user)
    session.commit()
    session.refresh(user)
    return {"ok": True, "name": user.name, "avatar_url": user.avatar_url}


@router.post("/me/delete")
def delete_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Необратимое удаление аккаунта и ВСЕХ персональных данных пользователя (152-ФЗ,
    право на удаление). Каскад по всем таблицам — в app/account.py. После — токен 401."""
    delete_user_account(session, user)
    return {"ok": True}


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
        session.commit()
        return {"ok": True}
    # Нового токена ещё нет — вставляем. Клиент шлёт токен из 2 мест на старте (сохранённый + свежий FCM),
    # оба запроса могут попасть на разные воркеры и одновременно пройти select-пусто → гонка на unique(token).
    try:
        session.add(DeviceToken(user_id=user.id, token=body.token))
        session.commit()
    except IntegrityError:
        # Параллельный запрос успел вставить тот же токен между select и commit → перепривязываем к текущему юзеру.
        session.rollback()
        row = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
        if row:
            row.user_id = user.id
            session.add(row)
            session.commit()
    return {"ok": True}
