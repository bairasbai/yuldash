"""Витрина заказов профессионального курьера тоже помнит блокировку.

История. Рамиль возит посылки как курьер. Один из отправителей, Гульназ, его заблокировала —
после прошлой доставки пересекаться она больше не хочет. Приём заказа приложение ей обещало
и обещание держало: нажатие «Взять» отвечало отказом. Но её заказы продолжали висеть у Рамиля
в списке доступных. Он видел маршрут, вес и деньги, тянулся за заказом — и упирался в отказ
без объяснения (аудит 2026-08-08, волна 73).

Обычная лента посылок «по пути» это понимала и такие заказы прятала, объясняя причину прямо
в комментарии: «человеку незачем видеть в ленте того, с кем он не хочет пересекаться». До
профессиональной витрины правило не дошло — там фильтра не было вовсе.

Обратная сторона обязательна: чужие заказы курьер видеть должен, иначе он останется без работы.
"""
from __future__ import annotations

from test_courier import _courier_on, _make_courier, _order  # noqa: F401  (_courier_on — фикстура)


def _ids(client, who, door: str) -> list[int]:
    r = client.get(door, headers=who["auth"])
    assert r.status_code == 200, r.text
    return [x["id"] for x in r.json()]


def test_витрина_курьера_прячет_заблокировавшего(client, user_factory, _courier_on):
    ramil = _make_courier(client, user_factory)
    gulnaz = user_factory(name="ЗаблокировалаГульназ")
    other = user_factory(name="ОбычныйОтправитель")

    hidden_id = _order(client, gulnaz).json()["id"]
    visible_id = _order(client, other).json()["id"]

    r = client.post("/blocks", headers=gulnaz["auth"], json={"blocked_user_id": ramil["id"]})
    assert r.status_code in (200, 201), r.text

    ids = _ids(client, ramil, "/courier/available")
    assert hidden_id not in ids, "курьер видит заказ человека, который его заблокировал"
    assert visible_id in ids, "витрина опустела — курьер остался без работы"


def test_отказ_на_взятии_остался(client, user_factory, _courier_on):
    """Пряча заказ из витрины, нельзя ослабить сам запрет: прямая ссылка (старый пуш,
    открытый экран) обходит любой фильтр списка."""
    ramil = _make_courier(client, user_factory)
    gulnaz = user_factory(name="ПрямаяСсылкаГульназ")
    pid = _order(client, gulnaz).json()["id"]

    r = client.post("/blocks", headers=gulnaz["auth"], json={"blocked_user_id": ramil["id"]})
    assert r.status_code in (200, 201), r.text

    rac = client.post(f"/parcels/{pid}/accept", headers=ramil["auth"])
    assert rac.status_code == 403, f"запрет на взятие ослаб: {rac.status_code} {rac.text[:200]}"
