"""Водитель: онлайн-статус, профиль авто, загрузка фото документов (приватно),
отправка на проверку, статус проверки, выдача защищённых документов, модерация админом."""
import json
import os
import uuid
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import Booking, BookingStatus, DriverProfile, Rating, Ride, User, UserRole
from ..security import current_user
from ..services import DOC_DIR, enforce_upload_quota, notify_admin_telegram, read_upload, secure_docs_url, user_rating
from ..timeutil import utcnow

router = APIRouter(tags=["drivers"])


def _doc_name_from_url(url: str) -> str:
    value = (url or "").strip().split("?", 1)[0].split("#", 1)[0].rstrip("/")
    return os.path.basename(value)


def _profile_doc_names(profile: DriverProfile | None) -> set[str]:
    if not profile:
        return set()
    return {
        name for name in (
            _doc_name_from_url(profile.license_url),
            _doc_name_from_url(profile.car_photo_url),
        ) if name
    }


def _is_owned_doc_name(name: str, user_id: int, profile: DriverProfile | None) -> bool:
    # New uploads are bound to the uploader by filename prefix. Exact profile match keeps
    # old already-submitted documents readable after deploy.
    return name.startswith(f"{user_id}_") or name in _profile_doc_names(profile)


def _ensure_owned_doc_url(url: str, user: User, profile: DriverProfile | None) -> str:
    name = _doc_name_from_url(url)
    if not name:
        raise HTTPException(400, "Нужен защищённый файл документа")
    if not _is_owned_doc_name(name, user.id, profile):
        raise HTTPException(403, "Можно отправить только свои загруженные документы")
    if not os.path.isfile(os.path.join(DOC_DIR, name)):
        raise HTTPException(404, "Файл документа не найден")
    return url.strip()


class OnlineIn(BaseModel):
    online: bool


@router.post("/driver/online", response_model=DriverProfile)
def driver_online(body: OnlineIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        dp = DriverProfile(user_id=user.id, online=body.online)
    else:
        dp.online = body.online
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp


@router.post("/upload/photo")
async def upload_photo(request: Request, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Загрузка фото документа/авто (multipart `file` ИЛИ base64 — обратная совместимость со
    старым клиентом) → приватная папка → защищённый URL (только админ/владелец)."""
    enforce_upload_quota(session, user.id)
    data, ext = await read_upload(request, settings.image_ext_set, "jpg", "фото", sniff_image=True)
    name = f"{user.id}_{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(DOC_DIR, name), "wb") as f:
        f.write(data)
    return {"url": secure_docs_url(name)}


@router.get("/secure/docs/{name}")
def secure_doc(name: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отдать фото документа водителя. Доступ: админ ИЛИ владелец этого документа."""
    safe = os.path.basename(name)   # защита от path traversal
    if user.role != UserRole.admin:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
        if not _is_owned_doc_name(safe, user.id, prof):
            raise HTTPException(403, "Нет доступа к документу")
    path = os.path.join(DOC_DIR, safe)
    if not os.path.isfile(path):
        raise HTTPException(404, "Файл не найден")
    return FileResponse(path)


class DriverProfileIn(BaseModel):
    car_make: str = Field("", max_length=60)
    car_model: str = Field("", max_length=60)
    car_color: str = Field("", max_length=40)
    car_plate: str = Field("", max_length=16)
    seats: int = Field(4, ge=1, le=8)   # мест в машине — реальный диапазон (было: примут −5 и 9999)


def _get_or_create_profile(session: Session, user_id: int) -> DriverProfile:
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
    if not dp:
        dp = DriverProfile(user_id=user_id)
        session.add(dp)
        session.commit()
        session.refresh(dp)
    return dp


@router.post("/driver/profile", response_model=DriverProfile)
def set_driver_profile(body: DriverProfileIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель заполняет реальные данные авто (вместо захардкоженных)."""
    dp = _get_or_create_profile(session, user.id)
    dp.car_make = body.car_make
    dp.car_model = body.car_model
    dp.car_color = body.car_color
    dp.car_plate = body.car_plate
    dp.seats = body.seats
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp


class DriverVerifyIn(BaseModel):
    license_url: str = ""
    car_photo_url: str = ""


def _run_autocheck(session: Session, dp: DriverProfile) -> None:
    """Авто-проверка документов (OCR прав): помечает заявку и опц. авто-решает.

    Никогда не валит отправку: любая ошибка → autocheck_result='error', статус
    остаётся 'pending' (заявка уходит к человеку). Авто-одобрение по умолчанию
    выключено (settings.driver_autoapprove_enabled) — финальная кнопка за админом.
    """
    if not settings.driver_autocheck_enabled:
        return
    try:
        from ..driver_check import check_driver_docs
        res = check_driver_docs(dp.license_url, dp.car_photo_url)
        dp.autocheck_result = res["result"]
        dp.autocheck_score = float(res["score"])
        dp.autocheck_data = json.dumps(res["data"], ensure_ascii=False)
        dp.autocheck_at = utcnow()
        if settings.driver_autoreject_enabled and res["result"] == "reject":
            dp.docs_status = "rejected"
            target = session.get(User, dp.user_id)
            if target:
                target.verified = False
                session.add(target)
        elif settings.driver_autoapprove_enabled and res["result"] == "pass":
            dp.docs_status = "verified"
            target = session.get(User, dp.user_id)
            if target:
                target.verified = True
                session.add(target)
        # иначе остаётся 'pending' → решает админ (с готовыми данными из autocheck_data)
    except Exception as e:  # OCR/разбор упали — не наказываем водителя, отдаём человеку
        dp.autocheck_result = "error"
        dp.autocheck_data = json.dumps({"reasons": ["autocheck_error"], "error": str(e)[:200]}, ensure_ascii=False)


@router.post("/driver/verify", response_model=DriverProfile)
def submit_driver_verify(body: DriverVerifyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отправляет документы на проверку → 'pending' + авто-проверка (OCR прав)."""
    if not body.license_url or not body.car_photo_url:
        raise HTTPException(400, "Нужны фото прав и фото автомобиля")
    dp = _get_or_create_profile(session, user.id)
    dp.license_url = _ensure_owned_doc_url(body.license_url, user, dp)
    dp.car_photo_url = _ensure_owned_doc_url(body.car_photo_url, user, dp)
    dp.docs_status = "pending"
    dp.verify_submitted_at = utcnow()
    _run_autocheck(session, dp)   # может сменить статус на verified/rejected (если включено)
    session.add(dp)
    session.commit()
    session.refresh(dp)
    if dp.docs_status == "pending":
        car = " ".join(x for x in [dp.car_make, dp.car_model, dp.car_color, dp.car_plate] if x).strip() or "авто не указано"
        details = f"OCR: {dp.autocheck_result or 'нет'} · score {dp.autocheck_score:.2f}"
        notify_admin_telegram(
            f"🚗 Проверка водителя\n"
            f"ID: {user.id}\n"
            f"Имя: {user.name or 'Водитель'}\n"
            f"Телефон: {user.phone}\n"
            f"Авто: {car}\n"
            f"{details}\n\n"
            f"Одобрить или отклонить можно прямо тут:",
            reply_markup={
                "inline_keyboard": [[
                    {"text": "✅ Одобрить", "callback_data": f"drv:ok:{user.id}"},
                    {"text": "❌ Отклонить", "callback_data": f"drv:no:{user.id}"},
                ]]
            },
        )
    return dp


@router.get("/driver/status")
def driver_status(user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    return {
        "docs_status": dp.docs_status if dp else "none",
        "verified": user.verified,
        "car_make": dp.car_make if dp else "",
        "car_model": dp.car_model if dp else "",
        "car_color": dp.car_color if dp else "",
        "car_plate": dp.car_plate if dp else "",
        "seats": dp.seats if dp else 4,
        "license_url": dp.license_url if dp else "",
        "car_photo_url": dp.car_photo_url if dp else "",
        "online": dp.online if dp else False,
        "autocheck_result": dp.autocheck_result if dp else "",
        "autocheck_score": dp.autocheck_score if dp else 0.0,
        "autocheck_data": dp.autocheck_data if dp else "",
    }


# ----------------------------- Публичный профиль водителя -----------------------------
class PublicReviewOut(BaseModel):
    author: str                              # имя автора (без телефона/ПДн)
    stars: int
    text: str
    created_at: datetime


class DriverPublicOut(BaseModel):
    """Публичная витрина водителя (тапом с карточки поездки). БЕЗ ПДн: без телефона.
    Доверие «между своими»: стаж, поездки, средний рейтинг, отзывы прошедшие модерацию, бейдж."""
    id: int
    name: str
    avatar_url: str = ""
    verified: bool = False                   # бейдж «Проверен»
    joined_at: datetime                      # дата регистрации → стаж в Юлдаше
    days_in_service: int = 0                 # стаж в днях (клиент покажет «X лет/мес» на 2 языках)
    trips_count: int = 0                     # число завершённых поездок как водитель
    car: str = ""                            # марка+модель (без госномера — не публично)
    rating: Optional[float] = None           # средний рейтинг (None если оценок нет)
    rating_count: int = 0
    reviews: List[PublicReviewOut] = []      # последние отзывы, прошедшие модерацию


@router.get("/drivers/{driver_id}/public", response_model=DriverPublicOut)
def driver_public(driver_id: int, limit: int = 5, session: Session = Depends(get_session)):
    """Публичные данные водителя + агрегаты + последние модерированные текстовые отзывы.
    Без auth и без ПДн (телефон/точные координаты не отдаём) — открывается тапом с карточки."""
    limit = max(1, min(20, limit))
    u = session.get(User, driver_id)
    if not u:
        raise HTTPException(404, "Пользователь не найден")
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()

    # Завершённые поездки как водитель: брони со статусом done по его поездкам.
    ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == driver_id)).all())
    trips_done = 0
    if ride_ids:
        trips_done = len(session.exec(
            select(Booking.id).where(Booking.ride_id.in_(ride_ids), Booking.status == BookingStatus.done)
        ).all())

    avg, cnt = user_rating(session, driver_id)

    # Последние текстовые отзывы, прошедшие модерацию (text_published=True), новые сверху.
    rows = session.exec(
        select(Rating)
        .where(Rating.ratee_id == driver_id, Rating.text_published == True, Rating.text != "")  # noqa: E712
        .order_by(Rating.created_at.desc())
        .limit(limit)
    ).all()
    author_ids = {r.rater_id for r in rows}
    authors = {a.id: a for a in session.exec(select(User).where(User.id.in_(author_ids))).all()} if author_ids else {}
    reviews = [
        PublicReviewOut(
            author=((authors.get(r.rater_id).name if authors.get(r.rater_id) else "") or "Аноним"),
            stars=r.stars, text=r.text, created_at=r.created_at,
        )
        for r in rows
    ]

    joined = u.created_at
    now = utcnow()
    # created_at и utcnow() оба наивные UTC → вычитание безопасно.
    days = max(0, (now - joined).days) if joined else 0
    car = " ".join(x for x in [dp.car_make, dp.car_model] if x).strip() if dp else ""

    return DriverPublicOut(
        id=u.id, name=(u.name or "Водитель"), avatar_url=u.avatar_url or "",
        verified=u.verified, joined_at=joined, days_in_service=days,
        trips_count=trips_done, car=car,
        rating=(round(avg, 1) if cnt > 0 else None), rating_count=cnt,
        reviews=reviews,
    )


class PendingDriverOut(BaseModel):
    user_id: int
    name: str
    phone: str
    car: str
    license_url: str
    car_photo_url: str
    autocheck_result: str = ""        # pass / needs_human / reject / error / "" — подсказка админу
    autocheck_score: float = 0.0
    autocheck_data: str = ""          # JSON: распознанные поля + коды причин


@router.get("/admin/drivers/pending", response_model=List[PendingDriverOut])
def pending_drivers(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водители, ждущие проверки (docs_status=pending) — для модерации админом."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    profs = session.exec(select(DriverProfile).where(DriverProfile.docs_status == "pending")).all()
    if not profs:
        return []
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_({p.user_id for p in profs}))).all()}
    out: list = []
    for p in profs:
        u = users.get(p.user_id)
        car = " ".join(x for x in [p.car_make, p.car_model, p.car_color, p.car_plate] if x).strip()
        out.append(PendingDriverOut(
            user_id=p.user_id, name=(u.name if u and u.name else "Водитель"),
            phone=(u.phone if u else ""), car=car,
            license_url=p.license_url, car_photo_url=p.car_photo_url,
            autocheck_result=p.autocheck_result, autocheck_score=p.autocheck_score,
            autocheck_data=p.autocheck_data,
        ))
    return out


class ModerateIn(BaseModel):
    approve: bool = True


@router.post("/admin/drivers/{user_id}/moderate")
def moderate_driver(user_id: int, body: ModerateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Модерация водителя админом: подтвердить (verified=True) или отклонить."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    target = session.get(User, user_id)
    if not target:
        raise HTTPException(404, "Пользователь не найден")
    dp = _get_or_create_profile(session, user_id)
    if body.approve:
        target.verified = True
        dp.docs_status = "verified"
    else:
        target.verified = False
        dp.docs_status = "rejected"
    session.add(target)
    session.add(dp)
    session.commit()
    return {"user_id": user_id, "verified": target.verified, "docs_status": dp.docs_status}
