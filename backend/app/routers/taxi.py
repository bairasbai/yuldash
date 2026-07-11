"""Гейт такси + онбординг таксиста (волна 2, 580-ФЗ).

Пассажиру: /instant/availability — доступно ли такси в его точке (глобальный флаг + города).
Водителю: /taxi/apply, /taxi/application — заявка «Стать таксистом» (ИНН, разрешение, ОСАГО).
Админу: очередь заявок (approve/reject + push заявителю) и CRUD городов такси.

ПОПУТКА этим роутером не затрагивается. Фото документов — приватное хранилище
(/upload/photo → /secure/docs, как license_url водителя). Персональные данные не логируем.
"""
from datetime import date
from typing import Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..db import get_session
from ..models import (
    DriverProfile, InstantOrder, InstantOrderStatus as S, TaxiApplication,
    TaxiApplicationStatus, TaxiCity, User, UserRole,
)
from ..security import current_user
from ..services import send_push
from ..timeutil import utcnow
from .. import antifraud as af_mod
from .. import geo as geo_mod
from .. import instant_service as isv
from .. import taxi as taxi_mod
from .drivers import _ensure_owned_doc_url

router = APIRouter(tags=["taxi"])

MIN_AGE_YEARS = 20        # возраст 20+ (бизнес-правило, юрист подтвердит минимум)
MIN_LICENSE_YEARS = 2     # стаж от 2 лет


# ------------------------------ доступность такси ------------------------------
@router.get("/instant/availability")
def instant_availability(lat: Optional[float] = None, lng: Optional[float] = None,
                         user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Доступно ли такси в точке пользователя. Клиент дёргает ДО пикера заказа:
    выключено → экран «Такси скоро в вашем городе» (тёплый текст на двух языках)."""
    if lat is not None and not (-90 <= lat <= 90):
        raise HTTPException(400, "Некорректная широта")
    if lng is not None and not (-180 <= lng <= 180):
        raise HTTPException(400, "Некорректная долгота")
    return taxi_mod.availability(session, lat, lng)


# ------------------------------ заявка таксиста ------------------------------
class TaxiApplyIn(BaseModel):
    inn: str = Field(..., max_length=20)
    permit_number: str = Field(..., min_length=1, max_length=60)
    birth_date: date
    license_since_year: int = Field(..., ge=1900, le=2100)
    permit_photo_url: str = Field("", max_length=500)
    osago_url: str = Field("", max_length=500)
    # Класс машины (§6): водитель ЗАЯВЛЯЕТ в онбординге, админ подтверждает/меняет при approve.
    car_class: Literal["economy", "comfort"] = "economy"


def _set_car_class(session: Session, user_id: int, car_class: Optional[str]) -> None:
    """Класс машины живёт на DriverProfile (matcher читает оттуда). Профиля нет → создаём
    выключенный (online=False): заявку таксиста подают и до первого выхода на линию."""
    if car_class not in ("economy", "comfort"):
        return
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
    if dp is None:
        dp = DriverProfile(user_id=user_id, online=False)
    dp.car_class = car_class
    session.add(dp)


def _full_years_since(d: date, today: date) -> int:
    return today.year - d.year - ((today.month, today.day) < (d.month, d.day))


def _validate_apply(body: TaxiApplyIn) -> None:
    """Валидация требований 580-ФЗ/бизнес-правил. Ошибки — понятной русской строкой."""
    inn = body.inn.strip()
    if not (inn.isdigit() and 10 <= len(inn) <= 12):
        raise HTTPException(400, "ИНН должен состоять из 10–12 цифр")
    today = utcnow().date()
    if _full_years_since(body.birth_date, today) < MIN_AGE_YEARS:
        raise HTTPException(400, f"Возить такси можно с {MIN_AGE_YEARS} лет")
    if body.license_since_year > today.year:
        raise HTTPException(400, "Год получения прав не может быть в будущем")
    if today.year - body.license_since_year < MIN_LICENSE_YEARS:
        raise HTTPException(400, f"Нужен стаж вождения от {MIN_LICENSE_YEARS} лет")


def _application_payload(app: TaxiApplication) -> dict:
    return {
        "id": app.id,
        "status": app.status.value,
        "inn": app.inn,
        "permit_number": app.permit_number,
        "permit_photo_url": app.permit_photo_url or "",
        "osago_url": app.osago_url or "",
        "birth_date": app.birth_date.isoformat(),
        "license_since_year": app.license_since_year,
        "comment": app.comment or "",
        "created_at": app.created_at.isoformat(),
        "reviewed_at": app.reviewed_at.isoformat() if app.reviewed_at else None,
    }


@router.post("/taxi/apply")
def taxi_apply(body: TaxiApplyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Подать заявку «Стать таксистом». Повторная подача после reject разрешена —
    обновляет ту же заявку (status → pending, комментарий админа очищается).
    Уже approved → 409 (заявка одна на пользователя, менять нечего)."""
    _validate_apply(body)
    app = taxi_mod.my_application(session, user.id)
    if app and app.status == TaxiApplicationStatus.approved:
        raise HTTPException(409, "Заявка уже одобрена — ты в такси Юлдаша")
    # Фото — только СВОИ загруженные защищённые документы (анти-подмена чужих URL).
    permit_url = _ensure_owned_doc_url(body.permit_photo_url, user, None) if body.permit_photo_url.strip() else None
    osago_url = _ensure_owned_doc_url(body.osago_url, user, None) if body.osago_url.strip() else None
    if app is None:
        app = TaxiApplication(user_id=user.id)
    app.inn = body.inn.strip()
    app.permit_number = body.permit_number.strip()
    app.permit_photo_url = permit_url
    app.osago_url = osago_url
    app.birth_date = body.birth_date
    app.license_since_year = body.license_since_year
    app.status = TaxiApplicationStatus.pending
    app.comment = None
    app.reviewed_at = None
    app.created_at = utcnow()
    session.add(app)
    _set_car_class(session, user.id, body.car_class)   # заявленный класс — на профиль водителя
    session.commit()
    session.refresh(app)
    return _application_payload(app)


@router.get("/taxi/application")
def my_taxi_application(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Моя заявка таксиста (для экрана статуса). Не подавал → 404."""
    app = taxi_mod.my_application(session, user.id)
    if not app:
        raise HTTPException(404, "Заявка не подана")
    return _application_payload(app)


# ------------------------------ админ: заявки ------------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


@router.get("/admin/taxi-applications")
def admin_taxi_applications(status: str = "pending", user: User = Depends(current_user),
                            session: Session = Depends(get_session)):
    """Очередь заявок таксистов для модерации. status=pending|approved|rejected|all."""
    _require_admin(user)
    q = select(TaxiApplication)
    if status != "all":
        try:
            q = q.where(TaxiApplication.status == TaxiApplicationStatus(status))
        except ValueError:
            raise HTTPException(400, "status: pending|approved|rejected|all")
    apps = session.exec(q.order_by(TaxiApplication.id.desc())).all()
    if not apps:
        return []
    ids = {a.user_id for a in apps}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    profs = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    out = []
    for a in apps:
        u = users.get(a.user_id)
        p = profs.get(a.user_id)
        out.append({
            **_application_payload(a),
            "user_id": a.user_id,
            "name": (u.name if u and u.name else "Водитель"),
            "phone": (u.phone if u else ""),
            "car_class": ((p.car_class if p and p.car_class else "economy")),  # заявленный класс (§6)
        })
    return out


def _get_app_or_404(session: Session, app_id: int) -> TaxiApplication:
    app = session.get(TaxiApplication, app_id)
    if not app:
        raise HTTPException(404, "Заявка не найдена")
    return app


class ApproveIn(BaseModel):
    # Админ может подтвердить/поправить класс машины при одобрении (None = не менять).
    car_class: Optional[Literal["economy", "comfort"]] = None


@router.post("/admin/taxi-applications/{app_id}/approve")
def admin_approve_taxi(app_id: int, body: ApproveIn | None = None,
                       user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Одобрить заявку → водитель может возить такси. Push заявителю (двуязычно).
    Опционально body.car_class — админ финально подтверждает класс (economy|comfort)."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = TaxiApplicationStatus.approved
    app.comment = None
    app.reviewed_at = utcnow()
    session.add(app)
    if body is not None and body.car_class is not None:
        _set_car_class(session, app.user_id, body.car_class)
    session.commit()
    send_push(session, app.user_id, "Ты в такси Юлдаша! 🚕",
              "Заявка одобрена — выходи на линию · Ғариза хупланды — линияға сыҡ")
    return {"id": app.id, "status": app.status.value}


class RejectIn(BaseModel):
    comment: str = Field("", max_length=500)


@router.post("/admin/taxi-applications/{app_id}/reject")
def admin_reject_taxi(app_id: int, body: RejectIn, user: User = Depends(current_user),
                      session: Session = Depends(get_session)):
    """Отклонить заявку (с комментарием — водитель увидит и сможет подать снова)."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = TaxiApplicationStatus.rejected
    app.comment = body.comment.strip() or None
    app.reviewed_at = utcnow()
    session.add(app)
    session.commit()
    send_push(session, app.user_id, "Заявка в такси отклонена",
              "Поправь документы и подай снова · Документтарҙы төҙәт тә яңынан ебәр")
    return {"id": app.id, "status": app.status.value}


# ------------------------------ админ: пульс такси (B7b-3) ------------------------------
ORDER_ACTIVE_STATUSES = (S.searching, S.offered, S.accepted, S.arriving, S.onboard)


def _online_driver_positions() -> dict:
    """Живые водители «на линии» из Redis GEO: {driver_id: (lat, lng)}. Без Redis — пусто
    (панель честно показывает 0, не падает). Координаты НЕ логируем — только агрегат по городам."""
    r = isv._redis()
    if r is None:
        return {}
    out: dict = {}
    try:
        members = r.zrange(isv.PRESENCE_KEY, 0, -1)
    except Exception:  # noqa: BLE001 — сбой Redis → панель без presence, не 500
        return {}
    for m in members:
        try:
            did = isv._member_driver_id(m)
        except (ValueError, IndexError, AttributeError):
            continue
        if not isv.presence_alive(r, did):
            continue   # «залипшие» координаты без свежего heartbeat — не считаем
        try:
            pos = r.geopos(isv.PRESENCE_KEY, m)
        except Exception:  # noqa: BLE001
            pos = None
        lnglat = pos[0] if pos else None
        out[did] = (float(lnglat[1]), float(lnglat[0])) if lnglat else None
    return out


def _city_of(session: Session, lat: Optional[float], lng: Optional[float]) -> str:
    if lat is None or lng is None:
        return "—"
    st = geo_mod.nearest_settlement(session, lat, lng)
    return st.name_ru if st else "—"


@router.get("/admin/taxi/pulse")
def admin_taxi_pulse(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Пульс такси» — живая сводка для админа: кто на линии (живой presence из Redis),
    активные заказы, счётчики дня, средний подбор и разбивка по городам (ближайший
    Settlement, как в availability). Прагматично: без Redis presence = 0."""
    _require_admin(user)
    now = utcnow()
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    # Заказы: активные (по статусу) + все созданные/закрытые сегодня — двумя запросами.
    active_orders = session.exec(
        select(InstantOrder).where(InstantOrder.status.in_(ORDER_ACTIVE_STATUSES))
    ).all()
    today_orders = session.exec(
        select(InstantOrder).where(InstantOrder.created_at >= day_start)
    ).all()
    done_today = len(session.exec(
        select(InstantOrder.id).where(InstantOrder.status == S.done, InstantOrder.done_at >= day_start)
    ).all())
    cancelled_rows = session.exec(
        select(InstantOrder).where(InstantOrder.status == S.cancelled, InstantOrder.cancelled_at >= day_start)
    ).all()
    # Средний подбор: created → accepted по принятым СЕГОДНЯ заказам (сколько пассажир ждал машину).
    waits = [
        (o.accepted_at - o.created_at).total_seconds()
        for o in session.exec(select(InstantOrder).where(InstantOrder.accepted_at >= day_start)).all()
        if o.accepted_at is not None and o.created_at is not None
        and o.accepted_at >= o.created_at   # аномалии (правленые задним числом записи) не портят метрику
    ]
    positions = _online_driver_positions()
    # Разбивка по городам: онлайн-водители по их живым координатам, активные заказы — по точке подачи.
    by_city: dict = {}
    for latlng in positions.values():
        city = _city_of(session, *(latlng or (None, None)))
        by_city.setdefault(city, {"online": 0, "active": 0})["online"] += 1
    for o in active_orders:
        city = _city_of(session, o.from_lat, o.from_lng)
        by_city.setdefault(city, {"online": 0, "active": 0})["active"] += 1
    return {
        "drivers_online": len(positions),
        "orders_active": len(active_orders),
        "orders_today": len(today_orders),
        "done_today": done_today,
        "cancelled_today": len(cancelled_rows),
        "no_show_today": sum(1 for o in cancelled_rows if o.no_show),
        # Анти-фрод (B8-3): сколько пользователей сегодня помечено GPS-подозрительными
        # (3+ телепорта за час; их точки игнорируются, разбирается человек).
        "gps_suspects_today": af_mod.gps_suspects_today(isv._redis()),
        "avg_search_sec_today": (round(sum(waits) / len(waits), 1) if waits else None),
        "by_city": [
            {"city": city, **counts}
            for city, counts in sorted(by_city.items(), key=lambda kv: -(kv[1]["online"] + kv[1]["active"]))
        ],
    }


# ------------------------------ админ: города такси ------------------------------
class TaxiCityIn(BaseModel):
    city: str = Field(..., min_length=1, max_length=80)
    enabled: bool = True


@router.get("/admin/taxi-cities")
def admin_taxi_cities(user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    rows = session.exec(select(TaxiCity).order_by(TaxiCity.id)).all()
    return [{"id": c.id, "city": c.city, "enabled": c.enabled, "created_at": c.created_at.isoformat()} for c in rows]


@router.post("/admin/taxi-cities")
def admin_add_taxi_city(body: TaxiCityIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Добавить город (или обновить enabled существующего — без дублей по имени)."""
    _require_admin(user)
    name = body.city.strip()
    existing = next((c for c in session.exec(select(TaxiCity)).all()
                     if c.city.strip().casefold() == name.casefold()), None)
    row = existing or TaxiCity(city=name)
    row.enabled = body.enabled
    session.add(row)
    session.commit()
    session.refresh(row)
    return {"id": row.id, "city": row.city, "enabled": row.enabled, "created_at": row.created_at.isoformat()}


@router.delete("/admin/taxi-cities/{city_id}")
def admin_delete_taxi_city(city_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    row = session.get(TaxiCity, city_id)
    if not row:
        raise HTTPException(404, "Город не найден")
    session.delete(row)
    session.commit()
    return {"ok": True}
