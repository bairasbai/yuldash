"""C1 — профиль «Курьер»: профессия (как таксист) + «заказать курьера» + «купи и привези».

Гибрид роли доставки:
- «по пути» (poputka) — это M3 (`parcels.py`), любой попутчик, бесплатно/символический сбор, БЕЗ гейта;
- «Курьер» (courier) — профессиональный режим: одобренный курьер выходит на линию и берёт заказы;
- «купи и привези» (buy_bring) — курьер тратит свои на товар, получатель возвращает (наложка ≤ потолок).

Красные линии (как в M3):
- ЦЕНА — на сервере. Клиенту не верим: расстояние по haversine между гео-точками × тариф. Без суржа.
- Комиссия платформы маленькая и прозрачная (settings.courier_service_fee_percent), «на доверии» (оплата фейк — СБП).
- Приватность: телефон получателя скрыт из /courier/available до принятия заказа (как в M3).
- Проверка курьера Уровень 1: селфи с документом + «кто пригласил» (invited_by по User.referred_by).
- Все пользовательские 4xx — двуязычные через herr(status, ru, ba).

Онбординг повторяет taxi.py (заявка → админ approve/reject → профиль). Флоу заказа переиспользует M3:
приём/движение статуса/доставка по коду — эндпоинты /parcels/{id}/accept|/status и /parcels/carrying
(они уже курьер-сторона); для courier/buy_bring-типов accept гейтится _guard_courier (см. parcels.py).
"""
from datetime import date, datetime, timedelta
from typing import Optional, Tuple

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import func
from sqlmodel import Session, select

from .. import funnel
from .. import instant_service as isv
from .. import priority as prio
from .. import price_freeze
from .. import pricing as pricing_mod
from ..antifraud import moderate_open_text
from ..rating_service import guard_rating_on_pause, guard_rating_window
from ..config import settings
from ..db import get_session
from ..logs import admin_action
from ..errors import herr
from ..models import (CourierApplication, CourierProfile, ParcelDelivery, Payment, Rating,
                      User, UserRole)
from ..safety_logic import ensure_active
from .drivers import _ensure_owned_doc_url
from .parcels import _FINAL_STATUSES, live_parcel_conds
from ..security import current_user
from ..services import (haversine_km, notify_admin_telegram, push_bilingual, push_notification,
                        user_rating)
from ..visibility import FEED_MAX, visible_parcels
from ..timeutil import local_date, utcnow
from . import parcels as parcels_mod
from .. import debt as debt_mod   # переиспользуем _local_day_expr: одна логика «локального дня» на проект
from .. import geo as geo_mod   # зоны работы: одни правила с такси (geo.zone_allows)
from . import instant as instant_mod   # normalize_zone: одна нормализация зоны на проект

router = APIRouter(tags=["courier"])

# ---------------------------------------------------------------------------
# Тарифы и пределы курьера — ПРАВЯТСЯ ЗДЕСЬ, без пересборки клиента (продуктовые
# параметры, не .env). Цена считается сервером — единственный источник истины.
# ---------------------------------------------------------------------------
COURIER_COD_CAP_KOP = 500_000          # потолок наложки/стоимости товара для buy_bring = 5000 ₽

# Насколько курьер может превысить сумму, на которую согласился заказчик (аудит 2026-08-08,
# волна 157). Раньше проверялся только общий потолок: заказчик просил купить лекарств на 1000 ₽,
# курьер вводил 5000 ₽, и получателю приходил счёт в пять раз больше — а тот стоит в дверях
# с пакетом и решает за секунду. Совсем без запаса тоже нельзя: цена в магазине не совпадает
# с ожиданием почти никогда. Берём БОЛЬШЕЕ из процента и фикса — иначе на заказе в 200 ₽ запас
# был бы тридцать рублей.
COURIER_GOODS_OVERRUN_PERCENT = 15     # запас к согласованной сумме, %
COURIER_GOODS_OVERRUN_MIN_KOP = 10000  # но не меньше 100 ₽ — для маленьких заказов


def goods_limit_kop(agreed_kop: int) -> int:
    """Потолок фактической стоимости товара: согласованная сумма плюс разумный запас."""
    agreed = max(int(agreed_kop or 0), 0)
    if agreed <= 0:
        return COURIER_COD_CAP_KOP     # сумма не согласована — держит только общий потолок
    запас = max(agreed * COURIER_GOODS_OVERRUN_PERCENT // 100, COURIER_GOODS_OVERRUN_MIN_KOP)
    return min(agreed + запас, COURIER_COD_CAP_KOP)

# Тариф курьера переехал в конфиг (settings.courier_base_kop и соседние): цену доставки
# нельзя было поправить без пересборки приложения, хотя тарифы такси живут в базе.
# Ставки лесенки живут в КОНФИГЕ (settings.courier_*), а не здесь: до аудита 2026-08-03 они
# были захардкожены, и правка комиссии в .env меняла такси, а курьера — нет, хотя комментарий
# ниже обещал «как у такси». Значения по умолчанию совпадают с такси (3% → 5% → 8%).

# C4 — умные правила комиссии (правятся ЗДЕСЬ, без пересборки; продуктовые параметры, не .env).
# Идея: плоские 8% на мелкой доставке — копейки (8% от 150 ₽ = 12 ₽). Не задираем процент
# (низкая честная комиссия — наш козырь против Яндекса ~25–40%), а делаем структуру умнее:
#   1) МИНИМУМ за доставку — комиссия не ниже пола (но и не выше самой цены доставки);
#   2) ЛЕСЕНКА по стажу курьера (как у такси) — новичку 3%, дальше 5%, ветерану 8%;
#   3) ПРОМО запуска — первым курьерам 0% на старте (подарок);
#   4) «Купи и привези» — чуть выше базового (курьер тратит своё и едет в магазин — ценность выше).
# Финал комиссии считается при ВРУЧЕНИИ (там уже известен назначенный курьер и его стаж);
# при создании заказа комиссия — лишь ОЦЕНКА (по дефолтной ступени), для показа в breakdown.
# Пол комиссии живёт в конфиге (settings.courier_commission_min_kop): 25 ₽ отменяли лесенку.

# Лесенка по стажу курьера (дни от одобрения заявки CourierApplication.reviewed_at) —
# в конфиге: settings.courier_fee_tier1_days/courier_fee_tier2_days и соответствующие проценты,
# верхняя ступень = settings.courier_service_fee_percent.

# Промо запуска «первым курьерам — 0%» (по образцу такси). Пусто = выключено.
COURIER_LAUNCH_PROMO_PERCENT = 0.0     # ставка на время промо (0% = бесплатно)
COURIER_LAUNCH_PROMO_UNTIL = ""        # ISO-дата (YYYY-MM-DD). Курьер одобрен ≤ этой даты и
                                       # now ≤ этой даты → комиссия 0%. Пусто/кривая дата → промо off.

# «Купи и привези» — надбавка к базовому проценту. Честно: курьер тратит СВОИ деньги на товар
# и едет в магазин (больше работы и риска) → ценность услуги выше, комиссия чуть выше обычной.
# Убрана 2026-08-28 (решение Александра). Придумывали её, когда база была 8% и два пункта
# были заметны; при базе 15% она делала самую трудную работу самой дорогой для курьера —
# ровно наоборот тому, что нужно. Риск «купи и привези» закрыт потолком стоимости товара,
# а не процентом. Ноль оставлен константой, чтобы витрины и тесты не переписывались.
COURIER_BUY_BRING_EXTRA_PERCENT = 0.0

# C3 — мягкая лестница качества курьера («по-соседски», без жёстких авто-блоков).
# Судим только при достаточном числе оценок (шум одного клиента репутацию не роняет).
COURIER_LADDER_MIN_RATINGS = 3         # меньше — не делаем выводов
COURIER_ADVICE_RATING = 4.6            # < → тёплый пуш-совет «подтяни качество» (дедуп 1/нед)
COURIER_PAUSE_RATING = 4.0             # < → мягкая КОРОТКАЯ пауза (аккаунт вечен, срок маленький)
COURIER_SOFT_PAUSE_DAYS = 2            # длительность мягкой паузы

_TRANSPORTS = ("car", "cargo")
_ZONES = ("city", "district", "intercity", "region")   # intercity/region — старые приложения
_SIZES = ("small", "medium", "large")
_URGENCIES = ("bypath", "now")
_COURIER_TYPES = ("courier", "buy_bring")

# Двуязычные сообщения гейта курьера (RU + черновой BA — финал за Александром).
MSG_COURIER_OFF = ("Курьер Юлдаш скоро запустится! А пока — доставка «по пути» 📦",
                   "Юлдаш курьеры тиҙҙән асыла! Ә әлегә — «юл ыңғайы» доставка 📦")
MSG_NOT_COURIER = ("Сначала стань курьером Юлдаша", "Башта Юлдаш курьеры бул")


# ---------------------------------------------------------------------------
# Гейты
# ---------------------------------------------------------------------------
def _guard_courier_enabled() -> None:
    """Мастер-флаг режима курьера. Выключен → «Курьер скоро» (доставка «по пути» работает)."""
    if not settings.courier_enabled:
        raise herr(403, *MSG_COURIER_OFF)


def _my_application(session: Session, user_id: int) -> Optional[CourierApplication]:
    return session.exec(
        select(CourierApplication).where(CourierApplication.user_id == user_id)
        .order_by(CourierApplication.id.desc())
    ).first()


def _guard_courier(user: User, session: Session) -> None:
    """Полный гейт курьера: режим включён + заявка курьера одобрена. Иначе herr 403 двуязычно."""
    _guard_courier_enabled()
    app = _my_application(session, user.id)
    if not app or app.status != "approved":
        raise herr(403, *MSG_NOT_COURIER)


def _my_profile(session: Session, user_id: int) -> Optional[CourierProfile]:
    return session.exec(select(CourierProfile).where(CourierProfile.user_id == user_id)).first()


def _guard_courier_debt(session: Session, courier_id: int) -> None:
    """Неоплаченная комиссия ≥ порога → новых заказов не берём.

    У такси такая блокировка была с самого начала, у курьера — не было ВООБЩЕ: можно было
    возить месяцами и не заплатить ни рубля (аудит 2026-07-26). Текст объясняет, сколько
    и где оплатить, а не просто отказывает: человек должен понимать, как вернуться в строй.
    Уже взятые заказы не трогаем — довезти надо.
    """
    # Сначала зачёт (волна 216): снимать человека с работы за долг, который его же деньги
    # в кошельке покрывают, — то же самое, что отобрать заработок за свои деньги.
    settle_courier_commission_from_wallet(session, courier_id)
    owed = _commission_owed_kop(session, courier_id)
    if owed < settings.courier_debt_block_threshold_kop:
        return
    rub = owed // 100
    raise herr(
        403,
        f"Накопилась комиссия {rub} ₽. Оплати её в кабинете курьера — и снова сможешь брать заказы.",
        f"{rub} һум комиссия йыйылған. Курьер кабинетында түлә — шунан яңынан заказ ала алаһың.",
    )


def _guard_not_paused(prof: Optional[CourierProfile]) -> None:
    """Мягкая пауза по качеству: пока не истекла — курьер не выходит на линию / не берёт заказы.
    Срок короткий, аккаунт остаётся. None/прошедшая — не мешаем."""
    if prof and prof.paused_until and prof.paused_until > utcnow():
        raise herr(403,
                   "Небольшая пауза по качеству. Отдышись — скоро снова в строю 💚",
                   "Сифат буйынса бәләкәй тәнәфес. Тын ал — тиҙҙән яңынан сафта 💚")


def courier_rating(session: Session, courier_id: int) -> Tuple[float, int]:
    """Рейтинг человека КАК КУРЬЕРА: только оценки за доставки, которые он вёз сам.

    Зачем отдельно от общего рейтинга (аудит 2026-08-08, волна 186). Общий балл человека
    складывается из всех его ролей сразу: он пассажир, он водитель попутки, он отправитель
    посылок и он же курьер. Для витрины это правильно — доверие человеку одно. Но лестница
    качества отнимает у него РАБОТУ, а работа у него одна — курьерская.

    Что было. Айрат работает курьером. Как ЗАКАЗЧИК он получил три единицы от курьеров,
    которые везли его коробки. Лестница посмотрела на общий балл, увидела 1.0 и сняла его
    с линии на двое суток — при том что как курьер он не вёз ещё ни одной посылки, и
    претензий к его работе не было ни у кого. Письмо сказало «Рейтинг заметно просел» —
    ни слова о том, какие это оценки и за что.

    Правила подсчёта берём общие (`services._rating_from_rows`): тот же кап на пару
    «оценил–оценённый» и то же окно свежести. Другой здесь только НАБОР строк — не все
    оценки человека, а те, где он был курьером доставки.
    """
    from ..services import _rating_from_rows
    rows = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .join(ParcelDelivery, Rating.parcel_id == ParcelDelivery.id)      # type: ignore[arg-type]
        .where(
            Rating.ratee_id == courier_id,
            Rating.excluded == False,        # noqa: E712 — снятые оценки вне среднего
            ParcelDelivery.courier_id == courier_id,
        )
    ).all())
    return _rating_from_rows(rows)


def _maybe_courier_soft_ladder(session: Session, courier_id: int, avg: float, cnt: int) -> None:
    """C3 мягкая лестница: оценили курьера → по-доброму реагируем на просевший рейтинг.
    - хватает данных и рейтинг < COURIER_ADVICE_RATING → тёплый пуш-совет (дедуп 1/нед);
    - рейтинг < COURIER_PAUSE_RATING → короткая мягкая пауза (курьер отдохнёт, потом вернётся).
    Не курьер (волонтёр «по пути», нет профиля) → лестницы нет. Без жёстких авто-блоков."""
    if cnt < COURIER_LADDER_MIN_RATINGS or avg <= 0:
        return
    prof = _my_profile(session, courier_id)
    if prof is None:
        return
    now = utcnow()
    if avg < COURIER_PAUSE_RATING:
        prof.paused_until = now + timedelta(days=COURIER_SOFT_PAUSE_DAYS)
        prof.online = False   # снимаем с линии: иначе «на линии», но заказы 403 — противоречие в UI
        prof.updated_at = now
        session.add(prof)
        session.commit()
        try:
            # Наказание = запись, а не пуш «как получится» (аудит 2026-08-08, волна 20).
            push_notification(
                session, courier_id, "safety",
                "Пауза по качеству", "Сифат буйынса пауза",
                "Оценки за твои доставки заметно просели. Дадим паузу на пару дней — "
                "вернёшься с новыми силами 💚",
                "Илтеүҙәрең өсөн баһалар ныҡ төштө. Бер-ике көн тәнәфес — "
                "яңы көс менән ҡайтырһың 💚",
            )
        except Exception:
            pass
        return
    if avg < COURIER_ADVICE_RATING:
        if (prof.low_rating_advice_at is not None
                and now - prof.low_rating_advice_at < timedelta(days=7)):
            return
        prof.low_rating_advice_at = now
        session.add(prof)
        session.commit()
        try:
            push_bilingual(session, courier_id,
                           "Совет от Юлдаша", "Юлдаштан кәңәш",
                           "Оценки за доставки немного просели. Бережная доставка и доброе "
                           "слово быстро возвращают звёзды 💚",
                           "Илтеүҙәр өсөн баһалар бер аҙ төштө. Иғтибарлы доставка һәм йылы "
                           "һүҙ йондоҙҙарҙы тиҙ кире ҡайтара 💚")
        except Exception:
            pass


# ---------------------------------------------------------------------------
# C4 — Умные правила комиссии (стаж/промо/минимум/надбавка). Единый источник,
# используется и при ОЦЕНКЕ (создание/estimate), и при ФИНАЛИЗАЦИИ (вручение).
# ---------------------------------------------------------------------------
def courier_commission_kop(price_kop: int, percent: float) -> int:
    """Комиссия в копейках по цене доставки и проценту.
    Формула: `min(price, max(МИНИМУМ, round(price*pct/100)))` —
    комиссия не ниже пола (settings.courier_commission_min_kop), но и НЕ БОЛЬШЕ цены доставки
    (комиссия не может превышать доставку — иначе курьер уйдёт в минус).
    Особый случай: percent ≤ 0 (промо запуска) → комиссия РЕАЛЬНО 0, без пола (подарок первым)."""
    price_kop = int(price_kop or 0)
    if percent <= 0:                      # промо 0% — без минимума, честный подарок
        return 0
    # M1: деньги — через Decimal/ROUND_HALF_UP (как весь ledger), а не float*round (banker's) —
    # иначе расхождение на .5-границах и дрейф float на некруглых процентах.
    from ..ledger import fee_kop_for
    raw = fee_kop_for(price_kop, percent)
    return min(price_kop, max(int(settings.courier_commission_min_kop), 0, raw))


def _courier_reviewed_at(session: Session, courier_id: int):
    """Дата одобрения курьера (стаж считаем от неё). None, если заявки нет / не одобрена."""
    app = _my_application(session, courier_id)
    if app and app.status == "approved" and app.reviewed_at:
        return app.reviewed_at
    return None


def _launch_promo_active(session: Session, courier_id: int, now) -> bool:
    """Промо запуска «первым курьерам — 0%»: задана дата COURIER_LAUNCH_PROMO_UNTIL,
    курьер одобрен НЕ позже неё (он из «первого набора») и окно ещё открыто (now ≤ даты).
    Пустая/кривая дата → выключено. По образцу launch_promo такси."""
    raw = (COURIER_LAUNCH_PROMO_UNTIL or "").strip()
    if not raw:
        return False
    try:
        until = date.fromisoformat(raw)
    except ValueError:
        return False                      # кривая дата в конфиге → промо не применяем, не падаем
    reviewed = _courier_reviewed_at(session, courier_id)
    # Обе даты — по Уфе, как и `until` из конфига. По мировому времени набор пускал
    # опоздавших (одобрен 1 сентября ночью → числился августовским), а само промо жило
    # лишние пять часов после последнего дня (волна 155, та же дыра, что у такси).
    if reviewed is None or local_date(reviewed) > until:
        return False                      # одобрен после окна набора — промо не для него
    return local_date(now) <= until       # окно ещё не закрылось


def courier_deliveries_before(session: Session, courier_id: int, when=None) -> int:
    """Сколько доставок курьер уже вручил К МОМЕНТУ `when` (строго до).

    Строго до — чтобы доставка не поднимала ставку сама себе: курьер взял 31-ю по 3%
    и по 3% её и оплачивает (то же правило, что у такси)."""
    when = when or utcnow()
    return int(session.exec(
        select(func.count()).select_from(ParcelDelivery).where(
            ParcelDelivery.courier_id == courier_id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivered_at.is_not(None),
            ParcelDelivery.delivered_at < when,
        )
    ).one() or 0)


def courier_fee_tier(session: Session, courier_id: int, now=None) -> Tuple[float, str]:
    """Базовая ступень лесенки курьера (БЕЗ промо и БЕЗ buy_bring надбавки).

    Ступень — по ВРУЧЁННЫМ ДОСТАВКАМ, как у такси по поездкам (2026-08-28): <30 → 3% (tier1);
    <100 → 5% (tier2); дальше → 8% (tier3). Календарь льготу больше не двигает: она достаётся
    тому, кто возит, а не тому, кто числится.

    Нет одобренной заявки → консервативно верхняя ступень: стажа мы про такого не знаем.
    Все цифры — из конфига (settings.courier_*), правятся в .env без пересборки."""
    now = now or utcnow()
    if _courier_reviewed_at(session, courier_id) is None:
        return settings.courier_service_fee_percent, "tier3"
    done = courier_deliveries_before(session, courier_id, now)
    if done < settings.courier_fee_tier1_deliveries:
        return settings.courier_fee_tier1_percent, "tier1"
    if done < settings.courier_fee_tier2_deliveries:
        return settings.courier_fee_tier2_percent, "tier2"
    return settings.courier_service_fee_percent, "tier3"


def courier_commission_percent(session: Session, courier_id: int,
                               delivery_type: str = "courier", now=None) -> Tuple[float, str]:
    """Эффективный процент комиссии для НАЗНАЧЕННОГО курьера и типа доставки + метка ступени.
    Промо активно → 0% (метка 'promo'). Иначе лесенка по стажу; для buy_bring — плюс надбавка."""
    now = now or utcnow()
    if _launch_promo_active(session, courier_id, now):
        return COURIER_LAUNCH_PROMO_PERCENT, "promo"
    percent, tier = courier_fee_tier(session, courier_id, now)
    if delivery_type == "buy_bring":
        percent += COURIER_BUY_BRING_EXTRA_PERCENT
    return percent, tier


def courier_commission_base_kop(parcel) -> int:
    """С чего берём комиссию у курьера: цена доставки МИНУС компенсации ему.

    То же правило, что у такси (решение Александра, 2026-08-28):
      • дорога к посылке и зимняя дорога — это его бензин, а не наш заработок → не берём;
      • ожидание — его рабочее время, а не расход → берём.

    Иначе в приложении оказалось бы две разных морали, и первый же курьер, который был
    таксистом, это заметит.
    """
    цена = int(getattr(parcel, "delivery_price_kop", 0) or 0)
    компенсации = (int(getattr(parcel, "pickup_fee_kop", 0) or 0)
                   + int(getattr(parcel, "weather_fee_kop", 0) or 0))
    return max(цена - компенсации, 0)


def finalize_commission_kop(session: Session, parcel, now=None) -> int:
    """Финализировать комиссию заказа по НАЗНАЧЕННОМУ курьеру (стаж/промо/тип) — вызывается при
    вручении. База = цена доставки минус компенсации курьеру (см. courier_commission_base_kop).
    Сохраняет commission_kop и fee_kop на заказе. Возвращает итоговую комиссию (коп)."""
    dtype = (getattr(parcel, "delivery_type", "courier") or "courier")
    if dtype not in _COURIER_TYPES or not parcel.courier_id:
        return int(getattr(parcel, "commission_kop", 0) or 0)
    now = now or utcnow()
    percent, _tier = courier_commission_percent(session, parcel.courier_id, dtype, now)
    commission = courier_commission_kop(courier_commission_base_kop(parcel), percent)
    parcel.commission_kop = commission
    parcel.fee_kop = commission           # fee_kop = доход платформы (statement в /admin/parcels)
    return commission


# ---------------------------------------------------------------------------
# Цена (сервер — источник истины)
# ---------------------------------------------------------------------------
def courier_night_k(when=None) -> float:
    """Ночной коэффициент доставки. 1.0 — день или надбавка выключена.

    Окно и величина — ОБЩИЕ с такси (settings.night_*): два разных ночных правила в одном
    приложении объяснить нельзя, а разъедутся они на первой же правке.
    """
    k = float(settings.night_k_default)
    if k <= 1.0:
        return 1.0
    час = isv.local_hour(when)
    в_окне = isv.in_night_window(час, int(settings.night_from_hour_default),
                                 int(settings.night_to_hour_default))
    return k if в_окне else 1.0


def courier_pickup_fee_kop(straight_km: Optional[float], zone: str) -> int:
    """Дорога курьера к посылке, копейки. Ноль — платить не за что.

    Формула такси (`isv.pickup_fee_rub`), свои цифры: бесплатные километры + ставка + потолок.
    Расстояние — от города, в котором курьер работает, до точки забора: координат курьера
    у нас нет, а город он указывает сам.
    """
    if straight_km is None:
        return 0
    if zone == "intercity":
        free_km = float(settings.courier_pickup_free_km_intercity)
        per_km = int(settings.courier_pickup_per_km_intercity_kop)
        max_kop = int(settings.courier_pickup_max_intercity_kop)
    else:
        free_km = float(settings.courier_pickup_free_km)
        per_km = int(settings.courier_pickup_per_km_kop)
        max_kop = int(settings.courier_pickup_max_kop)
    if per_km <= 0 or max_kop <= 0:
        return 0
    оплачиваемые = isv.pickup_road_km(straight_km) - free_km
    if оплачиваемые <= 0:
        return 0
    return min(int(round(оплачиваемые * per_km / 1000.0)) * 1000, max_kop)   # округление до 10 ₽


def settle_courier_pickup(session: Session, parcel, courier_id: int, now=None) -> None:
    """Зафиксировать дорогу курьера к посылке в момент, когда он взял заказ.

    Почему только сейчас. При создании заказа курьера ещё нет, и честного числа не
    существует — отправителю показан потолок и прямо сказано, что сумму назовём позже.
    Придумать её раньше значило бы взять деньги за километры, которых может не быть.

    Откуда берём расстояние. Координат курьера у нас нет (в отличие от таксиста с его
    presence): считаем от НАСЕЛЁННОГО ПУНКТА, который он сам указал как место работы,
    до точки забора. Для городской доставки это ноль — строка не появляется. Для «курьер
    в Баймаке, посылка в Темясово» это честные 35 км.

    Город не указан или не найден в справочнике → строки нет. Брать деньги «наверное он
    далеко» нельзя: это ровно тот случай, когда человек не может ни проверить, ни поспорить.
    """
    from .. import geo as geo_mod
    from ..services import haversine_km

    now = now or utcnow()
    if not bool(getattr(parcel, "pickup_pending", False)):
        return
    prof = _my_profile(session, courier_id)
    город = (getattr(prof, "work_city", "") or "").strip() if prof else ""
    точка = geo_mod.by_exact_name(session, город) if город else None
    if точка is None or parcel.from_lat is None or parcel.from_lng is None:
        parcel.pickup_pending = False
        session.add(parcel)
        session.commit()
        return
    прямая = haversine_km(точка.lat, точка.lng, parcel.from_lat, parcel.from_lng)
    зона = "intercity" if прямая > float(settings.instant_intercity_km) else "city"
    сумма = courier_pickup_fee_kop(прямая, зона)
    parcel.pickup_pending = False
    parcel.pickup_km = round(isv.pickup_road_km(прямая), 2) if сумма > 0 else 0.0
    parcel.pickup_fee_kop = сумма
    if сумма > 0:
        parcel.delivery_price_kop = int(parcel.delivery_price_kop or 0) + сумма
    session.add(parcel)
    session.commit()


# ============================ Платное ожидание курьера ============================
# Курьер ждёт на ДВУХ концах: отправителя, когда забирает, и получателя, когда привозит.
# Задерживают его разные люди, поэтому копим раздельно — в чеке видно, где сколько набежало.
# Правила общие с такси (3 минуты бесплатно, 7 ₽/мин, потолок 300 ₽ на доставку): два разных
# правила ожидания в одном приложении объяснить нельзя.
def courier_waiting_total_kop(parcel) -> int:
    return (int(getattr(parcel, "waiting_sender_kop", 0) or 0)
            + int(getattr(parcel, "waiting_receiver_kop", 0) or 0))


def settle_courier_waiting(session: Session, parcel, now=None) -> int:
    """Закрыть открытое ожидание и записать его на нужный конец. Возврат — сколько добавили.

    На чьей стороне ждали, решает статус: `accepted` — курьер стоит у отправителя,
    `in_transit` — у получателя, `returning` — снова у отправителя (везёт коробку назад).
    Ничего не ждали → ноль, счётчик не двигаем.

    При ВОЗВРАТЕ цену доставки не трогаем: услуга не оказана, платить за неё никто не будет,
    а это число служит потолком компенсации — подними мы его, потолок поехал бы вслед за
    ожиданием. Само ожидание при возврате попадает в `return_fee_kop` (см. parcel_return_done).
    """
    now = now or utcnow()
    начало = getattr(parcel, "waiting_started_at", None)
    if начало is None:
        return 0
    было = courier_waiting_total_kop(parcel)
    стало = isv.capped_waiting_kop(было, начало, now)
    добавили = max(стало - было, 0)
    if добавили > 0:
        возврат = (parcel.status or "") == "returning"
        if (parcel.status or "") == "accepted" or возврат:
            parcel.waiting_sender_kop = int(parcel.waiting_sender_kop or 0) + добавили
        else:
            parcel.waiting_receiver_kop = int(parcel.waiting_receiver_kop or 0) + добавили
        if not возврат:
            parcel.delivery_price_kop = int(parcel.delivery_price_kop or 0) + добавили
    parcel.waiting_started_at = None
    session.add(parcel)
    session.commit()
    return добавили


def courier_return_fee_parts_kop(parcel, now=None) -> dict:
    """Компенсация курьеру за возврат («получателя не было»), копейками, по строкам.

    Правило (решение Александра, 29.08). Платим за то, что действительно случилось:
    курьер съездил — дорога оплачена; коробку не вручили — за саму доставку не берём.
    Поэтому сюда входят километры маршрута, его дорога к посылке и ожидание, и НЕ входят
    подача, размер, срочность, ночная надбавка и зимняя дорога — это цена услуги, которой
    не было.

    Платим ТОЛЬКО если курьер отметил хотя бы одну попытку вручения (`delivery_attempts`).
    Без этого условия появилось бы «съездил, никого не было, поверьте на слово» — а кнопка
    «приехал, никого нет дома» у курьера уже есть, и она же считает попытки.

    Обратную дорогу отдельной строкой не считаем: курьер и так возвращается своим маршрутом,
    а платить дважды за одни и те же километры отправитель справедливо не поймёт.

    ПОВТОРНЫЕ ЗАЕЗДЫ (29.08, вторая волна). Первый маршрут курьер проехал один раз — он
    в `route_kop`. Но если отправитель попросил «получатель уже дома, заедь ещё раз», это
    отдельный крюк, и раньше он не стоил ничего: три заезда оплачивались как один. Правило
    UPS, которое мы забрали себе: платит тот, кто ПОПРОСИЛ. Считаем только заезды по просьбе
    отправителя (`redeliver_requests`), не больше `courier_redeliver_max`, каждый — как
    половина маршрута (`courier_redeliver_km_k`): курьер уже в районе, часть пути общая.
    Заезды по своей инициативе курьеру не оплачиваются — иначе попытки крутятся в одиночку.

    ПОТОЛОК. Вся компенсация не может быть дороже самой доставки (`delivery_price_kop`).
    Такого нет ни у кого на рынке: UPS за возврат берёт новую отправку плюс 100% исходной,
    то есть вдвое. У нас человек, которому посылку так и не вручили, в худшем случае платит
    ровно столько, сколько стоила бы удачная доставка, — и ни рубля сверх.

    Потолок по стоимости товара сюда сознательно НЕ добавлен. Дорога курьера не зависит от
    того, что в коробке: 90 км за посылкой с носками — те же 90 км. Резать по объявленной
    ценности значило бы заставить курьера возить дешёвые грузы за свой счёт, а сама
    ценность к тому же чаще всего не указана (`declared_value_kop` = 0).
    """
    now = now or utcnow()
    попыток = int(getattr(parcel, "delivery_attempts", 0) or 0)
    пусто = {"attempts": 0, "redeliveries": 0, "route_kop": 0, "redeliver_kop": 0,
             "next_redeliver_kop": 0, "pickup_kop": 0, "waiting_kop": 0,
             "cap_kop": 0, "capped_kop": 0, "total_kop": 0}
    if попыток <= 0:
        return пусто
    км = float(getattr(parcel, "distance_km", 0.0) or 0.0)
    зона = "intercity" if км > float(settings.instant_intercity_km) else "city"
    за_км = int(settings.courier_per_km_intercity_kop if зона == "intercity"
                else settings.courier_per_km_kop)
    маршрут = int(round(км * за_км))
    # Заезды по просьбе отправителя. Ограничены сверху и числом реальных попыток: попросить
    # можно сколько угодно раз, но платим за те, куда курьер действительно съездил.
    просили = max(int(getattr(parcel, "redeliver_requests", 0) or 0), 0)
    заездов = min(просили, int(settings.courier_redeliver_max), max(попыток - 1, 0))
    повторные = int(round(маршрут * float(settings.courier_redeliver_km_k))) * заездов
    дорога = int(getattr(parcel, "pickup_fee_kop", 0) or 0)
    ожидание = isv.capped_waiting_kop(courier_waiting_total_kop(parcel),
                                      getattr(parcel, "waiting_started_at", None), now)
    сырое = маршрут + повторные + дорога + ожидание
    потолок = max(int(getattr(parcel, "delivery_price_kop", 0) or 0), 0)
    итого = min(сырое, потолок) if потолок > 0 else сырое
    return {
        "attempts": попыток,
        "redeliveries": заездов,
        "route_kop": маршрут,
        "redeliver_kop": повторные,
        "pickup_kop": дорога,
        "waiting_kop": ожидание,
        # Во сколько обойдётся СЛЕДУЮЩИЙ заезд, если попросить его прямо сейчас. Считаем
        # здесь, а не на клиенте: цена заезда зависит от зоны, коэффициента и потолка, и
        # телефон, посчитавший её сам, однажды покажет не то число, которое спишется.
        # Ноль = предел заездов исчерпан или потолок уже съел бы доплату целиком.
        "next_redeliver_kop": (
            min(int(round(маршрут * float(settings.courier_redeliver_km_k))),
                max(потолок - итого, 0) if потолок > 0 else 10 ** 9)
            if заездов < int(settings.courier_redeliver_max) else 0
        ),
        # Потолок и сколько он срезал — чтобы «сумма меньше слагаемых» не читалась как ошибка.
        "cap_kop": потолок,
        "capped_kop": max(сырое - итого, 0),
        "total_kop": итого,
    }


def courier_return_fee_kop(parcel, now=None) -> int:
    """Сколько отправитель должен курьеру за возврат. Ноль — попыток вручения не было."""
    return int(courier_return_fee_parts_kop(parcel, now)["total_kop"])


def courier_cancel_fee_parts_kop(parcel, now=None) -> dict:
    """Из чего сложилась компенсация курьеру за отмену, копейками. Для человека.

    Одна сумма читается как «обобрали». «100 ₽ штраф + 300 ₽ дорога курьера + 70 ₽
    ожидание» — то же число, но с ним не спорят. Форма один в один с такси
    (`isv.cancel_fee_parts_kop`), чтобы экраны обоих режимов читались одинаково.
    """
    now = now or utcnow()
    штраф = int(settings.courier_cancel_fee_kop)
    дорога = int(getattr(parcel, "pickup_fee_kop", 0) or 0)
    ожидание = isv.capped_waiting_kop(courier_waiting_total_kop(parcel),
                                      getattr(parcel, "waiting_started_at", None), now)
    return {
        "base_kop": штраф,
        "pickup_kop": дорога,
        "waiting_kop": ожидание,
        "total_kop": штраф + дорога + ожидание,
    }


def courier_cancel_fee_kop(parcel, now=None) -> int:
    """Компенсация курьеру за отмену = штраф + его дорога к посылке + его ожидание.

    Раньше здесь было плоское число (100 ₽) — ровно та дыра, которую у такси закрыли
    28.08. Курьер из Баймака выезжал за посылкой в Темясово (35 км), отправитель
    передумывал у двери — и за 70 км порожняка курьер получал 100 ₽, хотя строку
    «дорога курьера» отправитель видел ДО заказа и она уже была зафиксирована.

    Ожидание считаем ЗДЕСЬ, а не берём готовым: счётчик закрывается вручением, а при
    отмене вручения не было ни разу. Курьер при этом отстоял столько же.

    Размер посылки, срочность и зимняя дорога сюда НЕ входят: коробку он не повёз.

    Отдельного потолка здесь нет, и это осознанно. Каждая часть уже ограничена там, где
    начисляется: дорога — потолком своей зоны при фиксации (300 ₽ город / 900 ₽ межгород),
    ожидание — `wait_fee_cap_rub`. Накрой мы сумму ещё одним потолком «по зоне доставки» —
    он резал бы курьеру уже зафиксированное и показанное отправителю число: курьер из
    соседнего города вёз бы 900 ₽ подачи, а получал 300 ₽ по потолку городской доставки.
    """
    return int(courier_cancel_fee_parts_kop(parcel, now)["total_kop"])

def _price(from_lat: Optional[float], from_lng: Optional[float],
           to_lat: Optional[float], to_lng: Optional[float],
           size: str, urgency: str, percent: Optional[float] = None,
           session: Optional[Session] = None, when=None) -> dict:
    """Честная цена доставки: haversine × road_k × тариф + размер + срочность. Возвращает
    price_kop, commission_kop (ОЦЕНКА по `percent`), distance_km и breakdown (прозрачно для UI).
    Без суржа. Комиссия здесь — ориентировочная (commission_estimated=True); финал — при вручении.
    `percent` не задан → верхняя ступень из конфига (консервативная оценка, как и раньше)."""
    if percent is None:
        percent = settings.courier_service_fee_percent
    # Потолок расстояния — тот же, что у такси (волна 188). Дыра была слово в слово та же:
    # цена доставки считается от расстояния, комиссия — от цены, неоплаченная комиссия
    # блокирует курьера. Промах по карте давал Уфа → Владивосток: доставка 140 778 ₽,
    # комиссия 4 223 ₽ при пороге блокировки 1000 ₽ — то есть человек терял работу
    # за чужой соскользнувший палец.
    #
    # Проверку ставим ЗДЕСЬ, в расчёте цены: через него ходят и оценка, и создание заказа.
    if None not in (from_lat, from_lng, to_lat, to_lng):
        прямая = haversine_km(from_lat, from_lng, to_lat, to_lng)
        if прямая > max(float(settings.max_trip_km), 1.0):
            raise herr(
                422,
                "Это слишком далеко для доставки. Проверь адрес получателя на карте 🗺",
                "Был илтеү өсөн бик алыҫ. Алыусының адресын картала тикшер 🗺",
            )
    прямая_км = (0.0 if None in (from_lat, from_lng, to_lat, to_lng)
                 else haversine_km(from_lat, from_lng, to_lat, to_lng))
    if прямая_км <= 0.01:
        # Точки совпали (забрать и отдать в одном месте) — платного запроса к маршрутизатору
        # такая «дорога» не стоит, а запасной расчёт округлил бы её до 500 метров.
        distance_km, zone = 0.0, "city"
    else:
        # Длину берём по ДОРОГАМ, а не линейкой по карте (как у такси с самого начала).
        # В горах прямая и дорога расходятся в разы: через хребет линейка показывала 8 км
        # там, где ехать 30, и курьер вёз двадцать километров даром. Маршрутизатор
        # недоступен → внутри честный запасной расчёт (прямая × 1,3), как было раньше.
        route = pricing_mod.route_metrics((from_lat, from_lng), (to_lat, to_lng))
        distance_km = round(max(route.distance_km, 0.0), 2)
        zone = "intercity" if distance_km > settings.instant_intercity_km else "city"
    per_km = (settings.courier_per_km_intercity_kop if zone == "intercity"
              else settings.courier_per_km_kop)
    base_kop = int(settings.courier_base_kop)
    distance_kop = int(round(distance_km * per_km))
    size_kop = {"medium": int(settings.courier_size_medium_kop),
                "large": int(settings.courier_size_large_kop)}.get(size, 0)
    urgency_kop = int(settings.courier_urgency_now_kop) if urgency == "now" else 0
    доставка_kop = base_kop + distance_kop + size_kop + urgency_kop

    # --- Ночная надбавка: НАЦЕНКА, а не компенсация — множит саму доставку ---
    # Ночью курьеров почти нет, и без надбавки ночной заказ просто никто не возьмёт: человек
    # смотрит на «никто не откликнулся». Окно и коэффициент — общие с такси.
    night_k = courier_night_k(when)
    доставка_kop = int(round(доставка_kop * night_k))

    # --- Дорога курьера к посылке: строка счёта, а не наценка ---
    # Курьера ещё нет — честного числа не существует. Показываем потолок и говорим об этом
    # прямо (pickup_pending), а точную сумму фиксируем, когда он возьмёт заказ. Придумывать
    # её сейчас значило бы взять деньги за километры, которых, может быть, не будет.
    pickup_max_kop = (settings.courier_pickup_max_intercity_kop if zone == "intercity"
                      else settings.courier_pickup_max_kop)
    pickup_kop, pickup_pending = 0, distance_km > 0

    # --- Зимняя дорога: тот же расчёт, что у такси ---
    # Гололёд не разбирает, человек в машине или коробка: риск, износ и скорость те же.
    weather_kind = ""
    weather_kop = 0
    if distance_km > 0 and session is not None:
        weather_kind = isv.winter_road_kind(
            session, (from_lat, from_lng), (to_lat, to_lng), when)
        weather_kop = isv.winter_road_fee_rub(
            distance_km, доставка_kop // 100, weather_kind) * 100

    price_kop = доставка_kop + pickup_kop + weather_kop
    # Комиссия — только с доставки: подача и зимняя дорога это бензин курьера (см.
    # courier_commission_base_kop). Взять с них процент значило бы заработать на его топливе.
    commission_kop = courier_commission_kop(доставка_kop, percent)
    return {
        "price_kop": price_kop,
        "commission_kop": commission_kop,
        "distance_km": distance_km,
        "zone": zone,
        # Из чего сложилась цена — теми же строками, что у такси: доставка + дорога курьера
        # + зимняя дорога. Человек должен видеть слагаемые, а не одно число.
        "delivery_kop": доставка_kop,
        "pickup_kop": pickup_kop,
        "pickup_pending": pickup_pending,
        "pickup_max_kop": pickup_max_kop,
        "weather_kop": weather_kop,
        "weather_kind": weather_kind,
        "night_k": night_k,
        "breakdown": {
            "base_kop": base_kop,
            "distance_kop": distance_kop,
            "size_kop": size_kop,
            "urgency_kop": urgency_kop,
            "per_km_kop": per_km,
            "zone": zone,
            "delivery_kop": доставка_kop,
            "pickup_kop": pickup_kop,
            "pickup_pending": pickup_pending,
            "pickup_max_kop": pickup_max_kop,
            "weather_kop": weather_kop,
            "weather_kind": weather_kind,
            "night_k": night_k,
            "commission_percent": percent,
            # C4 — аддитивные поля (старый клиент игнорирует):
            "commission_min_kop": int(settings.courier_commission_min_kop),  # пол комиссии
            "commission_estimated": True,   # ориентировочно; финал считается при вручении
            # «Если получателя не будет» — ОЦЕНКА возврата, показываемая ДО заказа.
            # Это не украшение. Конституционный суд (декабрь 2022, дело о возвратных
            # отправлениях) признал недопустимым брать плату за возврат с человека, которого
            # о ней заранее не предупредили. Без этой строки на экране заказа наша
            # компенсация юридически висит в воздухе.
            #
            # Считаем по тем же правилам, что и настоящую компенсацию, но без того, чего
            # при заказе ещё не существует: попыток не было, ожидание не тикало, повторных
            # заездов никто не просил. Остаются километры маршрута и дорога курьера за
            # коробкой — и тот же потолок «не дороже самой доставки».
            "return_fee_estimate_kop": min(distance_kop + pickup_kop, price_kop),
        },
    }


# ---------------------------------------------------------------------------
# Сериализация
# ---------------------------------------------------------------------------
def _application_payload(app: CourierApplication) -> dict:
    return {
        "id": app.id,
        "transport": app.transport,
        "status": app.status,
        "selfie_url": app.selfie_url or "",
        "full_name": getattr(app, "full_name", "") or "",
        "car_plate": getattr(app, "car_plate", "") or "",
        "rules_accepted": bool(getattr(app, "rules_accepted", False)),
        "invited_by": app.invited_by,
        "reject_reason": app.reject_reason or "",
        "created_at": app.created_at.isoformat() if app.created_at else None,
        "reviewed_at": app.reviewed_at.isoformat() if app.reviewed_at else None,
    }


def _profile_payload(p: Optional[CourierProfile]) -> Optional[dict]:
    if not p:
        return None
    return {
        "id": p.id,
        "online": p.online,
        "car_class": p.car_class,
        "zone": p.zone,
        "work_city": p.work_city or "",
        "work_district": p.work_district,
        "work_intercity": bool(p.work_intercity),
        "work_regions": bool(p.work_regions),
        "work_direction_id": p.work_direction_id,
        "paused_until": p.paused_until.isoformat() if p.paused_until else None,   # C3: мягкая пауза
        "updated_at": p.updated_at.isoformat() if p.updated_at else None,
    }


# ---------------------------------------------------------------------------
# Онбординг курьера
# ---------------------------------------------------------------------------
class CourierApplyIn(BaseModel):
    transport: str = Field("car", max_length=16)
    selfie_url: str = Field("", max_length=500)
    # Кто и на чём везёт (аудит 2026-07-26). Необязательные в схеме, но проверяются ниже:
    # у старого приложения этих полей нет, и жёсткий 422 выбил бы всех, кто ещё не обновился.
    full_name: str = Field("", max_length=120)
    car_plate: str = Field("", max_length=16)
    rules_accepted: bool = False


@router.post("/courier/apply")
def courier_apply(body: CourierApplyIn, user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    """Подать заявку «Стать курьером». transport ∈ {car,cargo}, селфи обязательно (проверка L1).
    invited_by берём из User.referred_by (доверие «между своими»). Одна активная заявка на юзера:
    pending/approved → 409; после reject подача обновляет строку (status → pending)."""
    transport = (body.transport or "").strip()
    if transport not in _TRANSPORTS:
        raise herr(422, "Выбери тип транспорта", "Транспорт төрөн һайла")
    selfie = (body.selfie_url or "").strip()
    if not selfie:
        raise herr(422, "Пришли селфи с документом", "Документ менән селфи ебәр")
    # Селфи принимаем ТОЛЬКО как ссылку на свой файл, загруженный через /upload/photo
    # (та же проверка, что у водителя и таксиста). Раньше строка бралась как есть, и это
    # был не «мусор в базе», а угон админа: модерация показывает селфи через Coil
    # с заголовком `Authorization: Bearer <токен админа>`, поэтому ссылка вида
    # `https://чужой-сервер/x.jpg` в заявке отправляла токен администратора этому серверу
    # ровно в тот момент, когда админ открывал очередь заявок (аудит 2026-08-08).
    selfie = _ensure_owned_doc_url(selfie, user, None)
    full_name = (body.full_name or "").strip()[:120]
    car_plate = (body.car_plate or "").strip().upper()[:16]
    # Мы доверяем курьеру чужую посылку — знать о нём хотя бы столько же, сколько о попутчике,
    # это минимум приличия. Но ЖЁСТКОЕ требование включается флагом: старое приложение этих
    # полей не шлёт, и включённая проверка мгновенно закрыла бы регистрацию новых курьеров
    # (тот же приём, что с предрейсовым подтверждением). Включать после выката приложения.
    if settings.courier_identity_required:
        if not full_name or len(full_name.split()) < 2:
            raise herr(422, "Укажи фамилию и имя как в документе",
                       "Документтағыса фамилия һәм исемде яҙ")
        if not car_plate:
            raise herr(422, "Укажи госномер машины — по нему тебя узнают",
                       "Машинаның дәүләт номерын яҙ — уның буйынса һине таныйҙар")
        if not body.rules_accepted:
            raise herr(422, "Нужно согласиться с правилами доставки",
                       "Илтеү ҡағиҙәләре менән килешергә кәрәк")
    elif full_name and len(full_name.split()) < 2:
        # Прислали, но одним словом — это опечатка, а не «старый клиент»: скажем сразу.
        raise herr(422, "Укажи фамилию и имя как в документе",
                   "Документтағыса фамилия һәм исемде яҙ")
    app = _my_application(session, user.id)
    if app and app.status in ("pending", "approved"):
        raise herr(409, "Заявка уже на рассмотрении", "Ғариза ҡаралыуҙа инде")
    if app is None:
        app = CourierApplication(user_id=user.id)
    app.transport = transport
    app.selfie_url = selfie
    app.full_name = full_name
    app.car_plate = car_plate
    app.rules_accepted = bool(body.rules_accepted)
    app.rules_accepted_at = utcnow() if body.rules_accepted else None
    app.invited_by = user.referred_by
    app.status = "pending"
    app.reject_reason = ""
    app.reviewed_at = None
    app.created_at = utcnow()
    session.add(app)
    session.commit()
    session.refresh(app)
    try:  # уведомление админа — best-effort, без ПДн
        notify_admin_telegram(
            f"📦 Новая заявка курьера\nID: {app.id}\nТранспорт: {app.transport}\n"
            f"От: {user.name or 'курьер'}"
        )
    except Exception:
        pass
    return _application_payload(app)


@router.get("/courier/application")
def courier_application(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Моя заявка курьера (или {application: null}, если не подавал)."""
    app = _my_application(session, user.id)
    return {"application": _application_payload(app) if app else None}


# ---------------------------------------------------------------------------
# Админ: заявки курьеров
# ---------------------------------------------------------------------------
def _require_admin(user: User) -> None:
    if user.role != UserRole.admin:
        raise herr(403, "Только для админа", "Тик администратор өсөн")


@router.get("/admin/courier-applications")
def admin_courier_applications(status: str = "pending", user: User = Depends(current_user),
                               session: Session = Depends(get_session)):
    """Очередь заявок курьеров для модерации. status=pending|approved|rejected|all."""
    _require_admin(user)
    q = select(CourierApplication)
    if status != "all":
        if status not in ("pending", "approved", "rejected"):
            raise HTTPException(400, "status: pending|approved|rejected|all")
        q = q.where(CourierApplication.status == status)
    apps = session.exec(q.order_by(CourierApplication.id.desc())).all()
    if not apps:
        return []
    ids = {a.user_id for a in apps}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(ids))).all()}
    ref_ids = {a.invited_by for a in apps if a.invited_by}
    refs = {u.id: u for u in session.exec(select(User).where(User.id.in_(ref_ids))).all()} if ref_ids else {}
    out = []
    for a in apps:
        u = users.get(a.user_id)
        ref = refs.get(a.invited_by) if a.invited_by else None
        out.append({
            **_application_payload(a),
            "user_id": a.user_id,
            "name": (u.name if u and u.name else "Курьер"),
            "phone": (u.phone if u else ""),
            "invited_by_name": (ref.name if ref and ref.name else None),
        })
    return out


def _get_app_or_404(session: Session, app_id: int) -> CourierApplication:
    app = session.get(CourierApplication, app_id)
    if not app:
        raise herr(404, "Заявка не найдена", "Заявка табылманы")
    return app


@router.post("/admin/courier-applications/{app_id}/approve")
def admin_approve_courier(app_id: int, user: User = Depends(current_user),
                          session: Session = Depends(get_session)):
    """Одобрить заявку → создаём/активируем CourierProfile (car_class = transport заявки). Push курьеру."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = "approved"
    app.reject_reason = ""
    app.reviewed_at = utcnow()
    session.add(app)
    prof = _my_profile(session, app.user_id)
    if prof is None:
        prof = CourierProfile(user_id=app.user_id)
    prof.car_class = app.transport
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    try:
        push_notification(
            session, app.user_id, "system",
            "Ты курьер Юлдаша! 📦", "Һин Юлдаш курьеры! 📦",
            "Заявка одобрена — выходи на линию.", "Ғариза хупланды — линияға сыҡ.",
            ref_kind="courier_apply", ref_id=app.id,
        )
    except Exception:
        pass
    admin_action(user.id, "courier.approve", app_id=app.id, target_user=app.user_id)
    return {"id": app.id, "status": app.status}


class CourierRejectIn(BaseModel):
    reason: str = Field("", max_length=500)


@router.post("/admin/courier-applications/{app_id}/reject")
def admin_reject_courier(app_id: int, body: CourierRejectIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Отклонить заявку (с причиной — курьер увидит и сможет подать снова)."""
    _require_admin(user)
    app = _get_app_or_404(session, app_id)
    app.status = "rejected"
    app.reject_reason = body.reason.strip()
    app.reviewed_at = utcnow()
    session.add(app)
    session.commit()
    try:
        push_notification(
            session, app.user_id, "system",
            "Заявка курьера отклонена", "Курьер ғаризаһы кире ҡағылды",
            (app.reject_reason or "Поправь и подай снова."),
            (app.reject_reason or "Төҙәт тә яңынан ебәр."),
            ref_kind="courier_apply", ref_id=app.id,
        )
    except Exception:
        pass
    admin_action(user.id, "courier.reject", app_id=app.id, target_user=app.user_id)
    return {"id": app.id, "status": app.status}


# ---------------------------------------------------------------------------
# Курьер на линии
# ---------------------------------------------------------------------------
class CourierOnlineIn(BaseModel):
    """База зоны + два согласия — как у таксиста (`instant.ZoneIn`). Старые значения
    zone=intercity/region принимаем: сервер переведёт их в базу + тумблеры."""
    zone: str = Field("city", max_length=16)
    work_city: str = Field("", max_length=80)
    work_district: Optional[str] = Field(None, max_length=80)
    work_intercity: Optional[bool] = None
    work_regions: Optional[bool] = None
    work_direction_id: Optional[int] = None

    # Имена полей у водителя и курьера исторически разные (work_zone vs zone) — приводим
    # к общему виду, чтобы нормализация зоны была ОДНА на проект, а не две расходящиеся копии.
    @property
    def work_zone(self) -> str:
        return (self.zone or "").strip()


@router.post("/courier/online")
def courier_online(body: CourierOnlineIn, user: User = Depends(current_user),
                   session: Session = Depends(get_session)):
    """Выйти на линию (гейт курьера). Зона: база (свой НП или свой район) + тумблеры
    «выезд загород» и «соседние регионы» — те же правила, что у таксиста."""
    _guard_courier(user, session)
    # Пауза «Справедливости» (§2). У таксиста она стоит на выходе на линию с самого начала
    # (`_guard_taxi_driver`), у курьера её тут не было: отстранённый разбором спокойно выходил
    # «на линию», висел там активным и получал рассылку о новых доставках. Взять заказ он бы
    # не смог — приём закрыт, — но само правило «наказанный не работает» держалось на честном
    # слове (проверено запросом: 200; аудит 2026-08-13, волна 61).
    ensure_active(session, user.id)
    if (body.zone or "").strip() not in _ZONES:
        raise herr(422, "Выбери зону работы", "Эш зонаһын һайла")
    prof = _my_profile(session, user.id)
    _guard_not_paused(prof)   # C3: на мягкой паузе по качеству на линию не выходим
    # Разбор тяжёлой жалобы — на линию не выходим тоже (волна 214). Ставим здесь, а не только
    # на приёме заказа: соседняя пауза стоит на обеих дверях, и человеку честнее узнать сразу,
    # а не после того, как он весь вечер прождал заказ, который ему всё равно не дадут.
    from .. import quality as quality_mod
    quality_mod.guard_not_under_severe_review(session, user.id)
    _guard_courier_debt(session, user.id)   # неоплаченная комиссия ≥ порога → сначала рассчитайся
    if prof is None:
        prof = CourierProfile(user_id=user.id)
    base, city, district, intercity, regions, direction_id = instant_mod.normalize_zone(body)
    if district and not geo_mod.district_exists(session, district):
        raise herr(422, "Такого района нет в справочнике. Выбери район из подсказок.",
                   "Бындай район белешмәлә юҡ. Районды тәҡдимдәрҙән һайла.")
    prof.online = True
    prof.zone = base
    prof.work_city = city or ""
    prof.work_district = district
    prof.work_intercity = intercity
    prof.work_regions = regions
    prof.work_direction_id = direction_id
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    session.refresh(prof)
    return _profile_payload(prof)


@router.post("/courier/offline")
def courier_offline(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Уйти с линии."""
    _guard_courier(user, session)
    prof = _my_profile(session, user.id)
    if prof is None:
        prof = CourierProfile(user_id=user.id)
    prof.online = False
    prof.updated_at = utcnow()
    session.add(prof)
    session.commit()
    session.refresh(prof)
    return _profile_payload(prof)


def _order_matches_zone(
    session: Session, p: ParcelDelivery, prof: CourierProfile,
    area_cache: dict | None = None,
) -> bool:
    """Подходит ли заказ под зону курьера. Правила общие с такси (`geo.zone_allows`):
    база — свой НП или свой район, выезд за неё — по тумблерам «загород»/«соседние регионы».

    Города посылки лежат текстом, поэтому сначала переводим их в справочник: «Берёзовка
    (Иглинский р-н)» → нужная из четырёх. Не узнали — не режем (fail-open).

    `area_cache` — словарь «название города → место», один на весь список заказов. Перевод
    названия в справочник ходит в базу, а городов в районе десяток на сотни посылок: без
    словаря один и тот же «Сибай» искали бы заново на каждую строку ленты."""
    def место(name: str):
        имя = name or ""
        if area_cache is None:
            return geo_mod.area_by_name(session, имя)
        ключ = имя.strip().casefold()
        if ключ not in area_cache:
            area_cache[ключ] = geo_mod.area_by_name(session, имя)
        return area_cache[ключ]

    return geo_mod.zone_allows(
        session,
        zone=prof.zone, work_city=prof.work_city, work_district=prof.work_district,
        intercity=bool(prof.work_intercity), regions=bool(prof.work_regions),
        direction_id=prof.work_direction_id,
        a=место(p.from_city),
        b=место(p.to_city),
        local_km=settings.instant_intercity_km,   # общий порог «местная поездка / межгород»
    )


@router.get("/courier/available")
def courier_available(from_city: Optional[str] = None, to_city: Optional[str] = None,
                      user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Доступные курьер-заказы (status=created, delivery_type ∈ courier|buy_bring, НЕ мои),
    отфильтрованные по зоне курьера. Приватность: БЕЗ телефона получателя (как M3). Гейт курьера."""
    _guard_courier(user, session)
    ensure_active(session, user.id)   # отстранённому разбором витрина заказов не нужна (волна 61)
    prof = _my_profile(session, user.id)
    _guard_not_paused(prof)   # C3: на мягкой паузе заказы не берём
    rows = session.exec(
        select(ParcelDelivery).where(
            *live_parcel_conds(),                   # протухшие в ленту не попадают (см. parcels.py)
            ParcelDelivery.sender_id != user.id,
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        ).order_by(ParcelDelivery.id.desc()).limit(FEED_MAX)   # потолок витрины (волна 89)
    ).all()
    # ⭐ Фора приоритетным (app/priority.py): свежий заказ первые секунды виден только тем,
    # кто работает много и хорошо. Никто заказа не теряет — через полминуты он у всех.
    # Задержка включается ТОЛЬКО если на линии есть приоритетный курьер: иначе это была бы
    # пустая пауза, когда заказ не виден никому, а отправитель смотрит на «ищем курьера».
    задержка = prio.visible_delay_sec(session, prio.courier_points(session, user.id)["points"])
    if задержка > 0:
        порог = utcnow() - timedelta(seconds=задержка)
        rows = [p for p in rows if (p.created_at or порог) <= порог]
    if from_city:
        fc = from_city.strip().casefold()
        rows = [p for p in rows if (p.from_city or "").strip().casefold() == fc]
    if to_city:
        tc = to_city.strip().casefold()
        rows = [p for p in rows if (p.to_city or "").strip().casefold() == tc]
    if prof is not None:
        # Один словарь городов на всю ленту: «Сибай» переводится в справочник один раз,
        # а не на каждую посылку. Пересчитывать его между запросами нельзя — справочник
        # общий, но живёт он ровно на время этого ответа.
        area_cache: dict = {}
        rows = [p for p in rows if _order_matches_zone(session, p, prof, area_cache)]
    # Заблокированных не показываем — как в обычной ленте посылок. Раньше этой витрине про
    # блокировку не сказали: курьер видел заказ человека, который его заблокировал, жал
    # «Взять» и получал 403 без объяснения (аудит 2026-08-08, волна 73).
    rows = visible_parcels(rows, user, session)
    # Комиссию в списке пересчитываем под СТАЖ ЭТОГО курьера. В заказе она сохранена по дефолтной
    # ступени (8%) — при создании курьер ещё не назначен. Курьеру на промо/tier1 показывался чужой,
    # заниженный доход: в кабинете «ты платишь 3%», а на карточке заказа вычиталось 8%. Финальная
    # комиссия по-прежнему считается при вручении (finalize_commission_kop) — здесь только витрина,
    # сохранённое значение не трогаем. Процент берём один раз на тип, а не на каждую заявку.
    now = utcnow()
    pct_by_type = {t: courier_commission_percent(session, user.id, t, now)[0] for t in _COURIER_TYPES}
    out = []
    for p in rows:
        item = parcels_mod._parcel_available(p)   # без телефона получателя
        dtype = (getattr(p, "delivery_type", "courier") or "courier")
        if dtype in pct_by_type:
            item["commission_kop"] = courier_commission_kop(
                int(getattr(p, "delivery_price_kop", 0) or 0), pct_by_type[dtype])
            item["commission_estimated"] = True   # финал — при вручении
        out.append(item)
    return out


# ---------------------------------------------------------------------------
# Оценка цены (auth, для UI до заказа)
# ---------------------------------------------------------------------------
@router.get("/courier/estimate")
def courier_estimate(from_lat: float, from_lng: float, to_lat: float, to_lng: float,
                     size: str = "small", urgency: str = "bypath",
                     delivery_type: str = "courier", user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Оценка цены доставки курьером. Сервер — источник истины (haversine × тариф). Без суржа.
    Комиссия — ОРИЕНТИРОВОЧНАЯ: по дефолтной ступени (8%) + минимум + надбавка buy_bring; точный
    процент зависит от стажа НАЗНАЧЕННОГО курьера и считается при вручении."""
    # Тот же персональный потолок, что у оценки такси: ручка дешёвая для нас сегодня, но стоит
    # рядом с /instant/estimate в клиенте и в лимитере — держим их правила одинаковыми.
    from .instant import guard_estimate_budget
    guard_estimate_budget(user.id)
    if size not in _SIZES:
        raise herr(422, "Выбери размер посылки", "Бандероль үлсәмен һайла")
    if urgency not in _URGENCIES:
        raise herr(422, "Некорректная срочность", "Ялған ашығыслыҡ")
    dtype = (delivery_type or "courier").strip()
    if dtype not in _COURIER_TYPES:
        raise herr(422, "Выбери тип доставки", "Доставка төрөн һайла")
    percent = settings.courier_service_fee_percent + (
        COURIER_BUY_BRING_EXTRA_PERCENT if dtype == "buy_bring" else 0.0)
    priced = _price(from_lat, from_lng, to_lat, to_lng, size, urgency, percent=percent,
                    session=session)
    # Цену закрепляем на те же две минуты, что у такси: пока человек думает, она не вырастет.
    подпись = f"{size}|{urgency}|{dtype}"
    priced["price_locked_sec"] = price_freeze.remember_courier(
        isv._redis(), user.id, (from_lat, from_lng), (to_lat, to_lng), подпись, priced)
    # Воронка «посмотрел цену → заказал» — своя для доставки: у неё другие люди и другие
    # причины передумать, и усреднять её с такси значит получить число ни о чём.
    funnel.note_price_view(isv._redis(), user.id, (from_lat, from_lng), (to_lat, to_lng),
                           kind=funnel.COURIER)
    return priced


# ---------------------------------------------------------------------------
# Заказ курьера
# ---------------------------------------------------------------------------
class CourierOrderIn(BaseModel):
    from_city: str = Field("", max_length=80)
    to_city: str = Field("", max_length=80)
    # «Куда именно» — дом/квартира/ориентир (как в ParcelIn). В платном курьере это нужнее, чем
    # в попутке: курьер едет на конкретный адрес за деньги, а не «по пути в Баймак».
    from_address: str = Field("", max_length=200)
    to_address: str = Field("", max_length=200)
    # Границы координат (как в instant): без них серверная цена (haversine) считалась от чего угодно.
    from_lat: Optional[float] = Field(None, ge=-90, le=90)
    from_lng: Optional[float] = Field(None, ge=-180, le=180)
    to_lat: Optional[float] = Field(None, ge=-90, le=90)
    to_lng: Optional[float] = Field(None, ge=-180, le=180)
    size: str = Field("small", max_length=16)
    description: str = Field("", max_length=2000)
    receiver_name: str = Field("", max_length=120)
    receiver_phone: str = Field("", max_length=40)
    rules_accepted: bool = False
    delivery_type: str = Field("courier", max_length=16)   # courier | buy_bring
    urgency: str = Field("bypath", max_length=16)
    declared_value_kop: int = Field(0, ge=0, le=100_000_00)   # объявленная ценность ≤ 100 000 ₽
    cod_amount_kop: int = Field(0, ge=0, le=100_000_00)       # buy_bring: наложка ≤ 100 000 ₽
    shopping_list: str = Field("", max_length=2000)        # buy_bring: что купить (уходит в description)
    # СРОК: «нужно доставить не позже этого дня» (ГГГГ-ММ-ДД). Не прислали → «когда получится».
    # Окно и текст ошибки — общие с посылкой «по пути» (parcels.validate_deliver_by).
    deliver_by: Optional[date] = None
    # Что везём — те же поля, что у посылки «по пути» (один язык на оба режима доставки).
    weight_kg: float = Field(0.0, ge=0, le=100)
    cargo_type: str = Field("", max_length=16)
    fragile: bool = False

    @field_validator("deliver_by", mode="before")
    @classmethod
    def _blank_deliver_by(cls, v):
        return parcels_mod.blank_date_to_none(v)


@router.post("/courier/orders")
def courier_order_create(body: CourierOrderIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Создать заказ курьера (courier|buy_bring). Цена — с сервера (commission фиксируется).
    Для buy_bring cod_amount_kop обязателен и ≤ COURIER_COD_CAP_KOP (защита курьера).
    Возвращает заявку + confirm_code (как M3): отправитель передаёт код получателю."""
    _guard_courier_enabled()   # заказать курьера можно только когда режим включён (заказчик — не курьер)
    # Пауза «Справедливости» (§2) распространяется и на доставку: отстранённый за нарушения
    # не заводит новые заказы. Раньше проверки не было — пауза была декорацией (аудит 2026-07-26).
    ensure_active(session, user.id)
    dtype = (body.delivery_type or "").strip()
    if dtype not in _COURIER_TYPES:
        raise herr(422, "Выбери тип доставки", "Доставка төрөн һайла")
    from_city = body.from_city.strip()
    to_city = body.to_city.strip()
    receiver_name = body.receiver_name.strip()
    if not from_city:
        raise herr(422, "Укажи адрес отправления", "Ебәреү адресын күрһәт")
    if not to_city:
        raise herr(422, "Укажи адрес получения", "Алыу адресын күрһәт")
    if not receiver_name:
        raise herr(422, "Укажи имя получателя", "Алыусы исемен күрһәт")
    size = (body.size or "").strip()
    if size not in _SIZES:
        raise herr(422, "Выбери размер посылки", "Бандероль үлсәмен һайла")
    urgency = (body.urgency or "").strip()
    if urgency not in _URGENCIES:
        raise herr(422, "Некорректная срочность", "Ялған ашығыслыҡ")
    if not body.rules_accepted:
        raise herr(422, "Прими правила доставки", "Доставка ҡағиҙәләрен ҡабул ит")
    # Срок «к какому дню нужно» — та же функция, что у посылки «по пути»: одно окно, один текст
    # ошибки. Отдельная копия проверки разъехалась бы при первой же правке (уроки, «один шов»).
    deliver_by = parcels_mod.validate_deliver_by(body.deliver_by)
    cargo_type = parcels_mod.validate_cargo_type(body.cargo_type)

    cod_amount_kop = 0
    description = body.description.strip()
    if dtype == "buy_bring":
        cod_amount_kop = int(body.cod_amount_kop or 0)
        if cod_amount_kop <= 0:
            raise herr(422, "Укажи стоимость покупки", "Һатып алыу хаҡын күрһәт")
        if cod_amount_kop > COURIER_COD_CAP_KOP:
            raise herr(422, f"Сумма покупки слишком большая (лимит {COURIER_COD_CAP_KOP // 100} ₽)",
                       f"Һатып алыу суммаһы бик ҙур (сик {COURIER_COD_CAP_KOP // 100} һ)")
        shopping = body.shopping_list.strip()
        if shopping:  # список покупок кладём в описание (курьер видит, что купить)
            description = (shopping + ("\n" + description if description else "")).strip()

    # То же правило, что у посылки «по пути»: открытое поле смотрим ДО того, как его увидит
    # лента курьеров. Здесь мотив увести сделку мимо приложения даже сильнее — в платном режиме
    # берётся комиссия, и «звони на другой номер» уносит вместе с деньгами SOS, чек и разбор
    # спора. Проверка стояла только у попутки, курьерский заказ её не проходил вообще
    # (аудит 2026-08-07). Текст не режем и заказ не роняем: метка копится, решает человек.
    # Проверяем уже собранное описание — вместе со списком покупок, это одно открытое поле.
    # И имя получателя: оно уходит в ленту курьеров тем же `_parcel_base`, что и описание,
    # а проверки на нём не было — то же место, где дыра нашлась у посылки (аудит 2026-08-08).
    moderate_open_text("\n".join(p for p in (description, receiver_name) if p), user.id)

    # Комиссия при создании — ОЦЕНКА (курьер ещё не назначен, лесенка зависит от ЕГО стажа):
    # дефолтная ступень (8%) + надбавка buy_bring + минимум. Финал пересчитается при вручении.
    est_percent = settings.courier_service_fee_percent + (
        COURIER_BUY_BRING_EXTRA_PERCENT if dtype == "buy_bring" else 0.0)
    priced = _price(body.from_lat, body.from_lng, body.to_lat, body.to_lng, size, urgency,
                    percent=est_percent, session=session)
    # Заморозка: человек видел цену минуту назад — по ней и везём. Подешевело — берём новую.
    priced = price_freeze.apply_courier(
        isv._redis(), user.id, (body.from_lat, body.from_lng), (body.to_lat, body.to_lng),
        f"{size}|{urgency}|{dtype}", priced)

    # Дедуп двойного тапа — тот же шов, что у посылки «по пути» (разбор №2). Заказ курьера
    # дороже обычной посылки (комиссия + выкуп товара), поэтому дубль тут стоит реальных денег.
    # Ключ и окно берём из parcels_mod, чтобы правило было ОДНО: две копии разъедутся при
    # первой правке (см. lessons.md про «один шов»).
    session.exec(select(User).where(User.id == user.id).with_for_update()).first()
    twin = session.exec(
        select(ParcelDelivery).where(
            ParcelDelivery.sender_id == user.id,
            ParcelDelivery.from_city == from_city,
            ParcelDelivery.to_city == to_city,
            ParcelDelivery.receiver_phone == body.receiver_phone.strip(),
            ParcelDelivery.size == size,
            ParcelDelivery.delivery_type == dtype,
            ParcelDelivery.from_address == body.from_address.strip(),
            ParcelDelivery.to_address == body.to_address.strip(),
            ParcelDelivery.description == description,
            ParcelDelivery.deliver_by == deliver_by,
            ParcelDelivery.declared_value_kop == int(body.declared_value_kop or 0),
            ParcelDelivery.cod_amount_kop == cod_amount_kop,
            ParcelDelivery.weight_kg == float(body.weight_kg or 0.0),
            ParcelDelivery.cargo_type == cargo_type,
            ParcelDelivery.fragile == bool(body.fragile),
            # Срочность и цена — В КЛЮЧЕ: от них зависит, когда поедут и сколько возьмут.
            # Без них человек, передумавший с «по пути» на «срочно», молча получал бы обратно
            # свой первый — дешёвый и медленный — заказ и был уверен, что заказал срочно.
            ParcelDelivery.urgency == urgency,
            ParcelDelivery.delivery_price_kop == priced["price_kop"],
            ParcelDelivery.receiver_name == receiver_name,
            ParcelDelivery.status == "created",
            ParcelDelivery.created_at >= utcnow() - timedelta(seconds=parcels_mod._DUPLICATE_WINDOW_SEC),
        ).order_by(ParcelDelivery.id.desc())
    ).first()
    if twin is not None:
        return parcels_mod._parcel_for_sender(twin, session)

    parcel = ParcelDelivery(
        sender_id=user.id,
        from_city=from_city,
        to_city=to_city,
        # «Куда именно»: дом/квартира/ориентир. Опциональны (город остаётся минимумом), приватность
        # общая с посылками — в открытом списке заказов их нет, открываются принявшему курьеру.
        from_address=body.from_address.strip(),
        to_address=body.to_address.strip(),
        from_lat=body.from_lat,
        from_lng=body.from_lng,
        to_lat=body.to_lat,
        to_lng=body.to_lng,
        size=size,
        description=description,
        receiver_name=receiver_name,
        receiver_phone=body.receiver_phone.strip(),
        # fee_kop = комиссия платформы (доход, «на доверии») — попадает в /admin/parcels statement.
        fee_kop=priced["commission_kop"],
        commission_kop=priced["commission_kop"],
        # C2: цена доставки для получателя (без комиссии) — фиксируем при создании.
        delivery_price_kop=priced["price_kop"],
        # Дорога курьера к посылке: курьера ещё нет, честного числа не существует —
        # отправителю показан потолок, сумму зафиксируем при взятии заказа.
        pickup_pending=bool(priced.get("pickup_pending")),
        weather_fee_kop=int(priced.get("weather_kop", 0) or 0),
        weather_kind=str(priced.get("weather_kind", ""))[:16],
        night_k=float(priced.get("night_k", 1.0) or 1.0),
        # Длина маршрута нужна не цене (она уже посчитана), а возврату: если получателя
        # не окажется дома, курьеру компенсируют именно километры, которые он проехал.
        distance_km=float(priced.get("distance_km", 0.0) or 0.0),
        declared_value_kop=int(body.declared_value_kop or 0),
        cod_amount_kop=cod_amount_kop,
        delivery_type=dtype,
        urgency=urgency,
        # «Нужно не позже этого дня» (None = не срочно, когда получится).
        deliver_by=deliver_by,
        weight_kg=float(body.weight_kg or 0.0),
        cargo_type=cargo_type,
        fragile=bool(body.fragile),
        status="created",
        confirm_code=parcels_mod._gen_code(session),
    )
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    # Вторая половина воронки доставки (см. app/funnel.py).
    funnel.note_order(isv._redis(), user.id, kind=funnel.COURIER)
    try:  # уведомление админа — best-effort, без телефона получателя
        notify_admin_telegram(
            f"📦 Новый заказ курьера ({dtype})\nID: {parcel.id}\n"
            f"Маршрут: {parcel.from_city} → {parcel.to_city}\n"
            f"Цена: {priced['price_kop'] // 100} ₽ · комиссия {priced['commission_kop'] // 100} ₽"
        )
    except Exception:
        pass
    # Пуш курьерам на линии: раньше заявка висела в пустоте, пока кто-то сам не откроет список
    # и не обновит его — три курьера ехали мимо и не знали о ней (аудит 2026-07-26).
    parcels_mod._notify_couriers_new_parcel(session, parcel)
    out = parcels_mod._parcel_for_sender(parcel, session)
    out["price_kop"] = priced["price_kop"]       # полная цена доставки (курьеру платят напрямую)
    out["breakdown"] = priced["breakdown"]
    return out


# ---------------------------------------------------------------------------
# «Купи и привези»: фактическая стоимость товара (расчёт с получателем)
# ---------------------------------------------------------------------------
class GoodsCostIn(BaseModel):
    actual_kop: int = 0   # сколько курьер реально потратил на товар в магазине


@router.post("/courier/orders/{order_id}/goods-cost")
def courier_goods_cost(order_id: int, body: GoodsCostIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session)):
    """Курьер вводит ФАКТИЧЕСКУЮ стоимость купленного товара (buy_bring). Получатель вернёт
    именно эту сумму + доставку. Только назначенный курьер (courier_id==me, иначе 404 — IDOR закрыт),
    только buy_bring (иначе 409), только до вручения (иначе 409). Сумма > 0 и ≤ потолок (иначе 422).
    Возвращает блок settlement — «к оплате получателем» (товар + доставка = итого)."""
    parcel = session.get(ParcelDelivery, order_id)
    if not parcel or parcel.courier_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") != "buy_bring":
        raise herr(409, "Только для «купи и привези»", "Тик «һатып ал да килтер» өсөн")
    # Список терминальных статусов — общий (`parcels._FINAL_STATUSES`), а не свой.
    # Здесь он отстал и не включал `returned`: курьер мог править стоимость товара,
    # уже начав возврат (независимая проверка аудита 2026-08-07). Денег это не двигает
    # (при возврате получатель ничего не платит), но два списка одного и того же
    # неизбежно разъезжаются — поэтому список один.
    if parcel.status in _FINAL_STATUSES:
        raise herr(409, "Заказ уже завершён", "Заказ инде тамамланған")
    actual_kop = int(body.actual_kop or 0)
    if actual_kop <= 0:
        raise herr(422, "Укажи стоимость покупки", "Һатып алыу хаҡын күрһәт")
    if actual_kop > COURIER_COD_CAP_KOP:
        raise herr(422, f"Сумма покупки слишком большая (лимит {COURIER_COD_CAP_KOP // 100} ₽)",
                   f"Һатып алыу суммаһы бик ҙур (сик {COURIER_COD_CAP_KOP // 100} һ)")
    # Главный потолок — не общий лимит, а то, на что согласился ЗАКАЗЧИК (волна 157). Запас
    # на разницу цен в магазине есть, но пятикратный счёт получателю выставить нельзя: он стоит
    # в дверях с пакетом и решает за секунду. Больше запаса — пусть заказчик поднимет сумму сам
    # (эндпоинт ниже), это его деньги и его решение.
    предел = goods_limit_kop(getattr(parcel, "cod_amount_kop", 0) or 0)
    if actual_kop > предел:
        raise herr(
            422,
            f"Заказчик согласился на {(parcel.cod_amount_kop or 0) // 100} ₽ "
            f"(с запасом — до {предел // 100} ₽). Свяжись с ним: он поднимет сумму в заказе",
            f"Заказ биреүсе {(parcel.cod_amount_kop or 0) // 100} һумға ризалашҡан "
            f"(запас менән — {предел // 100} һумға тиклем). Уның менән бәйләнеш: ул заказҙа "
            "сумманы арттырыр",
        )
    parcel.goods_actual_kop = actual_kop
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    return {"id": parcel.id, "settlement": parcels_mod._settlement(parcel)}


class RaiseBudgetIn(BaseModel):
    cod_amount_kop: int = 0   # новая сумма, на которую согласен заказчик


@router.post("/courier/orders/{order_id}/raise-budget")
def courier_raise_budget(order_id: int, body: RaiseBudgetIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Заказчик поднимает сумму, на которую согласен («в аптеке дороже — ладно, бери»).

    Без этой двери отказ курьеру («сумма выше согласованной») был бы тупиком: товар уже куплен,
    провести расчёт нельзя, и оба остаются в подвешенном состоянии. Поднимать может ТОЛЬКО
    заказчик и только вверх — курьер сумму своего же счёта не двигает. До вручения.
    """
    parcel = session.get(ParcelDelivery, order_id)
    if not parcel or parcel.sender_id != user.id:
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if (getattr(parcel, "delivery_type", "poputka") or "poputka") != "buy_bring":
        raise herr(409, "Только для «купи и привези»", "Тик «һатып ал да килтер» өсөн")
    if parcel.status in _FINAL_STATUSES:
        raise herr(409, "Заказ уже завершён", "Заказ инде тамамланған")
    новая = int(body.cod_amount_kop or 0)
    прежняя = int(parcel.cod_amount_kop or 0)
    if новая <= прежняя:
        raise herr(422, "Новая сумма должна быть больше прежней",
                   "Яңы сумма элеккенән ҙурыраҡ булырға тейеш")
    if новая > COURIER_COD_CAP_KOP:
        raise herr(422, f"Сумма покупки слишком большая (лимит {COURIER_COD_CAP_KOP // 100} ₽)",
                   f"Һатып алыу суммаһы бик ҙур (сик {COURIER_COD_CAP_KOP // 100} һ)")
    parcel.cod_amount_kop = новая
    session.add(parcel)
    session.commit()
    session.refresh(parcel)
    if parcel.courier_id:
        push_notification(
            session, parcel.courier_id, "parcel",
            "Заказчик поднял сумму покупки 💰", "Заказ биреүсе һатып алыу сумманы арттырҙы 💰",
            f"Теперь можно потратить до {goods_limit_kop(новая) // 100} ₽.",
            f"Хәҙер {goods_limit_kop(новая) // 100} һумға тиклем тотоу мөмкин.",
            ref_kind="parcel", ref_id=parcel.id,
        )
    return {"id": parcel.id, "cod_amount_kop": parcel.cod_amount_kop,
            "goods_limit_kop": goods_limit_kop(parcel.cod_amount_kop)}


# ---------------------------------------------------------------------------
# Кабинет курьера
# ---------------------------------------------------------------------------
def settle_courier_commission_from_wallet(session: Session, courier_id: Optional[int],
                                          now=None) -> int:
    """Погасить комиссию курьера тем, что уже лежит у него в кошельке (волна 216).

    Зеркало `debt.settle_debt_from_wallet`, которое волна 154 завела для таксиста с доводом
    «деньги платформы у водителя и долг водителя платформе гасят друг друга — так это и должно
    работать, без банковских договоров». У курьера этого не было: тот зачёт ходит по
    `CommissionDebt`, а он привязан к такси-заказу, тогда как комиссия курьера живёт флагом
    на самой доставке.

    Раньше это ничего не значило — класть курьеру в кошелёк было нечего. Волна 215 это
    изменила: туда попадает возврат уже уплаченной комиссии за доставку, по которой курьеру
    не заплатили. Получалось смешное: платформа держит его 50 ₽, вывести их нельзя (выплаты
    выключены до оформления ИП), и при этом просит перевести полные 200 ₽ комиссии.

    Правила те же, что у такси, и это не совпадение — два разных правила про одни и те же
    деньги разъедутся на первой правке:
      • по старшинству (FIFO) и ТОЛЬКО целиком: суммы на доставке не переписываем, у неё
        меняется флаг, а не цифра. Не хватило на самую старую — стоим, деньги ждут. Иначе
        свежая мелочь погасилась бы, а старая продолжила блокировать работу;
      • списание — отдельная запись fee (−сумма). Без неё долг исчезал бы, а число в кошельке
        оставалось прежним: человек читает эту историю;
      • замок на строке пользователя берём ТОТ ЖЕ, что у выплат и у такси-зачёта. Один замок
        на все денежные операции человека, иначе они разъедутся между собой;
      • условие «ещё не оплачена» живёт ВНУТРИ UPDATE: на SQLite замок — пустышка, а на нём
        живут тесты и демо-база (волна 201).
    """
    from ..ledger import driver_balance
    from ..models import LedgerEntry, LedgerKind, User
    from sqlalchemy import update as _update

    if courier_id is None:
        return 0
    session.exec(select(User).where(User.id == courier_id).with_for_update()).one_or_none()
    balance = driver_balance(session, courier_id)
    # Ранний выход ниже РАВНОСИЛЬНЫЙ: с нулевым балансом цикл всё равно упёрся бы
    # в `total + amount > balance` на первой же доставке. Он здесь ради экономии запроса
    # на горячем пути — гейт долга зовут на каждом приёме заказа. Мутация, которая его
    # снимает, поведение не меняет, и тестом её ловить нечем (разбор мутаций волны 216,
    # правило волны 208).
    if balance <= 0:
        return 0
    условия = [
        ParcelDelivery.courier_id == courier_id,
        ParcelDelivery.status == "delivered",
        ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        ParcelDelivery.commission_paid == False,              # noqa: E712
    ]
    # Доставки, которые человек ПРЯМО СЕЙЧАС оплачивает, кошельком не трогаем (волна 218).
    # Он нажал «оплатить», перевёл по СБП и ждёт Александра — а это «на доверии» и ждать
    # можно сутки. Придёт в это окно возврат в кошелёк (волна 215) — и зачёт закроет ими
    # доставку из снапшота платежа. Александр подтвердит перевод, активация пометит снапшот
    # оплаченным, и за одну и ту же комиссию человек отдаст и перевод, и деньги из кошелька.
    #
    # Граница та же, что у активации платежа: снапшот — это `delivered_at <= created_at`.
    # Доставки ПОСЛЕ снапшота в платёж не входят, их гасим как обычно: иначе висящий платёж
    # замораживал бы кошелёк целиком, пока у Александра не дойдут руки.
    в_оплате = session.exec(
        select(func.max(Payment.created_at)).where(
            Payment.user_id == courier_id,
            Payment.purpose == "courier_commission",
            Payment.status == "pending",
        )
    ).one()
    снапшот = в_оплате[0] if isinstance(в_оплате, (tuple, list)) else в_оплате
    if снапшот is not None:
        условия.append(ParcelDelivery.delivered_at > снапшот)
    rows = session.exec(
        select(ParcelDelivery).where(*условия)
        .order_by(ParcelDelivery.delivered_at, ParcelDelivery.id)
    ).all()
    total = 0
    for p in rows:
        amount = max(int(p.commission_kop or 0), 0)
        if amount == 0:
            continue
        if total + amount > balance:
            break                                  # на старейшую не хватило — дальше не идём
        закрыта = session.execute(
            _update(ParcelDelivery)
            .where(ParcelDelivery.id == p.id, ParcelDelivery.commission_paid == False)  # noqa: E712
            .values(commission_paid=True)
        )
        if закрыта.rowcount == 0:
            continue                               # закрыл параллельный проход — не списываем
        total += amount
    if total <= 0:
        return 0
    session.add(LedgerEntry(
        driver_id=courier_id, kind=LedgerKind.fee, amount_kop=-total,
        note="Комиссия за доставку удержана из кошелька",
    ))
    session.commit()
    return total


def _commission_owed_kop(session: Session, courier_id: int) -> int:
    """Комиссия платформы, которую курьер ещё НЕ оплатил: сумма commission_kop по моим
    доставленным курьер-заказам, где commission_paid=False. Единый источник для /courier/me
    и /courier/pay-commission (одна формула — не разъедутся)."""
    owed = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0)).where(
            ParcelDelivery.courier_id == courier_id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
            ParcelDelivery.commission_paid == False,   # noqa: E712
        )
    ).one()
    return int(owed or 0)


@router.get("/courier/priority")
def courier_priority(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мой приоритет курьера: сколько баллов, за что и что их отнимает.

    Показываем ЦЕЛИКОМ и всегда. Скрытый приоритет читается как «заказы раздают по блату» —
    это ровно та боль Яндекса, против которой мы и строимся: там человек не понимает,
    почему ему не падают заказы, и уходит.
    """
    _guard_courier(user, session)
    return prio.payload(session, user.id, prio.COURIER)


@router.get("/courier/me")
def courier_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Кабинет курьера: заявка + профиль + statement (комиссия платформы по моим доставленным
    курьер-заказам: всего заработали / к оплате сейчас / уже оплачено) + мой рейтинг + пауза.
    Гейт курьера (кабинет только одобренным)."""
    _guard_courier(user, session)
    app = _my_application(session, user.id)
    prof = _my_profile(session, user.id)
    earned = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0)).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        )
    ).one()
    paid = session.exec(
        select(func.coalesce(func.sum(ParcelDelivery.commission_kop), 0)).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
            ParcelDelivery.commission_paid == True,    # noqa: E712
        )
    ).one()
    delivered = session.exec(
        select(func.count()).select_from(ParcelDelivery).where(
            ParcelDelivery.courier_id == user.id,
            ParcelDelivery.status == "delivered",
            ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        )
    ).one()
    # Сначала зачёт, потом число: экран, который просит 200 ₽, пока платформа держит его
    # 50 ₽, — хуже отсутствия экрана. Тот же принцип, что у прогресса смены и осмотра.
    settle_courier_commission_from_wallet(session, user.id)
    owed = _commission_owed_kop(session, user.id)
    avg, cnt = user_rating(session, user.id)
    # C4: текущая ступень комиссии курьера (для UI — «сейчас ты платишь N%»). Тип courier (база).
    cur_percent, cur_tier = courier_commission_percent(session, user.id, "courier")
    return {
        "application": _application_payload(app) if app else None,
        "profile": _profile_payload(prof),
        "statement": {
            "delivered_count": int(delivered or 0),
            "commission_earned_kop": int(earned or 0),   # всего комиссии платформе с моих доставок
            "commission_owed_kop": owed,                  # к оплате прямо сейчас (неоплаченное)
            "commission_paid_kop": int(paid or 0),        # уже оплачено
            # Легаси-алиас (старый клиент C1 читал commission_kop = вся начисленная комиссия).
            "commission_kop": int(earned or 0),
            # C4 — текущая ступень курьера (аддитивно): какой % и минимум действуют сейчас.
            "current_fee_percent": cur_percent,
            "fee_tier": cur_tier,                         # tier1|tier2|tier3|promo
            "commission_min_kop": int(settings.courier_commission_min_kop),
        },
        "rating": {
            "avg": round(avg, 1) if cnt > 0 else None,    # None = ещё нет оценок
            "count": cnt,
        },
        "paused_until": prof.paused_until.isoformat() if (prof and prof.paused_until) else None,
    }


@router.get("/courier/earnings")
def courier_earnings(period: str = "week", user: User = Depends(current_user),
                     session: Session = Depends(get_session)):
    """Заработок курьера за период (week|month|all): всего, доставок, по дням.

    У водителя такой экран есть, у курьера не было: он видел только «должен Юлдашу столько-то»,
    и работа выглядела как один сплошной долг (аудит 2026-07-26). Считаем как у водителя —
    SQL-агрегатом по локальному дню, только СВОИ данные.

    База — цена доставки за вычетом комиссии платформы: это «чистыми», то есть ровно то,
    что человек оставляет себе. Деньги идут мимо платформы (Модель А) — мы лишь показываем счёт.
    """
    _guard_courier(user, session)
    period = period if period in ("week", "month", "all") else "week"
    conds = [
        ParcelDelivery.courier_id == user.id,
        ParcelDelivery.status == "delivered",
        ParcelDelivery.delivery_type.in_(_COURIER_TYPES),
        ParcelDelivery.delivered_at.is_not(None),
    ]
    if period != "all":
        tz = timedelta(hours=settings.local_tz_offset_hours)
        ln = utcnow() + tz
        days_back = 6 if period == "week" else 29
        start_local = datetime(ln.year, ln.month, ln.day) - timedelta(days=days_back)
        conds.append(ParcelDelivery.delivered_at >= start_local - tz)

    # В БД цена доставки — delivery_price_kop (в API она отдаётся как price_kop);
    # берём поле модели, а не имя из JSON, иначе агрегат молча считал бы не то.
    # Доставки, по которым разбор признал, что курьеру не заплатили, в заработок не идут —
    # но и не исчезают: их сумма называется отдельно (волна 191, по образцу волны 190).
    не_заплатили = debt_mod.unpaid_confirmed_parcel_ids(session, user.id)
    if не_заплатили:
        conds.append(ParcelDelivery.id.not_in(не_заплатили))
    net_expr = (func.coalesce(ParcelDelivery.delivery_price_kop, 0)
                - func.coalesce(ParcelDelivery.commission_kop, 0))
    total_row = session.exec(
        select(func.coalesce(func.sum(net_expr), 0),
               func.coalesce(func.sum(ParcelDelivery.commission_kop), 0),
               func.count()).where(*conds)
    ).one()
    day_expr = debt_mod._local_day_expr(session, ParcelDelivery.delivered_at)
    rows = session.exec(
        select(day_expr.label("day"), func.coalesce(func.sum(net_expr), 0), func.count())
        .where(*conds).group_by(day_expr).order_by(day_expr)
    ).all()
    неоплачено = (0, 0)
    if не_заплатили:
        неоплачено = session.exec(
            select(func.coalesce(func.sum(net_expr), 0), func.count()).where(
                *[c for c in conds if c is not conds[-1]],
                ParcelDelivery.id.in_(не_заплатили))
        ).one()
    return {
        "period": period,
        "net_kop": int(total_row[0] or 0),          # чистыми курьеру, копейки
        "commission_kop": int(total_row[1] or 0),   # комиссия платформы за тот же период
        "deliveries": int(total_row[2] or 0),
        "by_day": [{"date": str(r[0]), "net_kop": int(r[1] or 0), "deliveries": int(r[2] or 0)} for r in rows],
        # Разбор подтвердил, что по этим доставкам не заплатили (волна 191).
        "unpaid_net_kop": int(неоплачено[0] or 0),
        "unpaid_deliveries": int(неоплачено[1] or 0),
    }


# ---------------------------------------------------------------------------
# C3 — Рейтинг курьера: взаимная оценка доставки (после вручения)
# ---------------------------------------------------------------------------
class ParcelRateIn(BaseModel):
    stars: int = 5
    text: str = Field("", max_length=500)   # текстовый отзыв (опц.) — на модерацию, ≤500


@router.post("/parcels/{parcel_id}/rate")
def parcel_rate(parcel_id: int, body: ParcelRateIn, user: User = Depends(current_user),
                session: Session = Depends(get_session)):
    """Взаимная оценка ДОСТАВКИ после вручения. Отправитель оценивает курьера, курьер —
    отправителя (1..5 + опц. текст ≤500). Оценка анонимна (кто поставил — не раскрываем;
    наружу идёт только агрегат оценённого).

    Валидация: только участник (иначе 404, IDOR закрыт); только delivered (иначе 409);
    одна оценка на (доставка, автор) — повтор 409; stars 1..5 (иначе 422). Текст (если есть)
    появится в публичном профиле только после модерации (text_published=False)."""
    parcel = session.get(ParcelDelivery, parcel_id)
    if not parcel or user.id not in (parcel.sender_id, parcel.courier_id):
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    ratee_id = parcel.courier_id if user.id == parcel.sender_id else parcel.sender_id
    if not ratee_id:   # курьер ещё не назначен — оценивать некого (для чужого выше уже 404)
        raise herr(404, "Заказ не найден", "Заказ табылманы")
    if parcel.status != "delivered":
        raise herr(409, "Оценить можно после вручения", "Тапшырғандан һуң баһалап була")
    # Пауза лестницы и оценки (волна 161): свежую доставку оценить можно — это твой голос
    # о том, что было; по архиву за два месяца раздавать единицы нельзя. Эта дверь общий шов
    # не зовёт, поэтому правило берём оттуда же функцией, а не копией.
    guard_rating_on_pause(session, user.id, parcel.delivered_at or parcel.created_at)
    # Третья дверь к оценке — и в ней те же два пробела, что закрыли у попутки и такси
    # (волна 57): не было срока и не было модерации текста. Считаем от создания доставки:
    # своего «вручено в» у посылки нет, а доставка живёт дни, не месяцы (волна 58).
    guard_rating_window(parcel.created_at)
    stars = int(body.stars or 0)
    if stars < 1 or stars > 5:
        raise herr(422, "Оценка от 1 до 5 звёзд", "Баһа 1-ҙән 5 йондоҙға тиклем")
    existing = session.exec(
        select(Rating).where(Rating.parcel_id == parcel_id, Rating.rater_id == user.id)
    ).first()
    if existing:
        raise herr(409, "Ты уже оценил", "Һин баһаланың инде")
    text = (body.text or "").strip()[:500]
    if text:
        # Отзыв о курьере виден в его публичном профиле — то же открытое поле, что комментарий.
        # Помечаем (не режем и оценку не рвём): админ увидит телефон или грубость в очереди.
        moderate_open_text(text, user.id, place="review", ref_id=parcel_id, session=session)
    session.add(Rating(parcel_id=parcel_id, rater_id=user.id, ratee_id=ratee_id,
                       stars=stars, text=text, text_published=False))
    session.commit()
    avg, cnt = user_rating(session, ratee_id)
    # 🟡 Мягкая лестница — только тому, кого оценили ИМЕННО КАК КУРЬЕРА этой доставки,
    # и только по его курьерским оценкам (волна 186).
    #
    # Здесь дверь и была не та. Оценка взаимная: отправитель оценивает курьера, курьер —
    # отправителя. А лестница вызывалась для «кого оценили», кем бы он ни был, и смотрела
    # на общий балл человека. Заказчик с курьерским профилем получал двое суток без работы
    # за то, как он вёл себя в роли заказчика: у нас один человек часто и возит, и заказывает.
    if parcel.courier_id and ratee_id == parcel.courier_id:
        к_avg, к_cnt = courier_rating(session, ratee_id)
        _maybe_courier_soft_ladder(session, ratee_id, к_avg, к_cnt)
    return {"ratee_id": ratee_id, "rating": round(avg, 1) if cnt > 0 else 0.0, "count": cnt}


# ---------------------------------------------------------------------------
# C3 — Биллинг комиссии платформы «на доверии» (курьер декларирует оплату)
# ---------------------------------------------------------------------------
def _commission_payment_payload(p: Payment, confirmation_url: str = "") -> dict:
    """Ответ по платежу комиссии курьера. yookassa → confirmation_url (оплата картой, авто-чек);
    иначе — реквизиты СБП (перевод «на доверии», подтверждает админ)."""
    base = {"status": p.status, "payment_id": p.id, "amount_kop": p.amount_kop, "amount": p.amount_kop // 100}
    if p.method == "yookassa":
        return {**base, "method": "yookassa", "confirmation_url": confirmation_url}
    return {**base, "method": "sbp_manual",
            "payee": {"phone": settings.sbp_phone, "bank": settings.sbp_bank, "name": settings.sbp_name}}


@router.post("/courier/pay-commission")
def courier_pay_commission(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Курьер оплачивает накопленную комиссию платформе. По флажку payments_provider:
    yookassa → оплата картой (confirmation_url, авто-чек самозанятого; вебхук/поллинг гасит);
    иначе → перевод по СБП «на доверии» (админ подтверждает в /admin/payments).
    После оплаты доставки помечаются commission_paid=True. Идемпотентно: есть pending — вернём его.
    Нет комиссии к оплате (owed==0) → 409. Гейт курьера."""
    _guard_courier(user, session)
    from ..payments import fetch_payment
    from .payments import _activate_payment, _start_yookassa
    yk = settings.payments_provider == "yookassa"
    # В проде mock = «оплата» без денег → не даём гасить комиссию бесплатно.
    if settings.is_prod and settings.payments_provider == "mock":
        raise herr(503, "Оплата скоро будет доступна", "Түләү тиҙҙән асыла")
    # Идемпотентность: висящий pending — возвращаем его (yookassa: с актуальным confirmation_url).
    existing = session.exec(
        select(Payment).where(
            Payment.user_id == user.id,
            Payment.purpose == "courier_commission",
            Payment.status == "pending",
        ).order_by(Payment.id.desc())
    ).first()
    if existing:
        # Перепроверка у провайдера — ТОЛЬКО когда провайдер реально yookassa: при откате на
        # mock/sbp_manual fetch_payment честно отвечает «succeeded» (мок) → активация без денег.
        if settings.payments_provider == "yookassa" and existing.method == "yookassa" and existing.provider_id:
            try:
                info = fetch_payment(existing.provider_id)
            except Exception:
                info = None
            if info and info["status"] == "succeeded":
                _activate_payment(session, existing)
                return {"status": "succeeded", "method": "yookassa", "payment_id": existing.id}
            return _commission_payment_payload(existing, (info or {}).get("confirmation_url", ""))
        return _commission_payment_payload(existing)
    settle_courier_commission_from_wallet(session, user.id)   # его деньги идут в счёт первыми
    owed = _commission_owed_kop(session, user.id)
    if owed <= 0:
        raise herr(409, "Нет комиссии к оплате", "Түләргә комиссия юҡ")
    payment = Payment(user_id=user.id, purpose="courier_commission", amount_kop=owed,
                      method=("yookassa" if yk else "sbp"), status="pending")
    session.add(payment)
    session.commit()
    session.refresh(payment)

    if yk:
        res = _start_yookassa(session, payment, "Юлдаш · комиссия курьера", user.phone)
        payment.provider_id = res["provider_id"]
        session.add(payment)
        session.commit()
        if res["status"] == "succeeded":          # mock/dev — оплачено сразу
            _activate_payment(session, payment)
            return {"status": "succeeded", "method": "yookassa", "payment_id": payment.id}
        return _commission_payment_payload(payment, res["confirmation_url"])

    try:  # СБП «на доверии»: админу — best-effort, без ПДн
        notify_admin_telegram(
            f"💸 Курьер заявил оплату комиссии\n"
            f"Платёж ID: {payment.id}\n"
            f"Сумма: {owed // 100} ₽\n"
            f"Подтвердить: /admin/payments"
        )
    except Exception:
        pass
    return _commission_payment_payload(payment)
