"""Водитель: онлайн-статус, профиль авто, загрузка фото документов (приватно),
отправка на проверку, статус проверки, выдача защищённых документов, модерация админом."""
import json
import os
from urllib.parse import urlparse
import uuid
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse
from starlette.concurrency import run_in_threadpool
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import Booking, BookingStatus, DriverProfile, Rating, Ride, User, UserRole
from ..security import current_user
from ..services import (
    DOC_DIR, enforce_upload_quota, notify_admin_telegram, read_upload, secure_docs_url,
    set_driver_docs_verdict, set_user_gender, user_rating,
)
from ..storage import get_storage
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
    """Ссылка на документ → она же, если это СВОЙ загруженный файл. Иначе 400/403/404.

    Одна дверь для всех документов: права и фото авто водителя, разрешение/ОСАГО/селфи/справка
    таксиста, селфи курьера. Без неё в заявку въезжает чужой адрес, а модерация грузит его
    с токеном админа в заголовке (аудит 2026-08-08).
    """
    name = _doc_name_from_url(url)
    if not name:
        raise herr(400, "Нужен защищённый файл документа", "Документтың һаҡланған файлы кәрәк")
    if not _is_owned_doc_name(name, user.id, profile):
        raise herr(403, "Можно отправить только свои загруженные документы", "Тик үҙең йөкләгән документтарҙы ебәреп була")
    # Наличие файла спрашиваем У ХРАНИЛИЩА, а не у диска. Загрузка идёт через
    # `get_storage().save(...)`, и при STORAGE_BACKEND=s3 файла на диске нет вовсе — прямая
    # проверка `os.path.isfile` отвечала бы «не найден» на КАЖДУЮ заявку водителя, таксиста и
    # курьера. Сегодня включён локальный диск, поэтому мина не сработала ни разу; сработала бы
    # в день переезда в облако, и выглядело бы это как «проверка документов сломалась».
    if not get_storage().exists(f"docs/{name}"):
        raise herr(404, "Файл документа не найден", "Документ файлы табылманы")
    return url.strip()


def drop_replaced_doc(old_url: str, new_url: str) -> None:
    """Старый документ заменили новым — стираем прежний файл.

    Документы лежат в приватной области `docs/`, и ретеншен её НЕ трогает намеренно: пока
    человек работает, 580-ФЗ требует хранить действующие документы. Но ПРЕЖНЯЯ версия —
    просроченные права, старое ОСАГО, устаревшее селфи — цели больше не служит и оставалась
    на диске навсегда (аудит 2026-08-08, волна 11). Ст. 5 п. 7 152-ФЗ: хранить ровно столько,
    сколько нужно.

    Зовётся ПОСЛЕ commit: пока строка не сохранена, файл ещё нужен. Ничего не делает, если
    ссылка не изменилась (обычный случай — человек прислал ту же) или старой не было.
    """
    old, new = (old_url or "").strip(), (new_url or "").strip()
    if not old or old == new:
        return
    name = os.path.basename(urlparse(old).path)
    if not name or name in (".", ".."):
        return
    try:
        get_storage().delete(f"docs/{name}")
    except Exception:  # noqa: BLE001 — уборка мусора не вправе ронять сохранение документов
        pass


class OnlineIn(BaseModel):
    online: bool


@router.post("/driver/online", response_model=DriverProfile)
def driver_online(body: OnlineIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        dp = DriverProfile(user_id=user.id, online=body.online)
    if body.online:
        dp.online = True
        session.add(dp)
    else:
        # Снял тумблер — вместе с флагом убираем координаты из Redis: у GEO-множества нет
        # срока жизни (в отличие от heartbeat), и точка человека лежала бы там вечно
        # (аудит 2026-08-08, волна 12). Общая дверь — чтобы то же самое делали и те, кто
        # снимает водителя принудительно (просроченные документы).
        from ..instant_service import driver_go_offline
        driver_go_offline(session, dp)
    session.commit()
    session.refresh(dp)
    return dp


_ALLOWED_GENDERS = ("", "female", "male")


class GenderIn(BaseModel):
    # "" — снять/не указывать (opt-out), female / male. Пол — деликатное поле,
    # меняет его только сам водитель.
    gender: str = ""


@router.post("/driver/gender", response_model=DriverProfile)
def set_driver_gender(body: GenderIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """F9 «Женщинам — водитель-женщина»: пол по желанию (opt-in).

    Пишем в `User.gender` — пол переехал на человека (аудит 2026-08-08), потому что нужен и
    пассажиру: отметка «только женщины» на попутке проверяется у обеих сторон. Ручку оставляем
    как есть: установленные приложения зовут именно её. Тот же смысл есть в `POST /me/update`.

    Это по-прежнему ЗАЯВКА, а не подтверждение: публичный бейдж «женщина за рулём» и фильтр
    включает модератор (`/admin/drivers/{id}/moderate`, поле `DriverProfile.gender_verified`),
    сверив с фото прав. Иначе любой назовётся женщиной и попадёт в выдачу — жалоба, которая
    копится у Uber. Смена пола сбрасывает подтверждение: новое заявление — новый просмотр.
    """
    g = (body.gender or "").strip().lower()
    if g not in _ALLOWED_GENDERS:
        raise herr(400, "Недопустимое значение пола", "Ярамаған енес мәғәнәһе")
    # Профиль водителя создаём ДО записи: в нём живёт ПОДТВЕРЖДЕНИЕ пола, которое
    # `set_user_gender` гасит при смене заявления.
    dp = _get_or_create_profile(session, user.id)
    # Пол меняем только через общий хелпер — там же сброс подтверждения. Раньше это
    # правило было переписано здесь, а в `/me/update` его не было (см. services.set_user_gender).
    set_user_gender(session, user, g)
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
    # синхронный save() в async-хендлере → threadpool, чтобы заливка/зависший S3 не морозил event-loop.
    await run_in_threadpool(get_storage().save, f"docs/{name}", data)
    return {"url": secure_docs_url(name)}


@router.get("/secure/docs/{name}")
def secure_doc(name: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отдать фото документа водителя. Доступ: админ ИЛИ владелец этого документа.

    URL стабильный (/secure/docs/{name}) — авторизацию всегда делает приложение (152-ФЗ).
    Локально отдаём файл; в S3-режиме после проверки доступа редиректим на подписанный URL."""
    safe = os.path.basename(name)   # защита от path traversal
    if user.role != UserRole.admin:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
        if not _is_owned_doc_name(safe, user.id, prof):
            raise herr(403, "Нет доступа к документу", "Документҡа инеү юҡ")
    storage = get_storage()
    if not storage.exists(f"docs/{safe}"):
        raise herr(404, "Файл не найден", "Файл табылманы")
    if storage.is_remote:
        return RedirectResponse(storage.url(f"docs/{safe}"))
    return FileResponse(os.path.join(DOC_DIR, safe))


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
        # Вердикт ставим общим хелпером: там же гаснет подтверждение пола при отказе
        # (пол подтверждают по фото прав — см. services.set_driver_docs_verdict).
        if settings.driver_autoreject_enabled and res["result"] == "reject":
            target = session.get(User, dp.user_id)
            if target:
                set_driver_docs_verdict(session, target, dp, approved=False)
        elif settings.driver_autoapprove_enabled and res["result"] == "pass":
            target = session.get(User, dp.user_id)
            if target:
                set_driver_docs_verdict(session, target, dp, approved=True)
        # иначе остаётся 'pending' → решает админ (с готовыми данными из autocheck_data)
    except Exception as e:  # OCR/разбор упали — не наказываем водителя, отдаём человеку
        dp.autocheck_result = "error"
        dp.autocheck_data = json.dumps({"reasons": ["autocheck_error"], "error": str(e)[:200]}, ensure_ascii=False)


@router.post("/driver/verify", response_model=DriverProfile)
def submit_driver_verify(body: DriverVerifyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отправляет документы на проверку → 'pending' + авто-проверка (OCR прав)."""
    if not body.license_url or not body.car_photo_url:
        raise herr(400, "Нужны фото прав и фото автомобиля", "Права һәм машина фотоһы кәрәк")
    dp = _get_or_create_profile(session, user.id)
    prev_license, prev_car = dp.license_url, dp.car_photo_url
    dp.license_url = _ensure_owned_doc_url(body.license_url, user, dp)
    dp.car_photo_url = _ensure_owned_doc_url(body.car_photo_url, user, dp)
    dp.docs_status = "pending"
    # Пол подтверждали по ФОТО ПРАВ, а прежнее фото ниже стирается насовсем. Прислали другие
    # права — подтверждение висит на документе, которого больше нет: гасим, модератор сверит
    # заново. Фото не менялось (переотправили ту же пару) — подтверждение остаётся.
    if prev_license and dp.license_url != prev_license:
        dp.gender_verified = False
    dp.verify_submitted_at = utcnow()
    _run_autocheck(session, dp)   # может сменить статус на verified/rejected (если включено)
    session.add(dp)
    session.commit()
    session.refresh(dp)
    # Прежние фото прав и авто больше не нужны — стираем, чтобы не копить чужие ПДн навсегда.
    drop_replaced_doc(prev_license, dp.license_url)
    drop_replaced_doc(prev_car, dp.car_photo_url)
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
        "gender": user.gender or "",   # виден только самому себе; источник — User.gender
        # Подтверждён ли пол модератором. Без этого поля кабинет водителя обещал женщине
        # бейдж и женские заказы сразу после тумблера, хотя и то и другое включает только
        # проверка прав (см. `set_driver_gender`): она ждала заказы, которых не будет.
        "gender_verified": bool(dp.gender_verified) if dp else False,
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
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()

    # СОСТОЯВШИЕСЯ поездки как водитель: бронь закрыта И поездка уже выехала.
    # Второе условие обязательно (независимая проверка аудита 2026-08-07): эту карточку
    # отдают БЕЗ входа, по ней человек решает, садиться ли в машину. Без планки по времени
    # цикл «опубликовал на 2030 год → забронировал вторым аккаунтом → завершил рейс»
    # рисовал «5 поездок, рейтинг 5.0» тому, кто не проехал ни метра. То же правило —
    # в `services.driver_trips_agg` (лента) и `safety_logic.completed_trips_for` (доверие).
    ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == driver_id)).all())
    # Это витрина ВОДИТЕЛЯ, и открыта она без входа. Раньше id в адресе не проверялся ничем:
    # подставив номер обычного пассажира, посторонний получал его имя, фото, дату регистрации,
    # рейтинг и тексты отзывов о нём — и мог перебрать так всю базу по возрастанию id
    # (аудит 2026-08-08). Для приложения «между своими» это ровно то, от чего мы прячем
    # закрытые поездки и точные координаты заявок.
    #
    # Водителем считаем по трём признакам, любого достаточно: роль, кабинет водителя или хоть
    # одна опубликованная поездка. Роль здесь не «слово пользователя о себе» — подделать её
    # можно только СЕБЕ, а закрываем мы перебор ЧУЖИХ профилей. Никто из трёх — 404 тем же
    # текстом, существование аккаунта не раскрываем.
    if u.role != UserRole.driver and dp is None and not ride_ids:
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    trips_done = 0
    if ride_ids:
        trips_done = len(session.exec(
            select(Booking.id)
            .join(Ride, Booking.ride_id == Ride.id)
            .where(Booking.ride_id.in_(ride_ids), Booking.status == BookingStatus.done,
                   Ride.depart_at <= utcnow())
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
    # Что водитель ЗАЯВИЛ о поле ("" / female / male) и подтверждено ли это. Админ и так
    # смотрит фото прав — сверить там же дешевле, чем строить отдельный процесс.
    gender_claimed: str = ""
    gender_verified: bool = False


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
            # Заявленный пол берём у ЧЕЛОВЕКА: он переехал на User (аудит 2026-08-08), а в
            # профиле водителя осталось только подтверждение. Читали бы старое поле — модератор
            # видел бы пустую строку и не понимал, что вообще подтверждает.
            gender_claimed=((u.gender or "") if u else ""), gender_verified=p.gender_verified,
        ))
    return out


class ModerateIn(BaseModel):
    approve: bool = True
    # Подтверждение пола по фото прав. None = не трогать (старый клиент шлёт только approve
    # и ничего не ломает). True включает бейдж «женщина за рулём», False — снимает.
    gender_verified: Optional[bool] = None


@router.post("/admin/drivers/{user_id}/moderate")
def moderate_driver(user_id: int, body: ModerateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Модерация водителя админом: подтвердить (verified=True) или отклонить.

    Здесь же подтверждается пол: бейдж «женщина за рулём» и фильтр «только женщины» включает
    модератор, сверив с фото прав, а не сам водитель (см. models.DriverProfile.gender_verified).
    """
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    target = session.get(User, user_id)
    if not target:
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    dp = _get_or_create_profile(session, user_id)
    # Вердикт по документам — общим хелпером: отказ там же гасит подтверждение пола
    # (подтверждали его по этим самым правам).
    set_driver_docs_verdict(session, target, dp, approved=bool(body.approve))
    if body.gender_verified is not None:
        # Подтверждать нечего, если водитель ничего не заявил: пустой пол нигде не показывается.
        # Заявление читаем с `User.gender` — в профиле водителя это поле устарело и не пишется,
        # по нему условие всегда было бы ложным и подтверждение не включалось бы никогда.
        dp.gender_verified = bool(body.gender_verified) and (target.gender or "") in ("female", "male")
    session.add(target)
    session.add(dp)
    session.commit()
    return {
        "user_id": user_id, "verified": target.verified, "docs_status": dp.docs_status,
        "gender_verified": dp.gender_verified,
    }
