# -*- coding: utf-8 -*-
"""Не на всякой машине возят такси: стоп-список (2026-08-30).

БЫЛО. У Эконома не было ВООБЩЕ никаких требований к машине, кроме целого салона. То есть
«копейка» 1985 года и Ока формально проходили в такси: заказ уходил, человек выходил к
подъезду и видел то, что видел. Для пассажира это не «дёшево», а «страшно и стыдно».

ПОЧЕМУ СПИСОК, А НЕ ВОЗРАСТ. Возраст задачу не решает: «семёрку» выпускали до 2012 года,
Оку — до 2008. Любой порог, который их отсекает, заодно убивает рабочую «четырнадцатую»
2011 года — а на ней в райцентре возят каждый день. Список бьёт точно по цели.

ЧТО ЭТО НЕ ЕСТЬ. Не белый список моделей (от него отказались 29.08: какие машины пускать
в реестр, решает государство) и не классификатор Яндекса с ценами Авто.ру. Это два десятка
позиций, отвечающих на один вопрос: «на этом пассажира возить нельзя».

ПОПУТКА НЕ ЗАТРАГИВАЕТСЯ НИКОГДА. Сосед везёт соседа на том, что у него есть.
"""
import pytest
from sqlmodel import Session, select

from app import car_class as cc
from app import taxi as taxi_mod
from app.config import settings
from app.db import engine
from app.models import DriverProfile, UserRole


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


def _машина(user_id: int, make: str, model: str, year: int = 2015) -> None:
    """Записать водителю марку, модель и год, как это делает анкета или реестр."""
    with _сессия() as s:
        prof = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
        if prof is None:
            prof = DriverProfile(user_id=user_id, online=False)
        prof.car_make, prof.car_model, prof.car_year = make, model, year
        s.add(prof)
        s.commit()


# ==================== 1. Что список ловит ====================
@pytest.mark.parametrize("make,model", [
    ("ВАЗ", "2101"),        # та самая «копейка»
    ("ВАЗ", "2107"),
    ("ВАЗ", "21074"),       # реестр пишет пятизначный код — ловим по первым четырём
    ("ВАЗ", "21099"),
    ("ВАЗ", "Ока"),
    ("СеАЗ", "11113"),      # Ока камского завода
    ("ЗАЗ", "968М"),        # «Запорожец»
    ("Москвич", "2141"),
    ("ИЖ", "2126 Ода"),
])
def test_these_cars_do_not_carry_passengers(client, make, model):
    """Классика, «зубило», Ока, Запорожец, старые Москвичи — не такси."""
    assert cc.retired_model(make, model), f"{make} {model} прошла в такси"


# ==================== 2. Кого список НЕ должен задеть ====================
@pytest.mark.parametrize("make,model", [
    ("ВАЗ", "2114"),        # рабочая машина райцентра, выпускалась до 2013
    ("ВАЗ", "2110"),
    ("ВАЗ", "Priora"),
    ("Lada", "Granta"),
    ("Lada", "Vesta"),
    ("Москвич", "3"),       # НОВЫЙ «Москвич» — обычная современная машина
    ("Kia", "Rio"),
    ("Hyundai", "Solaris"),
    ("Toyota", "Camry"),
    ("УАЗ", "Патриот"),
    ("Chery", "Tiggo 4"),
    ("Haval", "Jolion"),
])
def test_ordinary_cars_are_not_touched(client, make, model):
    """Правило бьёт по цели и не задевает соседа.

    Отдельно про «Москвич»: слова в списке нет намеренно — новый «Москвич 3/6» современная
    машина и есть в перечне локализации. Старые ловятся по кодам 2140/2141/412.
    """
    assert cc.retired_model(make, model) == "", f"{make} {model} зря попала под запрет"


# ==================== 3. Гейт линии ====================
def test_a_retired_car_is_not_allowed_to_work_taxi(client, user_factory):
    """Машина из списка → на линию такси не пускаем."""
    d = user_factory("НаКопейке", role=UserRole.driver)
    _машина(d["id"], "ВАЗ", "21063", year=1988)
    with _сессия() as s:
        assert taxi_mod.car_retired(s, d["id"])
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is False


def test_an_ordinary_car_still_works(client, user_factory):
    """Обычная машина работает как работала — правило не должно ломать рабочих людей."""
    d = user_factory("НаГранте", role=UserRole.driver)
    _машина(d["id"], "Lada", "Granta", year=2019)
    with _сессия() as s:
        assert taxi_mod.car_retired(s, d["id"]) == ""
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_the_refusal_says_why_and_what_still_works(client):
    """Отказ объясняет причину и оставляет человеку путь: попутка работает."""
    текст = taxi_mod.MSG_CAR_RETIRED["ru"]
    assert "опутка" in текст, "не сказали, что попутка работает"
    assert "безопасно" in текст, "не объяснили, почему нельзя"
    assert taxi_mod.MSG_CAR_RETIRED["ba"].strip(), "нет башкирского текста"


def test_an_empty_list_switches_the_rule_off(client, user_factory, monkeypatch):
    """Пустой список в конфиге выключает правило целиком — без релиза приложения."""
    monkeypatch.setattr(settings, "taxi_retired_models", "")
    d = user_factory("НаОке", role=UserRole.driver)
    _машина(d["id"], "ВАЗ", "Ока", year=2007)
    with _сессия() as s:
        assert taxi_mod.car_retired(s, d["id"]) == ""
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_the_list_lives_in_config_and_can_be_edited(client, monkeypatch):
    """Список правится настройкой: добавили модель — правило подхватило её сразу."""
    monkeypatch.setattr(settings, "taxi_retired_models", "матиз")
    assert cc.retired_model("Daewoo", "Matiz") == ""        # латиницей не совпало
    assert cc.retired_model("Дэу", "Матиз") == "матиз"
    assert cc.retired_model("ВАЗ", "2107") == "", "старый список не должен действовать"


# ==================== 4. Пассажир видит возраст машины ====================
def test_the_passenger_sees_the_year_of_the_car(client, user_factory):
    """Год выпуска показываем ДО подачи — иначе «стрёмно» выясняется у подъезда.

    Год приклеен к витринной строке («белая Лада Гранта, 2019»), а не заведён отдельным
    полем: эту строку показывают четыре разных экрана, и новое поле пришлось бы протянуть
    в каждый ради одного числа.
    """
    from app import instant_service as isv
    from app.models import InstantOrder, InstantOrderStatus, User
    from app.timeutil import utcnow

    d = user_factory("СГодом", role=UserRole.driver)
    p = user_factory("ПассажирСГодом")
    _машина(d["id"], "Lada", "Granta", year=2019)
    with _сессия() as s:
        заказ = InstantOrder(
            passenger_id=p["id"], driver_id=d["id"], status=InstantOrderStatus.done,
            from_lat=52.7, from_lng=58.6, to_lat=52.8, to_lng=58.7,
            price_estimate=300, accepted_at=utcnow(), done_at=utcnow(),
        )
        s.add(заказ)
        s.commit()
        s.refresh(заказ)
        карточка = isv.order_payload(s, заказ, s.get(User, p["id"]))
    assert карточка["driver_car"] == "Lada Granta, 2019", карточка["driver_car"]
