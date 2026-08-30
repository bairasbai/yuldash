# -*- coding: utf-8 -*-
"""Жалоба «грязная машина» разбирается фотографией, а не словами (2026-08-30).

БЫЛО. Жалоба на грязь — слово против слова. Пассажиру могло показаться, водитель мог
только что кого-то довезти. Разбирать это «по справедливости» без фото невозможно, а
наказывать по одному сообщению — значит дать любому кнопку «испортить соседу неделю».

СТАЛО. Жалоба открывает требование: пришли фото салона за сутки.

    прислал чистое      → жалоба ОТКЛОНЕНА, никаких последствий
    прислал грязное     → жалоба ПОДТВЕРЖДЕНА → обычная лестница качества
    не прислал за сутки → жалоба ПОДТВЕРЖДЕНА → та же лестница

ТРИ ВЕЩИ, КОТОРЫЕ ЗДЕСЬ ВАЖНЕЕ ОСТАЛЬНЫХ и проверяются отдельно:

  • **Одна жалоба не наказывает никогда.** Наказывает только молчание или подтверждённая
    фотографией грязь. Сама по себе жалоба не двигает лестницу и не ставит паузу.
  • **Работа не ограничивается,** пока идут сутки: человек возит как обычно.
  • **Требование приходит только по РЕАЛЬНОЙ поездке.** Иначе любой вошедший заставлял бы
    незнакомого водителя фотографировать машину, а через сутки молчания получал бы ему
    подтверждённую жалобу — тот же приём, которым уже пытались снимать людей с линии
    тяжёлыми жалобами (волна 158).
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import carphoto as cp
from app import car_class as cc
from app import quality
from app.config import settings
from app.db import engine
from app.models import (Booking, BookingStatus, CarPhotoCheck, DriverProfile, InstantOrder,
                        InstantOrderStatus, Report, Ride, UserRole)
from app.timeutil import utcnow


def _сессия() -> Session:
    """Сессия, не гасящая объекты после commit (иначе строку не прочитать за пределами `with`)."""
    return Session(engine, expire_on_commit=False)


@pytest.fixture
def контроль_включён():
    было = (settings.car_photo_taxi_enabled, settings.car_photo_courier_enabled)
    settings.car_photo_taxi_enabled = True
    settings.car_photo_courier_enabled = True
    yield
    settings.car_photo_taxi_enabled, settings.car_photo_courier_enabled = было


def _поездка(driver_id: int, passenger_id: int) -> int:
    """Настоящий такси-заказ: водитель назначен, заказ выполнен."""
    with _сессия() as s:
        o = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id, status=InstantOrderStatus.done,
            from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7,
            price_estimate=300, accepted_at=utcnow() - timedelta(hours=2), done_at=utcnow(),
        )
        s.add(o)
        s.commit()
        return o.id


def _жалоба(client, автор, водитель, order_id=None, category="dirty_car"):
    body = {"category": category, "reason": "В салоне грязно"}
    if order_id is not None:
        body["order_id"] = order_id
    else:
        body["target_user_id"] = водитель["id"]
    return client.post("/reports", headers=автор["auth"], json=body)


def _требование(user_id: int):
    with _сессия() as s:
        return cp.current_complaint(s, user_id)


def _отчёт(report_id: int) -> Report:
    with _сессия() as s:
        return s.get(Report, report_id)


def _прислать_кадр(check_id: int, slot: str = "salon"):
    """Положить кадр в требование, как это делает загрузка."""
    with _сессия() as s:
        строка = s.get(CarPhotoCheck, check_id)
        cp.attach(s, строка, slot, f"/secure/carphoto/{строка.user_id}_c.jpg", "ok", "abc123")
        return s.get(CarPhotoCheck, check_id)


# ==================== 1. Требование появляется там, где ему место ====================
def test_a_dirty_car_complaint_asks_for_a_photo(client, user_factory, контроль_включён):
    """Пожаловались на грязь после реальной поездки — водителю пришло требование на сутки."""
    d = user_factory("ВодительГрязь", role=UserRole.driver)
    p = user_factory("ПассажирГрязь")
    заказ = _поездка(d["id"], p["id"])

    r = _жалоба(client, p, d, order_id=заказ)
    assert r.status_code == 200, r.text

    требование = _требование(d["id"])
    assert требование is not None, "жалоба не открыла требование фото"
    assert требование.kind == cp.COMPLAINT
    assert требование.report_id == r.json()["id"]
    часов = (требование.due_at - utcnow()).total_seconds() / 3600
    assert 23 < часов <= 24.1, f"дали не сутки, а {часов:.1f} ч"
    assert [с["code"] for с in cp.check_slots(требование)] == ["salon"], \
        "по жалобе на грязь просим салон, а не обход всей машины"


def test_a_complaint_from_a_stranger_asks_for_nothing(client, user_factory, контроль_включён):
    """Жалоба без общей поездки требование НЕ открывает.

    Иначе любой вошедший заставлял бы незнакомого водителя фотографировать машину, а через
    сутки молчания получал бы ему подтверждённую жалобу. Пожаловаться при этом можно —
    админ такую жалобу увидит; не срабатывает только автоматика.
    """
    d = user_factory("ВодительПосторонний", role=UserRole.driver)
    чужой = user_factory("Посторонний")

    r = _жалоба(client, чужой, d)
    assert r.status_code == 200, r.text
    assert _требование(d["id"]) is None, "требование пришло по жалобе без поездки"


def test_a_second_complaint_does_not_pile_up_demands(client, user_factory, контроль_включён):
    """Вторая жалоба за те же сутки не превращается во вторую фотосессию подряд."""
    d = user_factory("ВодительДваРаза", role=UserRole.driver)
    p1 = user_factory("Первый")
    p2 = user_factory("Второй")
    _жалоба(client, p1, d, order_id=_поездка(d["id"], p1["id"]))
    первое = _требование(d["id"])
    _жалоба(client, p2, d, order_id=_поездка(d["id"], p2["id"]))
    второе = _требование(d["id"])
    assert первое is not None and второе is not None
    assert первое.id == второе.id, "на человека повесили два требования разом"


def test_a_parcel_complaint_asks_for_the_trunk(client, user_factory, контроль_включён):
    """У курьера «грязная машина» — это багажник: посылка едет там."""
    from app.models import CourierApplication, ParcelDelivery

    к = user_factory("КурьерГрязь", taxi_approved=False)
    отправитель = user_factory("ОтправительГрязь")
    with _сессия() as s:
        s.add(CourierApplication(user_id=к["id"], status="approved", reviewed_at=utcnow()))
        посылка = ParcelDelivery(
            sender_id=отправитель["id"], courier_id=к["id"], status="delivered",
            from_city="Сибай", to_city="Акъяр", size="small",
            receiver_name="Айгүл", receiver_phone="+79990001188",
            accepted_at=utcnow() - timedelta(hours=3), delivered_at=utcnow(),
        )
        s.add(посылка)
        s.commit()
        s.refresh(посылка)
        parcel_id = посылка.id

    r = client.post("/reports", headers=отправитель["auth"],
                    json={"category": "dirty_car", "reason": "Багажник грязный",
                          "parcel_id": parcel_id})
    assert r.status_code == 200, r.text
    требование = _требование(к["id"])
    assert требование is not None and требование.mode == cp.COURIER
    assert [с["code"] for с in cp.check_slots(требование)] == ["trunk"]


# ==================== 2. Жалоба сама по себе не наказывает ====================
def test_the_demand_does_not_stop_the_work(client, user_factory, контроль_включён):
    """Пока идут сутки, человек возит как обычно: требование не ограничивает ничего.

    Это главное отличие требования от планового контроля. Плановый — обязанность с
    лестницей и паузой; требование — просто просьба показать то, о чём написали.
    """
    from app import taxi as taxi_mod

    d = user_factory("ВодительРаботает", role=UserRole.driver)
    p = user_factory("ПассажирРаботает")
    _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    требование = _требование(d["id"])
    # Даже если срок вышел: молчание закрывает жалобу, но линию не трогает.
    with _сессия() as s:
        строка = s.get(CarPhotoCheck, требование.id)
        строка.due_at = utcnow() - timedelta(days=3)
        s.add(строка)
        s.commit()
    with _сессия() as s:
        assert cp.blocked(s, d["id"], cp.TAXI) is False
        assert cp.slow(s, d["id"], cp.TAXI) is False
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_one_complaint_alone_punishes_nobody(client, user_factory, контроль_включён):
    """Жалоба создана — и всё: ни паузы, ни строки в лестнице, пока не будет решения."""
    d = user_factory("ВодительБезНаказания", role=UserRole.driver)
    p = user_factory("ПассажирБезНаказания")
    r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    assert _отчёт(r.json()["id"]).status == "new", "жалоба закрылась сама, без разбора"
    with _сессия() as s:
        assert quality.taxi_pause_until(s, d["id"]) is None


def test_the_demand_does_not_shadow_the_periodic_check(client, user_factory, контроль_включён):
    """Требование и плановый обход — разные строки и не мешают друг другу."""
    d = user_factory("ВодительДваКонтроля", role=UserRole.driver)
    p = user_factory("ПассажирДваКонтроля")
    with _сессия() as s:
        плановый = cp.ensure(s, d["id"], cp.TAXI)
    _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    with _сессия() as s:
        assert cp.current(s, d["id"], cp.TAXI).id == плановый.id, \
            "требование по жалобе подменило плановый контроль"
        assert cp.current_complaint(s, d["id"]).id != плановый.id
        assert len(cp.check_slots(плановый)) == 6, "у планового обхода остался весь набор кадров"


# ==================== 3. Чем кончается разбор ====================
def test_a_clean_salon_closes_the_complaint(client, user_factory, контроль_включён):
    """Салон чистый — жалоба отклонена, следа в лестнице нет."""
    d = user_factory("ВодительЧистый", role=UserRole.driver)
    p = user_factory("ПассажирЧистый")
    r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    требование = _прислать_кадр(_требование(d["id"]).id)

    with _сессия() as s:
        итог = cp.submit(s, s.get(CarPhotoCheck, требование.id))
        assert итог["status"] == cp.REVIEW, "фото по жалобе обязан смотреть человек"
        assert _отчёт(r.json()["id"]).status == "new", "жалоба закрылась до просмотра"
        cp.decide(s, s.get(CarPhotoCheck, требование.id), True, admin_id=None)

    жалоба = _отчёт(r.json()["id"])
    assert жалоба.status == "rejected", "чистый салон не снял обвинение"
    with _сессия() as s:
        assert quality.resolved_report_times(s, d["id"], utcnow() - timedelta(days=30)) == []


def test_a_dirty_salon_confirms_the_complaint(client, user_factory, контроль_включён):
    """Салон грязный — жалоба подтверждена и дальше работает обычная лестница."""
    d = user_factory("ВодительГрязный", role=UserRole.driver)
    p = user_factory("ПассажирГрязный")
    r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    требование = _прислать_кадр(_требование(d["id"]).id)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, требование.id))
        cp.decide(s, s.get(CarPhotoCheck, требование.id), False,
                  reason="Мусор на полу", admin_id=None)
    жалоба = _отчёт(r.json()["id"])
    assert жалоба.status == "resolved"
    assert "Мусор" in (жалоба.resolution or "")


def test_silence_for_a_day_counts_as_confirmed(client, user_factory, контроль_включён):
    """Сутки прошли, фото нет — жалоба засчитана. Единственное место, где молчание стоит."""
    d = user_factory("ВодительМолчит", role=UserRole.driver)
    p = user_factory("ПассажирМолчит")
    r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    требование = _требование(d["id"])
    with _сессия() as s:
        строка = s.get(CarPhotoCheck, требование.id)
        строка.due_at = utcnow() - timedelta(minutes=1)
        s.add(строка)
        s.commit()

    with _сессия() as s:
        тронули = cp.expire_demands(s)
    assert d["id"] in тронули
    assert _отчёт(r.json()["id"]).status == "resolved"
    with _сессия() as s:
        assert s.get(CarPhotoCheck, требование.id).status == cp.FAILED


def test_a_demand_answered_in_time_is_not_expired(client, user_factory, контроль_включён):
    """Успел прислать — ночной обход его не трогает: он ждёт нас, а не мы его."""
    d = user_factory("ВодительУспел", role=UserRole.driver)
    p = user_factory("ПассажирУспел")
    r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
    требование = _прислать_кадр(_требование(d["id"]).id)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, требование.id))
        строка = s.get(CarPhotoCheck, требование.id)
        строка.due_at = utcnow() - timedelta(days=2)     # срок давно вышел
        s.add(строка)
        s.commit()
        cp.expire_demands(s)
    assert _отчёт(r.json()["id"]).status == "new", "жалобу засчитали, хотя фото пришло вовремя"


def test_three_confirmed_complaints_pause_the_taxi(client, user_factory, контроль_включён):
    """Лестница на месте: несколько подтверждённых за месяц ставят такси на паузу.

    Отдельного наказания за грязь не придумываем — у нас уже есть общая лестница, и она
    известна людям. Новое здесь только одно: подтверждает жалобу фотография, а не спор.
    """
    d = user_factory("ВодительТриЖалобы", role=UserRole.driver)
    with _сессия() as s:                      # пауза живёт в профиле водителя
        s.add(DriverProfile(user_id=d["id"], online=False))
        s.commit()
    нужно = int(settings.quality_pause_reports)
    for i in range(нужно):
        p = user_factory(f"Жалобщик{i}")
        r = _жалоба(client, p, d, order_id=_поездка(d["id"], p["id"]))
        требование = _требование(d["id"])
        assert требование is not None, f"жалоба {i + 1} не открыла требование"
        with _сессия() as s:
            строка = s.get(CarPhotoCheck, требование.id)
            строка.due_at = utcnow() - timedelta(minutes=1)
            s.add(строка)
            s.commit()
            cp.expire_demands(s)
        assert _отчёт(r.json()["id"]).status == "resolved"
    with _сессия() as s:
        assert quality.taxi_pause_until(s, d["id"]) is not None, \
            "подтверждённые фотографией жалобы не двигают лестницу"


# ==================== 4. По каким пунктам смотрим салон ====================
def test_the_clean_rules_are_visible_on_a_photo(client):
    """Три пункта, и все три видно на фото. Запаха среди них нет — и не будет.

    Требование, которое нельзя проверить, превращает разбор в спор о вкусах: один скажет
    «пахнет», другой «не пахнет», и правым окажется тот, кто настойчивее.
    """
    assert len(cp.CLEAN_RULES) == 3
    for ru, ba in cp.CLEAN_RULES:
        assert ru.strip() and ba.strip(), "правило без одного из языков"
    весь_текст = " ".join(ru for ru, _ in cp.CLEAN_RULES).lower()
    assert "запах" not in весь_текст, "по фото запах не проверить — обещать его нельзя"
    assert "мусор" in весь_текст and "чехл" in весь_текст and "пол" in весь_текст


# ==================== 5. Классы: чем платят за Комфорт и Бизнес ====================
def _спека(**kw) -> cc.CarSpec:
    базовая = dict(year=utcnow().year - 2, seats=4, has_ac=True, clean_salon=True,
                   body_ok=True, is_sedan=True, color="black", premium=True)
    базовая.update(kw)
    return cc.CarSpec(**базовая)


def _чего_не_хватает(car_class: str, spec: cc.CarSpec) -> list:
    return cc.missing_for(car_class, spec, utcnow().year,
                          comfort_max_age=settings.car_comfort_max_age,
                          business_max_age=settings.car_business_max_age,
                          minivan_max_age=settings.car_minivan_max_age,
                          minivan_min_seats=settings.car_minivan_min_seats)


def test_a_light_salon_opens_business_without_leather(client):
    """Светлый салон — равноценная коже дорога в Бизнес.

    Кожа в райцентре редкость, а светлый ухоженный салон читается пассажиром как «дорого»
    ничуть не хуже. Требовать именно кожу значило бы закрыть Бизнес почти всем, кто его
    заслужил.
    """
    assert _чего_не_хватает(cc.BUSINESS, _спека(leather=False, light_salon=True)) == []
    assert _чего_не_хватает(cc.BUSINESS, _спека(leather=True, light_salon=False)) == []
    assert "no_leather" in _чего_не_хватает(cc.BUSINESS, _спека(leather=False, light_salon=False))


def test_seat_covers_close_comfort(client):
    """Чехлы-накидки с рынка — это не Комфорт. Эконом при этом работает как обычно."""
    в_чехлах = _спека(clean_salon=False)
    assert "clean_salon" in _чего_не_хватает(cc.COMFORT, в_чехлах)
    assert "clean_salon" in _чего_не_хватает(cc.ECONOMY, в_чехлах)


def test_age_rules_did_not_change(client):
    """Возраст мы не трогали: свежая машина проходит в Комфорт, как и раньше."""
    assert _чего_не_хватает(cc.COMFORT, _спека()) == []
