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
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..models import ParcelDelivery, Report, User, UserRole
from ..safety_logic import (ensure_active,
                            is_own_media_url)
from ..security import current_user
from ..services import notify_admin_telegram, push_notification, send_push
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
# Заявка «в работе» у курьера (для /carrying): принята и ещё не завершена. `returning` тоже в
# работе — коробка физически у курьера, он везёт её ОБРАТНО отправителю (аудит 2026-07-26:
# раньше такой посылки не было ни в одном списке — «в пути» навсегда).
_CARRYING_STATUSES = ("accepted", "in_transit", "returning")

# Посылка на руках у курьера: отсюда он может сняться (release) или начать возврат (return-start).
_COURIER_HOLDS = ("accepted", "in_transit")
# Финальные статусы: движения больше нет (нужны для идемпотентности и понятных 409).
_FINAL_STATUSES = ("delivered", "canceled", "returned")
# Статусы, в которых отмена отправителем даёт курьеру компенсацию (он уже выехал за посылкой).
_CANCEL_FEE_STATUSES = ("accepted", "in_transit")

# Анти-абуз отказов курьера: снялся с заказа больше стольких раз за окно → сигнал админу
# (не блокируем автоматически — «между своими» разбирается человеком).
_RELEASE_ABUSE_LIMIT = 3
_RELEASE_ABUSE_WINDOW_DAYS = 7
# Тип записи в Центре уведомлений, по которой считаем отказы курьера за окно. Отдельной таблицы
# «отказы» нет, а courier_id при снятии обнуляется — след остаётся только здесь (≤16 символов).
_NOTIF_RELEASE = "courier_release"

# Типы спора по доставке (подмножество safety_logic.INCIDENT_TYPES, раздел «B. Курьер / посылки»).
_PARCEL_INCIDENT_TYPES = ("parcel_damage", "parcel_lost", "parcel_delay",
                          "recipient_absent", "wrong_contents")
# Тип по умолчанию для старого клиента (он шлёт только текст `reason`): НЕ severe, чтобы обычная
# жалоба шла в двусторонний разбор (обвинённый объясняется), а не будила админа среди ночи.
_DEFAULT_INCIDENT_TYPE = "parcel_damage"


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
    # Объявленная ценность (коп) — ориентир при споре. Раньше форма «по пути» её не слала, и в
    # разборе всегда была ветка «ценность не объявлена» (аудит 2026-07-26). Потолок 1 млн ₽.
    declared_value_kop: int = Field(0, ge=0, le=100_000_00)


class ParcelAcceptIn(BaseModel):
    """Тело /accept — необязательное (старый клиент шлёт пустой POST, это по-прежнему работает)."""
    pickup_photo_url: str = Field("", max_length=500)   # фото «взял целой» (только НАШ URL)


class ParcelStatusIn(BaseModel):
    status: str = Field("", max_length=16)
    code: str = Field("", max_length=12)   # обязателен только для перехода в delivered
    delivery_photo_url: str = Field("", max_length=500)   # фото «отдал целой» (только НАШ URL)


class ParcelReasonIn(BaseModel):
    """Причина (снятие курьера / возврат / админ-действие) — видна обеим сторонам, ≤200."""
    reason: str = Field("", max_length=200)


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


def _parcel_base(p: ParcelDelivery, blur_coords: bool = False) -> dict:
    """Общие поля заявки БЕЗ приватного телефона и БЕЗ кода вручения.

    blur_coords=True — округляем точки отправления/получения до ~1 км (2 знака): в открытом
    списке заявок (до принятия) точный адрес дома отправителя/получателя показывать нельзя
    (152-ФЗ, приватность). Точные координаты открываются только принявшему курьеру."""
    def _blur(v):
        return round(v, 2) if (blur_coords and v is not None) else v
    return {
        "id": p.id,
        "sender_id": p.sender_id,
        "courier_id": p.courier_id,
        "from_city": p.from_city,
        "to_city": p.to_city,
        "from_lat": _blur(p.from_lat),
        "from_lng": _blur(p.from_lng),
        "to_lat": _blur(p.to_lat),
        "to_lng": _blur(p.to_lng),
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
        # --- Ветка «что-то пошло не так» (аудит 2026-07-26), аддитивно: старый клиент игнорирует.
        # Фото сюда НЕ кладём: они только для сторон и админа (см. _parcel_for_sender/_courier).
        "return_reason": getattr(p, "return_reason", "") or "",
        "returned_at": p.returned_at.isoformat() if getattr(p, "returned_at", None) else None,
        "delivery_attempts": getattr(p, "delivery_attempts", 0) or 0,
        "cancel_fee_kop": getattr(p, "cancel_fee_kop", 0) or 0,   # компенсация курьеру за отмену
    }


def _photos(p: ParcelDelivery) -> dict:
    """Фото-фиксация границ ответственности («взял целой» / «отдал целой»). Отдаём только
    сторонам сделки и админу — в открытом списке заявок их не показываем."""
    return {
        "pickup_photo_url": getattr(p, "pickup_photo_url", "") or "",
        "delivery_photo_url": getattr(p, "delivery_photo_url", "") or "",
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
    out.update(_photos(p))
    out["receiver_phone"] = p.receiver_phone
    out["confirm_code"] = p.confirm_code
    out["courier"] = _courier_public(session.get(User, p.courier_id), session) if p.courier_id else None
    return out


def _parcel_available(p: ParcelDelivery) -> dict:
    """Для курьера в списке открытых заявок: БЕЗ телефона получателя (скрыт до принятия), БЕЗ кода
    и с ОКРУГЛёнными координатами (точный адрес — только принявшему курьеру)."""
    return _parcel_base(p, blur_coords=True)


def _parcel_for_courier(p: ParcelDelivery, session: Optional[Session] = None) -> dict:
    """Для принявшего курьера: базовое + телефоны ОБЕИХ сторон (открыты после accept). Код вручения
    курьер НЕ видит заранее — его называет получатель при передаче (иначе подтверждение бессмысленно).

    sender_phone (аудит 2026-07-26): раньше курьеру отдавали ТОЛЬКО receiver_phone — приехал
    забирать, дома никого, позвонить отправителю нечем, разворачивается. До accept телефон
    по-прежнему скрыт (этот сериализатор используется только после принятия заказа)."""
    out = _parcel_base(p)
    out.update(_photos(p))
    out["receiver_phone"] = p.receiver_phone
    sender = session.get(User, p.sender_id) if (session is not None and p.sender_id) else None
    out["sender_phone"] = (sender.phone or "") if sender else ""
    out["sender_name"] = (sender.name or "") if sender else ""
    return out


# ---------- Отправитель ----------

@router.post("/parcels")
def parcel_create(body: ParcelIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Создать заявку на доставку посылки. Отправителю возвращаем заявку + confirm_code
    (он передаёт код получателю вне приложения; получатель назовёт его курьеру при вручении).

    Валидация: from_city/to_city/receiver_name обязательны (422); size ∈ PARCEL_FEES (422);
    rules_accepted обязателен True (422). fee_kop = PARCEL_FEES[size], фиксируется в заявке."""
    # Гейт «Справедливости»: приостановленный за нарушения (напр. кража груза) не заводит новые
    # доставки. Раньше проверки не было ни здесь, ни в accept — отстранённый в тот же день брал
    # следующую посылку, и пауза была декорацией (аудит 2026-07-26).
    ensure_active(session, user.id)
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
        # Объявленная ценность — ориентир при разборе спора (0 = не объявлена).
        declared_value_kop=int(body.declared_value_kop or 0),
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
    """Отменить свою заявку, пока не доставлена. Чужая → 404 (IDOR закрыт); уже delivered/canceled → 409.

    Компенсация курьеру (аудит 2026-07-26): отмена в статусе accepted/in_transit — курьер уже
    выехал (мог проехать 40 км) и раньше получал только пуш. Теперь фиксируем cancel_fee_kop =
    settings.courier_cancel_fee_kop и говорим об этом обоим. Модель А: деньги идут мимо платформы,
    мы только фиксируем сумму — стороны договариваются напрямую."""
    # Под row-lock: одновременный accept курьером и cancel отправителем иначе разъезжаются
    # (курьер «принял» уже отменённую посылку).
    parcel = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()
    ).one_or_none()
    if not parcel or parcel.sender_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status == "delivered":
        raise herr(409, "Посылка уже доставлена", "Бандероль инде еткерелгән")
    if parcel.status == "canceled":
        raise herr(409, "Заявка уже отменена", "Заявка инде кире алынған")
    if parcel.status == "returned":
        raise herr(409, "Посылка уже возвращена", "Бандероль инде кире ҡайтарылған")
    if parcel.status == "returning":
        raise herr(409, "Курьер уже везёт посылку обратно", "Курьер бандерольде кире алып ҡайта инде")
    # B5: для «Купи и привези» после закупки товара курьером (goods_actual_kop>0) отмена запрещена —
    # иначе курьер остаётся с товаром и без денег. Разбирается только через спор (parcel_dispute).
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") == "buy_bring" \
            and (getattr(parcel, "goods_actual_kop", 0) or 0) > 0:
        raise herr(409, "Курьер уже купил товар — отмена только через спор",
                   "Курьер тауарҙы һатып алған — кире алыу тик бәхәс аша")
    prev_courier = parcel.courier_id
    # Курьер уже в пути → фиксируем компенсацию (он потратил время и бензин).
    fee_kop = settings.courier_cancel_fee_kop if (prev_courier and parcel.status in _CANCEL_FEE_STATUSES) else 0
    parcel.status = "canceled"
    parcel.cancel_fee_kop = fee_kop
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    route = f"{parcel.from_city} → {parcel.to_city}"
    if prev_courier:  # курьер уже вёз — предупредим (best-effort), без телефонов
        try:
            if fee_kop > 0:
                push_notification(
                    session, prev_courier, "parcel",
                    "Доставка отменена", "Доставка кире алынды",
                    f"{route} · отправитель отменил. Компенсация {fee_kop // 100} ₽ — договоритесь напрямую.",
                    f"{route} · ебәреүсе кире алды. Компенсация {fee_kop // 100} һ — үҙ-ара килешегеҙ.",
                    ref_kind="parcel", ref_id=parcel.id,
                )
            else:
                push_notification(
                    session, prev_courier, "parcel",
                    "Доставка отменена", "Доставка кире алынды",
                    f"Отправитель отменил посылку {route}.", f"Ебәреүсе {route} бандеролен кире алды.",
                    ref_kind="parcel", ref_id=parcel.id,
                )
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
def parcel_accept(parcel_id: int, body: Optional[ParcelAcceptIn] = None,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Стать курьером заявки (created→accepted). Нельзя принять свою (409) или уже принятую/иную (409).
    Отменённой/несуществующей нет → 404. После принятия курьер видит телефоны отправителя и получателя.

    pickup_photo_url (опц.) — фото «взял целой». Принимаем ТОЛЬКО свой URL (/media, /secure/evidence):
    чужой хост при открытии у оппонента слил бы его IP. Чужой/пустой — молча игнорируем."""
    # Приостановленный за нарушения не берёт новые посылки (см. комментарий в parcel_create).
    ensure_active(session, user.id)
    # H1: под row-lock — иначе два курьера параллельно проходят проверку `created` и оба «берут»
    # посылку (last-write-wins на courier_id). FOR UPDATE сериализует: второй ждёт коммита первого
    # и видит уже `accepted` → 409. (На SQLite no-op, но тесты однопоточные.)
    parcel = session.exec(select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()).one_or_none()
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
    photo = ((body.pickup_photo_url if body else "") or "").strip()
    if photo and is_own_media_url(photo):
        parcel.pickup_photo_url = photo
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:  # отправителю — best-effort, без телефонов
        send_push(session, parcel.sender_id, "Курьер найден",
                  f"{user.name or 'Курьер'} везёт твою посылку {parcel.from_city} → {parcel.to_city}.")
    except Exception:
        pass
    return _parcel_for_courier(parcel, session)


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
def admin_parcels(limit: int = 200, offset: int = 0,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все заявки (контроль/поддержка) + statement дохода платформы: сумма fee по delivered."""
    _require_admin(user)
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    rows = session.exec(
        select(ParcelDelivery).order_by(ParcelDelivery.id.desc()).offset(offset).limit(limit)
    ).all()
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
