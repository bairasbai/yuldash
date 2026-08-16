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
from ..errors import herr
from ..middleware import user_over_limit
from ..models import Ad, AdEvent, Payment, User, UserRole
from ..safety_logic import ensure_active
from ..security import current_user
from ..services import guard_own_media_url, notify_admin_telegram, push_notification
from ..timeutil import utcnow

router = APIRouter(tags=["ads"])


def notify_ad_decision(session, ad, *, approved: bool) -> None:
    """Сказать владельцу рекламы, что решили по его объявлению. Одна точка на два входа:
    решение приходит и из админки, и кнопкой в Telegram (`routers/auth.py`), а раньше в каждом
    месте стоял свой голый пуш — на одном языке и без следа.

    Почему запись, а не только пуш: человек заплатил за размещение и ждёт ответа. Пуш живёт
    секунды и не доходит при выключенном телефоне или устаревшем токене — «мне ничего
    не сказали» здесь означает потраченные деньги (аудит 2026-08-12, волна 24).
    """
    if not ad.owner_id:
        return
    if approved:
        push_notification(
            session, ad.owner_id, "ads",
            "Реклама одобрена", "Реклама раҫланды",
            f"«{ad.title}» прошла модерацию. Осталось оплатить размещение.",
            f"«{ad.title}» тикшереүҙе үтте. Урынлаштырыуҙы түләргә генә ҡала.",
            ref_kind="ad", ref_id=ad.id,
        )
    else:
        push_notification(
            session, ad.owner_id, "ads",
            "Реклама отклонена", "Реклама кире ҡағылды",
            (ad.reject_reason or "Проверь и отправь снова")[:120],
            (ad.reject_reason or "Тикшереп, яңынан ебәр")[:120],
            ref_kind="ad", ref_id=ad.id,
        )


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
# Сколько событий рекламы засчитываем одному человеку в час. С запасом на обычную жизнь
# (реклама мелькает в ленте, на карте, в профиле), но не на скрипт «5 000 показов за минуту».
AD_EVENTS_PER_HOUR = 200


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
    любой неавторизованный накручивал бы статистику и засорял таблицу AdEvent.

    Два фильтра (аудит 2026-08-07). Денег накрутка не двигает — цена размещения фиксирована
    пакетом, не CPM. Но показами без кликов конкурент роняет CTR в кабинете партнёра, а таблица
    событий пухнет на каждый запрос от любого вошедшего.
      • объявление, которого публика не видела ни разу (черновик / отклонённое) или у которого
        срок показа истёк, — «показать» нельзя физически, такое событие просто мусор;
      • персональный потолок в час — против скрипта.
    Приостановленное (paused) по-прежнему считаем: оно было на экране секунду назад, и событие
    вполне могло уйти уже после паузы.

    Отказ считать — не ошибка: реклама не должна ломать экран, поэтому 200 и counted=false."""
    ad = session.get(Ad, ad_id)
    if not ad:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    now = utcnow()
    if ad.status in AD_EDITABLE_STATUSES or (ad.ends_at is not None and ad.ends_at <= now):
        return {"ok": True, "counted": False}
    if user_over_limit("ad_event", user.id, AD_EVENTS_PER_HOUR, window_sec=3600):
        return {"ok": True, "counted": False}
    t = "click" if body.type == "click" else "impression"
    session.add(AdEvent(ad_id=ad_id, event_type=t))
    session.commit()
    return {"ok": True, "counted": True}


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


def _events_by_ad(session: Session, ad_ids: list) -> dict:
    """Батч-агрегат показов/кликов по объявлениям (GROUP BY в SQL, не тянем всю AdEvent в память).
    Возвращает {ad_id: {"impressions": n, "clicks": m}} для КАЖДОГО запрошенного id (нули если событий нет)."""
    out = {aid: {"impressions": 0, "clicks": 0} for aid in ad_ids}
    if not ad_ids:
        return out
    rows = session.exec(
        select(AdEvent.ad_id, AdEvent.event_type, func.count())
        .where(AdEvent.ad_id.in_(ad_ids))
        .group_by(AdEvent.ad_id, AdEvent.event_type)
    ).all()
    for ad_id, etype, cnt in rows:
        key = "clicks" if etype == "click" else "impressions"
        out.setdefault(ad_id, {"impressions": 0, "clicks": 0})[key] = cnt
    return out


def _ad_stat_payload(ad: Ad, imp: int, clk: int, now: datetime) -> dict:
    """Статистика ОДНОГО объявления для кабинета партнёра: показы/клики/CTR + срок размещения.
    CTR = клики / показы * 100 (%), 1 знак; при 0 показов — 0.0 (без деления на ноль)."""
    ctr = round(clk / imp * 100, 1) if imp > 0 else 0.0
    days_left = None
    if ad.ends_at is not None:
        days_left = max(0, (ad.ends_at - now).days)
    return {
        "ad_id": str(ad.id),
        "title": ad.title,
        "status": ad.status,
        "impressions": imp,
        "clicks": clk,
        "ctr": ctr,
        "starts_at": ad.starts_at.isoformat() if ad.starts_at else None,
        "ends_at": ad.ends_at.isoformat() if ad.ends_at else None,
        "days_left": days_left,        # сколько дней размещения осталось (null = бессрочно/не запущено)
    }


@router.get("/ads/mine/stats")
def ads_mine_stats(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Статистика по ВСЕМ моим объявлениям (показы/клики/CTR/срок) — для кабинета рекламодателя.
    Приватность: строго owner_id == я. Чужую статистику не отдаём (IDOR закрыт)."""
    rows = session.exec(
        select(Ad).where(Ad.owner_id == user.id).order_by(Ad.created_at.desc())
    ).all()
    now = utcnow()
    ev = _events_by_ad(session, [a.id for a in rows])
    return [_ad_stat_payload(a, ev[a.id]["impressions"], ev[a.id]["clicks"], now) for a in rows]


@router.get("/ads/{ad_id}/stats")
def ad_mine_stats_one(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Статистика одного объявления. Только владелец — чужое отдаёт 404 (не раскрываем существование, IDOR-защита)."""
    ad = session.get(Ad, ad_id)
    if not ad or ad.owner_id != user.id:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    ev = _events_by_ad(session, [ad.id])[ad.id]
    return _ad_stat_payload(ad, ev["impressions"], ev["clicks"], utcnow())


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
    title: str = Field("", max_length=120)
    text: str = Field("", max_length=2000)
    button: str = Field("", max_length=60)
    target: str = Field("", max_length=300)
    package: str = Field("", max_length=40)       # код тарифа из AD_PACKAGES
    cities: str = Field("", max_length=500)        # CSV городов таргета; пусто = все


@router.post("/ads")
def ad_create(body: AdCreateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Партнёр создаёт своё объявление (черновик). Анти-спам: лимит на владельца."""
    ensure_active(session, user.id)   # отстранённый разбором рекламу не заводит (волна 62)
    count = session.exec(select(func.count()).select_from(Ad).where(Ad.owner_id == user.id)).one()
    if count >= MAX_ADS_PER_OWNER:
        raise herr(429, "Слишком много объявлений — удали лишние", "Иғландар артыҡ күп — артығын бетер")
    title = body.title.strip()
    if not title:
        raise herr(422, "Заголовок обязателен", "Башлыҡ мотлаҡ")
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
    404 (а не 403) на чужое — чтобы не раскрывать существование чужих объявлений.

    Отстранённый разбором (§2) объявление не правит и не сдаёт на модерацию: реклама — такое
    же активное действие, как публикация поездки (волна 62). Просмотр своей статистики
    при этом остаётся: свои цифры человек вправе видеть и на паузе."""
    ensure_active(session, user.id)
    ad = session.get(Ad, ad_id)
    if not ad or ad.owner_id != user.id:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    if ad.status not in AD_EDITABLE_STATUSES:
        raise herr(409, "Редактировать можно только черновик или отклонённое", "Тик ҡараламаны йәки кире ҡағылғанды үҙгәртеп була")
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
        raise herr(422, "Заполни заголовок и выбери тариф", "Башлыҡты тултыр һәм тариф һайла")
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
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    if ad.status != "active":
        raise herr(409, "Оплата доступна после одобрения модерацией", "Түләү модерация раҫлағандан һуң мөмкин")
    if ad.budget_kop <= 0:
        raise herr(422, "У объявления не выбран тариф", "Иғландың тарифы һайланмаған")
    if _is_paid(session, ad.id):
        raise herr(409, "Уже оплачено", "Түләнгән инде")
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


@router.post("/ads/{ad_id}/renew")
def ad_renew(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Продлить размещение своей рекламы ещё на один период (аудит 2026-08-08, волна 116).

    Чего тут не было. Кнопка «Продлить размещение» в приложении сразу показывала QR для СБП,
    НЕ сказав серверу ни слова. Человек переводил деньги, жал «Я перевёл» — и на сервере
    не появлялось ничего: ни заявки, ни сообщения Александру. Через несколько дней реклама
    гасла по сроку, а человек был уверен, что заплатил и его обманули.

    Обычная оплата для этого не годилась: она отвечает «Уже оплачено» (и правильно —
    иначе повторное нажатие плодило бы заявки на первую же оплату).

    Помечаем платёж `tier="renew"`: по этой метке подтверждение оплаты добавляет период
    К ОСТАТКУ, а не отсчитывает срок заново — иначе продление «про запас» съедало бы
    оплаченные дни.
    """
    ad = session.get(Ad, ad_id)
    if not ad or ad.owner_id != user.id:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    if ad.status != "active":
        raise herr(409, "Продлить можно только показывающееся объявление",
                   "Тик күрһәтелгән иғланды оҙайтырға мөмкин")
    if ad.budget_kop <= 0:
        raise herr(422, "У объявления не выбран тариф", "Иғландың тарифы һайланмаған")
    if not _is_paid(session, ad.id):
        raise herr(409, "Сначала оплати размещение", "Тәүҙә урынлаштырыуҙы түлә")
    # Идемпотентность: повторное нажатие не плодит заявки — отдаём уже созданную.
    existing = session.exec(
        select(Payment).where(Payment.purpose == "ad", Payment.ad_id == ad.id,
                              Payment.status == "pending", Payment.tier == "renew")
    ).first()
    if existing:
        return {"payment_id": existing.id, "amount_kop": existing.amount_kop, "status": "pending"}
    payment = Payment(user_id=user.id, purpose="ad", ad_id=ad.id,
                      amount_kop=ad.budget_kop, status="pending", tier="renew")
    session.add(payment)
    session.commit()
    session.refresh(payment)
    try:
        notify_admin_telegram(
            (
                f"🔁 Продление рекламы СБП\n"
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
    partner_name: str = Field("", max_length=120)
    partner_contact: str = Field("", max_length=200)
    title: str = Field("", max_length=120)
    text: str = Field("", max_length=600)
    button: str = Field("", max_length=40)
    target: str = Field("", max_length=500)
    image_url: str = Field("", max_length=500)
    erid: str = Field("", max_length=60)
    plan: str = Field("standard", max_length=20)                   # founder/standard/premium
    placements: str = Field("", max_length=200)                     # CSV
    cities: str = Field("", max_length=500)                         # CSV
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
def admin_ads(limit: int = 200, offset: int = 0,
              user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все объявления (кроме архива) + сколько founder-слотов занято."""
    _require_admin(user)
    now = utcnow()
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    rows = session.exec(
        select(Ad).where(Ad.status != "archived")
        .order_by(Ad.created_at.desc()).offset(offset).limit(limit)
    ).all()
    rows.sort(key=lambda a: a.created_at or now, reverse=True)
    paid = _paid_ad_ids(session, [a.id for a in rows])
    return {
        "founder_used": _founder_slots_used(session),
        "founder_limit": FOUNDER_LIMIT,
        "items": [_admin_view(a, now, a.id in paid) for a in rows],
    }


# ---------- Модерация партнёрских объявлений (self-serve) ----------

class AdApproveIn(BaseModel):
    erid: str = Field("", max_length=60)       # маркировка из ОРД (РФ закон): админ вставляет реальный erid при одобрении


class AdRejectIn(BaseModel):
    reason: str = Field("", max_length=500)


@router.post("/admin/ads/{ad_id}/approve", response_model=Ad)
def admin_approve_ad(ad_id: int, body: AdApproveIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Одобрить объявление (pending_review → active). В эфир пойдёт после оплаты (см. _is_live)."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
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
    notify_ad_decision(session, ad, approved=True)
    return ad


@router.post("/admin/ads/{ad_id}/reject", response_model=Ad)
def admin_reject_ad(ad_id: int, body: AdRejectIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отклонить объявление (→ rejected) с причиной. Партнёр увидит причину и сможет исправить."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    ad.status = "rejected"
    ad.reject_reason = body.reason.strip()[:500]
    ad.reviewed_at = utcnow()
    session.add(ad)
    session.commit()
    session.refresh(ad)
    notify_ad_decision(session, ad, approved=False)
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
        image_url=guard_own_media_url(body.image_url),   # чужой хост = слежка за всеми зрителями
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
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    for f in ("partner_name", "partner_contact", "title", "text", "button", "target", "erid", "placements", "cities"):
        setattr(ad, f, (getattr(body, f) or "").strip())
    # Картинку — через проверку хоста: она грузится у каждого, кто увидит объявление (волна 39).
    ad.image_url = guard_own_media_url(body.image_url)
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
        raise herr(400, "Недопустимый статус", "Ярамаған статус")
    ad = session.get(Ad, ad_id)
    if not ad:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    # При публикации founder — снова проверяем лимит (если был архивный)
    if body.status == "active" and ad.plan == "founder" and ad.status == "archived" and _founder_slots_used(session) >= FOUNDER_LIMIT:
        raise HTTPException(400, f"Слоты основателей заняты ({FOUNDER_LIMIT}/{FOUNDER_LIMIT})")
    was = ad.status
    ad.status = body.status
    session.add(ad)
    session.commit()
    session.refresh(ad)
    notify_ad_status_change(session, ad, was)
    return ad


def notify_ad_status_change(session, ad, was: str) -> None:
    """Сказать владельцу платной рекламы, что её сняли с эфира или вернули.

    Про одобрение и отказ человеку говорили с самой волны 24, а про снятие руками — нет
    (аудит 2026-08-08, волна 86). Для партнёра это выглядит так: он заплатил, реклама шла,
    а потом перестала показываться. Ни письма, ни пуша — только догадки, что сломалось.
    Деньги при этом уплачены вперёд, и молчание тут читается как «нас обманули».

    Админские объявления (без владельца) молчат: адресата нет. Смена статуса «ни туда
    ни сюда» (draft → archived у неопубликованного) тоже молчит — человек ещё ничего
    не видел в эфире и ничего не потерял.
    """
    if not ad.owner_id:
        return
    live_before, live_after = was == "active", ad.status == "active"
    if live_before == live_after:
        return
    if live_after:
        push_notification(
            session, ad.owner_id, "ads",
            "Реклама снова в эфире", "Реклама кире эфирҙа",
            f"«{ad.title}» опять показывается людям 💚",
            f"«{ad.title}» кире кешеләргә күренә 💚",
            ref_kind="ad", ref_id=ad.id,
        )
    else:
        push_notification(
            session, ad.owner_id, "ads",
            "Реклама снята с показа", "Реклама күрһәтеүҙән алынды",
            f"«{ad.title}» сейчас не показывается. Вопросы — напиши в поддержку 💚",
            f"«{ad.title}» хәҙер күренмәй. Һорауҙар — ярҙам хеҙмәтенә яҙ 💚",
            ref_kind="ad", ref_id=ad.id,
        )


@router.delete("/admin/ads/{ad_id}")
def admin_delete_ad(ad_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мягкое удаление — в архив (статистика сохраняется)."""
    _require_admin(user)
    ad = session.get(Ad, ad_id)
    if not ad:
        raise herr(404, "Объявление не найдено", "Иғлан табылманы")
    ad.status = "archived"
    session.add(ad)
    session.commit()
    return {"ok": True}
