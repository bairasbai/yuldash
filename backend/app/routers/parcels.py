"""M3 — доставка посылок между сёлами/городами (реальная боль села).

Философия (красные линии):
- Попутка ЛЮДЕЙ бесплатна; сбор берём ТОЛЬКО за вещь-доставку — символический и видимый,
  за реальную услугу «свели отправителя и попутного курьера» (fee_kop из PARCEL_FEES по размеру,
  фиксируется при создании заявки).
- Приватность: телефон получателя виден курьеру ТОЛЬКО после того, как он ПРИНЯЛ посылку.
  До принятия скрыт из /available. Телефоны не логируем (в уведомлениях/логах — без phone).
- Безопасность: отправитель обязан принять правила «не возим запрещённое» (rules_accepted=True)
  при создании — мы логистика между своими, не перевозчик запрещёнки.
- Все пользовательские 4xx — двуязычные через herr(status, ru, ba).

Устройство повторяет стиль этой волны (coupons.py/promo.py): herr, идемпотентность создания,
закрытый IDOR (чужая заявка → 404), короткий код вручения через _gen_code (как в coupons),
admin через _require_admin (обычный HTTPException-строка), уведомления best-effort try/except.

Флоу: отправитель POST /parcels (получает confirm_code, передаёт получателю вне приложения) →
курьер видит открытые заявки в /available (БЕЗ телефона) → POST /parcels/{id}/accept (теперь
видит телефон) → двигает статус accepted→in_transit→delivered; для delivered вводит code, который
получатель называет при передаче → status=delivered. Сбор fee_kop по delivered — доход платформы.
"""
import secrets
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
from ..models import ParcelDelivery, Report, User, UserRole
from ..security import current_user
from ..services import notify_admin_telegram, send_push
from ..timeutil import utcnow

router = APIRouter(tags=["parcels"])

# Символический сервисный сбор платформы за посредничество (свели отправителя и попутного курьера).
# В копейках: 30 / 60 / 120 ₽ — стартовая гипотеза по размеру посылки. Правится ЗДЕСЬ, без пересборки
# клиента (тариф продукта, не .env). Сбор фиксируется в заявке при создании (fee_kop) и не меняется.
PARCEL_FEES = {"small": 3000, "medium": 6000, "large": 12000}

# Алфавит кода вручения — без похожих символов (0/O, 1/I), чтобы диктовать/вводить без ошибок.
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_CODE_LEN = 6

# Статусы движения посылки у курьера (accepted → in_transit → delivered).
_COURIER_MOVE = ("in_transit", "delivered")
# Заявка «в работе» у курьера (для /carrying): принята, но ещё не завершена/не отменена.
_CARRYING_STATUSES = ("accepted", "in_transit")


# ---------- Тела запросов ----------

class ParcelIn(BaseModel):
    from_city: str = Field("", max_length=80)
    to_city: str = Field("", max_length=80)
    size: str = Field("small", max_length=16)
    description: str = Field("", max_length=2000)
    receiver_name: str = Field("", max_length=120)
    receiver_phone: str = Field("", max_length=40)
    rules_accepted: bool = False
    from_lat: Optional[float] = None
    from_lng: Optional[float] = None
    to_lat: Optional[float] = None
    to_lng: Optional[float] = None


class ParcelStatusIn(BaseModel):
    status: str = Field("", max_length=16)
    code: str = Field("", max_length=12)   # обязателен только для перехода в delivered


def _gen_code(session: Session) -> str:
    """Уникальный короткий код вручения (проверка коллизии по БД). Как _gen_code в coupons.py."""
    for _ in range(20):
        code = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN))
        exists = session.exec(select(ParcelDelivery.id).where(ParcelDelivery.confirm_code == code)).first()
        if not exists:
            return code
    # практически недостижимо (32^6 пространство) — на всякий случай удлиняем
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN + 2))


# ---------- Сериализация ----------

def _settlement(p: ParcelDelivery) -> Optional[dict]:
    """C2: «к оплате получателем» для buy_bring — товар (по факту) + доставка = итого.
    None для не-buy_bring (обычная доставка платится отдельно). goods_actual_kop=0 → товар ещё
    не подтверждён курьером (получатель увидит только доставку, итог обновится после ввода)."""
    if (getattr(p, "delivery_type", "poputka") or "poputka") != "buy_bring":
        return None
    goods = getattr(p, "goods_actual_kop", 0) or 0
    delivery = getattr(p, "delivery_price_kop", 0) or 0
    return {
        "goods_actual_kop": goods,      # сколько курьер потратил на товар (0 = ещё не введено)
        "delivery_kop": delivery,       # цена доставки для получателя (без комиссии платформы)
        "total_due_kop": goods + delivery,   # итого к оплате получателем
        "settled": bool(getattr(p, "settled", False)),   # получатель уже рассчитался?
    }


def _parcel_base(p: ParcelDelivery) -> dict:
    """Общие поля заявки БЕЗ приватного телефона и БЕЗ кода вручения."""
    return {
        "id": p.id,
        "sender_id": p.sender_id,
        "courier_id": p.courier_id,
        "from_city": p.from_city,
        "to_city": p.to_city,
        "from_lat": p.from_lat,
        "from_lng": p.from_lng,
        "to_lat": p.to_lat,
        "to_lng": p.to_lng,
        "size": p.size,
        "description": p.description,
        "receiver_name": p.receiver_name,
        "fee_kop": p.fee_kop,
        "status": p.status,
        # C1: тип доставки и поля курьерского режима (аддитивно; старый клиент их игнорирует).
        "delivery_type": getattr(p, "delivery_type", "poputka") or "poputka",
        "urgency": getattr(p, "urgency", "bypath") or "bypath",
        "declared_value_kop": getattr(p, "declared_value_kop", 0) or 0,
        "cod_amount_kop": getattr(p, "cod_amount_kop", 0) or 0,
        "commission_kop": getattr(p, "commission_kop", 0) or 0,
        # Цена доставки (courier/buy_bring): курьер должен видеть СВОЙ заработок, а не наш сбор.
        # Отдаём под ключом price_kop — его читает клиент (ParcelDto.priceKop). Для попутки = 0.
        "price_kop": getattr(p, "delivery_price_kop", 0) or 0,
        # C2: расчёт с получателем (buy_bring) — товар+доставка; None для остальных типов.
        "settlement": _settlement(p),
        "created_at": p.created_at.isoformat() if p.created_at else None,
        "accepted_at": p.accepted_at.isoformat() if p.accepted_at else None,
        "delivered_at": p.delivered_at.isoformat() if p.delivered_at else None,
    }


def _courier_public(courier: Optional[User], session: Optional[Session] = None) -> Optional[dict]:
    """Публичная карточка курьера для отправителя (без приватного — только имя/рейтинг/телефон водителя).
    C3: rating — реальный агрегат оценок доставки (средний stars по Rating где ratee=курьер) +
    count. Анонимно (кто оценил — не раскрываем)."""
    if not courier:
        return None
    avg, cnt = (0.0, 0)
    if session is not None:
        from ..services import user_rating
        avg, cnt = user_rating(session, courier.id)
    return {
        "id": courier.id,
        "name": courier.name or "",
        "rating": round(avg, 1) if cnt > 0 else None,   # None = ещё нет оценок (новый курьер)
        "rating_count": cnt,
        "phone": courier.phone or "",   # телефон ВОДИТЕЛЯ (публичный контакт для связи по доставке)
    }


def _parcel_for_sender(p: ParcelDelivery, session: Session) -> dict:
    """Для отправителя: базовое + мой confirm_code (я его передаю получателю) + инфо курьера, если принята.
    Телефон ПОЛУЧАТЕЛЯ отправитель и так знает сам — возвращаем как есть (это его собственные данные)."""
    out = _parcel_base(p)
    out["receiver_phone"] = p.receiver_phone
    out["confirm_code"] = p.confirm_code
    out["courier"] = _courier_public(session.get(User, p.courier_id), session) if p.courier_id else None
    return out


def _parcel_available(p: ParcelDelivery) -> dict:
    """Для курьера в списке открытых заявок: БЕЗ телефона получателя (скрыт до принятия) и БЕЗ кода."""
    return _parcel_base(p)


def _parcel_for_courier(p: ParcelDelivery) -> dict:
    """Для принявшего курьера: базовое + телефон получателя (открыт после accept). Код вручения
    курьер НЕ видит заранее — его называет получатель при передаче (иначе подтверждение бессмысленно)."""
    out = _parcel_base(p)
    out["receiver_phone"] = p.receiver_phone
    return out


# ---------- Отправитель ----------

@router.post("/parcels")
def parcel_create(body: ParcelIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать заявку на доставку посылки. Отправителю возвращаем заявку + confirm_code
    (он передаёт код получателю вне приложения; получатель назовёт его курьеру при вручении).

    Валидация: from_city/to_city/receiver_name обязательны (422); size ∈ PARCEL_FEES (422);
    rules_accepted обязателен True (422). fee_kop = PARCEL_FEES[size], фиксируется в заявке."""
    from_city = body.from_city.strip()
    to_city = body.to_city.strip()
    receiver_name = body.receiver_name.strip()
    if not from_city:
        raise herr(422, "Укажи город отправления", "Ебәреү ҡалаһын күрһәт")
    if not to_city:
        raise herr(422, "Укажи город получения", "Алыу ҡалаһын күрһәт")
    if not receiver_name:
        raise herr(422, "Укажи имя получателя", "Алыусы исемен күрһәт")
    size = (body.size or "").strip()
    if size not in PARCEL_FEES:
        raise herr(422, "Выбери размер посылки", "Бандероль үлсәмен һайла")
    if not body.rules_accepted:
        raise herr(422, "Прими правила доставки", "Доставка ҡағиҙәләрен ҡабул ит")

    parcel = ParcelDelivery(
        sender_id=user.id,
        from_city=from_city,
        to_city=to_city,
        from_lat=body.from_lat,
        from_lng=body.from_lng,
        to_lat=body.to_lat,
        to_lng=body.to_lng,
        size=size,
        description=body.description.strip(),
        receiver_name=receiver_name,
        receiver_phone=body.receiver_phone.strip(),
        fee_kop=PARCEL_FEES[size],
        status="created",
        confirm_code=_gen_code(session),
    )
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:  # уведомление админа — best-effort. Телефон получателя НЕ включаем (приватность).
        notify_admin_telegram(
            f"📦 Новая посылка на доставку\n"
            f"ID: {parcel.id}\n"
            f"Маршрут: {parcel.from_city} → {parcel.to_city}\n"
            f"Размер: {parcel.size} · сбор {parcel.fee_kop // 100} ₽\n"
            f"От: {user.name or 'отправитель'}"
        )
    except Exception:
        pass
    return _parcel_for_sender(parcel, session)


@router.get("/parcels/mine")
def parcels_mine(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои отправленные заявки (все статусы, новые сверху) + мой confirm_code + инфо курьера, если принята."""
    rows = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.sender_id == user.id).order_by(ParcelDelivery.id.desc())
    ).all()
    return [_parcel_for_sender(p, session) for p in rows]


@router.post("/parcels/{parcel_id}/cancel")
def parcel_cancel(parcel_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отменить свою заявку, пока не доставлена. Чужая → 404 (IDOR закрыт); уже delivered/canceled → 409."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or parcel.sender_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status == "delivered":
        raise herr(409, "Посылка уже доставлена", "Бандероль инде еткерелгән")
    if parcel.status == "canceled":
        raise herr(409, "Заявка уже отменена", "Заявка инде кире алынған")
    prev_courier = parcel.courier_id
    parcel.status = "canceled"
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    if prev_courier:  # курьер уже вёз — предупредим (best-effort), без телефонов
        try:
            send_push(session, prev_courier, "Доставка отменена",
                      f"Отправитель отменил посылку {parcel.from_city} → {parcel.to_city}.")
        except Exception:
            pass
    return _parcel_for_sender(parcel, session)


# ---------- Курьер-водитель ----------
# Любой может помочь (не блокируем жёстко по роли), НО телефон получателя видит только принявший.

@router.get("/parcels/available")
def parcels_available(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Открытые заявки «по пути» (status=created, delivery_type=poputka), НЕ мои. Приватность:
    БЕЗ телефона получателя (скрыт до принятия). Фильтр по городам опционален. Новые сверху.

    C1: courier/buy_bring-заказы сюда НЕ попадают — их видят только одобренные курьеры
    в /courier/available (профессиональный режим, гейт _guard_courier)."""
    q = select(ParcelDelivery).where(
        ParcelDelivery.status == "created",
        ParcelDelivery.sender_id != user.id,
        ParcelDelivery.delivery_type == "poputka",
    )
    rows = session.exec(q.order_by(ParcelDelivery.id.desc())).all()
    if from_city:
        fc = from_city.strip().lower()
        rows = [p for p in rows if p.from_city and p.from_city.strip().lower() == fc]
    if to_city:
        tc = to_city.strip().lower()
        rows = [p for p in rows if p.to_city and p.to_city.strip().lower() == tc]
    return [_parcel_available(p) for p in rows]


@router.post("/parcels/{parcel_id}/accept")
def parcel_accept(parcel_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Стать курьером заявки (created→accepted). Нельзя принять свою (409) или уже принятую/иную (409).
    Отменённой/несуществующей нет → 404. После принятия курьер видит телефон получателя."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or parcel.status == "canceled":
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.sender_id == user.id:
        raise herr(409, "Нельзя взять свою посылку", "Үҙ бандеролеңде алып булмай")
    if parcel.status != "created":
        raise herr(409, "Посылку уже взяли", "Бандерольде инде алғандар")
    # C1: courier/buy_bring-заказы берут только одобренные курьеры на линии (гейт).
    # «По пути» (poputka) — как раньше, без гейта (любой попутчик помогает).
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") != "poputka":
        from .courier import _guard_courier   # локальный импорт — избегаем циклической зависимости
        _guard_courier(user, session)
    parcel.courier_id = user.id
    parcel.status = "accepted"
    parcel.accepted_at = utcnow()
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:  # отправителю — best-effort, без телефонов
        send_push(session, parcel.sender_id, "Курьер найден",
                  f"{user.name or 'Курьер'} везёт твою посылку {parcel.from_city} → {parcel.to_city}.")
    except Exception:
        pass
    return _parcel_for_courier(parcel)


@router.post("/parcels/{parcel_id}/status")
def parcel_status(parcel_id: int, body: ParcelStatusIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Курьер двигает статус accepted→in_transit→delivered. Только courier_id==me (иначе 404, IDOR закрыт).

    delivered требует code (получатель называет курьеру при передаче): совпал → delivered + delivered_at;
    не совпал → 422. Сбор fee_kop по delivered учитывается доходом платформы (statement в /admin/parcels)."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    new_status = (body.status or "").strip()
    if new_status not in _COURIER_MOVE:
        raise herr(422, "Недопустимый статус", "Ярамаған статус")
    if parcel.status in ("delivered", "canceled"):
        raise herr(409, "Посылка уже завершена", "Бандероль инде тамамланған")

    if new_status == "in_transit":
        if parcel.status != "accepted":
            raise herr(409, "Сначала прими посылку", "Башта бандерольде ал")
        parcel.status = "in_transit"
    else:  # delivered — нужен верный код вручения
        # C2 «купи и привези»: сперва курьер должен ввести фактическую стоимость товара
        # (получатель возвращает её + доставку). Без неё расчёт невозможен → 409.
        if (getattr(parcel, "delivery_type", "poputka") or "poputka") == "buy_bring" \
                and (getattr(parcel, "goods_actual_kop", 0) or 0) <= 0:
            raise herr(409, "Сначала укажи стоимость покупки", "Башта һатып алыу хаҡын күрһәт")
        code = (body.code or "").strip().upper()
        if not code or code != (parcel.confirm_code or "").upper():
            raise herr(422, "Неверный код получения", "Ялған алыу коды")
        parcel.status = "delivered"
        parcel.delivered_at = utcnow()
        # C4: комиссия платформы финализируется ЗДЕСЬ — теперь известен назначенный курьер и его
        # стаж (лесенка 3/5/8 + промо + минимум + надбавка buy_bring). При создании commission_kop
        # был лишь оценкой. Для «по пути» (poputka) не трогаем (там свой fee_kop из конфига).
        dtype = (getattr(parcel, "delivery_type", "poputka") or "poputka")
        if dtype in ("courier", "buy_bring") and parcel.courier_id:
            from . import courier as courier_mod   # ленивый импорт — избегаем цикла courier↔parcels
            courier_mod.finalize_commission_kop(session, parcel, utcnow())
        if dtype == "buy_bring":
            parcel.settled = True          # получатель рассчитался (товар + доставка)
            parcel.settled_at = utcnow()

    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    if parcel.status == "delivered":
        try:  # отправителю — best-effort, без телефонов
            send_push(session, parcel.sender_id, "Посылка доставлена",
                      f"Твоя посылка {parcel.from_city} → {parcel.to_city} вручена получателю. Спасибо!")
        except Exception:
            pass
    return _parcel_for_courier(parcel)


@router.get("/parcels/carrying")
def parcels_carrying(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Что я везу: принятые мной и ещё не завершённые (accepted|in_transit), новые сверху.
    Я курьер → вижу телефон получателя (нужен для связи по доставке)."""
    rows = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status.in_(_CARRYING_STATUSES),
        ).order_by(ParcelDelivery.id.desc())
    ).all()
    return [_parcel_for_courier(p) for p in rows]


# ---------- Спор по доставке (ответственность) ----------

class DisputeIn(BaseModel):
    reason: str = Field("", max_length=1000)   # что случилось (повреждение/недоставка/расчёт)


@router.post("/parcels/{parcel_id}/dispute")
def parcel_dispute(parcel_id: int, body: DisputeIn, user: User = Depends(current_user),
                   session: Session = Depends(get_session)):
    """Открыть спор по доставке (ответственность). Кто может: участник заказа — отправитель ИЛИ
    курьер (получатель без аккаунта действует через отправителя). Чужой заказ → 404 (IDOR закрыт).

    Ориентир при разборе — объявленная ценность (declared_value_kop); без объявления — по
    договорённости (текст на клиенте). Спор — это Report(category=parcel_dispute, parcel_id=…),
    попадает в общую админ-ленту жалоб. Уведомление админа — best-effort."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or user.id not in (parcel.sender_id, parcel.courier_id):
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    # Цель жалобы — вторая сторона; если курьер ещё не назначен, привязываем к отправителю.
    counterparty = parcel.courier_id if user.id == parcel.sender_id else parcel.sender_id
    if not counterparty:
        counterparty = parcel.sender_id
    report = Report(
        reporter_id=user.id,
        target_user_id=counterparty,
        reason=(body.reason or "").strip(),
        category="parcel_dispute",
        parcel_id=parcel.id,
    )
    session.add(report)
    session.commit()
    session.refresh(report)
    try:  # админу — best-effort, без ПДн (телефоны не включаем)
        notify_admin_telegram(
            f"⚠️ Спор по доставке\nReport ID: {report.id}\nПосылка ID: {parcel.id}\n"
            f"Маршрут: {parcel.from_city} → {parcel.to_city}\n"
            f"Тип: {getattr(parcel, 'delivery_type', 'poputka')}"
        )
    except Exception:
        pass
    return {
        "id": report.id,
        "parcel_id": parcel.id,
        "category": report.category,
        "status": report.status,
        "declared_value_kop": getattr(parcel, "declared_value_kop", 0) or 0,
        "created_at": report.created_at.isoformat() if report.created_at else None,
    }


# ---------- Админ ----------
# Админские ошибки — обычный HTTPException-строка (двуязычие требуется только для 4xx пользователю).

def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def _parcel_admin(p: ParcelDelivery) -> dict:
    """Карточка заявки для админ-контроля (со всем, включая приватное — для поддержки/споров)."""
    out = _parcel_base(p)
    out["receiver_phone"] = p.receiver_phone
    out["confirm_code"] = p.confirm_code
    return out


@router.get("/admin/parcels")
def admin_parcels(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все заявки (контроль/поддержка) + statement дохода платформы: сумма fee по delivered."""
    _require_admin(user)
    rows = session.exec(select(ParcelDelivery).order_by(ParcelDelivery.id.desc())).all()
    collected = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.fee_kop), 0)).where(ParcelDelivery.status == "delivered")
    ).one()
    delivered_count = session.exec(
        select(func.count()).select_from(ParcelDelivery).where(ParcelDelivery.status == "delivered")
    ).one()
    return {
        "parcels": [_parcel_admin(p) for p in rows],
        "statement": {
            "delivered_count": int(delivered_count or 0),
            "collected_fee_kop": int(collected or 0),   # доход платформы по доставленным
        },
    }
