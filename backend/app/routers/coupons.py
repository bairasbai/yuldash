"""M1 — партнёрский слой + купонный маркетплейс «Скидки по пути».

Философия (красные линии):
- Купон = РЕАЛЬНАЯ скидка от бизнеса: честно, срок/лимит/скидка видны, без скрытых наценок.
- Зарабатываем на БИЗНЕСЕ (подписка партнёра + оплата за погашённый купон), НЕ на пассажирах.
- Приватность: при погашении бизнес видит только код и максимум имя — НЕ телефон, НЕ гео.
- Все пользовательские ошибки 4xx — двуязычные через herr(status, ru, ba).

Устройство повторяет ads.py (F20): тарифы в конфиге как AD_PACKAGES; подписка партнёра —
Payment(purpose="partner_sub", status=pending) → админ подтверждает в Telegram (pay:ok/pay:no) →
_activate_payment продлевает subscription_until. Витрина показывает купон только у active-партнёра
с оплаченной подпиской (гейт как у платной рекламы).
"""
import secrets
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlalchemy import update as sa_update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..antifraud import moderate_open_text
from ..db import get_session
from ..errors import herr
from ..logs import admin_action
from ..middleware import user_over_limit
from ..models import Coupon, CouponReport, CouponRedemption, Partner, Payment, User, UserRole
from ..safety_logic import ensure_active
from ..security import current_user
from ..services import notify_admin_telegram, push_notification
from ..timeutil import utcnow

router = APIRouter(tags=["coupons"])

# Тарифы подписки бизнеса в «Скидки по пути». Цены — стартовая гипотеза, правятся ЗДЕСЬ
# без пересборки клиента. amount_kop — стоимость за period_days. premium=True даёт право
# на выделенную метку купона на карте. Порядок = порядок показа в кабинете/прайсе.
PARTNER_PLANS = {
    "basic":    {"title": "Базовый",  "title_ba": "Базалы",   "amount_kop":  99_000, "period_days": 30, "premium": False},
    "standard": {"title": "Стандарт", "title_ba": "Стандарт", "amount_kop": 199_000, "period_days": 30, "premium": False},
    "premium":  {"title": "Премиум",  "title_ba": "Премиум",  "amount_kop": 299_000, "period_days": 30, "premium": True},
}

# Комиссия платформы «за одно погашение» (для statement кабинета). Держим в коде роутера,
# а не в .env: это тариф продукта, меняется здесь без пересборки. 1000 коп = 10 ₽.
PARTNER_REDEMPTION_FEE_KOP = 1000

# Анти-спам: не даём одному бизнесу плодить бесконечно купонов.
MAX_COUPONS_PER_PARTNER = 50

# Анти-спам жалобами: первая жалоба на купон дёргает Telegram админа. Без потолка один человек,
# пройдясь по витрине, шлёт Александру столько сообщений, сколько там купонов (аудит 2026-08-08 —
# ровно та причина, по которой /callback и /donate живут в строгом бюджете лимитера). Живой
# человек жалуется на один-два купона за раз, упереться можно только специально.
MAX_COUPON_REPORTS_PER_HOUR = 10

# Алфавит кода погашения — без похожих символов (0/O, 1/I), чтобы диктовать/вводить без ошибок.
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_CODE_LEN = 6

# Статусы, при которых бронь «занимает» лимит (общий и на пользователя).
_ACTIVE_REDEMPTION_STATUSES = ("reserved", "redeemed")


# ---------- Тела запросов ----------

class RedeemIn(BaseModel):
    code: str = Field("", max_length=12)


class SubscribeIn(BaseModel):
    plan: str = Field("", max_length=20)


class PartnerIn(BaseModel):
    name: str = Field("", max_length=120)
    category: str = Field("other", max_length=20)
    city: str = Field("", max_length=80)
    address: str = Field("", max_length=200)
    phone: str = Field("", max_length=40)
    description: str = Field("", max_length=2000)
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)


class CouponIn(BaseModel):
    title: str = Field("", max_length=120)
    description: str = Field("", max_length=2000)
    discount_text: str = Field("", max_length=80)
    city: str = Field("", max_length=80)
    route_hint: str = Field("", max_length=300)
    valid_from: Optional[datetime] = None
    valid_until: Optional[datetime] = None
    limit_total: int = 0
    limit_per_user: int = 1
    premium: bool = False


class CouponStatusIn(BaseModel):
    status: str


def _csv(s: str) -> List[str]:
    return [x.strip() for x in (s or "").split(",") if x.strip()]


def moderate_and_clamp_reason(reason: Optional[str], user_id: int) -> str:
    """Текст жалобы: обрезать и проверить. Его читает админ, но правило одно на все поля."""
    text = (reason or "").strip()[:500]
    if text:
        moderate_open_text(text, user_id)
    return text


def _moderate_storefront(user_id: int, *parts: str) -> str:
    """Проверить текст, который попадёт в ПУБЛИЧНУЮ витрину купонов. Возвращает метку ('' — чисто).

    Витрина `/coupons` открыта без входа, а модерации у неё не было вовсе (аудит 2026-08-08).
    Метка (`contact`/`abuse`/`warn`) копится в админ-пульсе, как везде, и дополнительно
    решает судьбу купона: помеченный в витрину не выпускаем (см. `_apply_review`).
    """
    text = "\n".join(p.strip() for p in parts if p and p.strip())
    return moderate_open_text(text, user_id) if text else ""


# Поля витрины бизнеса, которые ЧИТАЕТ человек. Их правка после одобрения возвращает карточку
# на модерацию (решение Александра, 2026-08-08). Категория, город и координаты сюда не входят
# намеренно: бизнес, передвинувший пин на карте или сменивший категорию, не должен пропадать
# из витрины до следующего захода админа — переписать этим текст объявления нельзя.
_PARTNER_TEXT_FIELDS = ("name", "description", "address", "phone")


def _partner_text(partner: Partner) -> tuple:
    return tuple((getattr(partner, f, "") or "").strip() for f in _PARTNER_TEXT_FIELDS)


def _requeue_partner_after_edit(session: Session, partner: Partner, before: tuple) -> bool:
    """Одобренный бизнес переписал видимый текст → карточка снова на модерации.

    Зачем строго. Одобрение админом закрывало только ПЕРВЫЙ показ: карточку, одобренную
    чистой, владелец потом переписывал во что угодно, и она уходила в витрину сразу — это
    классическая подмена после проверки (аудит 2026-08-08). Теперь как у рекламы: правка
    текста снимает карточку с витрины до нового одобрения.

    Цена решения принята сознательно: бизнес, поправивший телефон, пропадает из витрины до
    захода админа. Поэтому и re-moderation только на ТЕКСТ (см. `_PARTNER_TEXT_FIELDS`) и
    только при реальном изменении — повторное сохранение той же формы карточку не роняет.
    """
    if partner.status != "active" or _partner_text(partner) == before:
        return False
    partner.status = "pending"
    partner.reviewed_at = None
    partner.reject_reason = ""
    session.add(partner)
    return True


def _gen_code(session: Session) -> str:
    """Уникальный короткий код погашения (проверка коллизии по БД)."""
    for _ in range(20):
        code = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN))
        exists = session.exec(select(CouponRedemption.id).where(CouponRedemption.code == code)).first()
        if not exists:
            return code
    # практически недостижимо (32^6 пространство) — на всякий случай удлиняем
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN + 2))


def _sub_active(partner: Partner, now: datetime) -> bool:
    """Подписка бизнеса оплачена и не истекла (гейт места в витрине)."""
    return partner.subscription_until is not None and partner.subscription_until > now


def _partner_has_premium(partner: Partner) -> bool:
    """Право на premium-метку купона: активная подписка premium-тарифа."""
    plan = PARTNER_PLANS.get(partner.subscription_plan)
    return bool(plan and plan.get("premium")) and _sub_active(partner, utcnow())


def _in_window(coupon: Coupon, now: datetime) -> bool:
    if coupon.valid_from and coupon.valid_from > now:
        return False
    if coupon.valid_until and coupon.valid_until <= now:
        return False
    return True


def _total_exhausted(coupon: Coupon) -> bool:
    return coupon.limit_total > 0 and coupon.redeemed_count >= coupon.limit_total


def _coupon_visible(coupon: Coupon, partner: Optional[Partner], now: datetime) -> bool:
    """Виден ли купон в публичной витрине: партнёр active + подписка оплачена +
    купон active + в окне дат + общий лимит не исчерпан."""
    if partner is None or partner.status != "active" or not _sub_active(partner, now):
        return False
    if coupon.status != "active":
        return False
    # Состояние проверки: `held` (автопроверка пометила) и `blocked` (админ снял) в витрину
    # не пускаем. `pending` пускаем намеренно — чистый текст публикуется сразу, человек
    # смотрит его потом (иначе один админ становится узким местом всей витрины).
    if getattr(coupon, "review", "approved") not in COUPON_REVIEW_VISIBLE:
        return False
    if not _in_window(coupon, now):
        return False
    if _total_exhausted(coupon):
        return False
    return True


def _partner_public(partner: Partner) -> dict:
    """Публичные данные бизнеса для карточки купона (без приватного)."""
    return {
        "id": partner.id,
        "name": partner.name,
        "category": partner.category,
        "city": partner.city,
        "address": partner.address,
        "lat": partner.lat,
        "lng": partner.lng,
        "phone": partner.phone,      # публичный контакт БИЗНЕСА (не пользователя)
    }


def _coupon_public(coupon: Coupon, partner: Optional[Partner]) -> dict:
    remaining = None
    if coupon.limit_total > 0:
        remaining = max(0, coupon.limit_total - coupon.redeemed_count)
    return {
        "id": coupon.id,
        "partner": _partner_public(partner) if partner else None,
        "title": coupon.title,
        "description": coupon.description,
        "discount_text": coupon.discount_text,
        "city": coupon.city,
        "route_hint": _csv(coupon.route_hint),
        "valid_from": coupon.valid_from.isoformat() if coupon.valid_from else None,
        "valid_until": coupon.valid_until.isoformat() if coupon.valid_until else None,
        "limit_total": coupon.limit_total,
        "limit_per_user": coupon.limit_per_user,
        "redeemed_count": coupon.redeemed_count,
        "remaining": remaining,          # null = без общего лимита
        "premium": coupon.premium,
        "status": coupon.status,
    }


# ---------- Публичная витрина ----------

@router.get("/coupons")
def coupons_list(
    city: Optional[str] = None,
    route: Optional[str] = None,
    session: Session = Depends(get_session),
):
    """Активные купоны «Скидки по пути». Видны только у active-партнёра с оплаченной подпиской,
    в окне дат и с неисчерпанным общим лимитом. Premium выше. Просмотр без входа."""
    now = utcnow()
    coupons = session.exec(select(Coupon).where(Coupon.status == "active")).all()
    if not coupons:
        return []
    partner_ids = list({c.partner_id for c in coupons})
    partners = {p.id: p for p in session.exec(select(Partner).where(Partner.id.in_(partner_ids))).all()}
    live = [c for c in coupons if _coupon_visible(c, partners.get(c.partner_id), now)]
    if city:
        cl = city.strip().lower()
        live = [c for c in live if c.city and c.city.strip().lower() == cl]
    if route:
        rl = route.strip().lower()
        live = [c for c in live if rl in [x.lower() for x in _csv(c.route_hint)] or (c.city and c.city.strip().lower() == rl)]
    # premium выше; внутри — свежие сверху
    live.sort(key=lambda c: (1 if c.premium else 0, c.created_at or now), reverse=True)
    return [_coupon_public(c, partners.get(c.partner_id)) for c in live]


@router.post("/coupons/redeem")
def coupon_redeem(body: RedeemIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Бизнес гасит код у себя. Право — только владелец партнёра этого купона.
    Чужой/несуществующий код → 404 (не раскрываем существование). Уже погашён → 409.
    Приватность: возвращаем максимум имя держателя, НЕ телефон и НЕ гео."""
    code = (body.code or "").strip().upper()
    red = None
    if code:
        # Row-lock кода: два кассира с одним QR не погасят его дважды (оба «ok» = спор с клиентом).
        red = session.exec(select(CouponRedemption).where(CouponRedemption.code == code).with_for_update()).first()
    if not red:
        raise herr(404, "Код не найден", "Код табылманы")
    coupon = session.get(Coupon, red.coupon_id)
    partner = session.get(Partner, coupon.partner_id) if coupon else None
    # Право гасить — только владелец бизнеса. Чужому отдаём тот же 404 (не раскрываем код).
    if not partner or partner.owner_id != user.id:
        raise herr(404, "Код не найден", "Код табылманы")
    if red.status == "redeemed":
        raise herr(409, "Код уже погашён", "Код инде ҡулланылған")
    if red.status in ("canceled", "expired"):
        raise herr(409, "Код больше не действует", "Код артыҡ ғәмәлдә түгел")
    red.status = "redeemed"
    red.redeemed_at = utcnow()
    red.redeemed_by = user.id
    # Инкремент на стороне БД: read-modify-write в Python терял бы параллельные погашения
    # разных кодов (счётчик — основа statement'а партнёру по 10 ₽/погашение).
    session.exec(sa_update(Coupon).where(Coupon.id == coupon.id)
                 .values(redeemed_count=Coupon.redeemed_count + 1))
    session.add(red)
    session.commit()
    holder = session.get(User, red.user_id)
    return {
        "ok": True,
        "coupon_title": coupon.title,
        "discount_text": coupon.discount_text,
        "customer_name": (holder.name if holder and holder.name else ""),  # максимум имя, без телефона/гео
    }


@router.get("/coupons/{coupon_id}")
def coupon_detail(coupon_id: int, session: Session = Depends(get_session)):
    """Деталь купона (та же видимость, что в витрине)."""
    coupon = session.get(Coupon, coupon_id)
    partner = session.get(Partner, coupon.partner_id) if coupon else None
    if not coupon or not _coupon_visible(coupon, partner, utcnow()):
        raise herr(404, "Купон не найден", "Купон табылманы")
    return _coupon_public(coupon, partner)


@router.post("/coupons/{coupon_id}/activate")
def coupon_activate(coupon_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Пользователь бронирует погашение купона → получает короткий код.
    Идемпотентность: активная reserved-бронь этого юзера на этот купон возвращается как есть.

    Гранулярные ошибки (не общий 404): срок истёк → 422, лимиты → 409. «Недоступен вообще»
    (нет партнёра / не одобрен / подписка не оплачена / купон не active) → 404, как в витрине."""
    now = utcnow()
    # V2: row-lock на купоне сериализует параллельные активации — иначе два запроса оба проходят
    # COUNT-проверки limit_total/limit_per_user и оба вставляют бронь (пробитие лимита). На SQLite
    # FOR UPDATE — no-op (тесты однопоточны), на проде Postgres второй ждёт коммита первого.
    coupon = session.exec(select(Coupon).where(Coupon.id == coupon_id).with_for_update()).one_or_none()
    partner = session.get(Partner, coupon.partner_id) if coupon else None
    # Базовая доступность (без окна дат и лимитов — их проверяем отдельно ниже с точными кодами).
    available = (
        coupon is not None and partner is not None
        and partner.status == "active" and _sub_active(partner, now)
        and coupon.status == "active"
        # Состояние проверки — тем же правилом, что и витрина (аудит 2026-08-08, волна 145).
        # Раньше активация его не спрашивала: снятый за обман купон исчезал с полки, но
        # продолжал выдавать коды по прямой ссылке — из истории, из пересланного другу
        # сообщения. Человек приходил с этим кодом в кафе, а счётчик погашений рос, и платформа
        # выставляла бизнесу счёт за скидки, которые он уже отменил.
        #
        # «Снять с витрины» должно означать «не работает», а не «спрятана полка».
        and coupon.review in COUPON_REVIEW_VISIBLE
    )
    if not available:
        raise herr(404, "Купон не найден", "Купон табылманы")

    # Идемпотентность: уже есть активная бронь → возвращаем её же (тот же код), не плодим.
    existing = session.exec(
        select(CouponRedemption).where(
            CouponRedemption.coupon_id == coupon.id,
            CouponRedemption.user_id == user.id,
            CouponRedemption.status == "reserved",
        )
    ).first()
    if existing:
        return _activation_out(existing, coupon, partner)

    if not _in_window(coupon, now):
        raise herr(422, "Срок купона истёк", "Купон ваҡыты үтте")

    # Общий лимит: считаем занятые брони (reserved+redeemed) против limit_total.
    if coupon.limit_total > 0:
        taken = session.exec(
            select(func.count()).select_from(CouponRedemption).where(
                CouponRedemption.coupon_id == coupon.id,
                CouponRedemption.status.in_(_ACTIVE_REDEMPTION_STATUSES),
            )
        ).one()
        if taken >= coupon.limit_total:
            raise herr(409, "Купоны закончились", "Купондар бөттө")

    # Лимит на пользователя.
    mine = session.exec(
        select(func.count()).select_from(CouponRedemption).where(
            CouponRedemption.coupon_id == coupon.id,
            CouponRedemption.user_id == user.id,
            CouponRedemption.status.in_(_ACTIVE_REDEMPTION_STATUSES),
        )
    ).one()
    if mine >= max(1, coupon.limit_per_user):
        raise herr(409, "Ты уже воспользовался этим купоном", "Һин был купондан файҙаландың инде")

    red = CouponRedemption(coupon_id=coupon.id, user_id=user.id, code=_gen_code(session), status="reserved")
    session.add(red)
    session.commit()
    session.refresh(red)
    return _activation_out(red, coupon, partner)


def _activation_out(red: CouponRedemption, coupon: Coupon, partner: Optional[Partner]) -> dict:
    return {
        "code": red.code,
        "status": red.status,
        "reserved_at": red.reserved_at.isoformat() if red.reserved_at else None,
        "coupon": _coupon_public(coupon, partner),
    }


@router.get("/my/coupons")
def my_coupons(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои брони купонов (активные коды + история), новые сверху."""
    rows = session.exec(
        select(CouponRedemption).where(CouponRedemption.user_id == user.id).order_by(CouponRedemption.id.desc())
    ).all()
    if not rows:
        return []
    coupon_ids = list({r.coupon_id for r in rows})
    coupons = {c.id: c for c in session.exec(select(Coupon).where(Coupon.id.in_(coupon_ids))).all()}
    partner_ids = list({c.partner_id for c in coupons.values()})
    partners = {p.id: p for p in session.exec(select(Partner).where(Partner.id.in_(partner_ids))).all()} if partner_ids else {}
    out = []
    for r in rows:
        c = coupons.get(r.coupon_id)
        out.append({
            "code": r.code,
            "status": r.status,
            "reserved_at": r.reserved_at.isoformat() if r.reserved_at else None,
            "redeemed_at": r.redeemed_at.isoformat() if r.redeemed_at else None,
            "coupon": _coupon_public(c, partners.get(c.partner_id)) if c else None,
        })
    return out


# ---------- Кабинет партнёра ----------

@router.get("/partner/plans")
def partner_plans():
    """Тарифы подписки бизнеса (из PARTNER_PLANS) — прайс для кабинета. Без авторизации."""
    return [
        {"code": code, "title": p["title"], "title_ba": p["title_ba"],
         "amount_kop": p["amount_kop"], "period_days": p["period_days"], "premium": p["premium"]}
        for code, p in PARTNER_PLANS.items()
    ]


def _partner_mine(partner: Partner) -> dict:
    """Сериализация СВОЕГО бизнеса для кабинета (статус модерации, подписка, причина отказа)."""
    now = utcnow()
    return {
        "id": partner.id,
        "name": partner.name,
        "category": partner.category,
        "city": partner.city,
        "address": partner.address,
        "phone": partner.phone,
        "description": partner.description,
        "lat": partner.lat,
        "lng": partner.lng,
        "status": partner.status,
        "reject_reason": partner.reject_reason,
        "subscription_plan": partner.subscription_plan,
        "subscription_until": partner.subscription_until.isoformat() if partner.subscription_until else None,
        "subscription_active": _sub_active(partner, now),
        "has_premium": _partner_has_premium(partner),
        "created_at": partner.created_at.isoformat() if partner.created_at else None,
    }


@router.post("/partner")
def partner_register(body: PartnerIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Зарегистрировать МОЙ бизнес (один owner = один партнёр). status=pending (модерация админом)."""
    existing = session.exec(select(Partner).where(Partner.owner_id == user.id)).first()
    if existing:
        raise herr(409, "У тебя уже есть бизнес", "Һинең бизнесың бар инде")
    name = body.name.strip()
    if not name:
        raise herr(422, "Название бизнеса обязательно", "Бизнес исеме мотлаҡ")
    city = body.city.strip()
    if not city:
        raise herr(422, "Укажи город бизнеса", "Бизнес ҡалаһын күрһәт")
    ensure_active(session, user.id)   # отстранённый разбором бизнес не регистрирует (волна 62)
    _moderate_storefront(user.id, name, body.description, body.address)
    partner = Partner(
        owner_id=user.id, name=name, category=(body.category.strip() or "other"), city=city,
        address=body.address.strip(), phone=body.phone.strip(), description=body.description.strip(),
        lat=body.lat, lng=body.lng, status="pending",
    )
    session.add(partner)
    session.commit()
    session.refresh(partner)
    try:  # уведомление админа — best-effort (не роняем регистрацию)
        notify_admin_telegram(
            f"🏪 Новый бизнес на модерации\n"
            f"ID: {partner.id}\n"
            f"Название: «{partner.name}»\n"
            f"Категория: {partner.category}\n"
            f"Город: {partner.city}\n"
            f"От: {user.name or 'партнёр'}"
        )
    except Exception:
        pass
    return _partner_mine(partner)


@router.get("/partner/me")
def partner_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мой бизнес + статус подписки + statement (сколько погашений и сумма к оплате платформе)."""
    partner = session.exec(select(Partner).where(Partner.owner_id == user.id)).first()
    if not partner:
        return {"partner": None}
    # Statement: сумма погашений по всем моим купонам × комиссия за погашение.
    redeemed_total = session.exec(
        select(func.coalesce(func.sum(Coupon.redeemed_count), 0)).where(Coupon.partner_id == partner.id)
    ).one()
    redeemed_total = int(redeemed_total or 0)
    return {
        "partner": _partner_mine(partner),
        "statement": {
            "redeemed_total": redeemed_total,
            "fee_per_redemption_kop": PARTNER_REDEMPTION_FEE_KOP,
            "amount_kop": redeemed_total * PARTNER_REDEMPTION_FEE_KOP,
        },
    }


def _own_partner(partner_id: int, user: User, session: Session) -> Partner:
    """Достать СВОЙ бизнес или 404 (чужой не раскрываем — IDOR закрыт)."""
    # Пауза «Справедливости» (§2). Коммерческий контур её не знал вовсе: отстранённый разбором
    # владелец создавал купоны, публиковал их в витрину и правил карточку бизнеса — проверено
    # запросом, всё по 200 (аудит 2026-08-13, волна 62). Правило §2 — «приостановленный аккаунт
    # не совершает активных действий», и витрина такое же активное действие, как публикация
    # поездки. УЖЕ опубликованное и оплаченное не гасим: это деньги бизнеса и обязательство
    # перед ним, а не его наказание (решение аудита, см. журнал волны 62).
    ensure_active(session, user.id)
    partner = session.get(Partner, partner_id)
    if not partner or partner.owner_id != user.id:
        raise herr(404, "Бизнес не найден", "Бизнес табылманы")
    return partner


@router.post("/partner/subscribe")
def partner_subscribe(body: SubscribeIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать платёж подписки бизнеса (СБП «на доверии», подтверждает админ).
    Бизнес должен быть одобрен (active). Идемпотентно: существующий pending → возвращаем его."""
    partner = session.exec(select(Partner).where(Partner.owner_id == user.id)).first()
    if not partner:
        raise herr(404, "Сначала зарегистрируй бизнес", "Башта бизнесты теркә")
    plan_code = (body.plan or "").strip()
    plan = PARTNER_PLANS.get(plan_code)
    if not plan:
        raise herr(422, "Неизвестный тариф", "Билдәһеҙ тариф")
    if partner.status != "active":
        raise herr(409, "Бизнес ещё на проверке", "Бизнес әле тикшереүҙә")
    # Идемпотентность: повторное нажатие «Оплатить» не плодит заявки. Но старый счёт годится
    # ТОЛЬКО если тариф тот же (волна 125). Раньше сравнения не было: хозяйка кафе ткнула
    # «Базовый 990 ₽», передумала на «Премиум 2 990 ₽» — и приложение снова просило 990,
    # а после оплаты включался базовый. В обратную сторону было злее: с премиума на базовый
    # она навсегда видела счёт на 2 990 без кнопки «отменить».
    existing = session.exec(
        select(Payment).where(
            Payment.purpose == "partner_sub", Payment.partner_id == partner.id,
            Payment.status == "pending", Payment.tier == plan_code,
        )
    ).first()
    if existing:
        return {"payment_id": existing.id, "amount_kop": existing.amount_kop, "plan": existing.tier, "status": "pending"}
    payment = Payment(
        user_id=user.id, purpose="partner_sub", partner_id=partner.id,
        tier=plan_code, amount_kop=plan["amount_kop"], status="pending",
    )
    session.add(payment)
    session.commit()
    session.refresh(payment)
    try:
        notify_admin_telegram(
            (
                f"💳 Подписка бизнеса СБП\n"
                f"ID платежа: {payment.id}\n"
                f"Бизнес: «{partner.name}»\n"
                f"Тариф: {plan['title']} · {plan['amount_kop'] // 100} ₽ / {plan['period_days']}дн\n"
                f"От: {user.name or 'партнёра'}\n\n"
                "Сначала проверь поступление в банке, потом подтверди здесь."
            ),
            reply_markup={
                "inline_keyboard": [[
                    {"text": "✅ Подтвердить", "callback_data": f"pay:ok:{payment.id}"},
                    {"text": "❌ Отклонить", "callback_data": f"pay:no:{payment.id}"},
                ]]
            },
        )
    except Exception:
        pass
    return {"payment_id": payment.id, "amount_kop": payment.amount_kop, "plan": plan_code, "status": "pending"}


# ---------- Купоны партнёра ----------

def _coupon_mine(coupon: Coupon, session: Session) -> dict:
    """Сериализация СВОЕГО купона для кабинета (все статусы + счётчики)."""
    active = session.exec(
        select(func.count()).select_from(CouponRedemption).where(
            CouponRedemption.coupon_id == coupon.id,
            CouponRedemption.status.in_(_ACTIVE_REDEMPTION_STATUSES),
        )
    ).one()
    return {
        "id": coupon.id,
        "partner_id": coupon.partner_id,
        "title": coupon.title,
        "description": coupon.description,
        "discount_text": coupon.discount_text,
        "city": coupon.city,
        "route_hint": _csv(coupon.route_hint),
        "valid_from": coupon.valid_from.isoformat() if coupon.valid_from else None,
        "valid_until": coupon.valid_until.isoformat() if coupon.valid_until else None,
        "limit_total": coupon.limit_total,
        "limit_per_user": coupon.limit_per_user,
        "redeemed_count": coupon.redeemed_count,
        "activations": int(active),        # reserved + redeemed
        "premium": coupon.premium,
        "status": coupon.status,
        # Состояние проверки — партнёр должен понимать, почему купон не виден людям.
        "review": getattr(coupon, "review", "approved"),
        "review_note": getattr(coupon, "review_note", "") or "",
        "reports_count": int(getattr(coupon, "reports_count", 0) or 0),
        "created_at": coupon.created_at.isoformat() if coupon.created_at else None,
    }


def _my_active_partner(user: User, session: Session) -> Partner:
    """Мой бизнес; для операций с купонами он должен быть одобрен (active).

    Отстранённый разбором (§2) новых купонов не заводит — см. `_own_partner` (волна 62).
    Оплату подписки при этом не трогаем: платить сервису человек вправе всегда, иначе
    наказание превращается в «не дам рассчитаться»."""
    ensure_active(session, user.id)
    partner = session.exec(select(Partner).where(Partner.owner_id == user.id)).first()
    if not partner:
        raise herr(404, "Сначала зарегистрируй бизнес", "Башта бизнесты теркә")
    if partner.status != "active":
        raise herr(409, "Бизнес ещё на проверке", "Бизнес әле тикшереүҙә")
    return partner


@router.get("/partner/coupons")
def partner_coupons(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои купоны — все статусы, новые сверху. Нет бизнеса → пусто."""
    partner = session.exec(select(Partner).where(Partner.owner_id == user.id)).first()
    if not partner:
        return []
    rows = session.exec(
        select(Coupon).where(Coupon.partner_id == partner.id).order_by(Coupon.id.desc())
    ).all()
    return [_coupon_mine(c, session) for c in rows]


@router.post("/partner/coupons")
def partner_coupon_create(body: CouponIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать купон (draft). Бизнес должен быть active. Анти-спам: лимит купонов на бизнес."""
    partner = _my_active_partner(user, session)
    count = session.exec(select(func.count()).select_from(Coupon).where(Coupon.partner_id == partner.id)).one()
    if count >= MAX_COUPONS_PER_PARTNER:
        raise herr(429, "Слишком много купонов — удали лишние", "Купондар артыҡ күп — артыҡтарын бетер")
    title = body.title.strip()
    if not title:
        raise herr(422, "Заголовок купона обязателен", "Купон исеме мотлаҡ")
    coupon = Coupon(
        partner_id=partner.id, title=title, description=body.description.strip(),
        discount_text=body.discount_text.strip(),
        city=(body.city.strip() or partner.city), route_hint=body.route_hint.strip(),
        valid_from=body.valid_from, valid_until=body.valid_until,
        limit_total=max(0, body.limit_total), limit_per_user=max(1, body.limit_per_user),
        premium=bool(body.premium) and _partner_has_premium(partner),   # premium-метка только на premium-подписке
        status="draft",
    )
    # Состояние проверки считаем по УЖЕ СОБРАННОМУ купону (а не по присланным полям):
    # в витрину уедет карточка целиком. Купон рождается черновиком, поэтому в витрину он
    # сейчас всё равно не попадёт — но метка и очередь заводятся сразу.
    _apply_review(coupon, user.id)
    session.add(coupon)
    session.commit()
    session.refresh(coupon)
    return _coupon_mine(coupon, session)


def _coupon_flag(coupon: Coupon, user_id: int) -> str:
    """Метка модерации по тексту купона, который увидит витрина ('' — чисто)."""
    return _moderate_storefront(user_id, coupon.title, coupon.description,
                                coupon.route_hint, coupon.discount_text)


# Состояния проверки, при которых купон ВИДЕН в витрине (см. Coupon.review в models.py).
# `pending` виден намеренно: чистый текст публикуется сразу, человек смотрит его потом —
# иначе один Александр с пятью минутами в день становится узким местом всей витрины.
COUPON_REVIEW_VISIBLE = ("pending", "approved")


def _apply_review(coupon: Coupon, user_id: int) -> str:
    """Пересчитать состояние проверки по текущему тексту купона. Возвращает метку ('' — чисто).

    Правило одно на все двери (создание, правка, жалоба): помечено → `held` (в витрину не
    выпускаем, ждём человека); чисто → `pending` (публикуем сразу, но кладём в очередь).
    Снятое админом (`blocked`) правка не воскрешает молча — оно тоже уходит в очередь,
    решает снова человек.
    """
    kind = _coupon_flag(coupon, user_id)
    coupon.review_flag = kind
    coupon.review = "held" if kind else "pending"
    coupon.reviewed_at = None          # решение админа устарело: текст уже другой
    return kind


def _tell_admin_coupon_held(coupon: Coupon, kind: str) -> None:
    try:  # best-effort: правку/смену статуса не роняем из-за Telegram
        notify_admin_telegram(
            f"🚫 Купон задержан проверкой текста\n"
            f"ID: {coupon.id}\n"
            f"Заголовок: «{coupon.title}»\n"
            f"Метка: {kind}\n"
            f"В витрину не выпущен. Разбор: Кабинет админа → Модерация"
        )
    except Exception:
        pass


def _own_coupon(coupon_id: int, user: User, session: Session) -> Coupon:
    """Достать СВОЙ купон (по владельцу бизнеса) или 404. Отстранённый разбором купоны
    не правит и не публикует — см. `_own_partner` (волна 62)."""
    ensure_active(session, user.id)
    coupon = session.get(Coupon, coupon_id)
    if coupon:
        partner = session.get(Partner, coupon.partner_id)
        if partner and partner.owner_id == user.id:
            return coupon
    raise herr(404, "Купон не найден", "Купон табылманы")


@router.post("/partner/coupons/{coupon_id}")
def partner_coupon_update(coupon_id: int, body: CouponIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Правка своего купона. Чужой → 404.

    Купоны бизнес публикует сам (их до 50 на бизнес — ставить каждый в очередь к админу
    значило бы утопить его в работе). Поэтому строгость здесь другая, чем у карточки
    бизнеса: помеченный проверкой текст в витрину не выпускаем — купон уходит в черновик,
    а админ получает сигнал. Чистый текст публикуется сразу, как и раньше.
    """
    coupon = _own_coupon(coupon_id, user, session)
    partner = session.get(Partner, coupon.partner_id)
    if body.title.strip():
        coupon.title = body.title.strip()
    coupon.description = body.description.strip()
    coupon.discount_text = body.discount_text.strip()
    if body.city.strip():
        coupon.city = body.city.strip()
    coupon.route_hint = body.route_hint.strip()
    coupon.valid_from = body.valid_from
    coupon.valid_until = body.valid_until
    coupon.limit_total = max(0, body.limit_total)
    coupon.limit_per_user = max(1, body.limit_per_user)
    coupon.premium = bool(body.premium) and _partner_has_premium(partner)
    # Проверяем УЖЕ СОБРАННЫЙ купон (а не присланные поля): правка может подменить одно поле,
    # а «в витрину» уедет вся карточка целиком.
    kind = _apply_review(coupon, user.id)
    session.add(coupon)
    session.commit()
    session.refresh(coupon)
    if kind:
        _tell_admin_coupon_held(coupon, kind)
        push_notification(
            session, user.id, "coupon",
            "Купон снят с витрины", "Купон витринанан алынды",
            f"«{coupon.title}»: текст не прошёл проверку и в витрину не попал. "
            "Убери телефон или ссылку и сохрани снова.",
            f"«{coupon.title}»: текст тикшереүҙе үтмәне, витринаға эләкмәне. "
            "Телефонды йәки һылтанманы алып ташла ла яңынан һаҡла.",
            ref_kind="partner", ref_id=coupon.partner_id,
        )
    return _coupon_mine(coupon, session)


@router.post("/partner/coupons/{coupon_id}/status")
def partner_coupon_status(coupon_id: int, body: CouponStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сменить статус купона (draft↔active↔paused↔archived).

    Включить в витрину можно только купон с чистым текстом: иначе достаточно было сохранить
    карточку черновиком и нажать «включить» — правка проверяется, а включение нет.
    """
    coupon = _own_coupon(coupon_id, user, session)
    new_status = (body.status or "").strip()
    if new_status not in ("draft", "active", "paused", "archived"):
        raise herr(422, "Недопустимый статус", "Ярамаған статус")
    if new_status == "active":
        # Смотрим СОСТОЯНИЕ проверки, а не пересчитываем текст заново. Пересчёт здесь был бы
        # дырой: текст с прошлой правки не менялся, значит чистый купон, снятый админом
        # вручную, тем же пересчётом молча воскресал бы обратно в витрину.
        if coupon.review == "blocked":
            raise herr(409,
                       "Купон снят администратором — поправь текст и сохрани, он снова уйдёт на проверку.",
                       "Купонды администратор алды — тексты төҙәт тә һаҡла, ул тағы тикшереүгә китә.")
        if coupon.review == "held":
            _tell_admin_coupon_held(coupon, coupon.review_flag or "flagged")
            raise herr(422,
                       "Текст купона не прошёл проверку — убери телефон, ссылку или резкие слова.",
                       "Купон тексты тикшереүҙе үтмәне — телефонды, һылтанманы йәки ҡаты һүҙҙәрҙе алып ташла.")
    coupon.status = new_status
    session.add(coupon)
    session.commit()
    session.refresh(coupon)
    return _coupon_mine(coupon, session)


@router.get("/partner/coupons/{coupon_id}/stats")
def partner_coupon_stats(coupon_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Статистика купона: активации (reserved+redeemed), погашения, сумма к оплате платформе."""
    coupon = _own_coupon(coupon_id, user, session)
    activations = session.exec(
        select(func.count()).select_from(CouponRedemption).where(
            CouponRedemption.coupon_id == coupon.id,
            CouponRedemption.status.in_(_ACTIVE_REDEMPTION_STATUSES),
        )
    ).one()
    return {
        "coupon_id": coupon.id,
        "title": coupon.title,
        "status": coupon.status,
        "activations": int(activations),          # reserved + redeemed
        "redeemed": coupon.redeemed_count,
        "fee_per_redemption_kop": PARTNER_REDEMPTION_FEE_KOP,
        "amount_kop": coupon.redeemed_count * PARTNER_REDEMPTION_FEE_KOP,
    }


# Динамический /partner/{id} регистрируем ПОСЛЕ статических /partner/coupons и /partner/subscribe:
# FastAPI не сужает int-path на уровне роутинга, иначе «coupons»/«subscribe» ловились бы сюда → 422.
@router.post("/partner/{partner_id}")
def partner_update(partner_id: int, body: PartnerIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Правка своего бизнеса (name/category/address/phone/description/lat/lng). Чужой → 404.

    Правка ВИДИМОГО ТЕКСТА у одобренного бизнеса возвращает карточку на модерацию —
    см. `_requeue_partner_after_edit`. Категория, город и координаты карточку не роняют.
    """
    partner = _own_partner(partner_id, user, session)
    _moderate_storefront(user.id, body.name, body.description, body.address)
    before = _partner_text(partner)
    if body.name.strip():
        partner.name = body.name.strip()
    if body.category.strip():
        partner.category = body.category.strip()
    if body.city.strip():
        partner.city = body.city.strip()
    partner.address = body.address.strip()
    partner.phone = body.phone.strip()
    partner.description = body.description.strip()
    if body.lat is not None:
        partner.lat = body.lat
    if body.lng is not None:
        partner.lng = body.lng
    requeued = _requeue_partner_after_edit(session, partner, before)
    session.add(partner)
    session.commit()
    session.refresh(partner)
    if requeued:
        # Человеку — честно и сразу: почему его купоны исчезли из витрины.
        push_notification(
            session, partner.owner_id, "coupon",
            "Карточка снова на проверке", "Карточка яңынан тикшереүҙә",
            f"«{partner.name}»: текст изменён, поэтому купоны скрыты из витрины до проверки. "
            "Обычно это недолго.",
            f"«{partner.name}»: текст үҙгәртелде, шуға купондар тикшергәнгә тиклем витринанан "
            "йәшерелде. Ғәҙәттә был оҙаҡҡа бармай.",
            ref_kind="partner", ref_id=partner.id,
        )
        try:  # админу — best-effort, правку не роняем
            notify_admin_telegram(
                f"✏️ Бизнес изменил карточку — нужна проверка\n"
                f"ID: {partner.id}\n"
                f"Название: «{partner.name}»\n"
                f"Город: {partner.city}\n"
                f"Телефон: {partner.phone or '—'}\n"
                f"Купоны скрыты из витрины. Разбор: Кабинет админа → Бизнесы"
            )
        except Exception:
            pass
    return _partner_mine(partner)


# ---------- Админ: модерация бизнеса ----------
# Админские ошибки — обычный HTTPException со строкой (двуязычие требуется только для 4xx пользователю).

def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def _partner_admin(partner: Partner) -> dict:
    """Карточка бизнеса для админ-очереди модерации."""
    now = utcnow()
    return {
        "id": partner.id,
        "owner_id": partner.owner_id,
        "name": partner.name,
        "category": partner.category,
        "city": partner.city,
        "address": partner.address,
        "phone": partner.phone,
        "description": partner.description,
        "status": partner.status,
        "reject_reason": partner.reject_reason,
        "subscription_plan": partner.subscription_plan,
        "subscription_until": partner.subscription_until.isoformat() if partner.subscription_until else None,
        "subscription_active": _sub_active(partner, now),
        "created_at": partner.created_at.isoformat() if partner.created_at else None,
        "reviewed_at": partner.reviewed_at.isoformat() if partner.reviewed_at else None,
    }


class RejectIn(BaseModel):
    reason: str = Field("", max_length=500)


@router.get("/admin/partners")
def admin_partners(limit: int = 200, offset: int = 0,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все бизнесы (очередь модерации), pending сверху."""
    _require_admin(user)
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    rows = session.exec(
        select(Partner).order_by(Partner.id.desc()).offset(offset).limit(limit)
    ).all()
    order = {"pending": 0, "active": 1, "paused": 2, "rejected": 3, "archived": 4}
    rows.sort(key=lambda p: (order.get(p.status, 9), -(p.id or 0)))
    return [_partner_admin(p) for p in rows]


@router.post("/admin/partners/{partner_id}/approve")
def admin_partner_approve(partner_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Одобрить бизнес (→ active). Место в витрине даёт оплаченная подписка."""
    _require_admin(user)
    partner = session.get(Partner, partner_id)
    if not partner:
        raise herr(404, "Бизнес не найден", "Бизнес табылманы")
    partner.status = "active"
    partner.reject_reason = ""
    partner.reviewed_at = utcnow()
    session.add(partner)
    session.commit()
    session.refresh(partner)
    push_notification(
        session, partner.owner_id, "coupon",
        "Бизнес одобрен", "Бизнес раҫланды",
        f"«{partner.name}» прошёл проверку. Осталось выбрать тариф и разместить купоны.",
        f"«{partner.name}» тикшереүҙе үтте. Хәҙер тарифты һайлап, купондарҙы ҡуйырға ҡала.",
        ref_kind="partner", ref_id=partner.id,
    )
    admin_action(user.id, "partner.approve", partner_id=partner_id)
    return _partner_admin(partner)


@router.post("/admin/partners/{partner_id}/reject")
def admin_partner_reject(partner_id: int, body: RejectIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить бизнес (→ rejected) с причиной."""
    _require_admin(user)
    partner = session.get(Partner, partner_id)
    if not partner:
        raise herr(404, "Бизнес не найден", "Бизнес табылманы")
    partner.status = "rejected"
    partner.reject_reason = body.reason.strip()[:500]
    partner.reviewed_at = utcnow()
    session.add(partner)
    session.commit()
    session.refresh(partner)
    push_notification(
        session, partner.owner_id, "coupon",
        "Бизнес отклонён", "Бизнес кире ҡағылды",
        (partner.reject_reason or "Проверь данные и отправь снова")[:120],
        (partner.reject_reason or "Мәғлүмәттәрҙе тикшереп, яңынан ебәр")[:120],
        ref_kind="partner", ref_id=partner.id,
    )
    admin_action(user.id, "partner.reject", partner_id=partner_id)
    return _partner_admin(partner)


# ---------- Админ: единая очередь модерации витрины ----------
# Зачем одна очередь, а не два экрана. У Александра 5–10 минут в день. Пока «непросмотренное»
# лежало в двух местах (бизнесы — в своём списке, купоны — нигде), купон с чистым текстом
# не видел НИКТО и НИКОГДА: автопроверка ищет телефоны, ссылки и ругань по шаблонам и
# спокойно пропускает «скидка 90% при предоплате на карту» (аудит 2026-08-08).
#
# Порядок в очереди — по срочности, а не по дате: сверху то, что кого-то БЛОКИРУЕТ
# (бизнес ждёт первого одобрения, купон задержан автопроверкой), ниже — «посмотреть потом».

class CouponBlockIn(BaseModel):
    reason: str = Field("", max_length=500)


class CouponReportIn(BaseModel):
    reason: str = Field("", max_length=500)


def _coupon_admin(coupon: Coupon, partner: Optional[Partner]) -> dict:
    """Карточка купона для очереди модерации: текст целиком + чем помечен + жалобы."""
    return {
        "id": coupon.id,
        "partner_id": coupon.partner_id,
        "partner_name": (partner.name if partner else ""),
        "city": coupon.city,
        "title": coupon.title,
        "description": coupon.description,
        "discount_text": coupon.discount_text,
        "route_hint": _csv(coupon.route_hint),
        "status": coupon.status,
        "review": coupon.review,
        "review_flag": coupon.review_flag or "",
        "review_note": coupon.review_note or "",
        "reports_count": int(coupon.reports_count or 0),
        "visible": coupon.status == "active" and coupon.review in COUPON_REVIEW_VISIBLE,
        "created_at": coupon.created_at.isoformat() if coupon.created_at else None,
    }


@router.get("/admin/moderation")
def admin_moderation_queue(limit: int = 100, user: User = Depends(current_user),
                           session: Session = Depends(get_session)):
    """Одна очередь «что я ещё не смотрел»: бизнесы на модерации + купоны без решения.

    Купоны с меткой автопроверки (`held`) идут первыми: они не видны людям и ждут человека.
    Дальше — те, у кого есть жалобы. Потом просто непросмотренные (`pending`), которые уже
    висят в витрине. Возвращаем и общий счётчик — приложение рисует его на кнопке.
    """
    _require_admin(user)
    limit = max(1, min(limit, 300))

    partners = session.exec(
        select(Partner).where(Partner.status == "pending")
        .order_by(Partner.id.asc()).limit(limit)
    ).all()

    coupons = session.exec(
        select(Coupon).where(Coupon.review.in_(("held", "pending")))
        .order_by(Coupon.id.asc()).limit(limit)
    ).all()
    pmap = {p.id: p for p in session.exec(
        select(Partner).where(Partner.id.in_({c.partner_id for c in coupons}))
    ).all()} if coupons else {}
    # Срочность: задержанные автопроверкой → с жалобами → остальные непросмотренные.
    def _urgency(c: Coupon) -> tuple:
        return (0 if c.review == "held" else (1 if (c.reports_count or 0) > 0 else 2), c.id or 0)
    coupons = sorted(coupons, key=_urgency)

    return {
        "partners": [_partner_admin(p) for p in partners],
        "coupons": [_coupon_admin(c, pmap.get(c.partner_id)) for c in coupons],
        "total": len(partners) + len(coupons),
    }


@router.post("/admin/coupons/{coupon_id}/approve")
def admin_coupon_approve(coupon_id: int, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """«Посмотрел, всё в порядке» — купон уходит из очереди и виден людям."""
    _require_admin(user)
    coupon = session.get(Coupon, coupon_id)
    if not coupon:
        raise herr(404, "Купон не найден", "Купон табылманы")
    was_held = coupon.review == "held"
    coupon.review = "approved"
    coupon.review_note = ""
    coupon.reviewed_at = utcnow()
    session.add(coupon)
    session.commit()
    session.refresh(coupon)
    if was_held:   # человек ждал решения — скажем, что можно работать
        partner = session.get(Partner, coupon.partner_id)
        if partner:
            push_notification(
                session, partner.owner_id, "coupon",
                "Купон одобрен", "Купон раҫланды",
                f"«{coupon.title}» проверен и виден в витрине.",
                f"«{coupon.title}» тикшерелде һәм витринала күренә.",
                ref_kind="partner", ref_id=partner.id,
            )
    admin_action(user.id, "coupon.approve", coupon_id=coupon_id)
    return _coupon_admin(coupon, session.get(Partner, coupon.partner_id))


@router.post("/admin/coupons/{coupon_id}/block")
def admin_coupon_block(coupon_id: int, body: CouponBlockIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Снять купон с витрины с причиной. Партнёр видит причину и может поправить текст."""
    _require_admin(user)
    coupon = session.get(Coupon, coupon_id)
    if not coupon:
        raise herr(404, "Купон не найден", "Купон табылманы")
    coupon.review = "blocked"
    coupon.review_note = (body.reason or "").strip()[:500]
    coupon.reviewed_at = utcnow()
    session.add(coupon)
    session.commit()
    session.refresh(coupon)
    partner = session.get(Partner, coupon.partner_id)
    if partner:
        push_notification(
            session, partner.owner_id, "coupon",
            "Купон снят с витрины", "Купон витринанан алынды",
            (coupon.review_note or "Поправь текст и сохрани — он снова уйдёт на проверку")[:120],
            (coupon.review_note or "Текстты төҙәтеп һаҡла — ул яңынан тикшереүгә китә")[:120],
            ref_kind="partner", ref_id=partner.id,
        )
    admin_action(user.id, "coupon.block", coupon_id=coupon_id)
    return _coupon_admin(coupon, partner)


# ---------- Жалоба пользователя на купон ----------

@router.post("/coupons/{coupon_id}/report")
def report_coupon(coupon_id: int, body: CouponReportIn, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """«Обещали не то» — жалоба ставит купон перед глазами админа.

    Жалоба НЕ снимает купон с витрины: иначе конкурент выключал бы чужую скидку одной
    кнопкой. Она только возвращает купон в очередь (`approved` → `pending`) и увеличивает
    счётчик, по которому очередь сортируется. Уже задержанный (`held`) или снятый (`blocked`)
    состояние не меняет — там и так решает человек.

    Один человек — одна жалоба на купон (UNIQUE в БД): повторными нажатиями очередь не засыпать.
    """
    if user_over_limit("coupon_report", user.id, MAX_COUPON_REPORTS_PER_HOUR, window_sec=3600):
        raise herr(429, "Слишком много жалоб подряд. Подожди немного.",
                   "Артыҡ күп зар. Бер аҙ көт.")
    coupon = session.get(Coupon, coupon_id)
    if not coupon:
        raise herr(404, "Купон не найден", "Купон табылманы")
    reason = moderate_and_clamp_reason(body.reason, user.id)
    session.add(CouponReport(coupon_id=coupon.id, user_id=user.id, reason=reason))
    try:
        session.commit()
    except IntegrityError:      # уже жаловался — идемпотентно, второй раз счётчик не растёт
        session.rollback()
        return {"ok": True, "already": True}
    coupon.reports_count = int(coupon.reports_count or 0) + 1
    if coupon.review == "approved":
        coupon.review = "pending"       # снова в очередь: человек сказал, что тут что-то не так
        coupon.reviewed_at = None
    session.add(coupon)
    session.commit()
    if coupon.reports_count == 1:       # первый сигнал — зовём админа, дальше не спамим
        try:
            notify_admin_telegram(
                f"⚠️ Жалоба на купон\n"
                f"ID: {coupon.id}\n"
                f"Заголовок: «{coupon.title}»\n"
                f"Причина: {reason or '—'}\n"
                f"Купон в очереди. Разбор: Кабинет админа → Модерация"
            )
        except Exception:
            pass
    return {"ok": True, "already": False}
