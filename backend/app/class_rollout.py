"""Набор водителей по местам: когда класс открывается пассажирам (docs/taxi-classes-2026-08.md §4).

Правило Александра: класс не показываем, пока в городе или районе не набралось
`settings.car_class_min_drivers` подтверждённых водителей. Набралось — открывается сам,
без ручного включения.

Зачем: пустая кнопка «Бизнес», за которой никого нет, дороже отсутствующей — человек один
раз ткнул, не дождался и удалил приложение. Так же поступают все: Яндекс официально
подтверждает, что «в разных городах список основных тарифов может быть разным», а inDrive
прямо пишет, что иконки Comfort и XL появляются только там, где услуга есть.

**Единица набора — город или район, не деревня.** В справочнике 4482 села РБ; требовать
по три водителя на каждое — это 13 тысяч человек, чего не будет никогда. Деревня наследует
классы своего района: набралось трое в Баймакском районе — работает во всех его деревнях.
Итого ~75 точек набора (21 город + 54 района).
"""
from __future__ import annotations

from typing import Optional

from sqlmodel import Session, select

from . import car_class as cc
from .config import settings
from .models import DriverProfile, TaxiApplication, TaxiApplicationStatus, User

# Место, которое не удалось определить. Набор для него не считаем и классы не режем
# (fail-open): в глуши справочник может не знать НП, и молчащий экран хуже лишней кнопки.
UNKNOWN_PLACE = ""


def place_of_area(area) -> str:
    """Точка на карте → единица набора. Район приоритетнее города: Баймак сидит внутри
    Баймакского района, и водители райцентра должны считаться вместе с деревенскими."""
    if area is None or not getattr(area, "known", False):
        return UNKNOWN_PLACE
    return (getattr(area, "district", "") or getattr(area, "city", "") or UNKNOWN_PLACE).strip()


def place_of_driver(session: Session, p: DriverProfile) -> str:
    """Где водитель числится для набора. Зона работы у него уже есть (work_district/work_city),
    отдельного поля не заводим. Не выбрал зону — в набор не попадает, но заказы получает
    как раньше: счётчик не должен превращаться в новый гейт."""
    if p is None:
        return UNKNOWN_PLACE
    if (p.work_district or "").strip():
        return p.work_district.strip()
    city = (p.work_city or "").strip()
    if not city:
        return UNKNOWN_PLACE
    # Город без района — берём район из справочника (Баймак → Баймакский р-н), иначе сам город
    # (Сибай — городской округ, района у него нет).
    from . import geo
    area = geo.area_by_name(session, city)
    return place_of_area(area) or city


def _approved_driver_ids(session: Session) -> set[int]:
    """Кто прошёл модерацию таксиста и не выбыл по срокам документов."""
    rows = session.exec(
        select(TaxiApplication.user_id).where(
            TaxiApplication.status == TaxiApplicationStatus.approved,
            TaxiApplication.docs_expired == False,  # noqa: E712
        )
    ).all()
    return {r for r in rows}


def class_counts(session: Session, place: str) -> dict[str, int]:
    """Сколько подтверждённых водителей каждого класса в этом месте.

    Считаем по ДОСТУПНЫМ классам машины (что насчитал классификатор), а не по включённым
    водителем: набор — это «есть ли в районе такие машины», а не «кто сегодня вышел».
    Иначе тариф открывался бы и закрывался по несколько раз в день.
    """
    out = {c: 0 for c in cc.CLASSES}
    if not place:
        return out
    approved = _approved_driver_ids(session)
    if not approved:
        return out
    profiles = session.exec(
        select(DriverProfile).where(DriverProfile.user_id.in_(approved))
    ).all()
    verified_ids = {
        u.id for u in session.exec(
            select(User).where(User.id.in_([p.user_id for p in profiles]))
        ).all() if u.verified
    }
    for p in profiles:
        if p.user_id not in verified_ids:
            continue
        if place_of_driver(session, p) != place:
            continue
        for c in cc.parse_classes(p.car_classes_available):
            out[c] += 1
    return out


def threshold_for(car_class: str) -> int:
    """Сколько водителей нужно, чтобы класс открылся.

    Эконом — базовый, порога нет: спрятать его значит выключить такси целиком.
    У Бизнеса и Минивэна порог ниже общего: премиум-седанов и шестиместных машин в райцентре
    объективно единицы, а в Бизнес ещё и пускают через очный осмотр. Ждать третьего значило бы
    держать класс закрытым месяцами при двух готовых водителях.
    """
    base = int(settings.car_class_min_drivers or 0)
    if car_class == cc.ECONOMY:
        return 0
    if base <= 0:
        return 0                        # набор выключен целиком
    rare = {
        cc.BUSINESS: settings.car_class_min_drivers_business,
        cc.MINIVAN: settings.car_class_min_drivers_minivan,
    }
    if car_class in rare:
        return max(1, int(rare[car_class] or base))
    return base


def open_classes(session: Session, place: str) -> set[str]:
    """Классы, открытые пассажирам в этом месте."""
    if not place or int(settings.car_class_min_drivers or 0) <= 0:
        return set(cc.CLASSES)          # набор выключен или место неизвестно → не режем
    counts = class_counts(session, place)
    opened = {cc.ECONOMY}
    for c in (cc.COMFORT, cc.BUSINESS, cc.MINIVAN):
        if counts.get(c, 0) >= threshold_for(c):
            opened.add(c)
    return opened


def open_categories(session: Session, place: str) -> set[str]:
    """То же, но в терминах категорий заказа (economy → standard)."""
    return {cc.class_to_category(c) for c in open_classes(session, place)}


def progress(session: Session, place: str) -> list[dict]:
    """Прогресс набора для экрана водителя: сколько есть, сколько нужно, открыт ли класс.

    `first` — «ты будешь первым в Баймаке». Формулировка «набралось 0 из 3» демотивирует,
    а статус первопроходца — нет; в попутках «между своими» это работает сильнее рекламы.
    """
    counts = class_counts(session, place)
    out = []
    for c in cc.CLASSES:
        have = counts.get(c, 0)
        need = threshold_for(c)
        out.append({
            "car_class": c,
            "category": cc.class_to_category(c),
            "have": have,
            "need": need,
            "open": have >= need,
            "first": have == 0 and need > 0,
        })
    return out


def place_at(session: Session, lat: Optional[float], lng: Optional[float]) -> str:
    """Координаты → единица набора."""
    from . import geo
    return place_of_area(geo.area_at(session, lat, lng))
