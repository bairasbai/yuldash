"""Тариф открывается живыми водителями, а не теми, у кого просрочены документы.

История. Класс машины («Комфорт», «Бизнес») показывается пассажирам района только после того,
как там набралось достаточно подтверждённых водителей. Правило простое и правильное: пустая
кнопка тарифа дороже отсутствующей — человек один раз ткнул, не дождался машины и удалил
приложение.

Набор считался по флагу «документы просрочены», а флаг ставит фоновая задача (аудит
2026-08-08, волна 100). Между тем днём, когда у водителя кончилось ОСАГО, и приходом задачи
он для набора выглядел живым. Проверено запросом: класс открывался тремя водителями, у двоих
из которых страховка кончилась в 2020 году. Возить эти двое не могут — гейт такси их уже
не пускает, — а кнопку тарифа они району открыли.

Теперь набор считает по датам, той же функцией, что и гейт с ночной задачей: одно решение
на всех, чтобы они не разъехались.

Обратная сторона: водитель без заполненных сроков (поля появились позже живого приложения)
из набора выпадать не должен — иначе тариф закроется у тех, кто просто не успел обновиться.
"""
from __future__ import annotations

from datetime import date

import pytest
from sqlmodel import Session, select

from app import class_rollout as cr
from app.db import engine
from app.models import (DriverProfile, TaxiApplication, TaxiApplicationStatus, User, UserRole)

# У каждого теста свой город: набор считается по месту, и водители соседнего теста
# попадали бы в тот же счётчик — тест проверял бы чужие данные.
CITIES = {
    "просрочка": "Баймак",
    "живой": "Сибай",
    "без сроков": "Учалы",
    "флаг": "Белорецк",
}


@pytest.fixture
def make_driver(client, user_factory):
    def _make(tag: str, osago_until: date | None, city: str, classes: str = "standard,comfort"):
        user = user_factory(tag, role=UserRole.driver)
        with Session(engine) as s:
            person = s.get(User, user["id"])
            person.verified = True          # набор считает только проверенных
            s.add(person)

            profile = s.exec(select(DriverProfile).where(DriverProfile.user_id == user["id"])).first()
            if profile is None:
                profile = DriverProfile(user_id=user["id"])
            profile.work_city = city
            profile.car_classes_available = classes
            s.add(profile)

            application = s.exec(
                select(TaxiApplication).where(TaxiApplication.user_id == user["id"])
            ).first()
            if application is None:
                application = TaxiApplication(user_id=user["id"])
            application.status = TaxiApplicationStatus.approved
            application.docs_expired = False      # фоновая задача ещё не приходила
            application.osago_until = osago_until
            s.add(application)
            s.commit()
        return user

    return _make


def _counts(city: str) -> dict[str, int]:
    """Счётчик набора для места, к которому относится этот город."""
    with Session(engine) as s:
        from app import geo
        place = cr.place_of_area(geo.area_by_name(s, city)) or city
        return cr.class_counts(s, place)


def test_водитель_с_истёкшим_осаго_не_открывает_тариф(make_driver):
    city = CITIES["просрочка"]
    make_driver("НаборПросроченный1", date(2020, 1, 1), city)
    make_driver("НаборПросроченный2", date(2020, 6, 1), city)

    assert _counts(city)["comfort"] == 0, (
        "водители с ОСАГО из 2020 года посчитаны в наборе: район увидит кнопку тарифа, "
        "по которой никто не приедет"
    )


def test_живой_водитель_считается(make_driver):
    """Обратная сторона: строгость не должна закрывать тариф у тех, кто в порядке."""
    city = CITIES["живой"]
    make_driver("НаборЖивой", date(2030, 1, 1), city)

    assert _counts(city)["comfort"] >= 1, "водитель с действующими документами выпал из набора"


def test_водитель_без_заполненных_сроков_остаётся(make_driver):
    """Поля со сроками появились позже живого приложения: пустое — не повод выкидывать."""
    city = CITIES["без сроков"]
    make_driver("НаборБезСроков", None, city)

    assert _counts(city)["comfort"] >= 1, (
        "водителя без заполненного ОСАГО выкинули из набора — так тариф закроется "
        "у тех, кто просто не успел обновить анкету"
    )


def test_флаг_из_фоновой_задачи_тоже_учитывается(client, user_factory, make_driver):
    """Если задача уже отметила просрочку — доверяем и ей: даты и флаг не спорят."""
    city = CITIES["флаг"]
    user = make_driver("НаборФлаг", date(2030, 1, 1), city)
    with Session(engine) as s:
        application = s.exec(
            select(TaxiApplication).where(TaxiApplication.user_id == user["id"])
        ).first()
        application.osago_until = date(2020, 1, 1)   # задача проставила флаг ПО ЭТОЙ дате
        application.docs_expired = True
        s.add(application)
        s.commit()

    assert _counts(city)["comfort"] == 0, "водитель с отмеченной просрочкой всё ещё в наборе"
