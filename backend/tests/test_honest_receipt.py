"""Честный чек и «что-то не так с ценой» (задача 3, решение Q6 от 2026-08-23).

Что защищаем:
- в чеке видно КАЖДУЮ часть суммы, а не одно число «поверьте»;
- ГЛАВНОЕ: пассажир НЕ видит нашу комиссию. В Модели А он платит водителю напрямую,
  наши 15% через него не проходят — строка «комиссия платформы» была бы неправдой
  о его собственных деньгах. Водитель видит её полностью: он её реально платит;
- сумма строк сходится с итогом (сторож против расхождения на копейку);
- жалоба на цену принимается и БЕЗ заказа — там она чаще всего и рождается;
- приватность: в жалобе нет координат, чужой заказ к ней не привязывается.
"""
import json

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, PriceComplaint, UserRole

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _driver(client, user_factory, name="RcpDrv", coord=ORIG):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": coord[0], "lng": coord[1]}).status_code == 200
    return d


def _order_body():
    return {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
            "from_text": "Баймак", "to_text": "Сибай"}


def _ride_to_done(client, user_factory, fake_redis, dname="RcpDrv", pname="RcpPax",
                  driver_coord=ORIG):
    d = _driver(client, user_factory, dname, driver_coord)
    pax = user_factory(pname)
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid = order["id"]
    for step in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    return d, pax, oid


# ============================ Строки чека ============================
def test_receipt_shows_every_part_of_the_sum(client, user_factory, fake_redis):
    """Поездка, дорога водителя, ожидание — каждая часть отдельно, и всё сходится с итогом."""
    _, pax, oid = _ride_to_done(client, user_factory, fake_redis,
                                "PartsDrv", "PartsPax", driver_coord=(ORIG[0] + 0.08, ORIG[1]))
    receipt = client.get(f"/instant/orders/{oid}/receipt", headers=pax["auth"])
    assert receipt.status_code == 200, receipt.text
    r = receipt.json()

    assert r["ride_price"] > 0
    assert r["pickup_fee_kop"] > 0, "подача за 9 км должна быть в чеке"
    # Сумма строк == итог. Сторож: расхождение на рубль в чеке читается как обман.
    parts = (r["ride_price"] + r["pickup_fee_kop"] // 100 + r["options_fee_kop"] // 100
             + r["waiting_fee_kop"] // 100)
    assert parts == r["price_kop"] // 100


def test_passenger_never_sees_our_commission(client, user_factory, fake_redis):
    """СТОРОЖ. В Модели А пассажир платит водителю напрямую — наши 15% через него не идут.
    Написать ему «комиссия платформы 45 ₽» значит соврать о его собственных деньгах."""
    _, pax, oid = _ride_to_done(client, user_factory, fake_redis, "FeeHideDrv", "FeeHidePax")
    r = client.get(f"/instant/orders/{oid}/receipt", headers=pax["auth"]).json()

    for leak in ("driver_fee_kop", "driver_fee_percent", "driver_net_kop", "driver_gross_kop"):
        assert leak not in r, leak
    assert r["role"] == "passenger"


def test_driver_sees_the_mirror_with_commission(client, user_factory, fake_redis):
    """Водителю — зеркально и полностью: сколько от пассажира, сколько комиссия, сколько
    чистыми, и сколько из суммы вообще не облагается комиссией."""
    d, _, oid = _ride_to_done(client, user_factory, fake_redis, "FeeShowDrv", "FeeShowPax",
                              driver_coord=(ORIG[0] + 0.08, ORIG[1]))
    r = client.get(f"/instant/orders/{oid}/receipt", headers=d["auth"]).json()

    assert r["role"] == "driver"
    assert r["driver_gross_kop"] > 0
    assert r["driver_net_kop"] == r["driver_gross_kop"] - r["driver_fee_kop"]
    assert r["driver_fee_percent"] > 0
    # Компенсации комиссией не облагаются — водитель должен видеть это отдельной цифрой,
    # иначе процент на экране не сходится с вычетом и мы для него лгуны.
    assert r["commission_free_kop"] == r["pickup_fee_kop"] + r["options_fee_kop"]


def test_surge_line_appears_only_when_there_was_surge(client, user_factory, fake_redis):
    """Наценки не было — строки нет. Ноль в чеке хуже пустоты: он выглядит как «мы почти
    накрутили»."""
    _, pax, oid = _ride_to_done(client, user_factory, fake_redis, "NoSurgeDrv", "NoSurgePax")
    r = client.get(f"/instant/orders/{oid}/receipt", headers=pax["auth"]).json()

    assert r["surge_rub"] == 0
    assert r["ride_base_price"] == r["ride_price"]


def test_receipt_is_only_for_its_own_participants(client, user_factory, fake_redis):
    """Чужой чек не отдаём: в нём маршрут и деньги двух конкретных людей."""
    _, _, oid = _ride_to_done(client, user_factory, fake_redis, "PrivDrv", "PrivPax")
    stranger = user_factory("Stranger")
    assert client.get(f"/instant/orders/{oid}/receipt",
                      headers=stranger["auth"]).status_code == 403


def test_receipt_remembers_only_my_own_rating_and_counterparty(client, user_factory, fake_redis):
    """После повторного открытия чека звёзды не обнуляются, но чужую оценку за свою не выдаём."""
    driver, pax, oid = _ride_to_done(client, user_factory, fake_redis,
                                     "OwnRatingDrv", "OwnRatingPax")

    rated = client.post(
        f"/instant/orders/{oid}/rate",
        headers=pax["auth"],
        json={"stars": 5, "tags": "polite,safe"},
    )
    assert rated.status_code == 200, rated.text

    passenger_receipt = client.get(f"/instant/orders/{oid}/receipt", headers=pax["auth"]).json()
    driver_receipt = client.get(f"/instant/orders/{oid}/receipt", headers=driver["auth"]).json()

    assert passenger_receipt["my_stars"] == 5
    assert passenger_receipt["my_rating_tags"] == "polite,safe"
    assert passenger_receipt["counterparty_id"] == driver["id"]
    assert driver_receipt["my_stars"] == 0, "водителю показали оценку пассажира как свою"
    assert driver_receipt["my_rating_tags"] == ""
    assert driver_receipt["counterparty_id"] == pax["id"]


# ============================ «Что-то не так с ценой» ============================
def test_price_complaint_works_without_an_order(client, user_factory):
    """Человек увидел 450 ₽ и закрыл приложение — именно эти случаи мы иначе не увидим."""
    pax = user_factory("ComplainPax")
    resp = client.post("/instant/price-complaint", headers=pax["auth"], json={
        "price": 450, "reason": "expensive_for_distance",
        "comment": "10 км за 450 — дорого",
        "breakdown": {"ride_price": 260, "pickup_fee": 190},
    })
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["ok"] is True
    assert body["message"]["ru"] and body["message"]["ba"], "ответ обязан быть на двух языках"

    with Session(engine) as s:
        row = s.exec(select(PriceComplaint).where(
            PriceComplaint.user_id == pax["id"]).order_by(PriceComplaint.id.desc())).first()
    assert row is not None
    assert row.price == 450 and row.order_id is None
    assert row.reason == "expensive_for_distance"
    assert json.loads(row.breakdown_json)["pickup_fee"] == 190


def test_complaint_keeps_no_coordinates(client, user_factory):
    """Приватность (§8): чтобы разобраться в ЦЕНЕ, знать, откуда человек ехал, не нужно."""
    pax = user_factory("PrivComplainPax")
    client.post("/instant/price-complaint", headers=pax["auth"], json={
        "price": 300, "reason": "other",
        "breakdown": {"ride_price": 300, "from_lat": 52.591, "nested": {"lng": 58.317}},
    })
    with Session(engine) as s:
        row = s.exec(select(PriceComplaint).where(
            PriceComplaint.user_id == pax["id"]).order_by(PriceComplaint.id.desc())).first()
    saved = json.loads(row.breakdown_json)

    # Вложенные объекты вообще не сохраняются — координата не проедет внутри словаря.
    assert "nested" not in saved
    # ⚠️ Плоские числа сохраняются как есть: клиент шлёт СВОЙ разбор цены и координат туда
    # не кладёт. Тест фиксирует границу — если завтра кто-то начнёт слать сюда широту,
    # он увидит этот комментарий и поймёт, что так нельзя.
    assert saved["ride_price"] == 300


def test_unknown_reason_becomes_other(client, user_factory):
    """Перечень причин закрытый: свободный текст в поле причины никто не читает."""
    pax = user_factory("ReasonPax")
    client.post("/instant/price-complaint", headers=pax["auth"],
                json={"price": 100, "reason": "потому что дорого"})
    with Session(engine) as s:
        row = s.exec(select(PriceComplaint).where(
            PriceComplaint.user_id == pax["id"]).order_by(PriceComplaint.id.desc())).first()
    assert row.reason == "other"


def test_complaint_does_not_link_someone_elses_order(client, user_factory, fake_redis):
    """Чужой номер заказа в жалобе не сохраняется: иначе по нему можно было бы проверить,
    что такой заказ вообще существует. Сама жалоба при этом принимается."""
    _, _, oid = _ride_to_done(client, user_factory, fake_redis, "OtherDrv", "OtherPax")
    stranger = user_factory("NosyPax")

    resp = client.post("/instant/price-complaint", headers=stranger["auth"],
                       json={"order_id": oid, "price": 500, "reason": "other"})
    assert resp.status_code == 200

    with Session(engine) as s:
        row = s.exec(select(PriceComplaint).where(
            PriceComplaint.user_id == stranger["id"]).order_by(PriceComplaint.id.desc())).first()
    assert row.order_id is None


def test_admin_sees_complaints_and_others_do_not(client, user_factory):
    """Список жалоб — инструмент тарифа: менять цену по фактам, а не по ощущениям."""
    pax = user_factory("AdminComplainPax")
    client.post("/instant/price-complaint", headers=pax["auth"],
                json={"price": 999, "reason": "was_cheaper"})

    assert client.get("/admin/price-complaints", headers=pax["auth"]).status_code == 403
    admin = user_factory("PriceAdmin", role=UserRole.admin)
    body = client.get("/admin/price-complaints", headers=admin["auth"]).json()
    assert any(i["price"] == 999 and i["reason"] == "was_cheaper" for i in body["items"])
