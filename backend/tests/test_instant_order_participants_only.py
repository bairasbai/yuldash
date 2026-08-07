"""Заказ такси показывается ТОЛЬКО его участникам (аудит 2026-08-07).

Что было не так. Витрина заказа (`instant_service.order_payload`) решала, что показать,
по РОЛИ смотрящего: «водитель» — если он назначен или ему сейчас предложен заказ, иначе
«пассажир». Проверки «а ты вообще имеешь отношение к этому заказу?» не было нигде, а
17 ручек такси отдают эту витрину. Посторонний человек получал пассажирскую витрину чужого
заказа: **точную точку подачи и телефон водителя**.

Два входа, найденные аудитом:

* `POST /instant/orders/{id}/decline` — «отклонить оффер». Ручка идемпотентна: чужому она
  ничего не меняет, просто возвращает заказ. И вместе с ним — витрину.
* `POST /instant/orders/{id}/arrived` — «я на месте». Гео-проверка стояла ДО проверки прав:
  ответ 409 «ты ещё не на месте» против 200 отвечал на вопрос «водитель в 500 м от точки
  подачи?» для ЛЮБОГО заказа. Это гео-оракул: перебором координат чужая точка подачи
  находится без всякой витрины.

Почему это дорого именно здесь. Точка подачи — это адрес, откуда человек уезжает: как
правило, его дом. Телефон водителя мы намеренно открываем только после принятия заказа.
Приложение «между своими» в Баймаке, где все друг друга знают, — цена утечки выше, чем
в большом городе, а не ниже.

Починка — в корне, а не в двух ручках: витрина сама отказывает постороннему, и гео-проверка
«Я на месте» перенесена ПОСЛЕ проверки прав.
"""
import pytest
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, User, UserRole


ORIG = (52.5900, 58.3100)      # Баймак
DEST = (52.7200, 58.6600)      # Сибай


@pytest.fixture
def accepted_order(user_factory):
    """Живой принятый заказ: телефон водителя и точная точка подачи уже раскрыты — пассажиру."""
    pax = user_factory("Пассажир")
    drv = user_factory("Водитель", role=UserRole.driver)
    phone = f"+7999000{drv['id']:04d}"       # уникальный на каждый прогон фикстуры
    with Session(engine) as s:
        s.get(User, drv["id"]).phone = phone
        order = InstantOrder(
            passenger_id=pax["id"], driver_id=drv["id"], status=S.accepted,
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            from_text="Баймак, Ленина 12", to_text="Сибай",
            price_estimate=300, distance_km=42.0, eta_min=40,
        )
        s.add(order)
        s.commit()
        s.refresh(order)
        return {"id": order.id, "pax": pax, "drv": drv, "phone": phone}


def test_stranger_cannot_read_order_through_decline(client, user_factory, accepted_order):
    """Главная дыра: посторонний «отклоняет» чужой оффер и получает витрину заказа."""
    stranger = user_factory("Посторонний", role=UserRole.driver)
    r = client.post(f"/instant/orders/{accepted_order['id']}/decline", headers=stranger["auth"])

    assert r.status_code == 403, f"чужой заказ отдался постороннему: {r.text}"
    body = r.text
    assert accepted_order["phone"] not in body, "утёк телефон водителя"
    assert "Ленина 12" not in body, "утёк адрес подачи"


def test_stranger_cannot_probe_pickup_point_through_arrived(client, user_factory, accepted_order):
    """Гео-оракул: разный ответ на «я на месте» выдаёт, где точка подачи чужого заказа."""
    stranger = user_factory("Посторонний2", role=UserRole.driver)

    near = client.post(f"/instant/orders/{accepted_order['id']}/arrived",
                       headers=stranger["auth"], json={"lat": ORIG[0], "lng": ORIG[1]})
    far = client.post(f"/instant/orders/{accepted_order['id']}/arrived",
                      headers=stranger["auth"], json={"lat": DEST[0], "lng": DEST[1]})

    assert near.status_code == 403, f"чужой смог отметиться на месте: {near.text}"
    assert far.status_code == 403
    assert near.status_code == far.status_code, (
        "ответ зависит от координат → перебором находится чужая точка подачи"
    )


def test_stranger_cannot_read_order_through_get(client, user_factory, accepted_order):
    """Прямое чтение чужого заказа по номеру — та же дыра, самый очевидный вход."""
    stranger = user_factory("Посторонний3")
    r = client.get(f"/instant/orders/{accepted_order['id']}", headers=stranger["auth"])
    assert r.status_code in (403, 404), f"чужой заказ читается напрямую: {r.text}"


# --- обратная сторона: свои по-прежнему всё видят -------------------------------------------

def test_passenger_still_sees_own_order(client, accepted_order):
    """Починка не должна отобрать у пассажира его собственный заказ."""
    r = client.get(f"/instant/orders/{accepted_order['id']}", headers=accepted_order["pax"]["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["driver_phone"] == accepted_order["phone"], "пассажир обязан видеть телефон водителя"


def test_assigned_driver_still_sees_own_order(client, accepted_order):
    """И у назначенного водителя тоже."""
    r = client.get(f"/instant/orders/{accepted_order['id']}", headers=accepted_order["drv"]["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["role"] == "driver"


def test_offered_driver_sees_the_offer(client, user_factory):
    """Водитель, которому заказ ПРЕДЛОЖЕН, ещё не назначен — но видеть оффер обязан,
    иначе такси не работает вообще."""
    pax = user_factory("Пассажир2")
    drv = user_factory("Кандидат", role=UserRole.driver)
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=pax["id"], status=S.offered, current_offer_driver_id=drv["id"],
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            from_text="Баймак", to_text="Сибай",
            price_estimate=300, distance_km=42.0, eta_min=40,
        )
        s.add(order)
        s.commit()
        s.refresh(order)
        oid = order.id

    r = client.get(f"/instant/orders/{oid}", headers=drv["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["role"] == "driver"
    # Приватность до accept сохраняется: точка подачи округлена.
    assert r.json()["from_lat"] == round(ORIG[0], 2)

    # А отказаться от предложенного — по-прежнему можно.
    assert client.post(f"/instant/orders/{oid}/decline", headers=drv["auth"]).status_code == 200


def test_admin_also_goes_through_admin_handles(client, user_factory, accepted_order):
    """Админ пассажирскую ручку НЕ открывает — и до аудита не открывал (`_order_for_view`).
    Разбор споров и «Пульс такси» у него свои эндпоинты; расширять пассажирскую витрину
    ради админа значит ослаблять её для всех."""
    admin = user_factory("Админ", role=UserRole.admin)
    r = client.get(f"/instant/orders/{accepted_order['id']}", headers=admin["auth"])
    assert r.status_code == 403
