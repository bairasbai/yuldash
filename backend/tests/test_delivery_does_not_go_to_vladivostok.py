# -*- coding: utf-8 -*-
"""Тот же промах по карте — но в доставке, и цена ошибки выше (волна 188).

Волна 187 закрыла потолок расстояния у такси. Первое, что я сделал дальше, — проверил
соседнюю дверь, где деньги считаются так же. Дыра оказалась слово в слово та же:

    доставка Уфа → Владивосток (7034 км)
    цена доставки: 140 778 ₽
    комиссия платформы курьеру: 4 223 ₽
    порог, после которого курьера не пускают к заказам: 1 000 ₽

То есть один соскользнувший палец отправителя вешал на курьера долг вчетверо выше порога
блокировки. Чтобы вернуться к работе, ему пришлось бы заплатить платформе четыре тысячи
за доставку, которой не было.

Почему число одно на два сервиса. География у нас одна: Башкортостан и соседние регионы.
Два отдельных потолка («для такси» и «для доставки») разошлись бы при первой правке — этот
след в проекте уже есть у чата, кнопки «застрял» и показа суммы отмены. Поэтому в конфиге
одно `max_trip_km`, и обе двери спрашивают его.
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, UserRole
from conftest import upload_doc

УФА = (54.7351, 55.9587)
СИБАЙ = (52.716, 58.664)              # 372 км — живой межгород по республике
ВЛАДИВОСТОК = (43.1155, 131.8855)     # промах по карте


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _make_courier(client, user_factory, name):
    admin = user_factory(name=f"Админ{name}", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    client.post("/courier/online", headers=c["auth"], json={"zone": "region"})
    return c


def _оценка(client, кто, куда):
    return client.get("/courier/estimate", headers=кто["auth"], params={
        "from_lat": УФА[0], "from_lng": УФА[1], "to_lat": куда[0], "to_lng": куда[1],
        "size": "small", "urgency": "bypath",
    })


def _заказ(client, кто, куда, телефон):
    return client.post("/courier/orders", headers=кто["auth"], json={
        "from_city": "Уфа", "to_city": "Куда-то",
        "from_lat": УФА[0], "from_lng": УФА[1], "to_lat": куда[0], "to_lng": куда[1],
        "size": "small", "description": "Коробка", "receiver_name": "Айгуль",
        "receiver_phone": телефон, "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
    })


def test_доставка_через_полстраны_не_оформляется(client, user_factory):
    """Главное: чужой промах не должен становиться долгом курьера."""
    отправитель = _make_courier(client, user_factory, "Отпр188Промах")

    r = _заказ(client, отправитель, ВЛАДИВОСТОК, "+79990001881")

    assert r.status_code == 422, (
        f"заказ Уфа → Владивосток принят ({r.status_code}): курьер довезёт «коробку», получит "
        "комиссию 4 223 ₽ при пороге блокировки 1 000 ₽ и потеряет доступ к заказам"
    )


def test_человеку_говорят_куда_смотреть(client, user_factory):
    """Отправитель уверен, что адрес верный: без подсказки отказ — тупик."""
    отправитель = _make_courier(client, user_factory, "Отпр188Текст")

    detail = _заказ(client, отправитель, ВЛАДИВОСТОК, "+79990001882").json()["detail"]

    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"], detail
    assert "карт" in detail["ru"].lower(), f"не сказано, что проверить: {detail['ru']!r}"


def test_оценка_и_заказ_отвечают_одинаково(client, user_factory):
    """Цену показать и заказ не дать — худший из исходов: человек не поймёт, что не так."""
    отправитель = _make_courier(client, user_factory, "Отпр188Двери")

    оценка = _оценка(client, отправитель, ВЛАДИВОСТОК)
    заказ = _заказ(client, отправитель, ВЛАДИВОСТОК, "+79990001883")

    assert оценка.status_code == заказ.status_code == 422, (
        f"двери разошлись: оценка {оценка.status_code}, заказ {заказ.status_code}"
    )


def test_число_одно_на_такси_и_доставку(client, user_factory):
    """Один потолок на оба сервиса: два числа разъедутся при первой правке."""
    отправитель = _make_courier(client, user_factory, "Отпр188Одно")
    пассажир = user_factory("Пасс188Одно")

    доставка = _заказ(client, отправитель, ВЛАДИВОСТОК, "+79990001884")
    такси = client.post("/instant/estimate", headers=пассажир["auth"], json={
        "from_lat": УФА[0], "from_lng": УФА[1],
        "to_lat": ВЛАДИВОСТОК[0], "to_lng": ВЛАДИВОСТОК[1],
    })

    assert доставка.status_code == такси.status_code == 422, (
        f"такси и доставка судят по-разному: такси {такси.status_code}, "
        f"доставка {доставка.status_code}"
    )


# --------------------------- обратная сторона ---------------------------

def test_межгород_по_республике_возим_как_прежде(client, user_factory):
    """Перестраховка не должна отнять живые направления: Уфа → Сибай это 372 км."""
    отправитель = _make_courier(client, user_factory, "Отпр188Норма")

    оценка = _оценка(client, отправитель, СИБАЙ)
    заказ = _заказ(client, отправитель, СИБАЙ, "+79990001885")

    assert оценка.status_code == 200, f"оценка обычной доставки сломалась: {оценка.text[:200]}"
    assert заказ.status_code == 200, f"обычная доставка перестала оформляться: {заказ.text[:200]}"
    assert оценка.json()["distance_km"] > 300, оценка.json()


def test_доставка_доезжает_и_комиссия_прежняя(client, user_factory):
    """Полный путь живого заказа: правка не должна тронуть деньги нормальной доставки."""
    отправитель = _make_courier(client, user_factory, "Отпр188Полный")
    курьер = _make_courier(client, user_factory, "Кур188Полный")
    pid = _заказ(client, отправитель, СИБАЙ, "+79990001886").json()["id"]

    assert client.post(f"/parcels/{pid}/accept", headers=курьер["auth"]).status_code == 200
    client.post(f"/parcels/{pid}/status", headers=курьер["auth"], json={"status": "in_transit"})
    with Session(engine) as s:
        код = s.get(ParcelDelivery, pid).confirm_code
    r = client.post(f"/parcels/{pid}/status", headers=курьер["auth"],
                    json={"status": "delivered", "code": код})

    assert r.status_code == 200, r.text
    чек = client.get(f"/parcels/{pid}/receipt", headers=курьер["auth"]).json()
    assert чек["commission_kop"] > 0, "комиссия за доставку перестала считаться"
    assert чек["commission_kop"] < 100_000, (
        f"комиссия {чек['commission_kop'] // 100} ₽ за обычную доставку — это уже блокировка"
    )


def test_потолок_правится_настройкой(client, user_factory, monkeypatch):
    """Границы районов и планы меняются — число должно править конфиг, а не пересборка."""
    отправитель = _make_courier(client, user_factory, "Отпр188Настройка")
    monkeypatch.setattr(settings, "max_trip_km", 10.0)

    r = _оценка(client, отправитель, СИБАЙ)      # 372 км — теперь за потолком

    assert r.status_code == 422, "потолок захардкожен: настройка ни на что не влияет"
