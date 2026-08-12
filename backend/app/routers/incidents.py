"""Система «Справедливость»: инциденты (двусторонние споры по поездкам), standing, политика.

Обе стороны слышимы (due process): заявитель описывает → обвинённый объясняется → админ решает
соразмерно по лестнице и объясняет обеим. Дополняет анонимные жалобы (Report), не заменяет.
Приватность: телефон второй стороны участникам НЕ отдаём — только админу в /admin/incidents.
Фото-доказательства: /upload/evidence → URL в evidence_urls (заявитель) / respondent_evidence_urls
(обвинённый); файлы приватны, выдача — /secure/evidence/{name} только сторонам спора и админу.
"""
import os
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from fastapi.responses import FileResponse, RedirectResponse
from pydantic import BaseModel, Field
from sqlalchemy import or_
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..antifraud import moderate_open_text
from ..models import Booking, Incident, InstantOrder, Ride, User, UserRole
from ..safety_logic import (
    INCIDENT_TYPES, SEVERE_TYPES, active_incidents_count, apply_incident_resolution,
    clamp, completed_trips_for, csv_from_urls, ensure_active, guard_own_evidence,
    incidents_last_hour, is_suspended,
    refresh_standing, reliability_for, urls_from_csv,
)
from ..security import current_user
from ..services import (EVIDENCE_DIR, booking_and_ride_for_user, notify_admin_telegram,
                        push_notification, user_rating)
from ..storage import get_storage
from ..timeutil import utcnow

router = APIRouter(tags=["incidents"])


# ----------------------------- Схемы -----------------------------
class IncidentIn(BaseModel):
    respondent_id: int
    type: str
    description: str = Field("", max_length=2000)
    booking_id: Optional[int] = None
    # Такси-заказ как контекст спора. `create_incident` умел это с прошлого раунда, но публичная
    # ручка поля не принимала — и пожаловаться на поездку в такси было технически НЕЛЬЗЯ
    # (единственный контекст = бронь попутки). Та же дыра, что была у посылок (аудит 2026-07-26).
    order_id: Optional[int] = None
    evidence_urls: Optional[List[str]] = Field(None, max_length=10)   # свои /secure/evidence-URL


class RespondIn(BaseModel):
    statement: str = Field("", max_length=2000)
    evidence_urls: Optional[List[str]] = Field(None, max_length=10)   # право на защиту — тоже с фото


class AppealIn(BaseModel):
    text: str = Field("", max_length=2000)


class ResolveIn(BaseModel):
    resolution: str = Field("", max_length=40)   # dismissed/warning/strike/suspend/ban/mutual_resolved
    fault: str = Field("", max_length=20)         # none/reporter/respondent/both/unclear
    note: str = Field("", max_length=2000)
    # Потолок 1 млн ₽ и не-отрицательно: политика денег-в-копейках (int4-границы, опечатки админа).
    compensation_kop: int = Field(0, ge=0, le=100_000_000)
    strike: bool = False
    suspend_days: Optional[int] = Field(None, ge=1, le=3650)
    exclude_rating: bool = False
    shield: bool = False


class IncidentOut(BaseModel):
    id: int
    booking_id: Optional[int]
    type: str
    severe: bool
    status: str
    reporter_role: str
    description: str
    respondent_statement: str
    responded_at: Optional[datetime]
    resolution: str
    fault: str
    resolution_note: str
    compensation_kop: int
    appeal_text: str
    appeal_status: str
    created_at: datetime
    updated_at: datetime
    resolved_at: Optional[datetime]
    my_role: str                 # reporter / respondent / admin
    other_name: str              # имя второй стороны (без телефона — приватность)
    evidence_urls: List[str] = []                # фото заявителя (/secure/evidence, видят стороны+админ)
    respondent_evidence_urls: List[str] = []     # фото обвинённого
    booking_route: Optional[str] = None


class AdminIncidentOut(BaseModel):
    id: int
    booking_id: Optional[int]
    type: str
    severe: bool
    status: str
    reporter_role: str
    # None = сторона удалила аккаунт: спор обезличен, но жив (см. account.py, 3.7-bis).
    reporter_id: Optional[int]
    reporter_name: str
    reporter_phone: str
    respondent_id: Optional[int]
    respondent_name: str
    respondent_phone: str
    description: str
    respondent_statement: str
    responded_at: Optional[datetime]
    resolution: str
    fault: str
    resolution_note: str
    compensation_kop: int
    appeal_text: str
    appeal_status: str
    created_at: datetime
    updated_at: datetime
    resolved_at: Optional[datetime]
    evidence_urls: List[str] = []
    respondent_evidence_urls: List[str] = []
    booking_route: Optional[str] = None


# ----------------------------- Хелперы -----------------------------
def _route_for(session: Session, booking_id: Optional[int]) -> Optional[str]:
    if not booking_id:
        return None
    b = session.get(Booking, booking_id)
    if not b:
        return None
    r = session.get(Ride, b.ride_id)
    return f"{r.from_city}→{r.to_city}" if r else None


def _context_route(session: Session, inc: Incident) -> Optional[str]:
    """Человеческая подпись спора: маршрут поездки, доставки или такси-заказа — что заполнено."""
    if inc.booking_id:
        return _route_for(session, inc.booking_id)
    if inc.parcel_id:
        from ..models import ParcelDelivery
        p = session.get(ParcelDelivery, inc.parcel_id)
        return f"📦 {p.from_city}→{p.to_city}" if p else None
    if inc.order_id:
        o = session.get(InstantOrder, inc.order_id)
        return f"🚕 {o.from_text or '?'}→{o.to_text or '?'}" if o else None
    return None


def _name(u: Optional[User]) -> str:
    return (u.name if u and u.name else "Пользователь")


# Сторона спора удалила аккаунт (её ссылка обезличена) — так и подписываем, чтобы админ
# не искал «Пользователя», которого больше нет.
_GONE_NAME = "Удалённый аккаунт · Юйылған аккаунт"


def _party_name(session: Session, uid: Optional[int]) -> str:
    if uid is None:
        return _GONE_NAME
    return _name(session.get(User, uid))


def _incident_out(session: Session, inc: Incident, viewer: User) -> IncidentOut:
    if viewer.id == inc.reporter_id:
        my_role, other_id = "reporter", inc.respondent_id
    elif viewer.id == inc.respondent_id:
        my_role, other_id = "respondent", inc.reporter_id
    else:
        my_role, other_id = "admin", inc.respondent_id
    return IncidentOut(
        id=inc.id, booking_id=inc.booking_id, type=inc.type, severe=inc.type in SEVERE_TYPES,
        status=inc.status, reporter_role=inc.reporter_role, description=inc.description,
        respondent_statement=inc.respondent_statement, responded_at=inc.responded_at,
        resolution=inc.resolution, fault=inc.fault, resolution_note=inc.resolution_note,
        compensation_kop=inc.compensation_kop, appeal_text=inc.appeal_text, appeal_status=inc.appeal_status,
        created_at=inc.created_at, updated_at=inc.updated_at, resolved_at=inc.resolved_at,
        # other_id может быть None: вторая сторона удалила аккаунт (спор обезличен, но жив).
        my_role=my_role, other_name=_party_name(session, other_id),
        evidence_urls=urls_from_csv(inc.evidence_urls),
        respondent_evidence_urls=urls_from_csv(inc.respondent_evidence_urls),
        booking_route=_context_route(session, inc),
    )


def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def create_incident(
    session: Session, *, reporter: User, respondent_id: int, type: str,
    description: str = "", booking_id: Optional[int] = None, reporter_role: str = "",
    background: Optional[BackgroundTasks] = None, rate_limit: bool = True,
    evidence_urls: Optional[List[str]] = None,
    parcel_id: Optional[int] = None, order_id: Optional[int] = None,
) -> Incident:
    """Создать инцидент со всеми проверками/побочками. Общая точка для /incidents, спора по
    доставке (parcels.py) и будущих авто-детектов.

    Контекст спора — ровно один из трёх: booking_id (попутка), order_id (такси-заказ),
    parcel_id (доставка). Раньше поддерживалась только попутка, поэтому заведённые типы
    parcel_damage/parcel_lost были недостижимы: код требовал booking_id и отвечал 400 (аудит 2026-07-26)."""
    if respondent_id == reporter.id:
        raise herr(400, "Нельзя пожаловаться на себя", "Үҙеңә ялыу яҙып булмай")
    # Приложить можно только СВОИ фото. Проверка стоит здесь, в общей точке: её зовут и
    # /incidents, и спор по доставке (parcels.py), и будущие авто-детекты — правило одно на всех.
    guard_own_evidence(evidence_urls, reporter.id)
    if type not in INCIDENT_TYPES:
        raise herr(400, "Неизвестный тип инцидента", "Билдәһеҙ хәл төрө")
    if not session.get(User, respondent_id):
        # Тот же текст, что у прочих 400 ниже: различимая 404 давала бы перебор живых user_id.
        raise herr(400, "Не удалось создать обращение — проверь данные", "Мөрәжәғәт булдырып булманы — мәғлүмәтте тикшер")
    if rate_limit and incidents_last_hour(session, reporter.id) >= settings.safety_incidents_per_hour:
        raise herr(429, "Слишком много обращений за час. Попробуй позже.", "Бер сәғәттә мөрәжәғәт артыҡ күп. Һуңыраҡ ҡабатла.")
    # Анти-харассмент: обычная жалоба привязывается к ОБЩЕЙ сущности (поездка/заказ/доставка) —
    # иначе можно завалить инцидентами любого, с кем не пересекался. Только SEVERE допускается
    # без привязки (важен сигнал). Участие сторон в заказе/доставке проверяет вызывающий роутер
    # (там уже есть доступ к объекту и его правилам приватности).
    has_context = booking_id is not None or parcel_id is not None or order_id is not None
    if not has_context and type not in SEVERE_TYPES:
        raise herr(400, "Жалоба привязывается к вашей совместной поездке или доставке", "Ялыу бергә барған сәфәргә йәки илтеүгә бәйләнә")
    if booking_id is not None:
        booking, ride = booking_and_ride_for_user(session, booking_id, reporter)  # 403/404 если не участник
        if not reporter_role:
            reporter_role = "driver" if ride.driver_id == reporter.id else "passenger"
        if respondent_id not in (ride.driver_id, booking.passenger_id):
            raise herr(400, "Обвинённый не участвует в этой поездке", "Ғәйепләнеүсе был сәфәрҙә ҡатнашмай")

    severe = type in SEVERE_TYPES
    inc = Incident(
        booking_id=booking_id, parcel_id=parcel_id, order_id=order_id,
        reporter_id=reporter.id, respondent_id=respondent_id,
        type=type, reporter_role=reporter_role, description=clamp(description, 2000),
        evidence_urls=csv_from_urls(evidence_urls),   # владение проверено guard_own_evidence выше
        # severe → сразу на разбор человеком; иначе ждём объяснения обвинённого.
        status="under_review" if severe else "awaiting_response",
    )
    session.add(inc)
    session.commit()
    session.refresh(inc)
    # Текст спора читает вторая сторона — это такое же открытое поле, как отзыв, и оно
    # не проверялось. Спор и так место напряжённое; мат в нём мешает разобраться по сути.
    moderate_open_text(inc.description, reporter.id, place="incident", ref_id=inc.id, session=session)

    # Пуш обвинённому: приглашение объясниться (право на защиту). Исключение — SEVERE без общей
    # поездки: связь сторон не доказана, сначала жалобу видит человек (админ). Иначе это канал
    # харассмента: пуш «открыт спор» любому произвольному user_id, до 240/сутки с одного аккаунта.
    if not (severe and not has_context):
        # Именно push_notification, а не голый пуш: право на защиту не должно зависеть от того,
        # дошёл ли пуш. Телефон был выключен — человек молчит «сам», и разбор уходит к админу
        # без его версии (аудит 2026-08-08, волна 19). Запись в Центре уведомлений остаётся
        # и ведёт прямо в карточку разбора.
        push_notification(
            session, respondent_id, "safety",
            "Открыт разбор", "Тикшереү асылды",
            "По одной из поездок или доставок открыт спор. Опиши свою версию — это важно.",
            "Сәфәрҙәрҙең йәки ебәреүҙәрҙең береһе буйынса бәхәс асылды. Үҙ версияңды яҙ — был мөһим.",
            ref_kind="incident", ref_id=inc.id,
        )
    if severe:
        reporter_u = session.get(User, reporter.id)
        respondent_u = session.get(User, respondent_id)
        msg = (
            f"🛡️ SEVERE-инцидент (Юлдаш)\nТип: {type}\n"
            f"Заявитель: {_name(reporter_u)} ({reporter_u.phone if reporter_u else '—'})\n"
            f"Обвинён: {_name(respondent_u)} ({respondent_u.phone if respondent_u else '—'})\n"
            f"Поездка: {_route_for(session, booking_id) or '—'}\n"
            f"Суть: {clamp(description, 300) or '—'}"
        )
        if background is not None:
            background.add_task(notify_admin_telegram, msg)
        else:
            notify_admin_telegram(msg)
    return inc


# ----------------------------- Инциденты: участники -----------------------------
@router.post("/incidents", response_model=IncidentOut)
def file_incident(body: IncidentIn, background: BackgroundTasks,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    ensure_active(session, user.id)   # приостановленный аккаунт не подаёт новые жалобы (анти-абуз)
    reporter_role = ""
    if body.order_id is not None:
        # Участие сторон в такси-заказе проверяем ЗДЕСЬ: create_incident этого не знает
        # (у него нет правил приватности заказа), а без проверки спор стал бы каналом
        # харассмента — можно было бы «привязаться» к чужой поездке.
        order = session.get(InstantOrder, body.order_id)
        if not order or user.id not in (order.passenger_id, order.driver_id):
            raise herr(403, "Это не твоя поездка", "Был һинең сәфәрең түгел")
        if body.respondent_id not in (order.passenger_id, order.driver_id):
            raise herr(400, "Обвинённый не участвует в этой поездке", "Ғәйепләнеүсе был сәфәрҙә ҡатнашмай")
        reporter_role = "driver" if order.driver_id == user.id else "passenger"
    inc = create_incident(
        session, reporter=user, respondent_id=body.respondent_id, type=body.type,
        description=body.description, booking_id=body.booking_id, order_id=body.order_id,
        reporter_role=reporter_role, background=background,
        evidence_urls=body.evidence_urls,
    )
    return _incident_out(session, inc, user)


@router.get("/incidents/mine", response_model=List[IncidentOut])
def my_incidents(user: User = Depends(current_user), session: Session = Depends(get_session)):
    rows = session.exec(
        select(Incident)
        .where((Incident.reporter_id == user.id) | (Incident.respondent_id == user.id))
        .order_by(Incident.id.desc())
    ).all()
    return [_incident_out(session, inc, user) for inc in rows]


@router.get("/incidents/{incident_id}", response_model=IncidentOut)
def get_incident(incident_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise herr(404, "Спор не найден", "Бәхәс табылманы")
    if user.id not in (inc.reporter_id, inc.respondent_id) and user.role != UserRole.admin:
        raise herr(403, "Нет доступа к этому спору", "Был бәхәскә инеү юҡ")
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/respond", response_model=IncidentOut)
def respond_incident(incident_id: int, body: RespondIn,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise herr(404, "Спор не найден", "Бәхәс табылманы")
    if user.id != inc.respondent_id:
        raise herr(403, "Объясниться может только вторая сторона", "Аңлатманы тик икенсе яҡ бирә ала")
    if inc.status in ("resolved", "closed"):
        raise herr(409, "Спор уже закрыт", "Бәхәс ябылған инде")
    inc.respondent_statement = clamp(body.statement, 2000)
    # Две проверки из разных веток, обе обязательны: текст объяснения проходит модерацию
    # (мат и оскорбления в споре), а приложенные фото — проверку владения (чужую улику
    # подложить нельзя).
    moderate_open_text(inc.respondent_statement, user.id, place="incident_reply", ref_id=inc.id, session=session)
    if body.evidence_urls is not None:   # право на защиту — с фото (только СВОИ)
        # `already`: обвинённый может дополнять свой список, ранее приложенное остаётся своим.
        guard_own_evidence(body.evidence_urls, user.id, already=inc.respondent_evidence_urls)
        inc.respondent_evidence_urls = csv_from_urls(body.evidence_urls)
    inc.responded_at = utcnow()
    inc.status = "under_review"
    inc.updated_at = utcnow()
    session.add(inc)
    session.commit()
    session.refresh(inc)
    if inc.reporter_id is not None:   # заявитель мог удалить аккаунт — спор жив, писать некому
        push_notification(
            session, inc.reporter_id, "safety",
            "Ответ по спору", "Бәхәс буйынса яуап",
            "Вторая сторона описала свою версию.", "Икенсе яҡ үҙ версияһын яҙҙы.",
            ref_kind="incident", ref_id=inc.id,
        )
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/appeal", response_model=IncidentOut)
def appeal_incident(incident_id: int, body: AppealIn, background: BackgroundTasks,
                    user: User = Depends(current_user), session: Session = Depends(get_session)):
    inc = session.get(Incident, incident_id)
    if not inc:
        raise herr(404, "Спор не найден", "Бәхәс табылманы")
    if user.id not in (inc.reporter_id, inc.respondent_id):
        raise herr(403, "Обжаловать может только участник спора", "Тик бәхәс ҡатнашыусыһы ялыу бирә ала")
    # Апелляция — только на ВЫНЕСЕННОЕ решение и только один раз. Без гейтов: «обжаловать» можно
    # было открытый/закрытый спор (перетирая статус), а повторные апелляции спамили админ-канал
    # в обход часового лимита подачи и держали спор вечно «активным».
    if inc.status != "resolved":
        raise herr(409, "Обжаловать можно только решённый спор", "Тик хәл ителгән бәхәскә ялыу бирелә")
    if inc.appeal_status:
        raise herr(409, "Апелляция по этому спору уже подана", "Был бәхәс буйынса ялыу бирелгән инде")
    inc.appeal_text = clamp(body.text, 2000)
    inc.appeal_status = "requested"
    inc.status = "appealed"
    inc.updated_at = utcnow()
    session.add(inc)
    session.commit()
    session.refresh(inc)
    background.add_task(
        notify_admin_telegram,
        f"⚖️ Апелляция по спору #{inc.id} (Юлдаш). Тип: {inc.type}. Нужен разбор человеком.",
    )
    return _incident_out(session, inc, user)


@router.post("/incidents/{incident_id}/withdraw", response_model=IncidentOut)
def withdraw_incident(incident_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Мы решили миром» — заявитель закрывает спор без последствий (мир по умолчанию)."""
    inc = session.get(Incident, incident_id)
    if not inc:
        raise herr(404, "Спор не найден", "Бәхәс табылманы")
    if user.id != inc.reporter_id:
        raise herr(403, "Закрыть спор миром может только заявитель", "Бәхәсте тыныслыҡ менән тик ялыу биреүсе яба ала")
    if inc.status == "closed":
        return _incident_out(session, inc, user)
    # Мир — только ДО вердикта. После решения админа withdraw заявителя перетирал бы вердикт
    # (resolved-неявка выпадала из «Надёжности» и счёта эскалации — давление на заявителя
    # обнуляло наказание, при этом страйк в профиле оставался — рассинхрон).
    if inc.status not in ("open", "awaiting_response", "under_review"):
        raise herr(409, "Спор уже решён — оспорить можно апелляцией", "Бәхәс хәл ителгән — ялыу аша ғына ҡаршы сығып була")
    inc.resolution = "mutual_resolved"
    inc.fault = "none"
    inc.status = "closed"
    inc.resolved_at = utcnow()
    inc.updated_at = inc.resolved_at
    session.add(inc)
    session.commit()
    session.refresh(inc)
    for uid in (inc.reporter_id, inc.respondent_id):
        if uid is None:          # сторона удалила аккаунт — писать некому
            continue
        push_notification(
            session, uid, "safety",
            "Спор закрыт миром", "Бәхәс тыныслыҡ менән ябылды",
            "Спасибо, что договорились по-соседски 🤝",
            "Күршеләрсә килешкәнегеҙ өсөн рәхмәт 🤝",
            ref_kind="incident", ref_id=inc.id,
        )
    return _incident_out(session, inc, user)


# ----------------------------- Фото-доказательства: приватная выдача -----------------------------
def _can_view_evidence(session: Session, user_id: int, name: str) -> bool:
    """Файл виден только СТОРОНАМ спора, к которому он приложен (или админу — проверка снаружи).
    Ищем инцидент, где юзер — участник И имя файла встречается в одном из CSV доказательств."""
    row = session.exec(select(Incident.id).where(
        or_(Incident.reporter_id == user_id, Incident.respondent_id == user_id),
        or_(Incident.evidence_urls.contains(name), Incident.respondent_evidence_urls.contains(name)),
    )).first()
    return row is not None


@router.get("/secure/evidence/{name}")
def secure_evidence(name: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отдать фото-доказательство спора. Доступ: админ ИЛИ участник спора с этим файлом.
    На фото лица/номера/травмы — публичной раздачи нет по построению (область private/evidence).
    Локально отдаём файл; в S3-режиме после проверки доступа редиректим на подписанный URL."""
    safe = os.path.basename(name)   # защита от path traversal
    if user.role != UserRole.admin and not _can_view_evidence(session, user.id, safe):
        raise herr(403, "Нет доступа к файлу", "Файлға инеү юҡ")
    storage = get_storage()
    if not storage.exists(f"evidence/{safe}"):
        raise herr(404, "Файл не найден", "Файл табылманы")
    if storage.is_remote:
        return RedirectResponse(storage.url(f"evidence/{safe}"))
    return FileResponse(os.path.join(EVIDENCE_DIR, safe))


# ----------------------------- Инциденты: админ -----------------------------
@router.get("/admin/incidents", response_model=List[AdminIncidentOut])
def admin_incidents(status: Optional[str] = None, user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    q = select(Incident).order_by(Incident.id.desc())
    if status:
        q = q.where(Incident.status == status)
    rows = session.exec(q.limit(300)).all()
    ids: set = set()
    for r in rows:
        # None = сторона удалила аккаунт (спор обезличен, но жив) — в выборку юзеров не берём.
        ids.update(i for i in (r.reporter_id, r.respondent_id) if i is not None)
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()} if ids else {}
    out: List[AdminIncidentOut] = []
    for inc in rows:
        rep = users.get(inc.reporter_id) if inc.reporter_id is not None else None
        resp = users.get(inc.respondent_id) if inc.respondent_id is not None else None
        out.append(AdminIncidentOut(
            id=inc.id, booking_id=inc.booking_id, type=inc.type, severe=inc.type in SEVERE_TYPES,
            status=inc.status, reporter_role=inc.reporter_role,
            reporter_id=inc.reporter_id,
            reporter_name=(_name(rep) if inc.reporter_id is not None else _GONE_NAME),
            reporter_phone=(rep.phone if rep else ""),
            respondent_id=inc.respondent_id,
            respondent_name=(_name(resp) if inc.respondent_id is not None else _GONE_NAME),
            respondent_phone=(resp.phone if resp else ""),
            description=inc.description, respondent_statement=inc.respondent_statement,
            responded_at=inc.responded_at, resolution=inc.resolution, fault=inc.fault,
            resolution_note=inc.resolution_note, compensation_kop=inc.compensation_kop,
            appeal_text=inc.appeal_text, appeal_status=inc.appeal_status,
            created_at=inc.created_at, updated_at=inc.updated_at, resolved_at=inc.resolved_at,
            evidence_urls=urls_from_csv(inc.evidence_urls),
            respondent_evidence_urls=urls_from_csv(inc.respondent_evidence_urls),
            booking_route=_context_route(session, inc),
        ))
    return out


@router.post("/admin/incidents/{incident_id}/resolve", response_model=IncidentOut)
def resolve_incident(incident_id: int, body: ResolveIn,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    _require_admin(user)
    inc = session.get(Incident, incident_id)
    if not inc:
        raise herr(404, "Спор не найден", "Бәхәс табылманы")
    # Идемпотентность: уже решённый спор повторно не «дорешать» (двойной тап/ретрай иначе добавил бы страйк дважды).
    if inc.status in ("resolved", "closed"):
        raise herr(409, "Спор уже решён", "Бәхәс хәл ителгән инде")
    if body.resolution and body.resolution not in (
        "none", "warning", "strike", "suspend", "ban", "dismissed", "mutual_resolved",
    ):
        raise herr(422, "Неизвестное решение по спору", "Бәхәс буйынса билдәһеҙ ҡарар")
    if body.fault and body.fault not in ("none", "respondent", "reporter", "both", "unclear"):
        raise herr(422, "Неизвестная сторона вины", "Ғәйеп яғы билдәһеҙ")
    # Футган: карательные побочки ложатся ТОЛЬКО на обвинённого. «Виноват заявитель» + strike
    # наказал бы невиновного. Наказание лживого заявителя — встречным спором, где он respondent.
    if body.fault == "reporter" and (body.strike or body.resolution in ("warning", "strike", "suspend", "ban")):
        raise HTTPException(422, "Вина на заявителе: наказание легло бы на обвинённого — заведи встречный спор")
    inc, prof = apply_incident_resolution(
        session, inc, resolution=body.resolution, fault=body.fault, note=body.note,
        compensation_kop=body.compensation_kop, strike=body.strike, suspend_days=body.suspend_days,
        exclude_rating=body.exclude_rating, shield=body.shield, resolver_id=user.id,
    )
    # Прозрачность: обе стороны получают решение с человеческим объяснением.
    #
    # Голым пушем это слать нельзя (аудит 2026-08-08, волна 19). Проверено пробой: человека
    # отстранили на 7 дней — в Центре уведомлений у него НОЛЬ записей, а пуш ушёл только
    # по-русски, хотя в профиле выбран башкирский. Пуш не дошёл (ночь, выключенный телефон) —
    # и человек не знает ни за что его наказали, ни на какой срок, ни куда идти спорить.
    note = inc.resolution_note or "Решение принято."
    for uid in (inc.reporter_id, inc.respondent_id):
        if uid is None:          # сторона удалила аккаунт — писать некому
            continue
        push_notification(
            session, uid, "safety",
            "Решение по спору", "Бәхәс буйынса ҡарар",
            # Заметку админа не переводим — это его живые слова о конкретном разборе.
            # Двуязычна рамка: заголовок и, при паузе, срок с подсказкой ниже.
            note, note,
            ref_kind="incident", ref_id=inc.id,
        )
    # Отстранение — отдельным письмом обвинённому: срок и что делать дальше. Без даты
    # «пауза» превращается в «забанили навсегда» на ощущениях, а это чаще всего неправда.
    if inc.respondent_id and prof is not None and prof.suspended_until:
        until = prof.suspended_until.strftime("%d.%m.%Y")
        push_notification(
            session, inc.respondent_id, "safety",
            "Аккаунт на паузе", "Иҫәп паузала",
            f"Пауза до {until}. После неё всё вернётся само. "
            "Не согласен — открой разбор и подай апелляцию.",
            f"{until} тиклем пауза. Унан һуң бөтәһе лә үҙе ҡайта. "
            "Риза түгелһең — тикшереүҙе асып, апелляция бир.",
            ref_kind="incident", ref_id=inc.id,
        )
    return _incident_out(session, inc, user)


# ----------------------------- Standing / политика -----------------------------
class StandingOut(BaseModel):
    standing: str
    strikes: int
    warnings: int
    reliability: int
    suspended_until: Optional[datetime]
    suspend_reason: str
    rating_shield: bool
    active_incidents: int
    can_act: bool


@router.get("/me/standing", response_model=StandingOut)
def my_standing(user: User = Depends(current_user), session: Session = Depends(get_session)):
    prof = refresh_standing(session, user.id)
    return StandingOut(
        standing=prof.standing, strikes=prof.strikes, warnings=prof.warnings,
        reliability=reliability_for(session, user.id),
        suspended_until=prof.suspended_until, suspend_reason=prof.suspend_reason,
        rating_shield=prof.rating_shield,
        active_incidents=active_incidents_count(session, user.id),
        can_act=not is_suspended(prof),
    )


class TrustOut(BaseModel):
    rating: float
    rating_count: int
    trips: int
    verified: bool
    reliability: int
    member_since: Optional[datetime]


@router.get("/users/{user_id}/trust", response_model=TrustOut)
def user_trust(user_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Публичный снимок доверия (карточка водителя/пассажира): рейтинг, поездки, Надёжность,
    «проверен», с нами с… Без телефона и приватного — только витрина."""
    target = session.get(User, user_id)
    if not target:
        raise herr(404, "Пользователь не найден", "Ҡулланыусы табылманы")
    avg, cnt = user_rating(session, user_id)
    return TrustOut(
        rating=round(avg, 1) if cnt > 0 else 0.0, rating_count=cnt,
        trips=completed_trips_for(session, user_id), verified=bool(target.verified),
        reliability=reliability_for(session, user_id), member_since=target.created_at,
    )


@router.get("/safety/policy")
def safety_policy():
    """Пороги лестницы «Справедливость» — клиент показывает их из сервера, не хардкодит."""
    return {
        "strikes_to_limit": settings.safety_strikes_to_limit,
        "strikes_to_suspend": settings.safety_strikes_to_suspend,
        "suspend_1_days": settings.safety_suspend_1_days,
        "suspend_2_days": settings.safety_suspend_2_days,
        "suspend_3_days": settings.safety_suspend_3_days,
        "strike_decay_days": settings.safety_strike_decay_days,
    }
