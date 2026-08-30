# -*- coding: utf-8 -*-
"""Фотоконтроль машины: экран водителя/курьера и очередь просмотра у админа (580-ФЗ).

Водителю: узнать, что и до какого числа снять (`GET /carphoto`), прислать кадр
(`POST /carphoto/photo`) и отправить набор (`POST /carphoto/submit`).
Админу: очередь тех, кого смотрит человек, и решение по ней.

Кадры лежат в ПРИВАТНОЙ области хранилища (`carphoto/`) и отдаются только владельцу и
админу — как документы. Отличие от документов одно, но важное: эти файлы живут 90 дней и
дальше стираются ретеншеном (`cleanup._clean_carphoto`). Хранить снимок чужой машины у
подъезда дольше, чем нужно для разбора, незачем (152-ФЗ, ст. 5 п. 7).

Логика (расписание, лестница просрочки, автопроверка) живёт в `app/carphoto.py` — здесь
только двери. Правило то же, что везде: правило одно, вызывают его и экран, и гейт.
"""
import os
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, Request
from fastapi.responses import FileResponse, RedirectResponse
from pydantic import BaseModel, Field
from sqlmodel import Session, select
from starlette.concurrency import run_in_threadpool

from .. import carphoto as cp
from ..config import settings
from ..db import get_session
from ..errors import herr
from ..logs import admin_action
from ..models import CarPhotoCheck, User, UserRole
from ..security import current_user
from ..services import CARPHOTO_DIR, enforce_upload_quota, read_upload, secure_carphoto_url
from ..storage import get_storage

router = APIRouter(tags=["carphoto"])


def _mode(raw: Optional[str]) -> str:
    """Режим из запроса. Чужое слово — 400, а не молчаливое «такси»: иначе курьер,
    приславший опечатку, отчитался бы за машину не в том режиме."""
    mode = (raw or cp.TAXI).strip().lower()
    if mode not in (cp.TAXI, cp.COURIER):
        raise herr(400, "Неизвестный режим", "Билдәһеҙ режим")
    return mode


def _kind(raw: Optional[str]) -> str:
    """Плановый контроль или требование по жалобе. Чужое слово — 400, а не молчаливый выбор."""
    kind = (raw or cp.PERIODIC).strip().lower()
    if kind not in (cp.PERIODIC, cp.COMPLAINT):
        raise herr(400, "Неизвестный вид контроля", "Контролдең билдәһеҙ төрө")
    return kind


def _open_check(session: Session, user_id: int, mode: str,
                kind: str = cp.PERIODIC) -> CarPhotoCheck:
    """Открытый контроль человека — или honest 404, если его сейчас нет."""
    if not cp.enabled(mode):
        raise herr(403, "Фотоконтроль пока не включён", "Фотоконтроль әлегә ҡабыҙылмаған")
    if kind == cp.COMPLAINT:
        # Требование по жалобе не заводится по запросу человека: его открывает жалоба.
        проверка = cp.current_complaint(session, user_id, mode)
    else:
        проверка = cp.ensure(session, user_id, mode)
    if проверка is None or проверка.status != cp.WAITING:
        raise herr(404, "Сейчас фотоконтроль не нужен", "Хәҙер фотоконтроль кәрәкмәй")
    return проверка


@router.get("/carphoto")
def my_carphoto(mode: str = cp.TAXI, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Что снять, до какого числа и что сейчас с допуском."""
    return cp.payload(session, user.id, _mode(mode))


@router.post("/carphoto/photo")
async def upload_carphoto(request: Request, mode: str = cp.TAXI, slot: str = "",
                          kind: str = cp.PERIODIC,
                          user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Прислать ОДИН кадр. Ответ: ссылка и вердикт автомата по этому кадру.

    Вердикт возвращается сразу, а не при отправке набора: человек стоит у машины с
    телефоном в руках, и переснять кадр он может прямо сейчас. Узнать «первое фото не
    подошло» через пять минут после ухода домой — плохая работа, а не строгость.

    Дату съёмки читаем ДО того, как общая дверь загрузки срежет метаданные, — иначе
    смотреть будет уже не на что (`services._validate_upload`, параметр `probe`).
    """
    mode, kind = _mode(mode), _kind(kind)
    проверка = _open_check(session, user.id, mode, kind)
    коды = {слот["code"] for слот in cp.check_slots(проверка)}
    if slot not in коды:
        raise herr(400, "Неизвестный кадр", "Билдәһеҙ кадр")
    enforce_upload_quota(session, user.id)

    вердикт = {"ok": True, "reason": "", "hash": ""}
    известные = cp.known_hashes(session, user.id, mode)
    # Отпечатки кадров ЭТОГО же контроля тоже в списке: четыре стороны кузова не могут
    # оказаться одной и той же фотографией.
    свои = cp.hashes(проверка)
    известные += [h for код, h in свои.items() if h and код != slot]

    def _probe(raw: bytes, ext: str) -> None:
        вердикт.update(cp.inspect(raw, ext, known=известные))

    data, ext = await read_upload(request, settings.image_ext_set, "jpg", "фото",
                                  sniff_image=True, probe=_probe)
    name = f"{user.id}_{uuid.uuid4().hex}.{ext}"
    await run_in_threadpool(get_storage().save, f"carphoto/{name}", data)
    url = secure_carphoto_url(name)
    cp.attach(session, проверка, slot, url, "ok" if вердикт["ok"] else вердикт["reason"],
              вердикт.get("hash", ""))
    ru, ba = cp.REASON_TEXT.get(вердикт["reason"], ("", ""))
    return {"url": url, "slot": slot, "ok": bool(вердикт["ok"]),
            "reason": вердикт["reason"], "message": {"ru": ru, "ba": ba},
            "missing": cp.missing_slots(проверка)}


class SubmitIn(BaseModel):
    mode: str = Field(default=cp.TAXI, max_length=16)
    kind: str = Field(default=cp.PERIODIC, max_length=16)   # periodic | complaint


@router.post("/carphoto/submit")
def submit_carphoto(body: SubmitIn, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Отправить набор кадров. Принято сразу или ушло человеку на просмотр."""
    mode, kind = _mode(body.mode), _kind(body.kind)
    проверка = _open_check(session, user.id, mode, kind)
    итог = cp.submit(session, проверка)
    if not итог["ok"]:
        raise herr(400, "Не все кадры готовы", "Бөтә кадрҙар ҙа әҙер түгел")
    return cp.payload(session, user.id, mode)


@router.get("/secure/carphoto/{name}")
def secure_carphoto(name: str, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Кадр фотоконтроля. Доступ: админ ИЛИ тот, кто его прислал.

    Имя файла даёт сервер и начинает его с id владельца — по нему и проверяем. Локально
    отдаём файл, в S3-режиме после проверки доступа редиректим на подписанный URL."""
    safe = os.path.basename(name)          # защита от path traversal
    if user.role != UserRole.admin and not safe.startswith(f"{user.id}_"):
        raise herr(403, "Нет доступа к файлу", "Файлға инеү юҡ")
    storage = get_storage()
    if not storage.exists(f"carphoto/{safe}"):
        raise herr(404, "Файл не найден", "Файл табылманы")
    if storage.is_remote:
        return RedirectResponse(storage.url(f"carphoto/{safe}"))
    return FileResponse(os.path.join(CARPHOTO_DIR, safe))


# ------------------------------------------------------------------ очередь у админа
def _guard_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise herr(403, "Только для админа", "Тик админ өсөн")


def _admin_row(session: Session, row: CarPhotoCheck) -> dict:
    return {
        "id": row.id, "user_id": row.user_id, "mode": row.mode, "seq": row.seq,
        "status": row.status, "winter": row.winter,
        "manual_reason": row.manual_reason,
        "due_at": row.due_at.isoformat(),
        "submitted_at": row.submitted_at.isoformat() if row.submitted_at else None,
        "photos": cp.photos(row),
        # Что именно смотреть глазами: зимой чистоту кузова не спрашиваем, на первом
        # контроле такси — ещё и опознавательные знаки (фонарь, «шашечки»).
        "check_clean_body": not row.winter,
        "check_signs": bool(row.seq == 1 and row.mode == cp.TAXI),
        # Требование по жалобе смотрят иначе: там вопрос один — грязно или нет, и ответ
        # на него решает судьбу жалобы (`carphoto._settle_complaint`).
        "kind": getattr(row, "kind", cp.PERIODIC),
        "report_id": row.report_id,
        "clean_rules": [{"ru": ru, "ba": ba} for ru, ba in cp.CLEAN_RULES],
    }


@router.get("/admin/carphoto")
def admin_queue(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Очередь фотоконтролей, которые смотрит человек. Старые сверху — их ждут дольше."""
    _guard_admin(user)
    строки = session.exec(select(CarPhotoCheck).where(
        CarPhotoCheck.status == cp.REVIEW).order_by(CarPhotoCheck.submitted_at)).all()
    return [_admin_row(session, r) for r in строки]


class DecideIn(BaseModel):
    ok: bool
    reason: str = Field(default="", max_length=200)   # что переснять, если не принято


@router.post("/admin/carphoto/{check_id}/decide")
def admin_decide(check_id: int, body: DecideIn, user: User = Depends(current_user),
                 session: Session = Depends(get_session)):
    """Решение человека: принято или переснять. Водителю уходит объяснение, а не «отказано»."""
    _guard_admin(user)
    строка = session.get(CarPhotoCheck, check_id)
    if строка is None:
        raise herr(404, "Контроль не найден", "Контроль табылманы")
    if строка.status != cp.REVIEW:
        raise herr(400, "Этот контроль уже закрыт", "Был контроль ябылған инде")
    if not body.ok and not body.reason.strip():
        # Отказ без объяснения — это тупик для человека: он не знает, что переснимать.
        raise herr(400, "Напиши, что переснять", "Нимәне яңынан төшөрөргә — яҙ")
    cp.decide(session, строка, body.ok, reason=body.reason.strip(), admin_id=user.id)
    admin_action(user.id, "carphoto_decide", check_id=check_id, ok=body.ok,
                 target_user_id=строка.user_id, mode=строка.mode)
    return {"ok": True, "status": строка.status}
