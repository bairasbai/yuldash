# -*- coding: utf-8 -*-
"""Телефон в открытом поле помечается — во ВСЕХ открытых полях, а не в части.

Аудит 2026-08-12, волна 40. Проверка открытого текста (`antifraud.moderate_open_text`) ловит
телефоны и попытки увести человека мимо приложения. Её донесли до комментариев, описаний
и отзывов — 18 мест. И не донесли до двух полей, которые видит столько же людей:

  • марка, модель, цвет и НОМЕР машины — их читает каждый пассажир в карточке поездки;
  • адреса заказа такси «откуда» и «куда» — их читает водитель в оффере, а ещё любой,
    кому дали ссылку слежения (`/t/{token}` показывает маршрут).

Телефон в поле «Куда» работает как объявление «звони мимо приложения»: комиссию платформа
не получит, а человек останется без записи поездки, если что-то случится.

Тесты — от человека: «вписал телефон в номер машины / в адрес» → метка появилась.
"""
import pytest
from sqlmodel import Session, select

from test_instant import fake_redis  # noqa: F401 — фикстура Redis для заказов такси

from app.db import engine
from app.models import TextFlag, UserRole


def _flags(user_id: int, place: str) -> int:
    with Session(engine) as s:
        return len(s.exec(
            select(TextFlag).where(TextFlag.user_id == user_id, TextFlag.place == place)
        ).all())


@pytest.fixture
def driver(user_factory):
    return user_factory("Водитель с номером в марке", role=UserRole.driver)


def test_телефон_в_данных_машины_помечается(client, driver):
    """Карточку машины видит каждый пассажир — это такое же открытое поле, как комментарий."""
    before = _flags(driver["id"], "car_profile")
    r = client.post("/driver/profile", headers=driver["auth"], json={
        "car_make": "Лада", "car_model": "Гранта звони 89871234567",
        "car_color": "белый", "car_plate": "А123БВ102", "seats": 4,
    })
    assert r.status_code == 200, r.text
    assert _flags(driver["id"], "car_profile") == before + 1, "телефон в марке машины проехал мимо"


def test_обычная_машина_метку_не_получает(client, driver):
    """Ложных тревог быть не должно: честный водитель не должен ничего замечать."""
    before = _flags(driver["id"], "car_profile")
    r = client.post("/driver/profile", headers=driver["auth"], json={
        "car_make": "Лада", "car_model": "Веста", "car_color": "серый",
        "car_plate": "В456ГД102", "seats": 4,
    })
    assert r.status_code == 200, r.text
    assert _flags(driver["id"], "car_profile") == before


def test_телефон_в_адресе_заказа_помечается(client, user_factory, fake_redis):
    """Адрес пишет человек руками, и его читает водитель — правило то же, что для комментария."""
    pax = user_factory("Пассажир с телефоном в адресе")
    before = _flags(pax["id"], "order_comment")
    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.71, "to_lng": 58.66,
        "from_text": "Баймак, ул. Ленина 1",
        "to_text": "Сибай, звони 89871234567",
        "comment": "",
    })
    assert r.status_code in (200, 201), r.text
    assert _flags(pax["id"], "order_comment") == before + 1, "телефон в поле «Куда» проехал мимо"


def test_обычный_адрес_метку_не_получает(client, user_factory, fake_redis):
    before_user = user_factory("Пассажир с обычным адресом")
    before = _flags(before_user["id"], "order_comment")
    r = client.post("/instant/orders", headers=before_user["auth"], json={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.71, "to_lng": 58.66,
        "from_text": "Баймак, ул. Ленина 1", "to_text": "Сибай, автовокзал", "comment": "",
    })
    assert r.status_code in (200, 201), r.text
    assert _flags(before_user["id"], "order_comment") == before
