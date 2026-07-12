"""C1 — профиль «Курьер»: профессия (как таксист) + «заказать курьера» + «купи и привези».

Гибрид роли доставки:
- «по пути» (poputka) — это M3 (`parcels.py`), любой попутчик, бесплатно/символический сбор, БЕЗ гейта;
- «Курьер» (courier) — профессиональный режим: одобренный курьер выходит на линию и берёт заказы;
- «купи и привези» (buy_bring) — курьер тратит свои на товар, получатель возвращает (наложка ≤ потолок).

Красные линии (как в M3):
- ЦЕНА — на сервере. Клиенту не верим: расстояние по haversine между гео-точками × тариф. Без суржа.
- Комиссия платформы маленькая и прозрачная (COURIER_COMMISSION_PERCENT), «на доверии» (оплата фейк — СБП).
- Приватность: телефон получателя скрыт из /courier/available до принятия заказа (как в M3).
- Проверка курьера Уровень 1: селфи с документом + «кто пригласил» (invited_by по User.referred_by).
- Все пользовательские 4xx — двуязычные через herr(status, ru, ba).

Онбординг повторяет taxi.py (заявка → админ approve/reject → профиль). Флоу заказа переиспользует M3:
приём/движение статуса/доставка по коду — эндпоинты /parcels/{id}/accept|/status и /parcels/carrying
(они уже курьер-сторона); для courier/buy_bring-типов accept гейтится _guard_courier (см. parcels.py).
"""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import CourierApplication, CourierProfile, ParcelDelivery, Settlement, User, UserRole
from ..security import current_user
from ..services import haversine_km, notify_admin_telegram, send_push
from ..timeutil import utcnow
from . import parcels as parcels_mod

router = APIRouter(tags=["courier"])

# ---------------------------------------------------------------------------
# Тарифы и пределы курьера — ПРАВЯТСЯ ЗДЕСЬ, без пересборки клиента (продуктовые
# параметры, не .env). Цена считается сервером — единственный источник истины.
# ---------------------------------------------------------------------------
COURIER_COD_CAP_KOP = 500_000          # потолок наложки/стоимости товара для buy_bring = 5000 ₽

COURIER_TARIFF = {
    "base_kop": 10000,                 # подача курьера — 100 ₽
    "per_km_kop": 2000,                # 20 ₽ за км дороги
    "road_k": 1.3,                     # прямая (haversine) → примерная длина по дорогам
    "size_add_kop": {"small": 0, "medium": 5000, "large": 15000},  # надбавка за размер
    "urgency_now_kop": 10000,          # надбавка «нужен курьер сейчас» (+100 ₽)
}
COURIER_COMMISSION_PERCENT = 8.0       # прозрачная комиссия платформы (8% от цены доставки)

_TRANSPORTS = ("car", "cargo")
_ZONES = ("city", "intercity", "region")
_SIZES = ("small", "medium", "large")
_URGENCIES = ("bypath", "now")
_COURIER_TYPES = ("courier", "buy_bring")

# Двуязычные сообщения гейта курьера (RU + черновой BA — финал за Александром).
MSG_COURIER_OFF = ("Курьер Юлдаш скоро запустится! А пока — доставка «по пути» 📦",
                   "Юлдаш курьеры тиҙҙән асыла! Ә әлегә — «юл ыңғайы» доставка 📦")
MSG_NOT_COURIER = ("Сначала стань курьером Юлдаша", "Башта Юлдаш курьеры бул")


# ---------------------------------------------------------------------------
# Гейты
# ---------------------------------------------------------------------------
def _guard_courier_enabled() -> None:
    """Мастер-флаг режима курьера. Выключен → «Курьер скоро» (доставка «по пути» работает)."""
    if not settings.courier_enabled:
        raise herr(403, *MSG_COURIER_OFF)


def _my_application(session: Session, user_id: int) -> Optional[CourierApplication]:
    return session.exec(
        select(CourierApplication).where(CourierApplication.user_id == user_id)
        .order_by(CourierApplication.id.desc())
    ).first()


def _guard_courier(user: User, session: Session) -> None:
    """Полный гейт курьера: режим включён + заявка курьера одобрена. Иначе herr 403 двуязычно."""
    _guard_courier_enabled()
    app = _my_application(session, user.id)
    if not app or app.status != "approved":
        raise herr(403, *MSG_NOT_COURIER)


def _my_profile(session: Session, user_id: int) -> Optional[CourierProfile]:
    return session.exec(select(CourierProfile).where(CourierProfile.user_id == user_id)).first()


# ---------------------------------------------------------------------------
# Цена (сервер — источник истины)
# ---------------------------------------------------------------------------
def _price(from_lat: Optional[float], from_lng: Optional[float],
           to_lat: Optional[float], to_lng: Optional[float],
           size: str, urgency: str) -> dict:
    """Честная цена доставки: haversine × road_k × тариф + размер + срочность. Возвращает
    price_kop, commission_kop, distance_km и breakdown (прозрачно для UI). Без суржа."""
    t = COURIER_TARIFF
    if None in (from_lat, from_lng, to_lat, to_lng):
        distance_km = 0.0
    else:
        distance_km = round(haversine_km(from_lat, from_lng, to_lat, to_lng) * t["road_k"], 2)
    base_kop = t["base_kop"]
    distance_kop = int(round(distance_km * t["per_km_kop"]))
    size_kop = t["size_add_kop"].get(size, 0)
    urgency_kop = t["urgency_now_kop"] if urgency == "now" else 0
    price_kop = base_kop + distance_kop + size_kop + urgency_kop
    commission_kop = int(round(price_kop * COURIER_COMMISSION_PERCENT / 100))
    return {
        "price_kop": price_kop,
        "commission_kop": commission_kop,
        "distance_km": distance_km,
        "breakdown": {
            "base_kop": base_kop,
            "distance_kop": distance_kop,
            "size_kop": size_kop,
            "urgency_kop": urgency_kop,
            "commission_percent": COURIER_COMMISSION_PERCENT,
        },
    }


# ---------------------------------------------------------------------------
# Сериализация
# ---------------------------------------------------------------------------
def _application_payload(app: CourierApplication) -> dict:
    return {
        "id": app.id,
        "transport": app.transport,
        "status": app.status,
        "selfie_url": app.selfie_url or "",
        "invited_by": app.invited_by,
        "reject_reason": app.reject_reason or "",
        "created_at": app.created_at.isoformat() if app.created_at else None,
        "reviewed_at": app.reviewed_at.isoformat() if app.reviewed_at else None,
    }


def _profile_payload(p: Optional[CourierProfile]) -> Optional[dict]:
    if not p:
        return None
    return {
        "id": p.id,
        "online": p.online,
        "car_class": p.car_class,
        "zone": p.zone,
        "work_city": p.work_city or "",
        "work_direction_id": p.work_direction_id,
        "updated_at": p.updated_at.isoformat() if p.updated_at else None,
    }


# ---------------------------------------------------------------------------
# Онбординг курьера
# ---------------------------------------------------------------------------
class CourierApplyIn(BaseModel):
    transport: str = Field("car", max_length=16)
    selfie_url: str = Field("", max_length=500)


@router.post("/courier/apply")
def courier_apply(body: CourierApplyIn, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Подать заявку «Стать курьером». transport ∈ {car,cargo}, селфи обязательно (проверка L1).
    invited_by берём из User.referred_by (доверие «между своими»). Одна активная заявка на юзера:
    pending/approved → 409; после reject подача обновляет строку (status → pending)."""
    transport = (body.transport or "").strip()
    if transport not in _TRANSPORTS:
        raise herr(422, "Выбери тип транспорта", "Транспорт төрөн һайла")
    selfie = (body.selfie_url or "").strip()
    if not selfie:
        raise herr(422, "Пришли селфи с документом", "Документ менән селфи ебәр")
    app = _my_application(session, user.id)
    if app and app.status in ("pending", "approved"):
        raise herr(409, "Заявка уже на рассмотрении", "Ғариза ҡаралыуҙа инде")
    if app is None:
        app = CourierApplication(user_id=user.id)
    app.transport = transport
    app.selfie_url = selfie
    app.invited_by = user.referred_by
    app.status = "pending"
    app.reject_reason = ""
    app.reviewed_at = None
    app.created_at = utcnow()
    session.add(app)
    session.commit()
    session.refresh(app)
    try:  # уведомление админа — best-effort, без ПДн
        notify_admin_telegram(
            f"📦 Новая заявка курьера\nID: {app.id}\nТранспорт: {app.transport}\n"
            f"От: {user.name or 'курьер'}"
        )
    except Exception:
        pass
    return _application_payload(app)


@router.get("/courier/application")
def courier_application(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Моя заявка курьера (или {application: null}, если не подавал)."""
    app = _my_application(session, user.id)
    return {"application": _application_payload(app) if app else None}


# ---------------------------------------------------------------------------
# Админ: заявки курьеров
# ---------------------------------------------------------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


@router.get("/admin/courier-applications")
def admin_courier_applications(status: str = "pending", user: User = Depends(current_user),
                               session: Session = Depends(get_session)):
    """Очередь заявок курьеров для модерации. status=pending|approved|rejected|all."""
    _require_admin(user)
    q = select(CourierApplication)
    if status != "all":
        if status not in ("pending", "approved", "rejected"):
            raise HTTPException(400, "status: pending|approved|rejected|all")
        q = q.where(CourierApplication.status == status)
    apps = session.exec(q.order_by(CourierApplication.id.desc())).all()
    if not apps:
        return []
    ids = {a.user_id for a in apps}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    ref_ids = {a.invited_by for a in apps if a.invited_by}
    refs = {u.id: u for u in session.exec(select(User).where(User.id.in_(ref_ids))).all()} if ref_ids else {}
    out = []
    for a in apps:
        u = users.get(a.user_id)
        ref = refs.get(a.invited_by) if a.invited_by else None
        out.append({
            **_application_payload(a),
            "user_id": a.user_id,
            "name": (u.name if u and u.name else "Курьер"),
            "phone": (u.phone if u else ""),
            "invited_by_name": (ref.name if ref and ref.name else None),
        })
    return out


def _get_app_or_404(session: Session, app_id: int) -> CourierApplication:
    app = session.get(CourierApplication, app_id)
    if not app:
        raise HTTPException(404, "Заявка не найдена")
    return app


@router.post("/admin/courier-applications/{app_id}/approve")
def admin_approve_courier(app_id: int, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Одобрить заявку → создаём/активируем CourierProfile (car_class = transport заявки). Push курьеру."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = "approved"
    app.reject_reason = ""
    app.reviewed_at = utcnow()
    session.add(app)
    prof = _my_profile(session, app.user_id)
    if prof is None:
        prof = CourierProfile(user_id=app.user_id)
    prof.car_class = app.transport
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    try:
        send_push(session, app.user_id, "Ты курьер Юлдаша! 📦",
                  "Заявка одобрена — выходи на линию · Ғариза хупланды — линияға сыҡ")
    except Exception:
        pass
    return {"id": app.id, "status": app.status}


class CourierRejectIn(BaseModel):
    reason: str = Field("", max_length=500)


@router.post("/admin/courier-applications/{app_id}/reject")
def admin_reject_courier(app_id: int, body: CourierRejectIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Отклонить заявку (с причиной — курьер увидит и сможет подать снова)."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = "rejected"
    app.reject_reason = body.reason.strip()
    app.reviewed_at = utcnow()
    session.add(app)
    session.commit()
    try:
        send_push(session, app.user_id, "Заявка курьера отклонена",
                  "Поправь и подай снова · Төҙәт тә яңынан ебәр")
    except Exception:
        pass
    return {"id": app.id, "status": app.status}


# ---------------------------------------------------------------------------
# Курьер на линии
# ---------------------------------------------------------------------------
class CourierOnlineIn(BaseModel):
    zone: str = Field("city", max_length=16)
    work_city: str = Field("", max_length=80)
    work_direction_id: Optional[int] = None


@router.post("/courier/online")
def courier_online(body: CourierOnlineIn, user: User = Depends(current_user),
                   session: Session = Depends(get_session)):
    """Выйти на линию (гейт курьера). Фиксируем зону работы (city|intercity|region)."""
    _guard_courier(user, session)
    zone = (body.zone or "").strip()
    if zone not in _ZONES:
        raise herr(422, "Выбери зону работы", "Эш зонаһын һайла")
    prof = _my_profile(session, user.id)
    if prof is None:
        prof = CourierProfile(user_id=user.id)
    prof.online = True
    prof.zone = zone
    prof.work_city = body.work_city.strip()
    prof.work_direction_id = body.work_direction_id
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    session.refresh(prof)
    return _profile_payload(prof)


@router.post("/courier/offline")
def courier_offline(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Уйти с линии."""
    _guard_courier(user, session)
    prof = _my_profile(session, user.id)
    if prof is None:
        prof = CourierProfile(user_id=user.id)
    prof.online = False
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    session.refresh(prof)
    return _profile_payload(prof)


def _order_matches_zone(p: ParcelDelivery, prof: CourierProfile,
                        settlement: Optional[Settlement]) -> bool:
    """Подходит ли заказ под зону курьера. region — всё; city — совпадение с work_city;
    intercity — направление work_direction_id встречается в маршруте. Пустая привязка → не сужаем."""
    if prof.zone == "region":
        return True
    if prof.zone == "city":
        wc = (prof.work_city or "").strip().casefold()
        if not wc:
            return True
        return (p.from_city or "").strip().casefold() == wc or (p.to_city or "").strip().casefold() == wc
    if prof.zone == "intercity":
        if not settlement:
            return True
        names = {settlement.name_ru.casefold(), (settlement.name_ba or "").casefold()}
        return (p.from_city or "").strip().casefold() in names or (p.to_city or "").strip().casefold() in names
    return True


@router.get("/courier/available")
def courier_available(from_city: Optional[str] = None, to_city: Optional[str] = None,
                      user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Доступные курьер-заказы (status=created, delivery_type ∈ courier|buy_bring, НЕ мои),
    отфильтрованные по зоне курьера. Приватность: БЕЗ телефона получателя (как M3). Гейт курьера."""
    _guard_courier(user, session)
    prof = _my_profile(session, user.id)
    rows = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.status == "created",
            ParcelDelivery.sender_id != user.id,
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        ).order_by(ParcelDelivery.id.desc())
    ).all()
    if from_city:
        fc = from_city.strip().casefold()
        rows = [p for p in rows if (p.from_city or "").strip().casefold() == fc]
    if to_city:
        tc = to_city.strip().casefold()
        rows = [p for p in rows if (p.to_city or "").strip().casefold() == tc]
    if prof is not None:
        settlement = (session.get(Settlement, prof.work_direction_id)
                      if prof.work_direction_id else None)
        rows = [p for p in rows if _order_matches_zone(p, prof, settlement)]
    return [parcels_mod._parcel_available(p) for p in rows]   # без телефона получателя


# ---------------------------------------------------------------------------
# Оценка цены (auth, для UI до заказа)
# ---------------------------------------------------------------------------
@router.get("/courier/estimate")
def courier_estimate(from_lat: float, from_lng: float, to_lat: float, to_lng: float,
                     size: str = "small", urgency: str = "bypath",
                     user: User = Depends(current_user)):
    """Оценка цены доставки курьером. Сервер — источник истины (haversine × тариф). Без суржа."""
    if size not in _SIZES:
        raise herr(422, "Выбери размер посылки", "Бандероль үлсәмен һайла")
    if urgency not in _URGENCIES:
        raise herr(422, "Некорректная срочность", "Ялған ашығыслыҡ")
    return _price(from_lat, from_lng, to_lat, to_lng, size, urgency)


# ---------------------------------------------------------------------------
# Заказ курьера
# ---------------------------------------------------------------------------
class CourierOrderIn(BaseModel):
    from_city: str = Field("", max_length=80)
    to_city: str = Field("", max_length=80)
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None
    size: str = Field("small", max_length=16)
    description: str = Field("", max_length=2000)
    receiver_name: str = Field("", max_length=120)
    receiver_phone: str = Field("", max_length=40)
    rules_accepted: bool = False
    delivery_type: str = Field("courier", max_length=16)   # courier | buy_bring
    urgency: str = Field("bypath", max_length=16)
    declared_value_kop: int = 0
    cod_amount_kop: int = 0                                 # buy_bring: стоимость товара (наложка)
    shopping_list: str = Field("", max_length=2000)        # buy_bring: что купить (уходит в description)


@router.post("/courier/orders")
def courier_order_create(body: CourierOrderIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Создать заказ курьера (courier|buy_bring). Цена — с сервера (commission фиксируется).
    Для buy_bring cod_amount_kop обязателен и ≤ COURIER_COD_CAP_KOP (защита курьера).
    Возвращает заявку + confirm_code (как M3): отправитель передаёт код получателю."""
    _guard_courier_enabled()   # заказать курьера можно только когда режим включён (заказчик — не курьер)
    dtype = (body.delivery_type or "").strip()
    if dtype not in _COURIER_TYPES:
        raise herr(422, "Выбери тип доставки", "Доставка төрөн һайла")
    from_city = body.from_city.strip()
    to_city = body.to_city.strip()
    receiver_name = body.receiver_name.strip()
    if not from_city:
        raise herr(422, "Укажи адрес отправления", "Ебәреү адресын күрһәт")
    if not to_city:
        raise herr(422, "Укажи адрес получения", "Алыу адресын күрһәт")
    if not receiver_name:
        raise herr(422, "Укажи имя получателя", "Алыусы исемен күрһәт")
    size = (body.size or "").strip()
    if size not in _SIZES:
        raise herr(422, "Выбери размер посылки", "Бандероль үлсәмен һайла")
    urgency = (body.urgency or "").strip()
    if urgency not in _URGENCIES:
        raise herr(422, "Некорректная срочность", "Ялған ашығыслыҡ")
    if not body.rules_accepted:
        raise herr(422, "Прими правила доставки", "Доставка ҡағиҙәләрен ҡабул ит")

    cod_amount_kop = 0
    description = body.description.strip()
    if dtype == "buy_bring":
        cod_amount_kop = int(body.cod_amount_kop or 0)
        if cod_amount_kop <= 0:
            raise herr(422, "Укажи стоимость покупки", "Һатып алыу хаҡын күрһәт")
        if cod_amount_kop > COURIER_COD_CAP_KOP:
            raise herr(422, f"Сумма покупки слишком большая (лимит {COURIER_COD_CAP_KOP // 100} ₽)",
                       f"Һатып алыу суммаһы бик ҙур (сик {COURIER_COD_CAP_KOP // 100} һ)")
        shopping = body.shopping_list.strip()
        if shopping:  # список покупок кладём в описание (курьер видит, что купить)
            description = (shopping + ("\n" + description if description else "")).strip()

    priced = _price(body.from_lat, body.from_lng, body.to_lat, body.to_lng, size, urgency)

    parcel = ParcelDelivery(
        sender_id=user.id,
        from_city=from_city,
        to_city=to_city,
        from_lat=body.from_lat,
        from_lng=body.from_lng,
        to_lat=body.to_lat,
        to_lng=body.to_lng,
        size=size,
        description=description,
        receiver_name=receiver_name,
        receiver_phone=body.receiver_phone.strip(),
        # fee_kop = комиссия платформы (доход, «на доверии») — попадает в /admin/parcels statement.
        fee_kop=priced["commission_kop"],
        commission_kop=priced["commission_kop"],
        declared_value_kop=int(body.declared_value_kop or 0),
        cod_amount_kop=cod_amount_kop,
        delivery_type=dtype,
        urgency=urgency,
        status="created",
        confirm_code=parcels_mod._gen_code(session),
    )
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:  # уведомление админа — best-effort, без телефона получателя
        notify_admin_telegram(
            f"📦 Новый заказ курьера ({dtype})\nID: {parcel.id}\n"
            f"Маршрут: {parcel.from_city} → {parcel.to_city}\n"
            f"Цена: {priced['price_kop'] // 100} ₽ · комиссия {priced['commission_kop'] // 100} ₽"
        )
    except Exception:
        pass
    out = parcels_mod._parcel_for_sender(parcel, session)
    out["price_kop"] = priced["price_kop"]       # полная цена доставки (курьеру платят напрямую)
    out["breakdown"] = priced["breakdown"]
    return out


# ---------------------------------------------------------------------------
# Кабинет курьера
# ---------------------------------------------------------------------------
@router.get("/courier/me")
def courier_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Кабинет курьера: заявка + профиль + statement (комиссия платформы по моим доставленным
    курьер-заказам). Гейт курьера (кабинет только одобренным)."""
    _guard_courier(user, session)
    app = _my_application(session, user.id)
    prof = _my_profile(session, user.id)
    commission = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0)).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        )
    ).one()
    delivered = session.exec(
        select(func.count()).select_from(ParcelDelivery).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        )
    ).one()
    return {
        "application": _application_payload(app) if app else None,
        "profile": _profile_payload(prof),
        "statement": {
            "delivered_count": int(delivered or 0),
            "commission_kop": int(commission or 0),   # прозрачно: сколько комиссии платформе с моих доставок
        },
    }
