"""Рекламная система: публичная выдача (active + в периоде), события, статистика, админ-CRUD.

Слоты: founder — лимит 10, место навсегда (ends_at=null). standard/premium — по сроку.
Видимость в приложении: status==active И starts_at<=now И (ends_at null ИЛИ ends_at>now).
Маркировка (РФ закон): поле erid обязательно, в выдаче есть partner_name → «Реклама · …».
"""
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlmodel import Session, select

from ..db import get_session
from ..models import Ad, AdEvent, Payment, User, UserRole
from ..security import current_user
from ..services import notify_admin_telegram, send_push_bi
from ..timeutil import utcnow

router = APIRouter(tags=["ads"])

FOUNDER_LIMIT = 10
PLAN_PRIORITY = {"premium": 30, "standard": 20, "founder": 10}

# Тарифы размещения рекламы (self-serve кабинет партнёра). Цены — стартовая гипотеза,
# меняются ЗДЕСЬ без пересборки клиента. amount_kop — стоимость за period_days.
# placements — куда крутится объявление (CSV, см. Ad.placements). Порядок = порядок показа в кабинете.
AD_PACKAGES = {
    "city":  {"title": "Город",           "title_ba": "Ҡала",        "amount_kop": 100_000, "period_days": 30, "placements": "nearby,profile"},
    "route": {"title": "Маршрут",         "title_ba": "Маршрут",     "amount_kop": 300_000, "period_days": 30, "placements": "route,ridesList"},
    "main":  {"title": "Главный партнёр", "title_ba": "Төп партнёр", "amount_kop": 800_000, "period_days": 30, "placements": "route,ridesList,nearby,tripDetails"},
}
# Статусы объявления, которые партнёр может редактировать (черновик / после отказа).
AD_EDITABLE_STATUSES = ("draft", "rejected")
# Анти-спам: не даём одному владельцу плодить бесконечно объявлений.
MAX_ADS_PER_OWNER = 20


def _csv(s: str) -> List[str]:
    return [x.strip() for x in (s or "").split(",") if x.strip()]


def _is_live(ad: Ad, now: datetime) -> bool:
    if ad.status != "active":
        return False
    if ad.starts_at and ad.starts_at > now:
        return False
    if ad.ends_at is not None and ad.ends_at <= now:
        return False
    return True


def _ad_public(ad: Ad) -> dict:
    placements = _csv(ad.placements)
    cities = _csv(ad.cities)
    return {
        "id": str(ad.id),
        "partner": ad.partner_name,
        "title": ad.title,
        "text": ad.text,
        "button": ad.button,
        "target": ad.target,
        "contact": ad.partner_contact,  # реальный телефон/контакт партнёра (клик по рекламе → звонок именно сюда, не на демо-номер)
        "image": ad.image_url,
        "erid": ad.erid,
        "plan": ad.plan,
        "placements": placements,
        "placement": placements[0] if placements else "",  # совместимость со старым клиентом
        "cities": cities,
        "city": cities[0] if cities else "",  # первый город таргета (фильтр карточки «Партнёр рядом»)
    }


# ---------- Публично ----------

@router.get("/ads")
def ads(city: Optional[str] = None, placement: Optional[str] = None, session: Session = Depends(get_session)):
    """Активные объявления в периоде. Фильтр по городу/месту опционален. Сортировка по приоритету (premium выше)."""
    now = utcnow()
    rows = session.exec(select(Ad).where(Ad.status == "active")).all()
    live = [a for a in rows if _is_live(a, now)]
    # Гейт оплаты для партнёрских (self-serve) объявлений: показываем только оплаченные.
    # Админские (owner_id=None) — как раньше (публикуются админом/подтверждением оплаты).
    partner_ids = [a.id for a in live if a.owner_id is not None]
    if partner_ids:
        paid = _paid_ad_ids(session, partner_ids)
        live = [a for a in live if a.owner_id is None or a.id in paid]
    if city:
        live = [a for a in live if not _csv(a.cities) or city in _csv(a.cities)]
    if placement:
        live = [a for a in live if placement in _csv(a.placements)]
    live.sort(key=lambda a: (a.priority, PLAN_PRIORITY.get(a.plan, 0)), reverse=True)
    return [_ad_public(a) for a in live]


class AdEventIn(BaseModel):
    type: str


@router.post("/ads/{ad_id}/event")
def ad_event(ad_id: int, body: AdEventIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Записать показ/клик (реальная статистика кабинета). Требует входа — иначе
    любой неавторизованный накручивал бы статистику и засорял таблицу AdEvent."""
    if not session.get(Ad, ad_id):
        raise HTTPException(404, "Объявление не найдено")
    t = "click" if body.type == "click" else "impression"
    session.add(AdEvent(ad_id=ad_id, event_type=t))
    session.commit()
    return {"ok": True}


@router.get("/ads/stats")
def ad_stats(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Сводка показов/кликов по каждому объявлению (кабинет — только админ)."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    # Агрегат считаем в SQL (GROUP BY), а не тянем всю таблицу AdEvent в память — она растёт на каждый показ/клик.
    rows = session.exec(
        select(AdEvent.ad_id, AdEvent.event_type, func.count()).group_by(AdEvent.ad_id, AdEvent.event_type)
    ).all()
    imp: dict = {}
    clk: dict = {}
    for ad_id, etype, cnt in rows:
        (clk if etype == "click" else imp)[ad_id] = cnt
    return {aid: {"impressions": imp.get(aid, 0), "clicks": clk.get(aid, 0)} for aid in (set(imp) | set(clk))}


# ---------- Партнёр (self-serve кабинет) ----------

def _ad_mine(ad: Ad, paid: bool) -> dict:
    """Сериализация СВОЕГО объявления для кабинета партнёра (в отличие от _ad_public —
    здесь статус, причина отказа, тариф, оплата: то, что видит владелец, но не публика)."""
    pkg = AD_PACKAGES.get(ad.package)
    return {
        "id": str(ad.id),
        "title": ad.title,
        "text": ad.text,
        "button": ad.button,
        "target": ad.target,
        "erid": ad.erid,
        "status": ad.status,
        "reject_reason": ad.reject_reason,
        "package": ad.package,
        "package_title": pkg["title"] if pkg else "",
        "budget_kop": ad.budget_kop,
        "period_days": ad.period_days,
        "placements": _csv(ad.placements),
        "cities": _csv(ad.cities),
        "paid": paid,
        "created_at": ad.created_at.isoformat() if ad.created_at else None,
        "submitted_at": ad.submitted_at.isoformat() if ad.submitted_at else None,
        "starts_at": ad.starts_at.isoformat() if ad.starts_at else None,
        "ends_at": ad.ends_at.isoformat() if ad.ends_at else None,
    }


@router.get("/ad-packages")
def ad_packages():
    """Тарифы размещения (из конфига AD_PACKAGES) — для кабинета/витрины. Без авторизации: это просто прайс."""
    return [
        {"code": code, "title": p["title"], "title_ba": p["title_ba"],
         "amount_kop": p["amount_kop"], "period_days": p["period_days"]}
        for code, p in AD_PACKAGES.items()
    ]


@router.get("/ads/mine")
def ads_mine(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои объявления (владелец = я), ВСЕ статусы + причина отказа + оплачено/нет.
    Приватность: строго owner_id == я (чужие объявления недоступны — IDOR закрыт)."""
    rows = session.exec(
        select(Ad).where(Ad.owner_id == user.id).order_by(Ad.created_at.desc())
    ).all()
    if not rows:
        return []
    ad_ids = [a.id for a in rows]
    paid_ids = set(session.exec(
        select(Payment.ad_id).where(
            Payment.purpose == "ad",
            Payment.ad_id.in_(ad_ids),
            Payment.status == "succeeded",
        )
    ).all())
    return [_ad_mine(a, a.id in paid_ids) for a in rows]


def _is_paid(session: Session, ad_id: int) -> bool:
    row = session.exec(
        select(Payment.id).where(
            Payment.purpose == "ad", Payment.ad_id == ad_id, Payment.status == "succeeded"
        )
    ).first()
    return row is not None


def _paid_ad_ids(session: Session, ad_ids: list) -> set:
    """Множество ad_id с подтверждённой оплатой рекламы (батч, чтобы не дёргать БД по одному)."""
    if not ad_ids:
        return set()
    return set(session.exec(
        select(Payment.ad_id).where(
            Payment.purpose == "ad", Payment.ad_id.in_(ad_ids), Payment.status == "succeeded"
        )
    ).all())


def _apply_package(ad: Ad, code: str) -> None:
    """Проставить тариф из конфига (места показа/цена/срок фиксируются при выборе пакета)."""
    pkg = AD_PACKAGES.get(code)
    if not pkg:
        return
    ad.package = code
    ad.placements = pkg["placements"]
    ad.budget_kop = pkg["amount_kop"]
    ad.period_days = pkg["period_days"]


class AdCreateIn(BaseModel):
    title: str = ""
    text: str = ""
    button: str = ""
    target: str = ""
    package: str = ""       # код тарифа из AD_PACKAGES
    cities: str = ""        # CSV городов таргета; пусто = все


@router.post("/ads")
def ad_create(body: AdCreateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Партнёр создаёт своё объявление (черновик). Анти-спам: лимит на владельца."""
    count = session.exec(select(func.count()).select_from(Ad).where(Ad.owner_id == user.id)).one()
    if count >= MAX_ADS_PER_OWNER:
        raise HTTPException(429, "Слишком много объявлений — удали лишние")
    title = body.title.strip()
    if not title:
        raise HTTPException(422, "Заголовок обязателен")
    ad = Ad(
        owner_id=user.id, created_by=user.id, status="draft",
        title=title, text=body.text.strip(), button=body.button.strip(),
        target=body.target.strip(), cities=body.cities.strip(),
    )
    _apply_package(ad, body.package)
    session.add(ad)
    if not user.is_advertiser:            # первый созданный объявлением делает юзера рекламодателем
        user.is_advertiser = True
        session.add(user)
    session.commit()
    session.refresh(ad)
    return _ad_mine(ad, False)


def _own_editable_ad(ad_id: int, user: User, session: Session) -> Ad:
    """Достать СВОЁ редактируемое объявление или бросить понятную ошибку.
    404 (а не 403) на чужое — чтобы не раскрывать существование чужих объявлений."""
    ad = session.get(Ad, ad_id)
    if not ad or ad.owner_id != user.id:
        raise HTTPException(404, "Объявление не найдено")
    if ad.status not in AD_EDITABLE_STATUSES:
        raise HTTPException(409, "Редактировать можно только черновик или отклонённое")
    return ad


@router.post("/ads/{ad_id}")   # POST (не PATCH): единообразно с admin/ads и без сюрпризов HttpURLConnection
def ad_update(ad_id: int, body: AdCreateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Партнёр правит СВОЙ черновик/отклонённое."""
    ad = _own_editable_ad(ad_id, user, session)
    if body.title.strip():
        ad.title = body.title.strip()
    ad.text = body.text.strip()
    ad.button = body.button.strip()
    ad.target = body.target.strip()
    ad.cities = body.cities.strip()
    _apply_package(ad, body.package)
    session.add(ad)
    session.commit()
    session.refresh(ad)
    return _ad_mine(ad, _is_paid(session, ad.id))


@router.post("/ads/{ad_id}/submit")
def ad_submit(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отправить своё объявление на модерацию (draft/rejected → pending_review)."""
    ad = _own_editable_ad(ad_id, user, session)
    if not ad.title.strip() or ad.package not in AD_PACKAGES:
        raise HTTPException(422, "Заполни заголовок и выбери тариф")
    ad.status = "pending_review"
    ad.reject_reason = ""
    ad.submitted_at = utcnow()
    session.add(ad)
    session.commit()
    session.refresh(ad)
    try:  # уведомление админа — best-effort, не роняем сабмит если Telegram недоступен
        notify_admin_telegram(
            f"🆕 Реклама на модерации\n"
            f"ID: {ad.id}\n"
            f"Название: «{ad.title}»\n"
            f"От: {user.name or 'партнёр'}\n"
            f"Тариф: {ad.package or 'не указан'} · {ad.budget_kop // 100} ₽\n\n"
            f"Одобрить или отклонить можно прямо тут:",
            reply_markup={
                "inline_keyboard": [[
                    {"text": "✅ Одобрить", "callback_data": f"ad:ok:{ad.id}"},
                    {"text": "❌ Отклонить", "callback_data": f"ad:no:{ad.id}"},
                ]]
            },
        )
    except Exception:
        pass
    return _ad_mine(ad, _is_paid(session, ad.id))


@router.post("/ads/{ad_id}/pay")
def ad_pay(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Партнёр создаёт заявку на оплату СВОЕГО размещения (СБП «на доверии», подтверждает админ).
    Доступно после одобрения модерацией. В эфир объявление пойдёт, когда админ подтвердит оплату."""
    ad = session.get(Ad, ad_id)
    if not ad or ad.owner_id != user.id:
        raise HTTPException(404, "Объявление не найдено")
    if ad.status != "active":
        raise HTTPException(409, "Оплата доступна после одобрения модерацией")
    if ad.budget_kop <= 0:
        raise HTTPException(422, "У объявления не выбран тариф")
    if _is_paid(session, ad.id):
        raise HTTPException(409, "Уже оплачено")
    # Идемпотентность: повторное нажатие «Оплатить» не плодит заявки — возвращаем существующую pending.
    existing = session.exec(
        select(Payment).where(Payment.purpose == "ad", Payment.ad_id == ad.id, Payment.status == "pending")
    ).first()
    if existing:
        return {"payment_id": existing.id, "amount_kop": existing.amount_kop, "status": "pending"}
    payment = Payment(user_id=user.id, purpose="ad", ad_id=ad.id, amount_kop=ad.budget_kop, status="pending")
    session.add(payment)
    session.commit()
    session.refresh(payment)
    try:
        notify_admin_telegram(
            (
                f"💳 Оплата рекламы СБП\n"
                f"ID платежа: {payment.id}\n"
                f"Реклама: «{ad.title}»\n"
                f"Сумма: {ad.budget_kop // 100} ₽\n"
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
    return {"payment_id": payment.id, "amount_kop": ad.budget_kop, "status": "pending"}


# ---------- Админ ----------

def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


class AdIn(BaseModel):
    partner_name: str = ""
    partner_contact: str = ""
    title: str = ""
    text: str = ""
    button: str = ""
    target: str = ""
    image_url: str = ""
    erid: str = ""
    plan: str = "standard"                   # founder/standard/premium
    placements: str = ""                     # CSV
    cities: str = ""                         # CSV
    priority: int = 0
    starts_at: Optional[datetime] = None
    ends_at: Optional[datetime] = None       # null = бессрочно (founder)
    price: int = 0                           # ₽: если >0 → заявка на оплату админу (purpose=ad), реклама публикуется после подтверждения


class AdStatusIn(BaseModel):
    status: str                              # draft/active/paused/expired/archived


def _founder_slots_used(session: Session) -> int:
    rows = session.exec(select(Ad).where(Ad.plan == "founder", Ad.status != "archived")).all()
    return len(rows)


def _admin_view(ad: Ad, now: datetime, paid: bool = False) -> dict:
    d = _ad_public(ad)
    # партнёрское объявление живо только оплаченным; админское (owner_id=None) — как раньше
    live = _is_live(ad, now) and (ad.owner_id is None or paid)
    d.update({
        "partner_contact": ad.partner_contact,
        "founder_lock": ad.founder_lock,
        "priority": ad.priority,
        "status": ad.status,
        "reject_reason": ad.reject_reason,      # для админ-UI (что писали при отказе)
        "owner_id": ad.owner_id,                # чьё объявление (партнёр-самосервис или админское null)
        "package": ad.package,
        "paid": paid,
        "live": live,
        "expired": ad.ends_at is not None and ad.ends_at <= now,
        "starts_at": ad.starts_at.isoformat() if ad.starts_at else None,
        "ends_at": ad.ends_at.isoformat() if ad.ends_at else None,
        "created_at": ad.created_at.isoformat() if ad.created_at else None,
    })
    return d


@router.get("/admin/ads")
def admin_ads(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все объявления (кроме архива) + сколько founder-слотов занято."""
    _require_admin(user)
    now = utcnow()
    rows = session.exec(select(Ad).where(Ad.status != "archived")).all()
    rows.sort(key=lambda a: a.created_at or now, reverse=True)
    paid = _paid_ad_ids(session, [a.id for a in rows])
    return {
        "founder_used": _founder_slots_used(session),
        "founder_limit": FOUNDER_LIMIT,
        "items": [_admin_view(a, now, a.id in paid) for a in rows],
    }


# ---------- Модерация партнёрских объявлений (self-serve) ----------

class AdApproveIn(BaseModel):
    erid: str = ""       # маркировка из ОРД (РФ закон): админ вставляет реальный erid при одобрении


class AdRejectIn(BaseModel):
    reason: str = ""


@router.post("/admin/ads/{ad_id}/approve", response_model=Ad)
def admin_approve_ad(ad_id: int, body: AdApproveIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Одобрить объявление (pending_review → active). В эфир пойдёт после оплаты (см. _is_live)."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise HTTPException(404, "Объявление не найдено")
    ad.status = "active"
    ad.reject_reason = ""
    ad.reviewed_at = utcnow()
    if body.erid.strip():
        ad.erid = body.erid.strip()
    if ad.period_days > 0:            # провизорное окно (объявление скрыто до оплаты);
        ad.starts_at = utcnow()       # реальный отсчёт срока — от подтверждения оплаты
        ad.ends_at = utcnow() + timedelta(days=ad.period_days)  # (_activate_payment переставит)
    session.add(ad)
    session.commit()
    session.refresh(ad)
    if ad.owner_id:
        send_push_bi(
            session, ad.owner_id,
            "Реклама одобрена", "Реклама раҫланды",
            f"«{ad.title}» прошла модерацию. Осталось оплатить размещение.",
            f"«{ad.title}» модерацияны үтте. Урынлаштырыуҙы түләргә ҡалды.",
        )
    return ad


@router.post("/admin/ads/{ad_id}/reject", response_model=Ad)
def admin_reject_ad(ad_id: int, body: AdRejectIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить объявление (→ rejected) с причиной. Партнёр увидит причину и сможет исправить."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise HTTPException(404, "Объявление не найдено")
    ad.status = "rejected"
    ad.reject_reason = body.reason.strip()[:500]
    ad.reviewed_at = utcnow()
    session.add(ad)
    session.commit()
    session.refresh(ad)
    if ad.owner_id:
        send_push_bi(session, ad.owner_id, "Реклама отклонена", "Реклама кире ҡағылды", (ad.reject_reason or "Проверь и отправь снова")[:120])
    return ad


@router.post("/admin/ads", response_model=Ad)
def admin_create_ad(body: AdIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать объявление. founder ограничен лимитом 10."""
    _require_admin(user)
    plan = body.plan if body.plan in ("founder", "standard", "premium") else "standard"
    if plan == "founder" and _founder_slots_used(session) >= FOUNDER_LIMIT:
        raise HTTPException(400, f"Слоты основателей заняты ({FOUNDER_LIMIT}/{FOUNDER_LIMIT})")
    ad = Ad(
        partner_name=body.partner_name.strip(),
        partner_contact=body.partner_contact.strip(),
        title=body.title.strip(),
        text=body.text.strip(),
        button=body.button.strip(),
        target=body.target.strip(),
        image_url=body.image_url.strip(),
        erid=body.erid.strip(),
        plan=plan,
        placements=body.placements.strip(),
        cities=body.cities.strip(),
        founder_lock=(plan == "founder"),
        priority=body.priority,
        starts_at=body.starts_at or utcnow(),
        ends_at=None if plan == "founder" else body.ends_at,
        status="draft",
        created_by=user.id,
    )
    session.add(ad)
    session.commit()
    session.refresh(ad)
    # Платная реклама (price>0): заявка на оплату попадёт в /admin/payments/pending.
    # Реклама опубликуется автоматически, когда админ подтвердит платёж (purpose=ad).
    if body.price > 0:
        session.add(Payment(user_id=user.id, purpose="ad", ad_id=ad.id, amount_kop=body.price * 100))
        session.commit()
    return ad


@router.post("/admin/ads/{ad_id}", response_model=Ad)
def admin_update_ad(ad_id: int, body: AdIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Обновить контент/таргет/срок объявления."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise HTTPException(404, "Объявление не найдено")
    for f in ("partner_name", "partner_contact", "title", "text", "button", "target", "image_url", "erid", "placements", "cities"):
        setattr(ad, f, (getattr(body, f) or "").strip())
    ad.priority = body.priority
    if body.plan in ("founder", "standard", "premium"):
        ad.plan = body.plan
        ad.founder_lock = body.plan == "founder"
    if body.starts_at:
        ad.starts_at = body.starts_at
    ad.ends_at = None if ad.plan == "founder" else body.ends_at
    session.add(ad)
    session.commit()
    session.refresh(ad)
    return ad


@router.post("/admin/ads/{ad_id}/status", response_model=Ad)
def admin_set_status(ad_id: int, body: AdStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Опубликовать / поставить на паузу / архивировать."""
    _require_admin(user)
    if body.status not in ("draft", "active", "paused", "expired", "archived"):
        raise HTTPException(400, "Недопустимый статус")
    ad = session.get(Ad, ad_id)
    if not ad:
        raise HTTPException(404, "Объявление не найдено")
    # При публикации founder — снова проверяем лимит (если был архивный)
    if body.status == "active" and ad.plan == "founder" and ad.status == "archived" and _founder_slots_used(session) >= FOUNDER_LIMIT:
        raise HTTPException(400, f"Слоты основателей заняты ({FOUNDER_LIMIT}/{FOUNDER_LIMIT})")
    ad.status = body.status
    session.add(ad)
    session.commit()
    session.refresh(ad)
    return ad


@router.delete("/admin/ads/{ad_id}")
def admin_delete_ad(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мягкое удаление — в архив (статистика сохраняется)."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise HTTPException(404, "Объявление не найдено")
    ad.status = "archived"
    session.add(ad)
    session.commit()
    return {"ok": True}
