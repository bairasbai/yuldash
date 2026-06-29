"""Водитель: онлайн-статус, профиль авто, загрузка фото документов (приватно),
отправка на проверку, статус проверки, выдача защищённых документов, модерация админом."""
import json
import os
import uuid
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import DriverProfile, User, UserRole
from ..security import current_user
from ..services import DOC_DIR, enforce_upload_quota, read_upload, secure_docs_url
from ..timeutil import utcnow

router = APIRouter(tags=["drivers"])


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
    name = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(DOC_DIR, name), "wb") as f:
        f.write(data)
    return {"url": secure_docs_url(name)}


@router.get("/secure/docs/{name}")
def secure_doc(name: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отдать фото документа водителя. Доступ: админ ИЛИ владелец этого документа."""
    safe = os.path.basename(name)   # защита от path traversal
    if user.role != UserRole.admin:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
        owns = prof is not None and (safe in (prof.license_url or "") or safe in (prof.car_photo_url or ""))
        if not owns:
            raise HTTPException(403, "Нет доступа к документу")
    path = os.path.join(DOC_DIR, safe)
    if not os.path.isfile(path):
        raise HTTPException(404, "Файл не найден")
    return FileResponse(path)


class DriverProfileIn(BaseModel):
    car_make: str = ""
    car_model: str = ""
    car_color: str = ""
    car_plate: str = ""
    seats: int = 4


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
    dp.license_url = body.license_url
    dp.car_photo_url = body.car_photo_url
    dp.docs_status = "pending"
    dp.verify_submitted_at = utcnow()
    _run_autocheck(session, dp)   # может сменить статус на verified/rejected (если включено)
    session.add(dp)
    session.commit()
    session.refresh(dp)
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
