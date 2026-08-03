# -*- coding: utf-8 -*-
"""Честные условия удаления аккаунта (аудит 2026-08-03).

`POST /me/delete` раньше сносил всё без единой проверки, и это был не «право на удаление»,
а рабочий способ уйти от обязательств:
  • водитель с неоплаченной комиссией жал «удалить» — долг исчезал вместе с ним;
  • пассажир удалялся посреди поездки — заказ пропадал у водителя прямо в дороге;
  • курьер удалялся с чужой посылкой в руках — у посылки обнулялся курьер, и не оставалось
    даже следа, кто её вёз.

Теперь на каждый случай — 409 с человеческим объяснением на двух языках, а когда мешать
нечему, удаление проходит как раньше.
"""
from sqlmodel import Session, select

from app import models as M
from app.db import engine
from app.models import DebtStatus, InstantOrderStatus as S, User, UserRole


def _detail(r) -> dict:
    """Тело двуязычной ошибки herr → {"ru": ..., "ba": ...}."""
    d = r.json()["detail"]
    assert isinstance(d, dict), f"ожидали двуязычный detail, получили {d!r}"
    return d


def _assert_bilingual(r, *, must_contain_ru: str = "") -> None:
    d = _detail(r)
    assert d.get("ru") and d.get("ba"), f"обе языковые версии обязательны: {d}"
    assert d["ru"] != d["ba"], "башкирский текст не должен быть копией русского"
    if must_contain_ru:
        assert must_contain_ru in d["ru"], d["ru"]


def test_unpaid_taxi_commission_blocks_delete(client, user_factory):
    """Водитель должен платформе комиссию → аккаунт не удаляется, сумма названа."""
    drv = user_factory("ДолжникТакси", role=UserRole.driver)
    with Session(engine) as s:
        s.add(M.CommissionDebt(driver_id=drv["id"], amount_kop=45_000,
                               status=DebtStatus.unpaid))
        s.commit()

    r = client.post("/me/delete", headers=drv["auth"])
    assert r.status_code == 409, r.text
    _assert_bilingual(r, must_contain_ru="450")     # 45 000 коп = 450 ₽ — сумма в тексте

    with Session(engine) as s:                      # долг на месте, аккаунт жив
        assert s.get(User, drv["id"]) is not None
        assert s.exec(select(M.CommissionDebt)
                      .where(M.CommissionDebt.driver_id == drv["id"])).first() is not None


def test_paid_commission_does_not_block_delete(client, user_factory):
    """Долг закрыт (paid) → он больше не держит: удаление проходит."""
    drv = user_factory("ЗаплатилВсё", role=UserRole.driver)
    with Session(engine) as s:
        s.add(M.CommissionDebt(driver_id=drv["id"], amount_kop=45_000, status=DebtStatus.paid))
        s.commit()

    r = client.post("/me/delete", headers=drv["auth"])
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(User, drv["id"]) is None


def test_unpaid_courier_commission_blocks_delete(client, user_factory):
    """Курьер довёз заказы, но комиссию платформе не оплатил → удаление не проходит."""
    cur = user_factory("ДолжникКурьер")
    other = user_factory("Отправитель")
    with Session(engine) as s:
        s.add(M.ParcelDelivery(sender_id=other["id"], courier_id=cur["id"], status="delivered",
                               delivery_type="courier", commission_kop=12_000,
                               commission_paid=False, from_city="Сибай", to_city="Уфа"))
        s.commit()

    r = client.post("/me/delete", headers=cur["auth"])
    assert r.status_code == 409, r.text
    _assert_bilingual(r, must_contain_ru="120")


def test_active_taxi_order_blocks_delete_for_passenger(client, user_factory):
    """Пассажир в поездке → удалиться нельзя (иначе заказ исчезнет у водителя в дороге)."""
    pax = user_factory("ПассажирВПути")
    drv = user_factory("ВодительВПути", role=UserRole.driver)
    with Session(engine) as s:
        s.add(M.InstantOrder(passenger_id=pax["id"], driver_id=drv["id"], status=S.onboard,
                             from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1))
        s.commit()

    r = client.post("/me/delete", headers=pax["auth"])
    assert r.status_code == 409, r.text
    _assert_bilingual(r, must_contain_ru="заказ такси")

    # И водителю тоже нельзя — он сторона той же живой поездки.
    r2 = client.post("/me/delete", headers=drv["auth"])
    assert r2.status_code == 409, r2.text


def test_finished_order_does_not_block_delete(client, user_factory):
    """Завершённая поездка держать не должна — иначе удалиться нельзя было бы никогда."""
    pax = user_factory("ПассажирДоехал")
    with Session(engine) as s:
        s.add(M.InstantOrder(passenger_id=pax["id"], status=S.done,
                             from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1))
        s.commit()

    r = client.post("/me/delete", headers=pax["auth"])
    assert r.status_code == 200, r.text


def test_carrying_parcel_blocks_courier_delete(client, user_factory):
    """Курьер везёт ЧУЖУЮ посылку → уйти молча нельзя: сначала довези или снимись."""
    sender = user_factory("ОтправительПосылки")
    cur = user_factory("КурьерВПути")
    with Session(engine) as s:
        s.add(M.ParcelDelivery(sender_id=sender["id"], courier_id=cur["id"],
                               status="in_transit", from_city="Баймак", to_city="Сибай"))
        s.commit()

    r = client.post("/me/delete", headers=cur["auth"])
    assert r.status_code == 409, r.text
    _assert_bilingual(r, must_contain_ru="везёшь")

    # Отправителю тоже нельзя: посылка физически в пути, курьер её везёт.
    r2 = client.post("/me/delete", headers=sender["auth"])
    assert r2.status_code == 409, r2.text
    _assert_bilingual(r2, must_contain_ru="курьера")


def test_clean_account_still_deletes(client, user_factory):
    """Ни долгов, ни активных заказов → удаление работает как раньше (право не отобрали)."""
    u = user_factory("ЧистыйАккаунт")
    r = client.post("/me/delete", headers=u["auth"])
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        assert s.get(User, u["id"]) is None
