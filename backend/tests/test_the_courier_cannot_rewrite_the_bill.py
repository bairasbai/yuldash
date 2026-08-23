"""Счёт получателю писал курьер, а соглашался на сумму заказчик (волна 157).

**«Купи и привези»: курьер выставлял в пять раз больше.** Женщина просит купить лекарств
на 1000 ₽ и привезти маме в соседнее село. Курьер вводит фактическую стоимость товара —
и никакой связи с согласованной суммой у этого поля не было, держал только общий потолок
в 5000 ₽. То есть можно выставить счёт на 5145 ₽ человеку, который стоит в дверях с пакетом
и решает за секунду: платить или отказаться от лекарств.

Совсем без запаса тоже нельзя — цена в магазине почти никогда не совпадает с ожиданием.
Поэтому запас есть (больший из 15% и 100 ₽), а выше — пусть сумму поднимет сам заказчик:
это его деньги. Дверь для этого пришлось открыть: без неё отказ курьеру превращался в тупик,
товар уже куплен, а провести расчёт нечем.

**Оплата комиссии гасила чужие долги.** Курьер платит накопленную комиссию, и сумма к оплате
считается только по курьерским заказам. А помечались оплаченными все доставленные подряд —
включая попутные, которые в эту сумму не входили. Курьер платит 50 ₽ своего долга, и заодно
списывается комиссия по попутной доставке на 300 ₽. Платформа не получит эти деньги никогда
и даже не узнает: в отчёте они выглядят оплаченными.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, Payment, UserRole
from app.routers.courier import (COURIER_GOODS_OVERRUN_MIN_KOP, COURIER_GOODS_OVERRUN_PERCENT,
                                 goods_limit_kop)
from app.timeutil import utcnow


@pytest.fixture
def доставка_включена(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True)


def _курьер(user_factory, метка: str):
    from app.models import CourierApplication, CourierProfile
    человек = user_factory(метка, role=UserRole.driver)
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == человек["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=человек["id"])
        заявка.status = "approved"
        заявка.reviewed_at = utcnow()
        s.add(заявка)
        if s.exec(select(CourierProfile).where(
                CourierProfile.user_id == человек["id"])).first() is None:
            s.add(CourierProfile(user_id=человек["id"]))
        s.commit()
    return человек


def _заказ_на_покупку(client, user_factory, метка: str, согласовано_коп: int):
    """Заказчик просит купить товар на согласованную сумму, курьер берёт заказ."""
    заказчик = user_factory(метка + "Заказчик")
    курьер = _курьер(user_factory, метка + "Курьер")
    создан = client.post("/courier/orders", headers=заказчик["auth"], json={
        "from_city": "Баймак", "to_city": "Баймак",
        "from_address": "ул. Мира 1", "to_address": "ул. Ленина 2",
        "delivery_type": "buy_bring", "cod_amount_kop": согласовано_коп,
        "shopping_list": "лекарства по списку", "receiver_name": "Гульнара",
        "receiver_phone": "+79990001122", "size": "small", "rules_accepted": True,
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.60, "to_lng": 58.33,
    })
    assert создан.status_code == 200, создан.text
    oid = создан.json()["id"]
    assert client.post(f"/parcels/{oid}/accept", headers=курьер["auth"]).status_code == 200
    return заказчик, курьер, oid


def test_курьер_не_выставит_счёт_в_пять_раз_больше(client, user_factory, доставка_включена):
    """Главное: сумму счёта определяет тот, кто на неё согласился."""
    _, курьер, oid = _заказ_на_покупку(client, user_factory, "Счёт", 100_000)

    ответ = client.post(f"/courier/orders/{oid}/goods-cost", headers=курьер["auth"],
                        json={"actual_kop": 500_000})

    assert ответ.status_code == 422, (
        f"курьер выставил товар на 5000 ₽ там, где согласились на 1000 ₽ "
        f"(ответ {ответ.status_code}): человек в дверях решает за секунду, платить ли впятеро"
    )
    assert "ba" in ответ.text, "отказ не на двух языках"
    with Session(engine) as s:
        assert (s.get(ParcelDelivery, oid).goods_actual_kop or 0) == 0, "сумма всё-таки записана"


def test_подорожало_в_магазине_провести_можно(client, user_factory, доставка_включена):
    """Обратная сторона: цена в магазине почти никогда не совпадает с ожиданием."""
    _, курьер, oid = _заказ_на_покупку(client, user_factory, "Запас", 100_000)

    ответ = client.post(f"/courier/orders/{oid}/goods-cost", headers=курьер["auth"],
                        json={"actual_kop": 111_000})          # +110 ₽ к тысяче

    assert ответ.status_code == 200, (
        f"честное подорожание на 110 ₽ не провести: {ответ.text[:200]}. Курьер купил товар "
        "и не может закрыть расчёт"
    )
    assert ответ.json()["settlement"]["goods_actual_kop"] == 111_000


def test_маленькому_заказу_запас_не_копеечный():
    """На заказе в 200 ₽ пятнадцать процентов — это тридцать рублей, то есть почти ничего."""
    предел = goods_limit_kop(20_000)

    assert предел >= 20_000 + COURIER_GOODS_OVERRUN_MIN_KOP, (
        f"запас на маленьком заказе {предел - 20_000} коп: одна поднявшаяся цена — и расчёт встал"
    )


def test_большому_заказу_запас_процентный():
    """А на большом фиксированные сто рублей уже ничего не значат."""
    предел = goods_limit_kop(400_000)

    assert предел - 400_000 == 400_000 * COURIER_GOODS_OVERRUN_PERCENT // 100, (
        f"на заказе в 4000 ₽ запас посчитан не процентом: {предел - 400_000} коп"
    )


def test_запас_не_пробивает_общий_потолок():
    """Пять тысяч — граница, за которую курьер не тратит свои деньги ни при каком запасе."""
    from app.routers.courier import COURIER_COD_CAP_KOP

    assert goods_limit_kop(COURIER_COD_CAP_KOP) == COURIER_COD_CAP_KOP, (
        "запас поднял предел выше общего потолка — курьер рискует своими деньгами сверх лимита"
    )


def test_заказчик_поднимает_сумму_сам(client, user_factory, доставка_включена):
    """Без этой двери отказ курьеру был бы тупиком: товар куплен, расчёт провести нечем."""
    заказчик, курьер, oid = _заказ_на_покупку(client, user_factory, "Подъём", 100_000)

    поднял = client.post(f"/courier/orders/{oid}/raise-budget", headers=заказчик["auth"],
                         json={"cod_amount_kop": 500_000})

    assert поднял.status_code == 200, f"заказчик не может поднять сумму: {поднял.text[:200]}"
    теперь = client.post(f"/courier/orders/{oid}/goods-cost", headers=курьер["auth"],
                         json={"actual_kop": 500_000})
    assert теперь.status_code == 200, (
        f"сумму подняли, а расчёт всё равно не провести: {теперь.text[:200]}"
    )


def test_курьер_не_поднимает_сумму_себе(client, user_factory, доставка_включена):
    """Иначе дверь бессмысленна: тот, кто выставляет счёт, сам бы себе и разрешал."""
    _, курьер, oid = _заказ_на_покупку(client, user_factory, "Сам", 100_000)

    ответ = client.post(f"/courier/orders/{oid}/raise-budget", headers=курьер["auth"],
                        json={"cod_amount_kop": 500_000})

    assert ответ.status_code == 404, (
        f"курьер поднял сумму своего же счёта (ответ {ответ.status_code})"
    )


def test_сумму_нельзя_опустить_задним_числом(client, user_factory, доставка_включена):
    """Только вверх: иначе заказчик после покупки срезал бы сумму и не заплатил."""
    заказчик, _, oid = _заказ_на_покупку(client, user_factory, "Вниз", 100_000)

    ответ = client.post(f"/courier/orders/{oid}/raise-budget", headers=заказчик["auth"],
                        json={"cod_amount_kop": 10_000})

    assert ответ.status_code == 422, (
        f"согласованную сумму урезали задним числом (ответ {ответ.status_code}): курьер уже "
        "потратил свои деньги"
    )


def _доставка(session, отправитель_id: int, курьер_id: int, тип: str, комиссия: int) -> int:
    p = ParcelDelivery(sender_id=отправитель_id, courier_id=курьер_id, status="delivered",
                       delivery_type=тип, commission_kop=комиссия, commission_paid=False,
                       from_city="Баймак", to_city="Сибай", delivered_at=utcnow())
    session.add(p)
    session.commit()
    session.refresh(p)
    return p.id


def test_оплата_комиссии_не_гасит_попутные_доставки(client, user_factory):
    """Курьер платит за курьерские заказы — попутные в эту сумму не входили."""
    курьер = _курьер(user_factory, "ЧужойДолг")
    отправитель = user_factory("ЧужойДолгОтправитель")
    with Session(engine) as s:
        попутная = _доставка(s, отправитель["id"], курьер["id"], "poputka", 30_000)
        курьерская = _доставка(s, отправитель["id"], курьер["id"], "courier", 5_000)

    from app.routers.payments import _activate_payment
    with Session(engine) as s:
        платёж = Payment(user_id=курьер["id"], purpose="courier_commission",
                         amount_kop=5_000, status="pending", method="yookassa")
        s.add(платёж)
        s.commit()
        s.refresh(платёж)
        _activate_payment(s, платёж)

    with Session(engine) as s:
        assert s.get(ParcelDelivery, попутная).commission_paid is False, (
            "курьер заплатил 50 ₽ своего долга, а погасилась ещё и попутная доставка на 300 ₽: "
            "в отчёте эти деньги выглядят полученными, хотя их не было"
        )
        assert s.get(ParcelDelivery, курьерская).commission_paid is True, (
            "свой долг курьер оплатил, а он не погасился — теперь платит второй раз"
        )
