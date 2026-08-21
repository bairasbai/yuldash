"""Ранний доступ / лист ожидания (волна 2, §11 «Запуск»).

Публично: POST /waitlist — «оставь номер, сообщим, когда включим». Без аккаунта
(лендинг + экран «Такси скоро в твоём городе»), строгий rate-limit (middleware),
телефон нормализуем и валидируем (regex как в family.py), дедуп по номеру:
повторная подача обновляет city/role, не дублирует. СМС на этом этапе НЕ шлём —
рассылку по волнам Александр делает сам.

Админ: список + счётчики по городам/ролям, CSV-экспорт, пометка «позван в волне»
(invited_at). Приватность: телефоны видит ТОЛЬКО админ; в логи не пишем (152-ФЗ).
"""
import csv
import io
import re
from collections import Counter
from typing import List, Literal, Optional

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..db import get_session
from ..logs import admin_action
from ..errors import herr
from ..models import User, UserRole, WaitlistEntry
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["waitlist"])

_PHONE_RE = re.compile(r"^\+?\d{10,15}$")   # как в family.py: 10–15 цифр, опц. ведущий +
MAX_INVITE_IDS = 500                        # потолок одной «волны» (анти-случайный гигантский запрос)


def _normalize_phone(raw: str) -> str:
    """Убираем пробелы/дефисы/скобки: «+7 (917) 000-00-00» и «+79170000000» — один номер."""
    return re.sub(r"[\s\-()]", "", raw or "")


# ------------------------------ публичная подача ------------------------------
class WaitlistIn(BaseModel):
    phone: str = Field(..., max_length=32)
    city: Optional[str] = Field(None, max_length=80)
    role: Literal["passenger", "driver"] = "passenger"


@router.post("/waitlist")
def join_waitlist(body: WaitlistIn, session: Session = Depends(get_session)):
    """Оставить номер в листе ожидания. ПУБЛИЧНЫЙ (без токена) — сюда шлют и лендинг,
    и приложение до входа. Повтор того же номера обновляет city/role (город — только
    если передан, чтобы подача без города не затирала известный). Не дублирует."""
    phone = _normalize_phone(body.phone)
    if not _PHONE_RE.match(phone):
        raise herr(400, "Неверный номер телефона", "Телефон номеры дөрөҫ түгел")
    city = (body.city or "").strip()[:80]
    entry = session.exec(select(WaitlistEntry).where(WaitlistEntry.phone == phone)).first()
    if entry is None:
        entry = WaitlistEntry(phone=phone)
    if city:
        entry.city = city
    entry.role = body.role
    session.add(entry)
    try:
        session.commit()
    except IntegrityError:
        # Гонка: два одновременных запроса с ОДНИМ новым номером — оба увидели entry=None и
        # вставили; unique(phone) отклонит второй. Не 500: перечитываем и обновляем существующую.
        session.rollback()
        entry = session.exec(select(WaitlistEntry).where(WaitlistEntry.phone == phone)).first()
        if entry is not None:
            if city:
                entry.city = city
            entry.role = body.role
            session.add(entry)
            session.commit()
    return {"ok": True}


# ------------------------------ админ ------------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def _filtered(session: Session, city: Optional[str], role: Optional[str],
              invited: Optional[bool], limit: Optional[int] = None,
              offset: int = 0) -> List[WaitlistEntry]:
    q = select(WaitlistEntry)
    if city:
        q = q.where(WaitlistEntry.city == city.strip())
    if role:
        q = q.where(WaitlistEntry.role == role)
    if invited is not None:
        q = q.where(WaitlistEntry.invited_at.is_not(None) if invited   # type: ignore[union-attr]
                    else WaitlistEntry.invited_at.is_(None))           # type: ignore[union-attr]
    q = q.order_by(WaitlistEntry.id.desc())
    if limit is not None:
        q = q.offset(max(0, offset)).limit(limit)
    return session.exec(q).all()


def _entry_payload(e: WaitlistEntry) -> dict:
    return {
        "id": e.id,
        "phone": e.phone,
        "city": e.city or "",
        "role": e.role,
        "created_at": e.created_at.isoformat(),
        "invited_at": e.invited_at.isoformat() if e.invited_at else None,
    }


@router.get("/admin/waitlist")
def admin_waitlist(city: Optional[str] = None, role: Optional[str] = None, invited: Optional[bool] = None,
                   limit: int = 200, offset: int = 0,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Список ожидающих + счётчики. Счётчики (total/invited/by_city/by_role) — по ВСЕЙ базе
    (общая картина набора), items — по фильтрам city/role/invited. by_city отсортирован по
    убыванию (JSON-массив: порядок важен для UI, объект его не гарантирует)."""
    _require_admin(user)
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    # Счётчики — агрегатами в БД (было: вся таблица в память + Counter на каждый запрос).
    total = session.exec(select(func.count()).select_from(WaitlistEntry)).one()
    invited_total = session.exec(
        select(func.count()).select_from(WaitlistEntry)
        .where(WaitlistEntry.invited_at.is_not(None))  # type: ignore[union-attr]
    ).one()
    by_role = dict(session.exec(select(WaitlistEntry.role, func.count()).group_by(WaitlistEntry.role)).all())
    # Город группируем в БД; нормализацию «пусто/пробелы → —» доводим по УЖЕ сгруппированным
    # строкам (их число = число городов, а не всех записей — таблицу в память не тянем).
    by_city: Counter = Counter()
    for city_val, n in session.exec(select(WaitlistEntry.city, func.count()).group_by(WaitlistEntry.city)).all():
        by_city[(city_val or "").strip() or "—"] += n
    items = _filtered(session, city, role, invited, limit=limit, offset=offset)
    return {
        "total": total,
        "invited": invited_total,
        "by_city": [{"city": c, "count": n} for c, n in by_city.most_common()],
        "by_role": {"passenger": by_role.get("passenger", 0), "driver": by_role.get("driver", 0)},
        "items": [_entry_payload(e) for e in items],
    }


@router.get("/admin/waitlist.csv")
def admin_waitlist_csv(city: Optional[str] = None, role: Optional[str] = None, invited: Optional[bool] = None,
                       user: User = Depends(current_user), session: Session = Depends(get_session)):
    """CSV-экспорт (те же фильтры, что и список) — для рассылки/таблицы у Александра."""
    _require_admin(user)
    rows = _filtered(session, city, role, invited)
    # След обязателен (волна 167). Это не просмотр карточки, а вынос файла с телефонами живых
    # людей за пределы приложения: список ожидания — единственное место, где номер лежит
    # у человека, который сервисом ещё даже не пользуется. Журнал стерёг изменения данных
    # и не видел массового чтения, хотя утечка выгрузкой опаснее любой правки.
    # Пишем сколько и по какому фильтру — самих номеров в журнале быть не должно (§8).
    admin_action(user.id, "waitlist.export", rows=len(rows), city=city, role=role,
                 invited=invited)
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["id", "phone", "city", "role", "created_at", "invited_at"])
    for r in rows:
        w.writerow([r.id, r.phone, r.city or "", r.role, r.created_at.isoformat(),
                    r.invited_at.isoformat() if r.invited_at else ""])
    return PlainTextResponse(
        buf.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="waitlist.csv"'},
    )


class InviteIn(BaseModel):
    ids: List[int] = Field(..., min_length=1, max_length=MAX_INVITE_IDS)


@router.post("/admin/waitlist/invite")
def admin_waitlist_invite(body: InviteIn, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Пометить волну: проставить invited_at выбранным. Уже позванных не перетираем
    (сохраняем номер волны по времени). Рассылку админ делает сам — СМС тут нет."""
    _require_admin(user)
    now = utcnow()
    n = 0
    for wid in body.ids:
        e = session.get(WaitlistEntry, wid)
        if e is not None and e.invited_at is None:
            e.invited_at = now
            session.add(e)
            n += 1
    session.commit()
    admin_action(user.id, "waitlist.invite", count=n)
    return {"ok": True, "invited": n}


@router.delete("/admin/waitlist/{entry_id}")
def admin_waitlist_delete(entry_id: int, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Убрать номер из листа ожидания по просьбе человека (152-ФЗ, ст. 14 «право на удаление»).

    Ручки не было вообще: номер, оставленный на сайте, удалить было нечем — только руками
    в базе (аудит 2026-08-08). Аккаунта у этого человека нет, значит `/me/delete` ему не
    поможет; просьба приходит письмом или в бота, и у Александра должна быть кнопка.
    Идемпотентно: нет строки — считаем, что уже удалили.
    """
    _require_admin(user)
    entry = session.get(WaitlistEntry, entry_id)
    if entry is None:
        return {"ok": True, "deleted": 0}
    session.delete(entry)
    session.commit()
    admin_action(user.id, "waitlist.delete", entry_id=entry_id)
    return {"ok": True, "deleted": 1}
