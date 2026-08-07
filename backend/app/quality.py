"""Качество: жалобы + лестница наказаний (волна 2, §9 «Качество, рейтинги, наказания»).

Принципы (утверждены): честно / прозрачно / анонимно; человек в контуре (разбор у админа,
право объяснения); попутка мягче такси; SOS/безопасность — железно. Цель жалобы НИКОГДА
не видит автора: reporter_id отдаётся ТОЛЬКО админу, пуш цели — без имени/деталей.

Лестница (авто + человек):
  🟡 rating < quality_advice_rating (4.8)  → мягкий пуш-совет (дедуп: не чаще 1/нед).
  🟠 rating < matcher_low_rating (4.6)     → штраф к score в matcher (реже получает заказы).
  🔴 ≥ quality_pause_reports resolved-жалоб за quality_window_days → авто-пауза ТАКСИ
     quality_pause_hours (72ч) + пуш. Гейт как долговой: presence/offer/accept; ПОПУТКА работает.
  ⛔ Тяжёлая категория (safety_threat/kicked_out/dangerous_driving) при создании жалобы →
     немедленный Telegram админу + авто-пауза такси до разбора (reason=review).
     Разбор: /admin/reports/{id}/resolve|reject — resolve снимает/оставляет паузу (keep_pause).

Пассажирская сторона: resolved-жалобы категорий no_show/unpaid/damage → страйк пассажиру
(переиспользует механику B3: то же окно/лимит/пауза такси-заказов; попутка не затрагивается).

Право объяснения: GET /me/restrictions — цель видит свои активные ограничения (что, до когда,
«попутка работает», «напиши в поддержку») БЕЗ раскрытия автора.
"""
from datetime import datetime, timedelta
from typing import Optional

from fastapi import HTTPException
from sqlmodel import Session, select

from .config import settings
from .models import DriverProfile, Report, User
from .services import send_push
from .timeutil import utcnow

# ------------------------------ перечень категорий ------------------------------
# Закрытый список (Literal в схеме ручки). Названия RU + черновой BA (финал — за Александром).
REPORT_CATEGORIES = (
    "rude",               # нахамил
    "kicked_out",         # высадил (тяжёлая)
    "dangerous_driving",  # опасное вождение (тяжёлая)
    "price_fraud",        # обман с ценой
    "dirty_car",          # грязная машина
    "late",               # опоздал
    "safety_threat",      # угроза безопасности (тяжёлая)
    "no_show",            # не пришёл (пассажирский страйк)
    "damage",             # испортил машину (пассажирский страйк)
    "unpaid",             # не заплатил (пассажирский страйк)
    "other",              # другое (свободный текст)
)

# ⛔ Тяжёлые: мгновенный Telegram админу + авто-пауза такси до разбора.
SEVERE_CATEGORIES = frozenset({"safety_threat", "kicked_out", "dangerous_driving"})
# Пассажирские: resolved-жалоба = страйк пассажиру (механика B3, пауза такси-заказов).
PASSENGER_STRIKE_CATEGORIES = frozenset({"no_show", "unpaid", "damage"})

# Человеческие названия категорий (RU, черновой BA) — для пуша цели и админки.
CATEGORY_LABELS = {
    "rude": ("грубость", "тупаҫлыҡ"),
    "kicked_out": ("высадил в пути", "юлда төшөрөп ҡалдырған"),
    "dangerous_driving": ("опасное вождение", "хәүефле йөрөтөү"),
    "price_fraud": ("обман с ценой", "хаҡ менән алдау"),
    "dirty_car": ("грязная машина", "бысраҡ машина"),
    "late": ("опоздание", "һуңлау"),
    "safety_threat": ("угроза безопасности", "хәүефһеҙлеккә янау"),
    "no_show": ("не пришёл к машине", "машинаға килмәгән"),
    "damage": ("испортил машину", "машинаны боҙған"),
    "unpaid": ("не заплатил", "түләмәгән"),
    "other": ("другое", "башҡа"),
}

# Причины паузы такси (DriverProfile.taxi_pause_reason).
PAUSE_REASON_REPORTS = "reports"   # 🔴 накопленные resolved-жалобы (авто, 72ч)
PAUSE_REASON_REVIEW = "review"     # ⛔ тяжёлая жалоба — до разбора у админа
PAUSE_REASON_ADMIN = "admin"       # вручную админом (/admin/quality/{id}/pause)

# «Далеко» для паузы до разбора: разбор снимает/оставляет, само не истекает.
REVIEW_PAUSE_DAYS = 3650


def category_label(cat: str) -> tuple[str, str]:
    return CATEGORY_LABELS.get(cat, CATEGORY_LABELS["other"])


# ------------------------------ пауза такси (водитель) ------------------------------
def _profile(session: Session, user_id: int) -> Optional[DriverProfile]:
    return session.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()


def taxi_pause_until(session: Session, user_id: int, now: Optional[datetime] = None) -> Optional[datetime]:
    """Действующая пауза такси по качеству: datetime конца или None (можно возить)."""
    now = now or utcnow()
    prof = _profile(session, user_id)
    if prof is not None and prof.taxi_paused_until is not None and prof.taxi_paused_until > now:
        return prof.taxi_paused_until
    return None


def taxi_pause_message() -> str:
    """Тёплый текст гейта (RU + черновой BA одной строкой, detail показывается как есть)."""
    return ("Такси на паузе до разбора жалоб. Попутка работает как обычно 💚 "
            "Детали — в кабинете, вопросы — в поддержку."
            " · Такси ялыуҙарҙы тикшергәнсе паузала. Юлдаш ғәҙәттәгесә эшләй 💚 "
            "Ентеклеләр — кабинетта, һорауҙар — ярҙам хеҙмәтенә.")


def guard_taxi_quality(session: Session, driver_id: int, now: Optional[datetime] = None) -> None:
    """Гейт такси по качеству (в стиле долгового/отдыха): presence/offer/accept.
    Активный заказ НЕ рубим (переходы arrived/onboard/done через гейт не ходят)."""
    if taxi_pause_until(session, driver_id, now) is not None:
        raise HTTPException(403, taxi_pause_message())


def pause_taxi(session: Session, user_id: int, hours: Optional[int] = None,
               reason: str = PAUSE_REASON_ADMIN, now: Optional[datetime] = None) -> Optional[DriverProfile]:
    """Поставить такси на паузу (пауза только УДЛИНЯЕТСЯ — короткая не съедает длинную).
    hours=None → «до разбора» (REVIEW_PAUSE_DAYS). Нет профиля водителя → None (пассажир)."""
    now = now or utcnow()
    prof = _profile(session, user_id)
    if prof is None:
        return None
    until = now + (timedelta(hours=hours) if hours is not None else timedelta(days=REVIEW_PAUSE_DAYS))
    if prof.taxi_paused_until is None or until > prof.taxi_paused_until:
        prof.taxi_paused_until = until
        prof.taxi_pause_reason = reason
        session.add(prof)
        session.commit()
        session.refresh(prof)
    return prof


def unpause_taxi(session: Session, user_id: int) -> Optional[DriverProfile]:
    prof = _profile(session, user_id)
    if prof is None:
        return None
    if prof.taxi_paused_until is not None or prof.taxi_pause_reason is not None:
        prof.taxi_paused_until = None
        prof.taxi_pause_reason = None
        session.add(prof)
        session.commit()
        session.refresh(prof)
    return prof


# ------------------------------ жалобы: создание / разбор ------------------------------
def notify_target_new_report(session: Session, report: Report) -> None:
    """Пуш цели о жалобе — БЕЗ имени/деталей автора (анонимность = продукт).
    Только категория + спокойный тон + путь (кабинет/поддержка)."""
    ru, ba = category_label(report.category)
    send_push(
        session, report.target_user_id, "Поступила жалоба",
        f"Категория: {ru}. Мы разберёмся спокойно — детали в кабинете, "
        f"своя версия — через поддержку."
        f" · Ялыу килде: {ba}. Тыныс ҡына тикшерәбеҙ — ентеклеләр кабинетта, "
        f"үҙ һүҙең — ярҙам хеҙмәте аша.",
    )


def escalate_severe(session: Session, report: Report, reporter: User) -> None:
    """⛔ Тяжёлая категория: немедленный Telegram админу (личности видит ТОЛЬКО модератор)
    + авто-пауза такси цели до разбора (попутка работает). SOS/безопасность — железно.

    Пауза ставится ТОЛЬКО по жалобе, привязанной к общей поездке, заказу или доставке
    (аудит 2026-08-07). Раньше привязки не требовалось: жалоба без поездки берёт цель прямо
    из `target_user_id`, участие никто не проверяет — и любой вошедший одним запросом
    выключал такси любому водителю на REVIEW_PAUSE_DAYS, то есть до ручного разбора. Для
    водителя это потерянный заработок, для конкурента — кнопка «убрать соседа с линии».

    Замысел был правильный и записан в самой модели: «привязка к поездке доказывает, что
    стороны реально ехали вместе» (models.py, Report). Не хватало, чтобы авто-пауза этой
    привязки требовала. Пожаловаться на постороннего по-прежнему можно, и админ такую жалобу
    увидит — нет только автоматического наказания без доказательства встречи.
    """
    from .services import notify_admin_telegram   # локальный импорт: тесты патчат services
    if report.category not in SEVERE_CATEGORIES:
        return
    tied_to_trip = any((report.order_id, report.booking_id, report.parcel_id))
    if tied_to_trip:
        pause_taxi(session, report.target_user_id, hours=None, reason=PAUSE_REASON_REVIEW)
    target = session.get(User, report.target_user_id)
    ru, _ = category_label(report.category)
    notify_admin_telegram(
        f"⛔ Тяжёлая жалоба (Юлдаш) #{report.id}\n"
        f"Категория: {ru} ({report.category})\n"
        f"На: {(target.name if target else '')} (id {report.target_user_id}, "
        f"{target.phone if target else '—'})\n"
        f"От: {reporter.name or '—'} (id {reporter.id})\n"
        f"Детали: {report.reason or '—'}\n"
        # Честная строка вместо всегда-одинаковой: без общей поездки паузы НЕТ, и админ
        # должен это видеть сразу — иначе решит, что человек уже отстранён, и не поспешит.
        + (f"Такси цели на паузе до разбора. Разбор: /admin/reports"
           if tied_to_trip else
           f"⚠️ Жалоба БЕЗ общей поездки — паузу автоматически не ставим (иначе так можно "
           f"выключить любого водителя). Реши вручную. Разбор: /admin/reports")
    )


def resolved_report_times(session: Session, target_id: int, since: datetime,
                          categories: Optional[frozenset] = None) -> list[datetime]:
    """Метки времени resolved-жалоб на target за окно (для лестницы и страйков)."""
    rows = session.exec(
        select(Report).where(
            Report.target_user_id == target_id,
            Report.status == "resolved",
            Report.created_at >= since,
        )
    ).all()
    return [r.resolved_at or r.created_at for r in rows
            if categories is None or r.category in categories]


def apply_ladder_after_resolve(session: Session, target_id: int, now: Optional[datetime] = None) -> bool:
    """🔴 ≥ quality_pause_reports resolved-жалоб за quality_window_days → авто-пауза такси
    quality_pause_hours + пуш. Возврат: поставили ли паузу этим вызовом."""
    now = now or utcnow()
    since = now - timedelta(days=settings.quality_window_days)
    times = resolved_report_times(session, target_id, since)
    if len(times) < settings.quality_pause_reports:
        return False
    prof = pause_taxi(session, target_id, hours=settings.quality_pause_hours,
                      reason=PAUSE_REASON_REPORTS, now=now)
    if prof is None:
        return False
    h = settings.quality_pause_hours
    send_push(
        session, target_id, "Такси на паузе",
        f"За месяц накопилось несколько подтверждённых жалоб — такси на паузе {h} ч. "
        f"Попутка работает как обычно 💚 Своя версия — напиши в поддержку."
        f" · Айҙа бер нисә раҫланған ялыу йыйылды — такси {h} сәғәткә паузала. "
        f"Юлдаш ғәҙәттәгесә эшләй 💚 Үҙ һүҙең булһа — ярҙам хеҙмәтенә яҙ.",
    )
    return True


def maybe_release_review_pause(session: Session, target_id: int) -> None:
    """После reject тяжёлой жалобы: если пауза стояла «до разбора» и других открытых
    тяжёлых жалоб на цель нет — снимаем (честность: отклонили → не наказываем)."""
    prof = _profile(session, target_id)
    if prof is None or prof.taxi_pause_reason != PAUSE_REASON_REVIEW:
        return
    open_severe = session.exec(
        select(Report).where(
            Report.target_user_id == target_id,
            Report.status.in_(["new", "reviewing"]),
        )
    ).all()
    if any(r.category in SEVERE_CATEGORIES for r in open_severe):
        return
    unpause_taxi(session, target_id)


# ------------------------------ пассажирские страйки (механика B3) ------------------------------
def unpaid_tap_strike_times(session: Session, target_id: int, since: datetime) -> list[datetime]:
    """B8-7 «Пассажир не заплатил» одним тапом: страйк действует сразу (не ждём разбора),
    ПОКА жалобу не отклонил админ (rejected → страйк исчезает; честность в контуре человека).
    Считаем только unpaid-жалобы С ПРИВЯЗКОЙ к заказу/брони (стороны реально ехали вместе)
    и только в статусах new/reviewing — resolved уже считает resolved_report_times (не двоим)."""
    rows = session.exec(
        select(Report).where(
            Report.target_user_id == target_id,
            Report.category == "unpaid",
            Report.status.in_(["new", "reviewing"]),
            Report.created_at >= since,
        )
    ).all()
    return [r.created_at for r in rows if r.order_id is not None or r.booking_id is not None]


def passenger_pause_until(session: Session, passenger_id: int, now: Optional[datetime] = None) -> Optional[datetime]:
    """Пауза такси-ЗАКАЗОВ пассажира: страйки B3 (платные отмены/no-show заказов) +
    resolved-жалобы категорий no_show/unpaid/damage + свежие «не заплатил» одним тапом (B8-7).
    Общий счётчик, те же лимит/окно/длительность (strike_limit / strike_window_days /
    strike_pause_hours). Попутка работает."""
    from . import instant_service as isv   # локальный импорт: без циклов на старте
    now = now or utcnow()
    since = now - timedelta(days=settings.strike_window_days)
    times = list(isv.order_strike_times(session, passenger_id, since))
    times += resolved_report_times(session, passenger_id, since, PASSENGER_STRIKE_CATEGORIES)
    times += unpaid_tap_strike_times(session, passenger_id, since)
    if len(times) < settings.strike_limit:
        return None
    until = max(times) + timedelta(hours=settings.strike_pause_hours)
    return until if until > now else None


# ------------------------------ 🟡 мягкий совет при просевшем рейтинге ------------------------------
def maybe_low_rating_advice(session: Session, ratee_id: int, avg: float,
                            now: Optional[datetime] = None) -> None:
    """rating < quality_advice_rating → тёплый пуш-совет (без наказания).
    Дедуп: не чаще раза в quality_advice_interval_days (метка на DriverProfile)."""
    if avg <= 0 or avg >= settings.quality_advice_rating:
        return
    prof = _profile(session, ratee_id)
    if prof is None:
        return
    now = now or utcnow()
    if (prof.low_rating_advice_at is not None
            and now - prof.low_rating_advice_at < timedelta(days=settings.quality_advice_interval_days)):
        return
    prof.low_rating_advice_at = now
    session.add(prof)
    session.commit()
    send_push(
        session, ratee_id, "Совет от Юлдаша",
        "Рейтинг немного просел. Чистая машина, спокойная езда и доброе слово быстро "
        "возвращают звёзды 💚"
        " · Рейтинг бер аҙ төштө. Таҙа машина, тыныс йөрөү һәм йылы һүҙ "
        "йондоҙҙарҙы тиҙ кире ҡайтара 💚",
    )


# ------------------------------ право объяснения: /me/restrictions ------------------------------
def restrictions_payload(session: Session, user: User, now: Optional[datetime] = None) -> dict:
    """Активные ограничения пользователя — что, почему (категория, БЕЗ автора), до когда.
    Пусто → items=[]. Попутка не ограничивается никогда — это прямо написано в тексте."""
    now = now or utcnow()
    items: list[dict] = []
    prof = _profile(session, user.id)
    if prof is not None and prof.taxi_paused_until is not None and prof.taxi_paused_until > now:
        # Категория последней жалобы, породившей паузу (актуальная причина — без автора).
        last = session.exec(
            select(Report).where(
                Report.target_user_id == user.id,
                Report.status.in_(["new", "reviewing", "resolved"]),
            ).order_by(Report.id.desc())
        ).first()
        ru, ba = category_label(last.category) if last else ("", "")
        review = prof.taxi_pause_reason == PAUSE_REASON_REVIEW
        items.append({
            "kind": "taxi_pause",
            "reason": prof.taxi_pause_reason or PAUSE_REASON_ADMIN,
            "category": (last.category if last else None),
            "category_ru": ru, "category_ba": ba,
            # «до разбора» не показываем как дату через 10 лет — until=None, ждём человека.
            "until": (None if review else prof.taxi_paused_until.isoformat()),
            "title_ru": "Такси на паузе", "title_ba": "Такси паузала",
            "note_ru": ("Идёт разбор жалобы — решает живой человек. Попутка работает как обычно."
                        if review else "Попутка работает как обычно."),
            "note_ba": ("Ялыу тикшерелә — тере кеше хәл итә. Юлдаш ғәҙәттәгесә эшләй."
                        if review else "Юлдаш ғәҙәттәгесә эшләй."),
        })
    # Пауза офферов за брошенные ПРИНЯТЫЕ заказы (разбор №2). Без этой строки водитель стоял бы
    # на линии с зелёным тумблером три часа тишины и не понял бы, что это не сбой связи:
    # предупреждение перед отменой есть, а самой паузы не видно нигде.
    from .instant_service import driver_pause_until      # локальный импорт: избегаем цикла
    d_until = driver_pause_until(session, user.id, now)
    if d_until is not None:
        items.append({
            "kind": "driver_offers_pause",
            "reason": "driver_cancels",
            "category": None, "category_ru": "", "category_ba": "",
            "until": d_until.isoformat(),
            "title_ru": "Заказы приходят с паузой", "title_ba": "Заказдар паузанан һуң килә",
            "note_ru": ("Несколько принятых заказов подряд были отменены. Пассажир после такой "
                        "отмены ищет машину заново. Попутка работает как обычно."),
            "note_ba": ("Ҡабул ителгән заказдар бер нисә тапҡыр кире алынды. Пассажир ундай кире "
                        "алыуҙан һуң машинаны яңынан эҙләй. Юлдаш ғәҙәттәгесә эшләй."),
        })
    p_until = passenger_pause_until(session, user.id, now)
    if p_until is not None:
        items.append({
            "kind": "orders_pause",
            "reason": "strikes",
            "category": None, "category_ru": "", "category_ba": "",
            "until": p_until.isoformat(),
            "title_ru": "Заказы такси на паузе", "title_ba": "Такси заказдары паузала",
            "note_ru": "Накопились страйки (отмены/жалобы). Попутка работает как обычно.",
            "note_ba": "Страйктар йыйылды (кире алыуҙар/ялыуҙар). Юлдаш ғәҙәттәгесә эшләй.",
        })
    return {
        "items": items,
        "support_ru": "Не согласен или хочешь объяснить — напиши в поддержку, разберёмся по-человечески.",
        "support_ba": "Риза түгелһең йәки аңлатырға теләйһең — ярҙам хеҙмәтенә яҙ, кешеләрсә хәл итербеҙ.",
    }
