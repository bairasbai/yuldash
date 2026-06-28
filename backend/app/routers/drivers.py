"""Водитель: онлайн-статус, профиль авто, загрузка фото документов (приватно),
отправка на проверку, статус проверки, выдача защищённых документов, модерация админом."""
import os
import uuid

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import DriverProfile, User, UserRole
from ..security import current_user
from ..services import DOC_DIR, decode_upload_b64, enforce_upload_quota, secure_docs_url
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


class PhotoIn(BaseModel):
    photo_b64: str
    ext: str = "jpg"


@router.post("/upload/photo")
def upload_photo(body: PhotoIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Загрузка фото документа/авто → приватная папка → защищённый URL (только админ/владелец)."""
    enforce_upload_quota(session, user.id)
    ext = "".join(c for c in body.ext.lower() if c.isalnum()) or "jpg"
    data, ext = decode_upload_b64(body.photo_b64, settings.image_ext_set, ext, "фото", sniff_image=True)
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


@router.post("/driver/verify", response_model=DriverProfile)
def submit_driver_verify(body: DriverVerifyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отправляет документы на проверку → статус 'pending' (модерацию делает админ)."""
    if not body.license_url or not body.car_photo_url:
        raise HTTPException(400, "Нужны фото прав и фото автомобиля")
    dp = _get_or_create_profile(session, user.id)
    dp.license_url = body.license_url
    dp.car_photo_url = body.car_photo_url
    dp.docs_status = "pending"
    dp.verify_submitted_at = utcnow()
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
    }


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
