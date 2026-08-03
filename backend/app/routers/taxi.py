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
from ..errors import herr
from ..models import (
    Booking, DriverProfile, InstantOrder, InstantOrderStatus as S, PreTripCheck, TaxiApplication,
    TaxiApplicationStatus, TaxiCity, User, UserRole,
)
from ..security import current_user
from ..services import send_push
from ..timeutil import utcnow
from .. import antifraud as af_mod
from .. import geo as geo_mod
from .. import instant_service as isv
from .. import pretrip as pretrip_mod
from .. import taxi as taxi_mod
from .drivers import _ensure_owned_doc_url

router = APIRouter(tags=["taxi"])

MIN_AGE_YEARS = 20        # возраст 20+ (бизнес-правило, юрист подтвердит минимум)
MIN_LICENSE_YEARS = 3     # стаж от 3 лет (580-ФЗ; было 2 — расхождение с законом, аудит 2026-07-26)


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
    # Точная дата выдачи прав — опционально (старые клиенты шлют только год). Если пришла,
    # стаж считаем ПО ДАТАМ: год в одиночку давал допуск при реальном стаже 2 года и 1 день.
    license_since_date: Optional[date] = None
    permit_photo_url: str = Field("", max_length=500)
    osago_url: str = Field("", max_length=500)
    # Проверки водителя, Уровень 1: селфи с правами в руках (сверка лица) + справка о несудимости (опц.).
    selfie_url: str = Field("", max_length=500)
    criminal_record_url: str = Field("", max_length=500)
    # Сроки документов (580-ФЗ, аудит 2026-07-26). Необязательные НАМЕРЕННО: поля появились
    # позже живого приложения, и жёсткое требование выбило бы 422 всем, кто ещё не обновился.
    # Кто их не заполнил — получает напоминания от app/doc_check.py, а модератор видит пропуск
    # в очереди заявок (`docs_missing`) и может не одобрять до заполнения.
    osago_until: Optional[date] = None          # до какого числа действует ОСАГО
    permit_until: Optional[date] = None         # срок разрешения на такси
    inspection_until: Optional[date] = None     # диагностическая карта (техосмотр)
    # Класс машины (§6): водитель ЗАЯВЛЯЕТ в онбординге, админ подтверждает/меняет при approve.
    car_class: Literal["economy", "comfort"] = "economy"


class TaxiDocsIn(BaseModel):
    """Обновление сроков и фото документов БЕЗ пере-подачи заявки.

    Нужно уже одобренным: ОСАГО кончается каждый год, и заставлять человека заново проходить
    модерацию из-за нового полиса — значит гарантированно оставить его без работы на пару дней.
    Присланные поля перезаписываются, пропущенные остаются как были (частичное обновление).
    """
    osago_until: Optional[date] = None
    permit_until: Optional[date] = None
    inspection_until: Optional[date] = None
    osago_url: Optional[str] = Field(None, max_length=500)
    permit_photo_url: Optional[str] = Field(None, max_length=500)


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


def license_start_date(license_since_date: Optional[date], license_since_year: int) -> date:
    """С какой даты считаем стаж вождения.

    Есть точная дата выдачи прав → берём её. Пришёл только год (старые клиенты) → берём
    31 ДЕКАБРЯ этого года: внутри года дата неизвестна, и ошибиться нужно в пользу
    безопасности пассажира, а не в пользу допуска. Раньше стаж считался вычитанием годов
    (`today.year - year`), и права от 31.12.2023 проходили 01.01.2026 при реальном стаже
    2 года и 1 день — прямое нарушение требования к перевозчику (аудит 2026-08-03)."""
    if license_since_date is not None:
        return license_since_date
    return date(int(license_since_year), 12, 31)


def _validate_apply(body: TaxiApplyIn) -> None:
    """Валидация требований 580-ФЗ/бизнес-правил. Ошибки — понятной русской строкой."""
    inn = body.inn.strip()
    if not (inn.isdigit() and 10 <= len(inn) <= 12):
        raise herr(400, "ИНН должен состоять из 10–12 цифр", "ИНН 10–12 һандан торорға тейеш")
    today = utcnow().date()
    if _full_years_since(body.birth_date, today) < MIN_AGE_YEARS:
        raise herr(400, f"Возить такси можно с {MIN_AGE_YEARS} лет", f"Такси йөрөтөргә {MIN_AGE_YEARS} йәштән мөмкин")
    if body.license_since_year > today.year:
        raise herr(400, "Год получения прав не может быть в будущем", "Права алған йыл киләсәктә була алмай")
    if body.license_since_date is not None and body.license_since_date > today:
        raise herr(400, "Дата получения прав не может быть в будущем",
                   "Права алған дата киләсәктә була алмай")
    # Стаж считаем по ДАТАМ, а не вычитанием годов (см. license_start_date).
    since = license_start_date(body.license_since_date, body.license_since_year)
    if _full_years_since(since, today) < MIN_LICENSE_YEARS:
        raise herr(400, f"Нужен стаж вождения от {MIN_LICENSE_YEARS} лет", f"Руль артында {MIN_LICENSE_YEARS} йыл стаж кәрәк")
    # Сроки документов: если указаны — только в будущем. Просроченный документ в момент подачи
    # это не «почти готов», это отказ; лучше сказать сразу, чем одобрить и снять допуск назавтра.
    _validate_doc_dates(body.osago_until, body.permit_until, body.inspection_until)


def _validate_doc_dates(osago: Optional[date], permit: Optional[date], inspection: Optional[date]) -> None:
    """Общая проверка сроков (подача заявки и обновление документов — одно правило)."""
    today = utcnow().date()
    for value, ru, ba in (
        (osago, "ОСАГО", "ОСАГО"),
        (permit, "разрешения на такси", "такси рөхсәтенең"),
        (inspection, "диагностической карты", "диагностика картаһының"),
    ):
        if value is None:
            continue
        if value < today:
            raise herr(400, f"Срок {ru} уже истёк — обнови документ и укажи новую дату",
                       f"{ba} ваҡыты үткән — документты яңыртып, яңы датаны күрһәт")
        if value.year > today.year + 20:
            raise herr(400, "Проверь дату — она слишком далеко в будущем",
                       "Датаны тикшер — ул артыҡ алыҫ киләсәктә")


def _doc_dates(app: TaxiApplication) -> dict:
    """Сроки документов + производные флаги для экрана (клиент не считает даты сам)."""
    today = utcnow().date()
    dates = {
        "osago_until": getattr(app, "osago_until", None),
        "permit_until": getattr(app, "permit_until", None),
        "inspection_until": getattr(app, "inspection_until", None),
    }
    filled = [d for d in dates.values() if d is not None]
    soonest = min(filled) if filled else None
    return {
        **{k: (v.isoformat() if v else None) for k, v in dates.items()},
        "docs_expired": bool(getattr(app, "docs_expired", False)),
        # Пропущенные сроки: модератору — сигнал «не одобряй вслепую», водителю — что дозаполнить.
        "docs_missing": [k for k, v in dates.items() if v is None],
        # Сколько дней до ближайшего истечения (None = сроков нет; отрицательное = просрочен).
        "docs_days_left": ((soonest - today).days if soonest else None),
    }


def _application_payload(app: TaxiApplication) -> dict:
    return {
        **_doc_dates(app),
        "id": app.id,
        "status": app.status.value,
        "inn": app.inn,
        "permit_number": app.permit_number,
        "permit_photo_url": app.permit_photo_url or "",
        "osago_url": app.osago_url or "",
        "selfie_url": app.selfie_url or "",
        "criminal_record_url": app.criminal_record_url or "",
        "birth_date": app.birth_date.isoformat(),
        "license_since_year": app.license_since_year,
        # Точная дата выдачи прав — модератору видно, по чему считался стаж (None = только год).
        "license_since_date": (app.license_since_date.isoformat()
                               if getattr(app, "license_since_date", None) else None),
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
        raise herr(409, "Заявка уже одобрена — ты в такси Юлдаша", "Заявка раҫланған — һин Юлдаш таксиһында")
    # Фото — только СВОИ загруженные защищённые документы (анти-подмена чужих URL).
    permit_url = _ensure_owned_doc_url(body.permit_photo_url, user, None) if body.permit_photo_url.strip() else None
    osago_url = _ensure_owned_doc_url(body.osago_url, user, None) if body.osago_url.strip() else None
    selfie_url = _ensure_owned_doc_url(body.selfie_url, user, None) if body.selfie_url.strip() else None
    criminal_url = _ensure_owned_doc_url(body.criminal_record_url, user, None) if body.criminal_record_url.strip() else None
    if app is None:
        app = TaxiApplication(user_id=user.id)
    app.inn = body.inn.strip()
    app.permit_number = body.permit_number.strip()
    app.permit_photo_url = permit_url
    app.osago_url = osago_url
    app.selfie_url = selfie_url
    app.criminal_record_url = criminal_url
    app.birth_date = body.birth_date
    app.license_since_date = body.license_since_date
    # Год держим в согласии с датой: пришла точная дата — год берём из неё (иначе в карточке
    # модератора год и дата могли бы противоречить друг другу).
    app.license_since_year = (body.license_since_date.year if body.license_since_date
                              else body.license_since_year)
    app.osago_until = body.osago_until
    app.permit_until = body.permit_until
    app.inspection_until = body.inspection_until
    app.docs_expired = False        # свежая заявка с проверенными датами — допуск не снят
    app.docs_warned_at = None
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


@router.post("/taxi/documents")
def update_taxi_documents(body: TaxiDocsIn, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Обновить сроки (и фото) документов, НЕ пере-подавая заявку.

    Раньше выхода не было вообще: продлил ОСАГО — а сказать об этом системе нечем, кроме
    повторной подачи заявки, которая сбрасывает статус в pending и оставляет человека без
    работы до следующей модерации. Это наказание за законопослушность (аудит 2026-07-26).

    Допуск возвращается СРАЗУ, как только все заполненные даты снова в будущем: ждать ночного
    прогона app/doc_check.py, чтобы поехать, водитель не должен. Обратное (снятие допуска)
    делает только фоновая задача — чтобы случайная опечатка не выбила человека с линии мгновенно.
    """
    app = taxi_mod.my_application(session, user.id)
    if not app:
        raise HTTPException(404, "Заявка не подана")
    _validate_doc_dates(body.osago_until, body.permit_until, body.inspection_until)
    if body.osago_until is not None:
        app.osago_until = body.osago_until
    if body.permit_until is not None:
        app.permit_until = body.permit_until
    if body.inspection_until is not None:
        app.inspection_until = body.inspection_until
    # Фото — только СВОИ загруженные защищённые документы (анти-подмена чужих URL).
    if body.osago_url is not None and body.osago_url.strip():
        app.osago_url = _ensure_owned_doc_url(body.osago_url, user, None)
    if body.permit_photo_url is not None and body.permit_photo_url.strip():
        app.permit_photo_url = _ensure_owned_doc_url(body.permit_photo_url, user, None)
    today = utcnow().date()
    dates = [d for d in (app.osago_until, app.permit_until, app.inspection_until) if d is not None]
    if app.docs_expired and dates and all(d >= today for d in dates):
        app.docs_expired = False
        app.docs_warned_at = None
    session.add(app)
    session.commit()
    session.refresh(app)
    return _application_payload(app)


# ------------------------------ предрейсовое подтверждение (580-ФЗ) ------------------------------
class PreTripIn(BaseModel):
    """Три пункта готовности. Все обязательны — «частично готов» это не готов."""
    health_ok: bool = False
    car_ok: bool = False
    no_alcohol: bool = False
    note: str = Field("", max_length=300)


@router.get("/taxi/pretrip")
def get_pretrip(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Подтвердил ли водитель готовность на сегодня (для экрана «Выйти на линию»)."""
    return pretrip_mod.payload(session, user.id)


@router.post("/taxi/pretrip")
def confirm_pretrip(body: PreTripIn, user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    """Подтвердить готовность на сегодня. Запись остаётся — это след, а не тумблер.

    Мы честно называем это самодекларацией, а не медосмотром: медцентра у платформы нет.
    Но осознанное действие работает и без врача, а при разборе ДТП видно, что человек заявил.
    """
    pretrip_mod.confirm(session, user.id, body.health_ok, body.car_ok, body.no_alcohol, body.note)
    return pretrip_mod.payload(session, user.id)


# ------------------------------ админ: заявки ------------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


@router.get("/admin/taxi/pretrip")
def admin_pretrip_journal(day: Optional[date] = None, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Журнал предрейсовых подтверждений за день (по умолчанию — сегодня).

    Смысл записи: при разборе ДТП или проверки видно, что водитель заявил в этот день.
    Отдаём только факт и заметку — никаких координат и телефонов пассажиров тут нет.
    """
    _require_admin(user)
    target = day or pretrip_mod.local_day()
    rows = session.exec(
        select(PreTripCheck).where(PreTripCheck.day == target).order_by(PreTripCheck.created_at.desc())
    ).all()
    if not rows:
        return {"day": target.isoformat(), "items": []}
    ids = {r.driver_id for r in rows}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    return {
        "day": target.isoformat(),
        "items": [
            {
                "driver_id": r.driver_id,
                "name": ((users.get(r.driver_id).name if users.get(r.driver_id) else "") or "Водитель"),
                "phone": ((users.get(r.driver_id).phone if users.get(r.driver_id) else "") or ""),
                "confirmed_at": r.created_at.isoformat(),
                "note": r.note or "",
            }
            for r in rows
        ],
    }


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
    # «Кто пригласил» (доверие «между своими»): имя пригласившего по User.referred_by.
    ref_ids = {u.referred_by for u in users.values() if u.referred_by}
    referrers = {u.id: u for u in session.exec(select(User).where(User.id.in_(ref_ids))).all()} if ref_ids else {}
    out = []
    for a in apps:
        u = users.get(a.user_id)
        p = profs.get(a.user_id)
        ref = referrers.get(u.referred_by) if (u and u.referred_by) else None
        out.append({
            **_application_payload(a),
            "user_id": a.user_id,
            "name": (u.name if u and u.name else "Водитель"),
            "phone": (u.phone if u else ""),
            "car_class": ((p.car_class if p and p.car_class else "economy")),  # заявленный класс (§6)
            "invited_by": (ref.name if ref and ref.name else None),   # кто пригласил (доверие между своими)
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
    # B8-8: «увод мимо приложения» за день — отмены ПОСЛЕ открытия телефона/чата
    # (такси + попутка). Паттерн виден админу, наказывает только человек.
    ctc_bookings = len(session.exec(
        select(Booking.id).where(
            Booking.contact_then_cancel == True,  # noqa: E712
            Booking.cancelled_at >= day_start,
        )
    ).all())
    ctc_today = sum(1 for o in cancelled_rows if o.contact_then_cancel) + ctc_bookings
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
        # Анти-фрод (B8-8): отмены после открытия телефона/чата за день (такси + попутка).
        "contact_then_cancel_today": ctc_today,
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
