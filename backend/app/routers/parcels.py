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
from datetime import date, timedelta
from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import and_, func, or_
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..flood import TOO_MANY_PARCELS, guard_open_items
from ..models import ParcelDelivery, User, UserRole
from ..safety_logic import (ensure_active,
                            is_own_media_url)
from ..security import current_user
from ..services import blocked_user_ids, is_blocked, notify_admin_telegram, push_notification
from ..timeutil import utcnow
from ..workday import local_day

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
# Типы доставки, где у комиссии платформы есть РЕАЛЬНЫЙ путь оплаты: одобренный курьер видит
# долг в кабинете и гасит его через /courier/pay-commission. У «по пути» такого пути нет —
# поэтому в отчёте админа эти деньги считаются отдельно и не называются «собрано»
# (дубль _COURIER_TYPES из courier.py: импортировать оттуда нельзя — цикл, courier импортирует нас).
_BILLABLE_TYPES = ("courier", "buy_bring")

# СРОК доставки («нужно к какому дню»): окно выбора — сегодня … +30 дней. Дальше это уже не срок,
# а опечатка в календаре: посылка «к 2027 году» не помогает ни отправителю, ни курьеру.
_DELIVER_BY_MAX_DAYS = 30

# Окно, внутри которого одинаковая заявка от одного человека считается ПОВТОРНЫМ ТАПОМ, а не
# второй посылкой. Минуты хватает с запасом: авто-ретрай запроса и «нажал ещё раз, потому что
# не отреагировало» укладываются в секунды. Отправить вторую такую же посылку тому же человеку
# через минуту — сценарий настолько редкий, что цена ошибки (подождать минуту) ничтожна рядом
# с ценой дубля (две заявки у курьеров, двойная оплата, спор).
_DUPLICATE_WINDOW_SEC = 60

# Что за груз. Закрытый список: свободный текст никто не фильтрует и не агрегирует, а курьеру
# нужно одно слово, по которому он за секунду решит — берусь или нет. «Лекарство» и «рассада»
# требуют разного обращения, но в описании они выглядели одинаково.
CARGO_TYPES = {"documents", "medicine", "food", "clothes", "tech", "other"}


def validate_cargo_type(value: str) -> str:
    """Пусто = не указан (так шлёт старый клиент, это валидно). Незнакомое → «другое»:
    ронять заявку из-за категории нельзя, посылка важнее ярлыка."""
    v = (value or "").strip().lower()
    if not v:
        return ""
    return v if v in CARGO_TYPES else "other"

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

# data-payload пуша: по нему клиент понимает, КУДА вести человека по тапу. Android открывает
# экран «Посылки», когда type начинается на "parcel". Без этого пуш «Курьер найден» вёл просто
# в приложение, и отправитель узнавал статус, только если сам догадывался переключить вкладку
# (аудит 2026-08-03). Тип держим один на весь жизненный цикл доставки — маршрут-то один.
_PARCEL_PUSH_TYPE = "parcel_status"
# Отдельный тип для рассылки курьерам о НОВОЙ заявке: экран тот же, но это не «мой статус».
_PARCEL_NEW_PUSH_TYPE = "parcel_new"


def _parcel_data(parcel_id: Optional[int], ptype: str = _PARCEL_PUSH_TYPE) -> dict:
    """data для FCM: тип события + id посылки (клиент открывает нужную карточку)."""
    return {"type": ptype, "id": parcel_id}


# ---------- Тела запросов ----------

def blank_date_to_none(v):
    """Пустая строка от клиента = «срок не выбран», а не кривая дата.

    Форма, где поле просто не заполнили, не должна отвечать 422 — «когда получится» это
    нормальный заказ. Общий before-валидатор для обеих точек приёма (ParcelIn, CourierOrderIn)."""
    return None if isinstance(v, str) and not v.strip() else v


class ParcelIn(BaseModel):
    from_city: str = Field("", max_length=80)
    to_city: str = Field("", max_length=80)
    # «Куда именно» — дом/квартира/ориентир. Опциональны (старый клиент их не шлёт), свободный
    # текст: в селе адрес чаще ориентир («у мечети», «синие ворота»), чем улица с табличкой.
    from_address: str = Field("", max_length=200)
    to_address: str = Field("", max_length=200)
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
    # разборе всегда была ветка «ценность не объявлена» (аудит 2026-07-26). Потолок 100 000 ₽.
    declared_value_kop: int = Field(0, ge=0, le=100_000_00)
    # Сколько отправитель платит курьеру за доставку (коп). У «по пути» цены не было ВООБЩЕ:
    # курьер видел маршрут и размер, а за сколько везти — нигде (аудит 2026-07-26). Теперь
    # отправитель называет сумму сам, и она видна ДО принятия. 0 — тоже честный ответ:
    # «по-соседски, бесплатно», и так и подписано. Деньги идут напрямую (Модель А). Потолок 100 000 ₽.
    price_kop: int = Field(0, ge=0, le=100_000_00)
    # «Нужно доставить не позже этого дня» (ГГГГ-ММ-ДД). Не прислали / null → «не срочно,
    # когда получится» (поведение как раньше). Проверка окна — validate_deliver_by.
    deliver_by: Optional[date] = None
    # Что именно везём. Всё опционально (старый клиент не шлёт → 0/пусто/false, как раньше).
    # Потолок веса 100 кг: выше — это уже не «посылка между своими», а грузоперевозка,
    # для которой нужен другой транспорт и другая ответственность.
    weight_kg: float = Field(0.0, ge=0, le=100)
    cargo_type: str = Field("", max_length=16)
    fragile: bool = False

    @field_validator("deliver_by", mode="before")
    @classmethod
    def _blank_deliver_by(cls, v):
        return blank_date_to_none(v)


class ParcelAcceptIn(BaseModel):
    """Тело /accept — необязательное (старый клиент шлёт пустой POST, это по-прежнему работает)."""
    pickup_photo_url: str = Field("", max_length=500)   # фото «взял целой» (только НАШ URL)


class ParcelStatusIn(BaseModel):
    status: str = Field("", max_length=16)
    code: str = Field("", max_length=12)   # обязателен только для перехода в delivered
    delivery_photo_url: str = Field("", max_length=500)   # фото «отдал целой» (только НАШ URL)
    # Фото «взял целой» на переходе accepted→in_transit. Поле принималось только в /accept, а
    # это разные моменты: заказ берут за час до выезда, у посылки курьер оказывается позже —
    # и снимок в момент «беру заказ» физически невозможен. Без снимка на границе ответственности
    # спор «было битое / стало битое» упирается в слово против слова.
    pickup_photo_url: str = Field("", max_length=500)


class ParcelReasonIn(BaseModel):
    """Причина (снятие курьера / возврат / админ-действие) — видна обеим сторонам, ≤200."""
    reason: str = Field("", max_length=200)


def validate_deliver_by(value: Optional[date]) -> Optional[date]:
    """Проверить срок «нужно доставить не позже этого дня» и вернуть его же.

    Пустое/не прислали → None и никакой ошибки: «не срочно, когда получится» — нормальный
    ответ, а не забытое поле. Прошлое → 422 (срок, который уже вышел, не может быть целью).
    Дальше окна → 422 (см. _DELIVER_BY_MAX_DAYS).

    День МЕСТНЫЙ (Уфа UTC+5, workday.local_day), а не UTC: человек живёт по своим суткам, и
    «сегодня» у него наступает на пять часов раньше, чем у сервера. ОДНА функция на обе точки
    приёма — «по пути» (ParcelIn) и заказ курьера (CourierOrderIn) судят срок одинаково."""
    if value is None:
        return None
    today = local_day()
    if value < today:
        raise herr(422, "Срок доставки уже прошёл — выбери сегодняшний день или позже",
                   "Доставка ваҡыты үткән — бөгөнгө йәки һуңғараҡ көндө һайла")
    if value > today + timedelta(days=_DELIVER_BY_MAX_DAYS):
        raise herr(422, f"Срок доставки — не дальше {_DELIVER_BY_MAX_DAYS} дней",
                   f"Доставка ваҡытын {_DELIVER_BY_MAX_DAYS} көндән алыҫҡа ҡуйып булмай")
    return value


def _is_overdue(p: ParcelDelivery) -> bool:
    """Срок вышел, а доставка ещё не завершена.

    Считаем на СЕРВЕРЕ: у телефона свои часы, свой часовой пояс и своё представление о полуночи —
    «просрочено» должно быть одинаковым и у отправителя, и у курьера. Завершённая доставка
    (delivered/canceled/returned) не просрочена никогда: везти уже нечего, а красный значок на
    вручённой посылке — просто враньё."""
    deadline = getattr(p, "deliver_by", None)
    if deadline is None or p.status in _FINAL_STATUSES:
        return False
    return deadline < local_day()


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
    """Общие поля заявки БЕЗ приватного телефона, БЕЗ кода вручения и БЕЗ адресов.

    blur_coords=True — округляем точки отправления/получения до ~1 км (2 знака): в открытом
    списке заявок (до принятия) точный адрес дома отправителя/получателя показывать нельзя
    (152-ФЗ, приватность). Точные координаты открываются только принявшему курьеру.

    from_address/to_address («у мечети», «синие ворота») сюда НЕ кладём по той же причине, что
    и телефон получателя: до принятия заявку видит любой курьер. Их добавляет _addresses()."""
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
        # Условия груза — видны ДО принятия: именно по ним курьер решает, берётся ли он вообще.
        "weight_kg": float(getattr(p, "weight_kg", 0.0) or 0.0),
        "cargo_type": getattr(p, "cargo_type", "") or "",
        "fragile": bool(getattr(p, "fragile", False)),
        "receiver_name": p.receiver_name,
        "fee_kop": p.fee_kop,
        "status": p.status,
        # C1: тип доставки и поля курьерского режима (аддитивно; старый клиент их игнорирует).
        "delivery_type": getattr(p, "delivery_type", "poputka") or "poputka",
        "urgency": getattr(p, "urgency", "bypath") or "bypath",
        # СРОК доставки и серверный флаг «срок вышел». Это НЕ персональные данные, а УСЛОВИЕ
        # заказа: курьер решает, берётся ли он успеть, — значит должен видеть срок ДО принятия,
        # в том числе в открытом списке (в отличие от телефона и адресов, которые скрыты).
        "deliver_by": (p.deliver_by.isoformat() if getattr(p, "deliver_by", None) else None),
        "overdue": _is_overdue(p),
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


def _addresses(p: ParcelDelivery) -> dict:
    """«Куда именно»: точка забора и точка вручения (дом/квартира/ориентир). Персональные данные —
    отдаём ровно тем же, кому уже отдаём receiver_phone: принявшему курьеру, отправителю и админу.
    В открытом списке заявок (_parcel_available) адресов нет вообще; получателю по трекинг-ссылке
    уходит только его собственный to_address (см. routers/share._parcel_state)."""
    return {
        "from_address": getattr(p, "from_address", "") or "",
        "to_address": getattr(p, "to_address", "") or "",
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
    out.update(_addresses(p))          # отправитель их сам вводил — отдаём как есть
    out["receiver_phone"] = p.receiver_phone
    out["confirm_code"] = p.confirm_code
    out["courier"] = _courier_public(session.get(User, p.courier_id), session) if p.courier_id else None
    return out


def _parcel_available(p: ParcelDelivery) -> dict:
    """Для курьера в списке открытых заявок: БЕЗ телефона получателя (скрыт до принятия), БЕЗ кода,
    БЕЗ адресов «куда именно» и с ОКРУГЛёнными координатами (точная точка — только принявшему)."""
    return _parcel_base(p, blur_coords=True)


def _parcel_for_courier(p: ParcelDelivery, session: Optional[Session] = None) -> dict:
    """Для принявшего курьера: базовое + телефоны ОБЕИХ сторон (открыты после accept). Код вручения
    курьер НЕ видит заранее — его называет получатель при передаче (иначе подтверждение бессмысленно).

    sender_phone (аудит 2026-07-26): раньше курьеру отдавали ТОЛЬКО receiver_phone — приехал
    забирать, дома никого, позвонить отправителю нечем, разворачивается. До accept телефон
    по-прежнему скрыт (этот сериализатор используется только после принятия заказа).

    from_address/to_address — по той же логике: «куда именно» нужно только тому, кто уже везёт."""
    out = _parcel_base(p)
    out.update(_photos(p))
    out.update(_addresses(p))
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
    rules_accepted обязателен True (422).

    Деньги (аудит 2026-07-26):
    - `price_kop` — сколько отправитель платит курьеру. Фиксируем в заявке и показываем курьеру
      ДО принятия: раньше он брал посылку, не зная, заплатят ли вообще.
    - `fee_kop` — сервисный сбор платформы. Начисляем ТОЛЬКО если settings.parcel_fee_enabled:
      пока платить его некому и нечем (см. комментарий у флага), а «начислили и показали как
      собранное» — это выдуманная выручка."""
    # Гейт «Справедливости»: приостановленный за нарушения (напр. кража груза) не заводит новые
    # доставки. Раньше проверки не было ни здесь, ни в accept — отстранённый в тот же день брал
    # следующую посылку, и пауза была декорацией (аудит 2026-07-26).
    ensure_active(session, user.id)
    # Анти-флуд: посылок «в работе» у одного отправителя — не больше потолка. Каждая висит
    # в ленте курьеров, шестьдесят подряд её просто топят (аудит 2026-08-06).
    # Считаем ЖИВЫЕ: посылки в пути плюс открытые, но ещё не протухшие. Иначе пятнадцать
    # заброшенных заявок запирали бы отправителя навсегда.
    now_cut = utcnow() - timedelta(days=settings.parcel_open_days)
    guard_open_items(session, ParcelDelivery.id, ParcelDelivery.sender_id == user.id,
                     or_(
                         ParcelDelivery.status.in_(("accepted", "in_transit", "returning")),
                         and_(ParcelDelivery.status == "created",
                              ParcelDelivery.created_at >= now_cut),
                     ),
                     limit=settings.flood_active_parcels_max,
                     ru=TOO_MANY_PARCELS[0], ba=TOO_MANY_PARCELS[1])
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
    cargo_type = validate_cargo_type(body.cargo_type)
    # Срок «к какому дню нужно» (опционально). Проверяем ДО создания: заявка с сроком из
    # прошлого родилась бы уже просроченной.
    deliver_by = validate_deliver_by(body.deliver_by)

    # Двойное создание (разбор №2, 2026-08-03). У такси серверный гард был, у доставки — нет:
    # держалось всё на `enabled = !working` в приложении. Лаг сети или авто-ретрай запроса —
    # и у человека две одинаковые посылки, обе видны курьерам, за обе он платит.
    # Одну активную на человека, как у такси, тут запретить НЕЛЬЗЯ: отправить сразу три посылки
    # разным людям — нормальный сценарий. Поэтому дедуп по СОДЕРЖИМОМУ в коротком окне:
    # тот же отправитель, маршрут, получатель и размер за последнюю минуту = тот же самый тап.
    # Лочим строку отправителя, иначе два одновременных запроса оба пройдут SELECT и оба вставят.
    session.exec(select(User).where(User.id == user.id).with_for_update()).first()
    twin = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.sender_id == user.id,
            ParcelDelivery.from_city == from_city,
            ParcelDelivery.to_city == to_city,
            ParcelDelivery.receiver_phone == body.receiver_phone.strip(),
            ParcelDelivery.size == size,
            # Сверяем ВЕСЬ смысл заявки, а не только маршрут: повторный тап шлёт байт в байт
            # тот же запрос, а «исправил адрес и отправил заново» — это уже другая заявка,
            # и схлопнуть её означало бы потерять правку.
            ParcelDelivery.from_address == body.from_address.strip(),
            ParcelDelivery.to_address == body.to_address.strip(),
            ParcelDelivery.description == body.description.strip(),
            ParcelDelivery.deliver_by == deliver_by,
            ParcelDelivery.declared_value_kop == int(body.declared_value_kop or 0),
            ParcelDelivery.delivery_price_kop == int(body.price_kop or 0),
            ParcelDelivery.weight_kg == float(body.weight_kg or 0.0),
            ParcelDelivery.cargo_type == cargo_type,
            ParcelDelivery.fragile == bool(body.fragile),
            # Имя получателя и тип доставки — тоже в ключе. На один номер шлют разным людям
            # (общий семейный телефон), и схлопнуть такие заявки значило бы потерять посылку.
            ParcelDelivery.receiver_name == receiver_name,
            ParcelDelivery.delivery_type == "poputka",
            ParcelDelivery.status == "created",
            ParcelDelivery.created_at >= utcnow() - timedelta(seconds=_DUPLICATE_WINDOW_SEC),
        ).order_by(ParcelDelivery.id.desc())
    ).first()
    if twin is not None:
        return _parcel_for_sender(twin, session)

    parcel = ParcelDelivery(
        sender_id=user.id,
        from_city=from_city,
        to_city=to_city,
        # «Куда именно»: дом/квартира/ориентир. Не обязательны (город остаётся минимумом), но
        # без них курьер ехал «в Баймак» и искал получателя по телефону уже на месте.
        from_address=body.from_address.strip(),
        to_address=body.to_address.strip(),
        from_lat=body.from_lat,
        from_lng=body.from_lng,
        to_lat=body.to_lat,
        to_lng=body.to_lng,
        size=size,
        description=body.description.strip(),
        receiver_name=receiver_name,
        receiver_phone=body.receiver_phone.strip(),
        fee_kop=(PARCEL_FEES[size] if settings.parcel_fee_enabled else 0),
        # Сколько отправитель платит курьеру (0 = «по-соседски», это честный вариант).
        delivery_price_kop=int(body.price_kop or 0),
        # Объявленная ценность — ориентир при разборе спора (0 = не объявлена).
        declared_value_kop=int(body.declared_value_kop or 0),
        # «Нужно не позже этого дня» (None = не срочно, когда получится).
        deliver_by=deliver_by,
        # Что везём: вес отвечает на «унесу ли», тип — на «возьмусь ли» (лекарство ≠ рассада),
        # «хрупкое» — на «как положить». Размер ни на один из этих вопросов не отвечает.
        weight_kg=float(body.weight_kg or 0.0),
        cargo_type=cargo_type,
        fragile=bool(body.fragile),
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
            f"Размер: {parcel.size} · курьеру {parcel.delivery_price_kop // 100} ₽"
            + (f" · сбор {parcel.fee_kop // 100} ₽" if parcel.fee_kop else "") + "\n"
            f"От: {user.name or 'отправитель'}"
        )
    except Exception:
        pass
    _notify_couriers_new_parcel(session, parcel)
    return _parcel_for_sender(parcel, session)


def _notify_couriers_new_parcel(session: Session, parcel: ParcelDelivery) -> int:
    """Пуш курьерам на линии о новой заявке. Возврат: скольким отправили.

    Раньше уведомлений курьерам не было вообще: заявка висела в пустоте, пока кто-то сам не
    откроет список и не обновит его. Бабушке срочно нужно лекарство из Сибая, три курьера в
    этот момент едут мимо — и не знают (аудит 2026-07-26). Для «по пути» не шлём: там заявку
    берёт попутчик, у которого и так свой маршрут, а рассылка была бы спамом."""
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") == "poputka":
        return 0
    try:
        from ..models import CourierProfile
        rows = session.exec(
            select(CourierProfile).where(CourierProfile.online == True)  # noqa: E712
        ).all()
        sent = 0
        from .. import geo as geo_mod
        from ..config import settings as _settings
        area_a = geo_mod.area_by_name(session, parcel.from_city or "")
        area_b = geo_mod.area_by_name(session, parcel.to_city or "")
        for prof in rows:
            if prof.user_id == parcel.sender_id:
                continue                       # свою же посылку курьеру не предлагаем
            # Зона курьера — те же правила, что в списке заказов (`geo.zone_allows`). Раньше
            # здесь стояла своя проверка «по городу строкой»: пуш звал на заказ, которого
            # человек потом не находил в списке — район и «загород» она не понимала.
            if not geo_mod.zone_allows(
                session,
                zone=prof.zone, work_city=prof.work_city, work_district=prof.work_district,
                intercity=bool(prof.work_intercity), regions=bool(prof.work_regions),
                direction_id=prof.work_direction_id, a=area_a, b=area_b,
                local_km=_settings.instant_intercity_km,
            ):
                continue
            push_notification(
                session, prof.user_id, "parcel",
                "Новая доставка рядом 📦", "Яҡында яңы доставка 📦",
                f"{parcel.from_city} → {parcel.to_city}. Открой «Курьер», чтобы взять.",
                f"{parcel.from_city} → {parcel.to_city}. Алыр өсөн «Курьер»ҙы ас.",
                ref_kind="parcel", ref_id=parcel.id,
                data=_parcel_data(parcel.id, _PARCEL_NEW_PUSH_TYPE),
            )
            sent += 1
        return sent
    except Exception:  # noqa: BLE001 — уведомления вторичны, создание заявки важнее
        return 0


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
                    ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
                )
            else:
                push_notification(
                    session, prev_courier, "parcel",
                    "Доставка отменена", "Доставка кире алынды",
                    f"Отправитель отменил посылку {route}.", f"Ебәреүсе {route} бандеролен кире алды.",
                    ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
                )
        except Exception:
            pass
    return _parcel_for_sender(parcel, session)


# ---------- Курьер-водитель ----------
# Любой может помочь (не блокируем жёстко по роли), НО телефон получателя видит только принявший.


def live_parcel_conds():
    """Условия «посылку ещё имеет смысл показывать»: открыта И не протухла.

    Раньше везде стояло просто `status == "created"`, и закрывать посылку было нечем: никем
    не взятая висела в ленте курьеров вечно. Через три месяца курьер её брал, звонил — а
    отправитель давно отвёз гостинцы сам. Плюс с потолком на число посылок в работе такие
    вечные заявки превращали его в пожизненный запрет (аудит 2026-08-06).

    Срок считаем ТОЛЬКО от создания. Первая версия смотрела ещё и на «нужно доставить к дате»
    и прятала посылку, если та дата прошла, — и сломала живую вещь: у просроченной посылки
    поднимается флаг `overdue`, она ОСТАЁТСЯ в ленте, и курьер видит «срок сорван, но везти
    всё равно надо». Срок доставки — это обещание, а не срок годности объявления: отправителю
    посылка нужна и на следующий день. Поймал существующий тест `test_parcel_deadline`.

    Тридцать дней, а не неделя как у заявки: посылка не привязана к часу, её нормально ждать
    неделями. Тридцать дней без единого курьера — это уже не ожидание, а забытая заявка."""
    created_cut = utcnow() - timedelta(days=settings.parcel_open_days)
    return [
        ParcelDelivery.status == "created",
        ParcelDelivery.created_at >= created_cut,
    ]


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
        *live_parcel_conds(),                       # протухшие в ленту не попадают
        ParcelDelivery.sender_id != user.id,
        ParcelDelivery.delivery_type == "poputka",
    )
    rows = session.exec(q.order_by(ParcelDelivery.id.desc())).all()
    # Заблокированных не показываем вовсе. Отказ при попытке взять посылку — обязательная
    # защита, но человеку незачем и видеть в ленте того, с кем он не хочет пересекаться:
    # иначе он жмёт «Взять» и получает необъяснимый отказ.
    blocked = blocked_user_ids(session, user.id)
    if blocked:
        rows = [p for p in rows if p.sender_id not in blocked]
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
    # Протухшая: из ленты она уже ушла, но прямая ссылка (старый пуш, открытый экран) обходила
    # бы фильтр — курьер вёз бы то, что отправитель давно отвёз сам (аудит 2026-08-06).
    if parcel.status == "created" and session.exec(
        select(ParcelDelivery.id).where(ParcelDelivery.id == parcel_id, *live_parcel_conds())
    ).first() is None:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.sender_id == user.id:
        raise herr(409, "Нельзя взять свою посылку", "Үҙ бандеролеңде алып булмай")
    if parcel.status != "created":
        raise herr(409, "Посылку уже взяли", "Бандерольде инде алғандар")
    # Блокировка между людьми (проверка сценариев 2026-08-05). Кнопка «заблокировать» —
    # это обещание безопасности: человек не хочет больше пересекаться. В попутках, заявках
    # и чате оно выполнялось, в такси матчер тоже фильтрует заблокированных, а в доставке
    # проверки не было вообще: заблокированный курьер брал посылку того, кто его заблокировал,
    # и вместе с ней получал адрес, имя и телефон получателя.
    if is_blocked(session, user.id, parcel.sender_id):
        raise herr(403, "Эту посылку взять нельзя", "Был бандерольде алып булмай")
    # C1: courier/buy_bring-заказы берут только одобренные курьеры на линии (гейт).
    # «По пути» (poputka) — как раньше, без гейта (любой попутчик помогает).
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") != "poputka":
        from .courier import _guard_courier, _guard_courier_debt   # локальный импорт — избегаем цикла
        _guard_courier(user, session)
        # Комиссия платформы копится долгом (Модель А). У такси блокировка была с начала,
        # у курьера — не было вообще: можно было возить месяцами и не платить (аудит 2026-07-26).
        _guard_courier_debt(session, user.id)
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
        route = f"{parcel.from_city} → {parcel.to_city}"
        who = user.name or "Курьер"
        push_notification(
            session, parcel.sender_id, "parcel",
            "Курьер найден 📦", "Курьер табылды 📦",
            f"{who} везёт твою посылку {route}.",
            f"{who} һинең бандеролеңде {route} алып бара.",
            ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
        )
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
        # Фото «взял целой» — именно здесь, а не при взятии заказа: это момент, когда курьер
        # реально стоит у посылки. Чужой хост не принимаем (открытие такой ссылки у оппонента
        # слило бы его IP) — то же правило, что у фото вручения. Пустое/чужое молча игнорим,
        # снимок необязателен и не должен ломать сам переход в путь.
        pickup_photo = (body.pickup_photo_url or "").strip()
        if pickup_photo and is_own_media_url(pickup_photo):
            parcel.pickup_photo_url = pickup_photo
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
        # Фото «отдал целой» — вторая граница ответственности, парная к pickup_photo_url выше.
        # Поле объявлено в схеме и в модели с самого начала, но присвоения не было НИГДЕ: снимок
        # приходил с телефона, экран рисовал «Фото приложено ✓» — и сервер его молча выбрасывал.
        # В споре «привёз битой» доказательства не оказывалось, хотя курьер был уверен, что снял.
        # Чужой хост не принимаем: открытие такой ссылки оппонентом слило бы его IP.
        delivery_photo = (body.delivery_photo_url or "").strip()
        if delivery_photo and is_own_media_url(delivery_photo):
            parcel.delivery_photo_url = delivery_photo
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
    # Отправителю — на каждом шаге, а не только в конце. Раньше про «курьер забрал и поехал»
    # он не узнавал вообще: посылка молча висела «принята» до самой доставки (аудит 2026-08-03).
    route = f"{parcel.from_city} → {parcel.to_city}"
    try:  # best-effort, без телефонов
        if parcel.status == "in_transit":
            push_notification(
                session, parcel.sender_id, "parcel",
                "Посылка в пути 🚗", "Бандероль юлда 🚗",
                f"Курьер забрал посылку и повёз её {route}.",
                f"Курьер бандерольде алды һәм {route} юлға сыҡты.",
                ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
            )
        elif parcel.status == "delivered":
            push_notification(
                session, parcel.sender_id, "parcel",
                "Посылка доставлена ✅", "Бандероль еткерелде ✅",
                f"Твоя посылка {route} вручена получателю. Спасибо!",
                f"Һинең бандеролең {route} алыусыға тапшырылды. Рәхмәт!",
                ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
            )
    except Exception:
        pass
    return _parcel_for_courier(parcel, session)


@router.post("/parcels/{parcel_id}/release")
def parcel_release(parcel_id: int, body: Optional[ParcelReasonIn] = None,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Курьер снимает себя с заказа: «не смогу везти».

    Раньше отказаться было НЕЛЬЗЯ — снятия courier_id не существовало нигде в коде. Замело
    дорогу на Баймак, курьер заболел, сломалась машина: посылка у него дома, заказ мёртвый,
    отправитель ничего не понимает (аудит 2026-07-26). Для зимнего Башкортостана это норма.

    Посылка возвращается в общий список (created), отправитель получает пуш с причиной.
    Анти-абуз: слишком частые отказы — сигнал админу, но НЕ авто-блокировка: «между своими»
    разбирается человеком (может, у человека правда неделя тяжёлая)."""
    parcel = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()
    ).one_or_none()
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status not in _COURIER_HOLDS:
        raise herr(409, "На этом этапе сняться уже нельзя — открой спор",
                   "Был этапта баш тартып булмай — бәхәс ас")
    # «Купи и привези» после закупки: курьер уже потратил СВОИ деньги (до 5000 ₽). Отправителю
    # отмену на этом шаге запретили давно (см. parcel_cancel), а курьер мог сняться — и заказ
    # уходил в общий список без единой записи о том, что платформа (или получатель) должна ему
    # за товар: деньги просто терялись (аудит 2026-08-03).
    # Почему запрет, а не «зафиксируем компенсацию»: компенсация за отмену — это фиксированные
    # 100 ₽ за бензин, она не возвращает стоимость товара. Курьеру честнее оставить дело живым
    # и увести его в спор: там есть и разбор человеком, и поле компенсации (Incident.
    # compensation_kop), то есть реальный путь получить свои деньги. Молчаливое «снялся» —
    # это гарантированный минус курьеру.
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") == "buy_bring" \
            and (getattr(parcel, "goods_actual_kop", 0) or 0) > 0:
        raise herr(409, "Ты уже купил товар — сняться нельзя. Открой спор, чтобы вернуть деньги",
                   "Һин тауарҙы һатып алғанһың — баш тартып булмай. Аҡсаны ҡайтарыр өсөн бәхәс ас")
    reason = ((body.reason if body else "") or "").strip()[:200]
    sender_id = parcel.sender_id
    parcel.courier_id = None
    parcel.status = "created"
    parcel.accepted_at = None
    parcel.pickup_photo_url = ""          # фото «взял целой» относилось к прошлому курьеру
    parcel.return_reason = reason
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    # След отказа: courier_id обнулён, отдельной таблицы нет — считаем по записям Центра уведомлений.
    try:
        push_notification(
            session, user.id, _NOTIF_RELEASE,
            "Ты снялся с доставки", "Доставканан баш тарттың",
            f"{parcel.from_city} → {parcel.to_city}. Посылка снова в общем списке.",
            f"{parcel.from_city} → {parcel.to_city}. Бандероль кире дөйөм исемлектә.",
            ref_kind="parcel", ref_id=parcel.id, push=False, data=_parcel_data(parcel.id),
        )
        push_notification(
            session, sender_id, "parcel",
            "Курьер не смог везти", "Курьер алып бара алманы",
            (f"Причина: {reason}. " if reason else "") + "Ищем другого курьера — посылка снова в поиске.",
            (f"Сәбәбе: {reason}. " if reason else "") + "Башҡа курьер эҙләйбеҙ.",
            ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
        )
    except Exception:  # noqa: BLE001 — уведомления вторичны
        pass
    _check_release_abuse(session, user)
    return _parcel_base(parcel)


def _check_release_abuse(session: Session, user: User) -> None:
    """Частые отказы курьера за окно → сигнал админу (без авто-наказания)."""
    try:
        from ..models import Notification
        since = utcnow() - timedelta(days=_RELEASE_ABUSE_WINDOW_DAYS)
        n = len(session.exec(
            select(Notification.id).where(
                Notification.user_id == user.id,
                Notification.type == _NOTIF_RELEASE,
                Notification.created_at >= since,
            ).limit(_RELEASE_ABUSE_LIMIT + 1)
        ).all())
        if n > _RELEASE_ABUSE_LIMIT:
            notify_admin_telegram(
                f"🚩 Частые отказы курьера (Юлдаш): пользователь #{user.id}, "
                f"{n} снятий за {_RELEASE_ABUSE_WINDOW_DAYS} дней — стоит посмотреть."
            )
    except Exception:  # noqa: BLE001
        pass


@router.post("/parcels/{parcel_id}/attempt-failed")
def parcel_attempt_failed(parcel_id: int, body: Optional[ParcelReasonIn] = None,
                          user: User = Depends(current_user), session: Session = Depends(get_session)):
    """«Приехал — никого нет дома», но посылка остаётся у курьера и он попробует ещё раз.

    Зачем отдельно от возврата (разбор №2, 2026-08-03). Раньше у сценария «получателя нет» был
    ровно один исход — везти коробку обратно. Между Сибаем и Акъяром это 60 км в один конец,
    и всё ради того, что человек вышел в магазин и вернётся через два часа. Курьер терял
    полдня, отправитель — доставку, а посылка — смысл.

    Что делает: фиксирует попытку и говорит отправителю, что нужно связаться с получателем.
    Статус НЕ меняется — посылка по-прежнему «в пути», курьер везёт её дальше по своим делам
    и заедет позже. Возврат остаётся отдельной, осознанной кнопкой: если не вышло и со второго
    раза, никого не заставляем ездить бесконечно.

    Число попыток видно обеим сторонам (`delivery_attempts`) — это и мера терпения курьера,
    и аргумент в споре «он даже не приезжал».
    """
    parcel = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()
    ).one_or_none()
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status != "in_transit":
        raise herr(409, "Отметить неудачную попытку можно, пока посылка в пути",
                   "Уңышһыҙ барыуҙы бандероль юлда саҡта билдәләп була")
    reason = ((body.reason if body else "") or "").strip()[:200]
    parcel.delivery_attempts = (parcel.delivery_attempts or 0) + 1
    if reason:
        parcel.return_reason = reason   # последняя причина неудачи; при возврате перезапишется
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    attempts = parcel.delivery_attempts or 1
    try:
        push_notification(
            session, parcel.sender_id, "parcel",
            "Курьер не застал получателя", "Курьер алыусыны тапманы",
            (f"Причина: {reason}. " if reason else "")
            + f"Попытка {attempts}. Свяжись с получателем и напиши курьеру, когда заехать.",
            (f"Сәбәбе: {reason}. " if reason else "")
            + f"{attempts}-се тапҡыр. Алыусы менән бәйләнеш тот һәм курьерға ҡасан килергә яҙ.",
            ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
        )
    except Exception:  # noqa: BLE001 — уведомление не должно ломать отметку попытки
        pass
    return _parcel_for_courier(parcel, session)


@router.post("/parcels/{parcel_id}/return-start")
def parcel_return_start(parcel_id: int, body: Optional[ParcelReasonIn] = None,
                        user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Курьер везёт посылку ОБРАТНО: получателя нет дома / отказался / не выходит на связь.

    Раньше возврата не существовало вообще — в статусах были только «доставлена» и «отменена».
    Курьер физически вёз коробку назад, а в приложении заказ навсегда висел «в пути»: закрыть
    его не мог никто, даже админ (аудит 2026-07-26)."""
    parcel = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()
    ).one_or_none()
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status not in _COURIER_HOLDS:
        raise herr(409, "Возврат доступен, пока посылка у тебя", "Кире ҡайтарыу бандероль һиндә саҡта мөмкин")
    parcel.status = "returning"
    parcel.return_reason = ((body.reason if body else "") or "").strip()[:200]
    parcel.delivery_attempts = (parcel.delivery_attempts or 0) + 1
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:
        push_notification(
            session, parcel.sender_id, "parcel",
            "Посылку везут обратно", "Бандерольде кире алып киләләр",
            (f"Причина: {parcel.return_reason}. " if parcel.return_reason else "")
            + "Курьер возвращает посылку тебе.",
            (f"Сәбәбе: {parcel.return_reason}. " if parcel.return_reason else "")
            + "Курьер бандерольде һиңә кире ҡайтара.",
            ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
        )
    except Exception:  # noqa: BLE001
        pass
    return _parcel_for_courier(parcel, session)


@router.post("/parcels/{parcel_id}/return-done")
def parcel_return_done(parcel_id: int, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Курьер вернул посылку отправителю → заказ закрыт (returned).

    Комиссию платформы за возврат НЕ берём: услуга не оказана, груз не доставлен."""
    parcel = session.exec(
        select(ParcelDelivery).where(ParcelDelivery.id == parcel_id).with_for_update()
    ).one_or_none()
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    if parcel.status == "returned":
        return _parcel_for_courier(parcel, session)      # идемпотентно
    if parcel.status != "returning":
        raise herr(409, "Сначала начни возврат", "Башта кире ҡайтарыуҙы башла")
    parcel.status = "returned"
    parcel.returned_at = utcnow()
    parcel.commission_kop = 0            # услуга не оказана — комиссии нет
    parcel.commission_paid = True        # и в «к оплате» она попасть не должна
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    try:
        push_notification(
            session, parcel.sender_id, "parcel",
            "Посылка вернулась к тебе", "Бандероль һиңә ҡайтты",
            "Курьер вернул посылку. Комиссию за возврат мы не берём.",
            "Курьер бандерольде кире ҡайтарҙы. Кире ҡайтарыу өсөн комиссия алмайбыҙ.",
            ref_kind="parcel", ref_id=parcel.id, data=_parcel_data(parcel.id),
        )
    except Exception:  # noqa: BLE001
        pass
    return _parcel_for_courier(parcel, session)


@router.get("/parcels/carrying")
def parcels_carrying(include_recent: bool = False, recent_limit: int = 10,
                     user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Что я везу: активные доставки, новые сверху.

    ``include_recent=true`` аддитивно возвращает до ``recent_limit`` последних завершённых
    доставок этого же курьера. Это нужно Android-клиенту, чтобы после вручения, отмены или
    возврата не исчезала квитанция с компенсацией, оценкой и входом в спор. Старые клиенты
    без параметра по-прежнему получают только активные статусы.

    Закрытая витрина всегда сериализуется с ``session``: после принятия курьеру нужны контакты
    и получателя, и отправителя — в том числе для забора и возврата посылки.
    """
    active = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status.in_(_CARRYING_STATUSES),
        ).order_by(ParcelDelivery.id.desc())
    ).all()
    rows = list(active)
    if include_recent:
        limit = max(1, min(int(recent_limit or 10), 20))
        recent = session.exec(
            select(ParcelDelivery).where(
                ParcelDelivery.courier_id == user.id,
                ParcelDelivery.status.in_(_FINAL_STATUSES),
            ).order_by(ParcelDelivery.id.desc()).limit(limit)
        ).all()
        rows.extend(recent)
        rows.sort(key=lambda p: int(p.id or 0), reverse=True)
    return [_parcel_for_courier(p, session) for p in rows]


# ---------- Спор по доставке (ответственность) ----------

class DisputeIn(BaseModel):
    reason: str = Field("", max_length=1000)   # что случилось (повреждение/недоставка/расчёт)
    type: str = Field("", max_length=32)       # см. _DISPUTE_TYPES; пусто = совместимость со старым клиентом
    evidence_urls: Optional[List[str]] = Field(None, max_length=10)   # фото «до/после» (наши /secure/evidence)


@router.post("/parcels/{parcel_id}/dispute")
def parcel_dispute(parcel_id: int, body: DisputeIn, background: BackgroundTasks,
                   user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Открыть спор по доставке — через настоящую «Справедливость», а не урезанную жалобу.

    Было (аудит 2026-07-26): спор создавал обычный Report — одно текстовое поле, БЕЗ фото, без
    ответа второй стороны и без поля компенсации. Курьер даже не узнавал, что на него пожаловались.
    А полноценная система разбора уже existовала, и типы parcel_damage/parcel_lost/parcel_delay/
    recipient_absent были заведены — но недостижимы: у спора не было привязки к посылке.

    Стало: Incident(parcel_id=…) — обвинённый получает пуш и может объясниться, обе стороны
    прикладывают фото (/upload/evidence), у админа есть компенсация и лестница решений.
    Кто может: отправитель или назначенный курьер (получатель без аккаунта — через отправителя)."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or user.id not in (parcel.sender_id, parcel.courier_id):
        raise herr(404, "Посылка не найдена", "Бандероль табылманы")
    counterparty = parcel.courier_id if user.id == parcel.sender_id else parcel.sender_id
    if not counterparty or counterparty == user.id:
        raise herr(409, "Курьер ещё не назначен — спорить не с кем",
                   "Курьер әле билдәләнмәгән — бәхәсләшергә кем менән юҡ")
    dispute_type = (body.type or "").strip() or _DEFAULT_INCIDENT_TYPE
    if dispute_type not in _PARCEL_INCIDENT_TYPES:
        raise herr(422, "Неизвестный тип спора", "Билдәһеҙ бәхәс төрө")
    role = "sender" if user.id == parcel.sender_id else "courier"
    from .incidents import create_incident
    inc = create_incident(
        session, reporter=user, respondent_id=counterparty, type=dispute_type,
        description=(body.reason or "").strip(), parcel_id=parcel.id, reporter_role=role,
        background=background, evidence_urls=body.evidence_urls,
    )
    return {
        "id": inc.id,
        "parcel_id": parcel.id,
        "type": inc.type,
        "status": inc.status,
        "declared_value_kop": getattr(parcel, "declared_value_kop", 0) or 0,
        "created_at": inc.created_at.isoformat() if inc.created_at else None,
    }


# ---------- Админ ----------
# Админские ошибки — обычный HTTPException-строка (двуязычие требуется только для 4xx пользователю).

def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")


def _parcel_admin(p: ParcelDelivery) -> dict:
    """Карточка заявки для админ-контроля (со всем, включая приватное — для поддержки/споров)."""
    out = _parcel_base(p)
    out.update(_addresses(p))          # адреса нужны для разбора «не довёз / не открыли»
    out["receiver_phone"] = p.receiver_phone
    out["confirm_code"] = p.confirm_code
    return out


@router.get("/admin/parcels")
def admin_parcels(limit: int = 200, offset: int = 0,
                  user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Все заявки (контроль/поддержка) + ЧЕСТНЫЙ statement по деньгам доставки.

    Раньше здесь была одна строка «собрано» = сумма fee_kop по доставленным. Она врала: сбор
    «по пути» не выставляется никому (платить его некому — попутчик не курьер), то есть цифра
    показывала деньги, которых нет (аудит 2026-07-26). Теперь три разных числа, и каждое
    означает ровно то, что написано:

    - collected_fee_kop — РЕАЛЬНО оплаченные курьерами комиссии (деньги дошли);
    - owed_commission_kop — начислено курьерам и ещё не оплачено (долг, путь оплаты есть);
    - unbilled_fee_kop — сбор по «по пути»: начислен тарифом, но никому не выставлен (не выручка).
    """
    _require_admin(user)
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    rows = session.exec(
        select(ParcelDelivery).order_by(ParcelDelivery.id.desc()).offset(offset).limit(limit)
    ).all()
    _delivered_billable = (ParcelDelivery.status == "delivered",
                           ParcelDelivery.delivery_type.in_(_BILLABLE_TYPES))
    collected = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0))
        .where(*_delivered_billable, ParcelDelivery.commission_paid == True)   # noqa: E712
    ).one()
    owed = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0))
        .where(*_delivered_billable, ParcelDelivery.commission_paid == False)  # noqa: E712
    ).one()
    unbilled = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.fee_kop), 0))
        .where(ParcelDelivery.status == "delivered")
    ).one()
    delivered_count = session.exec(
        select(func.count()).select_from(ParcelDelivery).where(ParcelDelivery.status == "delivered")
    ).one()
    return {
        "parcels": [_parcel_admin(p) for p in rows],
        "statement": {
            "delivered_count": int(delivered_count or 0),
            "collected_fee_kop": int(collected or 0),      # деньги дошли (курьеры оплатили комиссию)
            "owed_commission_kop": int(owed or 0),         # начислено, ещё не оплачено
            "unbilled_fee_kop": int(unbilled or 0),        # сбор «по пути» — выставить некому
        },
    }


# ---------- Админ: рычаги на «зависшую» доставку (аудит 2026-07-26) ----------
# Раньше по посылкам была ОДНА ручка — GET /admin/parcels (просмотр). Звонит бабушка из
# Темясово: «посылка две недели висит, курьер трубку не берёт» — а сделать нельзя ничего:
# ни отменить, ни переназначить, ни закрыть. Единственный способ был — лезть руками в базу.

def _notify_parties(session: Session, parcel: ParcelDelivery, title_ru: str, title_ba: str,
                    body_ru: str, body_ba: str) -> None:
    """Уведомить обе стороны доставки (best-effort, без ПДн)."""
    for uid in {parcel.sender_id, parcel.courier_id} - {None}:
        try:
            push_notification(session, uid, "parcel", title_ru, title_ba, body_ru, body_ba,
                              ref_kind="parcel", ref_id=parcel.id,
                              data=_parcel_data(parcel.id))
        except Exception:  # noqa: BLE001 — уведомления вторичны
            pass


@router.post("/admin/parcels/{parcel_id}/cancel")
def admin_cancel_parcel(parcel_id: int, body: Optional[ParcelReasonIn] = None,
                        user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Принудительно отменить доставку (разбор вручную, обе стороны получают причину)."""
    _require_admin(user)
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise HTTPException(404, "Посылка не найдена")
    if parcel.status in _FINAL_STATUSES:
        return {"ok": True, "status": parcel.status, "already": True}
    reason = ((body.reason if body else "") or "").strip()[:200]
    parcel.status = "canceled"
    parcel.return_reason = reason
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    _notify_parties(session, parcel, "Доставка отменена", "Доставка кире алынды",
                    (f"Причина: {reason}. " if reason else "") + "Отменено поддержкой Юлдаша.",
                    (f"Сәбәбе: {reason}. " if reason else "") + "Юлдаш ярҙамы кире алды.")
    return {"ok": True, "status": parcel.status, "reason": reason}


@router.post("/admin/parcels/{parcel_id}/release-courier")
def admin_release_courier(parcel_id: int, body: Optional[ParcelReasonIn] = None,
                          user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Снять курьера с доставки: он пропал, не отвечает, физически не может довезти.
    Посылка возвращается в общий список и её сможет взять другой курьер."""
    _require_admin(user)
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise HTTPException(404, "Посылка не найдена")
    if parcel.status in _FINAL_STATUSES:
        raise HTTPException(409, "Доставка уже завершена")
    prev_courier = parcel.courier_id
    reason = ((body.reason if body else "") or "").strip()[:200]
    parcel.courier_id = None
    parcel.status = "created"
    parcel.accepted_at = None
    parcel.pickup_photo_url = ""      # фото относилось к снятому курьеру
    parcel.return_reason = reason
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    if prev_courier:
        try:
            push_notification(session, prev_courier, "parcel",
                              "Тебя сняли с доставки", "Һине доставканан алдылар",
                              (f"Причина: {reason}. " if reason else "") + "Вопросы — напиши в поддержку.",
                              (f"Сәбәбе: {reason}. " if reason else "") + "Һорауҙар — ярҙамға яҙ.",
                              ref_kind="parcel", ref_id=parcel.id,
                              data=_parcel_data(parcel.id))
        except Exception:  # noqa: BLE001
            pass
    try:
        push_notification(session, parcel.sender_id, "parcel",
                          "Ищем другого курьера", "Башҡа курьер эҙләйбеҙ",
                          "Прежний курьер снят с доставки, посылка снова в поиске.",
                          "Элекке курьер алынды, бандероль яңынан эҙләүҙә.",
                          ref_kind="parcel", ref_id=parcel.id,
                          data=_parcel_data(parcel.id))
    except Exception:  # noqa: BLE001
        pass
    return {"ok": True, "status": parcel.status, "released_courier_id": prev_courier}


class AdminCloseIn(BaseModel):
    status: str = Field("returned", max_length=16)   # delivered | returned | canceled
    reason: str = Field("", max_length=200)


@router.post("/admin/parcels/{parcel_id}/close")
def admin_close_parcel(parcel_id: int, body: AdminCloseIn,
                       user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Принудительно закрыть доставку по итогам разбора (когда стороны договорились вне приложения).

    Комиссию берём только за реально доставленное: при returned/canceled обнуляем — услуга
    не оказана, брать деньги не за что."""
    _require_admin(user)
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel:
        raise HTTPException(404, "Посылка не найдена")
    status = (body.status or "").strip()
    if status not in _FINAL_STATUSES:
        raise HTTPException(422, f"status: {' | '.join(_FINAL_STATUSES)}")
    reason = (body.reason or "").strip()[:200]
    parcel.status = status
    parcel.return_reason = reason
    now = utcnow()
    if status == "delivered":
        parcel.delivered_at = parcel.delivered_at or now
    elif status == "returned":
        parcel.returned_at = parcel.returned_at or now
    if status in ("returned", "canceled"):
        parcel.commission_kop = 0
        parcel.commission_paid = True
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    _notify_parties(session, parcel, "Доставка закрыта", "Доставка ябылды",
                    (f"Итог: {status}. " if status else "") + (reason or "Решение поддержки Юлдаша."),
                    (f"Һөҙөмтә: {status}. " if status else "") + (reason or "Юлдаш ярҙамы ҡарары."))
    return {"ok": True, "status": parcel.status, "reason": reason}
