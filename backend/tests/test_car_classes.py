"""Классы машин, опции салона, набор по местам и фолбэк поиска.

Спека — docs/taxi-classes-2026-08.md. Проверяем то, ради чего всё затевалось:
класс нельзя завысить, водитель сам решает что берёт, кресло не подменяется «раз никого
нет», пустых кнопок в витрине не бывает, и класс никогда не понижается молча.
"""
from datetime import timedelta

from sqlmodel import Session

from app import car_class as cc
from app import class_rollout
from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, User, UserRole
from app.timeutil import utcnow

# Своя точка на карте, далеко от Баймака (его занимают тесты тарифов и суржа). Сурж считает
# спрос по заказам в радиусе 7 км от точки подачи, а тесты делят одну БД: сеять заказы там же
# значит поднять соседям коэффициент и уронить их проверки «спокойной» цены.
ORIG = (54.7000, 55.9000)      # окрестности Уфы
DEST = (54.8000, 56.0000)


def _spec(**kw):
    base = dict(year=2020, seats=4, has_ac=True, clean_salon=True, body_ok=True)
    base.update(kw)
    return cc.CarSpec(**base)


def _avail(spec, year=2026):
    return cc.available_classes(spec, year, comfort_max_age=10, business_max_age=9,
                                minivan_max_age=10, minivan_min_seats=6)


# ============================ 1. Классификатор ============================
def test_classes_checked_independently_not_as_hierarchy():
    """Иерархии «высокий класс берёт всё, что ниже» НЕТ — каждый класс проверяется отдельно.

    Разница не теоретическая: премиум-седан не проходит в Минивэн (мест мало), а семиместный
    минивэн не проходит в Бизнес (не седан). Правило «всё, что ниже» оба случая обработало бы
    неверно и посадило бы шестерых человек в седан.
    """
    sedan = _spec(is_sedan=True, leather=True, color="чёрный", premium=True)
    assert _avail(sedan) == ["economy", "comfort", "business"]
    assert "minivan" not in _avail(sedan)

    minivan = _spec(seats=7)
    assert _avail(minivan) == ["economy", "comfort", "minivan"]
    assert "business" not in _avail(minivan)


def test_minivan_needs_a_working_air_conditioner():
    """Минивэну кондиционер обязателен — как Комфорту, и по той же причине, только сильнее.

    Шесть-восемь человек в салоне нагревают его быстрее четверых, а Минивэн стоит ДОРОЖЕ
    Комфорта. Класс без кондиционера дороже класса с кондиционером — это обещание, которое
    пассажир не поймёт и правильно сделает.
    """
    без_кондея = _spec(seats=7, has_ac=False)
    assert "minivan" not in _avail(без_кондея)
    assert "no_ac" in cc.missing_for("minivan", без_кондея, 2026, comfort_max_age=10,
                                     business_max_age=9, minivan_max_age=10, minivan_min_seats=6)
    # Эконом от этого не страдает: там кондиционер никогда не требовался.
    assert "economy" in _avail(без_кондея)
    # А с кондиционером класс на месте.
    assert "minivan" in _avail(_spec(seats=7, has_ac=True))


def test_old_car_falls_to_economy_only():
    """Возраст режет Комфорт: машина старше 10 лет остаётся Экономом, сколько бы галочек
    водитель ни поставил."""
    assert _avail(_spec(year=2005)) == ["economy"]
    assert "too_old" in cc.missing_for("comfort", _spec(year=2005), 2026, comfort_max_age=10,
                                       business_max_age=9, minivan_max_age=10, minivan_min_seats=6)


def test_year_unknown_blocks_everything_above_economy():
    """Без года выпуска класс подтвердить нечем — только Эконом."""
    assert _avail(_spec(year=None)) == ["economy"]


def test_business_needs_moderator_not_just_good_car():
    """В Бизнес не пускает даже идеальная машина, пока модератор не подтвердил её очно.
    Там цена выше и ожидания пассажира другие — по одной анкете туда нельзя."""
    good = _spec(is_sedan=True, leather=True, color="белый", premium=False)
    assert "business" not in _avail(good)
    assert "not_verified_premium" in cc.missing_for(
        "business", good, 2026, comfort_max_age=10, business_max_age=9,
        minivan_max_age=10, minivan_min_seats=6)


def test_nine_seats_is_a_bus_and_gets_no_classes():
    """9 пассажирских мест — уже не легковое такси (категория M1), а автобус: водителю нужна
    категория D, службе заказа — лицензия. Такую машину не пускаем никуда."""
    assert _avail(_spec(seats=9)) == []
    assert cc.missing_for("economy", _spec(seats=9), 2026, comfort_max_age=10, business_max_age=9,
                          minivan_max_age=10, minivan_min_seats=6) == ["too_many_seats"]


def test_color_rules_for_bashkortostan():
    """Цвет кузова в РБ: чёрный, белый, жёлтый. Нераспознанный — не отказ, а «решит модератор»."""
    assert cc.color_allowed("чёрный") is True
    assert cc.color_allowed("Белый металлик") is True
    assert cc.color_allowed("серебристый") is False
    assert cc.color_allowed("мокрый асфальт") is None
    # В соседних регионах цвет не регулируется — Магнитогорск ездит любого цвета.
    assert cc.color_allowed("серебристый", region="Челябинская обл") is True


# ============================ 2. Мультитариф водителя ============================
def test_driver_takes_only_enabled_classes():
    """Из доступных классов водитель включает нужные сам. Снял галочку Эконома — дешёвые
    заказы ему больше не летят."""
    avail = ["economy", "comfort"]
    assert cc.driver_takes("standard", avail, "") is True          # ничего не выбрал → берёт всё
    assert cc.driver_takes("standard", avail, "comfort") is False   # оставил только Комфорт
    assert cc.driver_takes("comfort", avail, "comfort") is True


def test_enabled_falls_back_to_all_when_empty():
    """Пустой список включённых = берёт все доступные. Человек прошёл модерацию и вышел на
    линию — он ждёт заказы, а не пустой экран из-за незаполненной галочки."""
    assert cc.effective_classes(["economy", "comfort"], "") == ["economy", "comfort"]
    # Включил то, чего машине не положено → игнорируем и возвращаем доступное.
    assert cc.effective_classes(["economy"], "business") == ["economy"]


def test_legacy_profile_keeps_previous_matching():
    """У водителей, заведённых до классификатора, available пуст. Прежнее поведение обязано
    сохраниться: комфорт-машина и раньше получала обычные заказы."""
    assert cc.available_or_legacy("", "comfort") == ["economy", "comfort"]
    assert cc.driver_takes("standard", cc.available_or_legacy("", "comfort"), "") is True


# ============================ 3. Опции салона ============================
def test_options_are_a_hard_filter():
    """Заказ с детским креслом машине без кресла не предлагаем ВООБЩЕ. Подобрать «раз никого
    нет» — значит обмануть в том единственном, ради чего галочку и ставили."""
    assert cc.covers_options("seat_1_4,pets", "seat_1_4") is True
    assert cc.covers_options("pets", "seat_1_4") is False
    assert cc.covers_options("", "") is True          # ничего не просили — подходит любой


def test_wheelchair_and_guide_dog_are_separate_options():
    """Коляска и собака-проводник — разные вещи и разные машины. Плюс детская коляска: её
    отсутствие в списке приводило к трём отменам подряд «у меня багажник забит»."""
    assert cc.OPT_GUIDE_DOG in cc.OPTIONS and cc.OPT_STROLLER in cc.OPTIONS
    assert cc.OPT_GUIDE_DOG in cc.ACCESSIBILITY_OPTIONS
    assert cc.OPT_STROLLER in cc.TRUNK_OPTIONS
    assert cc.covers_options("stroller", "guide_dog") is False


def test_unknown_option_from_newer_client_is_ignored():
    """Клиент новее сервера не должен ломать запись профиля."""
    assert cc.dump_options(["seat_1_4", "teleport"]) == "seat_1_4"


# ============================ 4. Подбор водителя ============================
def _driver(session: Session, name: str, **profile) -> int:
    from app.models import TaxiApplication, TaxiApplicationStatus
    from datetime import date
    u = User(phone=f"cls-{name}", name=name, telegram_id=f"cls{name}", verified=True,
             role=UserRole.driver)
    session.add(u)
    session.commit()
    session.refresh(u)
    session.add(TaxiApplication(user_id=u.id, inn="123456789012", permit_number="Т-1",
                                birth_date=date(1990, 1, 1), license_since_year=2010,
                                status=TaxiApplicationStatus.approved))
    session.add(DriverProfile(user_id=u.id, online=True, **profile))
    session.commit()
    return u.id


def _order(session: Session, passenger_id: int, **kw) -> InstantOrder:
    # created_at ЗА окном спроса: сурж считает searching-заказы за последние 10 минут, а тесты
    # делят одну БД. Свежий заказ здесь ломал бы соседние проверки коэффициента — и сломал,
    # пока эта строка не появилась.
    kw.setdefault("created_at", utcnow() - timedelta(hours=1))
    o = InstantOrder(passenger_id=passenger_id, status=S.searching,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1], **kw)
    session.add(o)
    session.commit()
    session.refresh(o)
    return o


def test_eligible_respects_class_and_options(user_factory):
    """Сквозная проверка фильтра: класс и опции работают вместе."""
    pax = user_factory("ClsFilterPax")
    with Session(engine) as s:
        econom = _driver(s, "EconomOnly", car_classes_available="economy")
        comfort = _driver(s, "ComfortCar", car_classes_available="economy,comfort")
        with_seat = _driver(s, "WithSeat", car_classes_available="economy",
                            car_options="seat_1_4")

        # Комфорт-заказ обычной машине не предлагаем, комфорт-машине — да.
        comfort_order = _order(s, pax["id"], category="comfort")
        got = isv.eligible(s, [econom, comfort, with_seat], comfort_order)
        assert got == [comfort]

        # Заказ с детским креслом уходит только тому, у кого кресло есть.
        seat_order = _order(s, pax["id"], category="standard", options="seat_1_4")
        got = isv.eligible(s, [econom, comfort, with_seat], seat_order)
        assert got == [with_seat]


def test_driver_can_opt_out_of_cheap_orders(user_factory):
    """Водитель Комфорта снял галочку Эконома — обычные заказы ему больше не летят.
    Раньше отказаться было нельзя: комфорт-машина получала дешёвые заказы принудительно."""
    pax = user_factory("OptOutPax")
    with Session(engine) as s:
        picky = _driver(s, "PickyDriver", car_classes_available="economy,comfort",
                        car_classes_enabled="comfort")
        std = _order(s, pax["id"], category="standard")
        assert isv.eligible(s, [picky], std) == []
        cmf = _order(s, pax["id"], category="comfort")
        assert isv.eligible(s, [picky], cmf) == [picky]


def test_agreed_fallback_class_widens_search(user_factory):
    """Пассажир согласился искать и в Экономе → эконом-машина становится кандидатом на
    комфорт-заказ. Без согласия — не становится."""
    pax = user_factory("FallbackPax")
    with Session(engine) as s:
        econom = _driver(s, "EconomFb", car_classes_available="economy")
        order = _order(s, pax["id"], category="comfort")
        assert isv.eligible(s, [econom], order) == []
        order.fallback_categories = "economy"
        s.add(order)
        s.commit()
        s.refresh(order)
        assert isv.eligible(s, [econom], order) == [econom]


# ============================ 5. Набор водителей по местам ============================
def test_class_hidden_until_three_drivers(monkeypatch, user_factory):
    """Класс не показывается, пока в районе не набралось трёх водителей. Пустая кнопка,
    за которой никого нет, дороже отсутствующей."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 3)
    place = "Тестовый р-н"
    with Session(engine) as s:
        for i in range(2):
            _driver(s, f"RollA{i}", car_classes_available="economy,comfort",
                    work_district=place)
        opened = class_rollout.open_classes(s, place)
        assert "comfort" not in opened
        assert "economy" in opened          # базовый класс открыт всегда
        _driver(s, "RollA2", car_classes_available="economy,comfort", work_district=place)
        assert "comfort" in class_rollout.open_classes(s, place)


def test_business_opens_at_two_drivers(monkeypatch):
    """У Бизнеса порог ниже: там очный допуск и премиум-седанов в городе меньше. Двое готовых
    водителей не должны месяцами ждать третьего с закрытым классом."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 3)
    monkeypatch.setattr(settings, "car_class_min_drivers_business", 2)
    place = "Бизнес р-н"
    with Session(engine) as s:
        _driver(s, "BizA", car_classes_available="economy,comfort,business", work_district=place)
        assert "business" not in class_rollout.open_classes(s, place)
        _driver(s, "BizB", car_classes_available="economy,comfort,business", work_district=place)
        opened = class_rollout.open_classes(s, place)
        assert "business" in opened          # двоих Бизнесу достаточно
        assert "comfort" not in opened       # а Комфорту всё ещё нужен третий


def test_threshold_per_class():
    """Пороги по классам: Эконом — без порога, у редких (Бизнес, Минивэн) свой, у Комфорта общий."""
    assert class_rollout.threshold_for("economy") == 0
    assert class_rollout.threshold_for("business") == settings.car_class_min_drivers_business
    assert class_rollout.threshold_for("minivan") == settings.car_class_min_drivers_minivan
    assert class_rollout.threshold_for("comfort") == settings.car_class_min_drivers


def test_minivan_opens_at_two_drivers(monkeypatch):
    """Минивэну тоже хватает двоих: шестиместных машин в райцентре единицы, и ждать третьего
    значит держать класс закрытым для семей, которым просто некуда сесть вшестером."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 3)
    monkeypatch.setattr(settings, "car_class_min_drivers_minivan", 2)
    place = "Минивэн р-н"
    with Session(engine) as s:
        _driver(s, "MvA", car_classes_available="economy,comfort,minivan", work_district=place)
        assert "minivan" not in class_rollout.open_classes(s, place)
        _driver(s, "MvB", car_classes_available="economy,comfort,minivan", work_district=place)
        opened = class_rollout.open_classes(s, place)
        assert "minivan" in opened
        assert "comfort" not in opened       # Комфорту всё ещё нужен третий


def test_progress_marks_first_driver(monkeypatch):
    """Первому в районе показываем «ты будешь первым», а не «набралось 0 из 3»."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 3)
    monkeypatch.setattr(settings, "car_class_min_drivers_business", 2)
    monkeypatch.setattr(settings, "car_class_min_drivers_minivan", 2)
    with Session(engine) as s:
        rows = {p["car_class"]: p for p in class_rollout.progress(s, "Пустой р-н")}
    assert rows["comfort"]["first"] is True and rows["comfort"]["need"] == 3
    assert rows["business"]["need"] == 2 and rows["minivan"]["need"] == 2   # редкие классы
    assert rows["economy"]["need"] == 0 and rows["economy"]["open"] is True


def test_rollout_disabled_opens_everything(monkeypatch):
    """Порог 0 = набор выключен: показываем все классы (для запуска и для тестов)."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    with Session(engine) as s:
        assert class_rollout.open_classes(s, "Любой р-н") == set(cc.CLASSES)


# ============================ 6. Фолбэк класса ============================
def test_business_never_falls_back_to_economy(monkeypatch, user_factory):
    """Заказавшему Бизнес предлагаем только Комфорт. Гранта вместо Мерседеса — не экономия,
    а испорченная поездка: человек едет на встречу или в аэропорт."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("BizPax")
    with Session(engine) as s:
        order = _order(s, pax["id"], category="business", distance_km=10.0, eta_min=15.0,
                       price_estimate=600)
        cats = [o["category"] for o in isv.fallback_options(s, order)]
    assert cats == ["comfort"]


def test_economy_has_no_alternatives(monkeypatch, user_factory):
    """Ниже Эконома ничего нет — предлагать нечего, честно ищем дальше."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("EcoPax")
    with Session(engine) as s:
        order = _order(s, pax["id"], category="standard", distance_km=10.0, eta_min=15.0,
                       price_estimate=200)
        assert isv.fallback_options(s, order) == []


def test_minivan_has_no_alternatives(monkeypatch, user_factory):
    """Шестерым в седан не сесть: у Минивэна альтернативы нет в принципе."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("MvPax")
    with Session(engine) as s:
        order = _order(s, pax["id"], category="minivan", distance_km=10.0, eta_min=15.0,
                       price_estimate=400)
        assert isv.fallback_options(s, order) == []


def test_agreeing_to_cheaper_class_lowers_the_price(monkeypatch, user_factory):
    """Согласился на Эконом — цена сразу пересчитывается вниз и фиксируется. Человек видел
    «Эконом 240 ₽» на экране и должен заплатить ровно 240, кто бы ни приехал."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("CheapPax")
    with Session(engine) as s:
        order = _order(s, pax["id"], category="comfort", distance_km=10.0, eta_min=15.0,
                       price_estimate=500)
        alts = isv.fallback_options(s, order)
        assert [a["category"] for a in alts] == ["standard"]
        assert alts[0]["price"] < 500
        res = isv.add_fallback_category(s, order, "standard")
        assert res["price"] == alts[0]["price"]
        assert set(res["categories"]) == {"comfort", "standard"}


def test_cannot_add_arbitrary_class(monkeypatch, user_factory):
    """Клиент не может подсунуть произвольный класс — Бизнес к эконом-заказу не добавляется."""
    import pytest
    from fastapi import HTTPException
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("HackPax")
    with Session(engine) as s:
        order = _order(s, pax["id"], category="standard", distance_km=10.0, eta_min=15.0,
                       price_estimate=200)
        with pytest.raises(HTTPException):
            isv.add_fallback_category(s, order, "business")


# ============================ 7. Витрина пассажира ============================
def test_estimate_lists_all_four_classes(client, user_factory, monkeypatch):
    """В ответе на расчёт цены — все четыре класса, у каждого своя цена и признак open."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 0)
    pax = user_factory("ShowcasePax")
    body = {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
            "from_text": "Уфа", "to_text": "Шакша"}
    r = client.post("/instant/estimate", headers=pax["auth"], json=body)
    assert r.status_code == 200, r.text
    opts = {o["category"]: o for o in r.json()["options"]}
    assert set(opts) == {"standard", "comfort", "business", "minivan"}
    assert opts["business"]["price"] > opts["comfort"]["price"] > opts["standard"]["price"]
    assert all(o["open"] for o in opts.values())


def test_closed_class_is_marked_not_hidden(client, user_factory, monkeypatch):
    """Закрытый класс не исчезает, а приходит с open=false — клиент покажет «скоро»
    и кнопку «сообщить». Так мы меряем спрос до того, как искать машины."""
    monkeypatch.setattr(settings, "car_class_min_drivers", 99)
    pax = user_factory("ClosedPax")
    body = {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
            "from_text": "Уфа", "to_text": "Шакша"}
    opts = {o["category"]: o for o in
            client.post("/instant/estimate", headers=pax["auth"], json=body).json()["options"]}
    assert opts["standard"]["open"] is True        # Эконом открыт всегда
    assert opts["business"]["open"] is False
