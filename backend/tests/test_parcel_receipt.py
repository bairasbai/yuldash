"""Чек за доставку — такой же, как за попутку и за такси.

Аудит 2026-08-06. Квитанция была у попутки (`/trips/{id}/receipt`) и у такси
(`/instant/orders/{id}/receipt`), а у доставки её не было — хотя деньги там настоящие:
цена доставки, комиссия платформы, а у «купи и привези» ещё и стоимость товара, которую
курьер потратил из своего кармана.

Правила, которые сторожит файл:
  • чек видят обе стороны — и отправитель, и курьер, посторонний не видит;
  • до завершения доставки чека нет: итоговых сумм ещё не существует;
  • телефонов и адресов в чеке нет — чеком делятся, а адрес получателя это его дом.
"""
from sqlmodel import Session

from app.db import engine
from app.models import ParcelDelivery


def _parcel(sender_id, courier_id=None, *, status="delivered", **kw):
    with Session(engine) as s:
        p = ParcelDelivery(
            sender_id=sender_id, courier_id=courier_id, status=status,
            from_city="Сибай", to_city="Уфа",
            receiver_name="Гуля", receiver_phone="+79170009999",
            delivery_price_kop=30000, commission_kop=3000,
            **kw,
        )
        s.add(p)
        s.commit()
        s.refresh(p)
        return p


def test_sender_sees_the_receipt(client, user_factory):
    sender = user_factory("ParcelRcpSender")
    courier = user_factory("ParcelRcpCourier")
    p = _parcel(sender["id"], courier["id"])

    r = client.get(f"/parcels/{p.id}/receipt", headers=sender["auth"])
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["role"] == "sender"
    assert d["delivery_price_kop"] == 30000
    assert d["commission_kop"] == 3000
    assert d["from_city"] == "Сибай" and d["to_city"] == "Уфа"


def test_courier_sees_the_same_receipt(client, user_factory):
    sender = user_factory("ParcelRcpSender2")
    courier = user_factory("ParcelRcpCourier2")
    p = _parcel(sender["id"], courier["id"])

    r = client.get(f"/parcels/{p.id}/receipt", headers=courier["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["role"] == "courier"


def test_stranger_gets_nothing(client, user_factory):
    sender = user_factory("ParcelRcpSender3")
    courier = user_factory("ParcelRcpCourier3")
    stranger = user_factory("ParcelRcpStranger")
    p = _parcel(sender["id"], courier["id"])

    r = client.get(f"/parcels/{p.id}/receipt", headers=stranger["auth"])
    assert r.status_code == 403, f"чужой чек отдали постороннему: {r.text}"


def test_no_receipt_while_still_carrying(client, user_factory):
    """Итоговых сумм ещё нет: товар может стоить не столько, сколько заявляли."""
    sender = user_factory("ParcelRcpSender4")
    courier = user_factory("ParcelRcpCourier4")
    p = _parcel(sender["id"], courier["id"], status="in_transit")

    r = client.get(f"/parcels/{p.id}/receipt", headers=sender["auth"])
    assert r.status_code == 409, f"чек выдали до завершения: {r.text}"


def test_receipt_never_leaks_phone_or_address(client, user_factory):
    """Чеком делятся. Телефон получателя и его адрес в нём появиться не должны."""
    sender = user_factory("ParcelRcpSender5")
    courier = user_factory("ParcelRcpCourier5")
    p = _parcel(sender["id"], courier["id"], to_address="Ленина 12, кв 5")

    r = client.get(f"/parcels/{p.id}/receipt", headers=courier["auth"])
    assert r.status_code == 200, r.text
    body = r.text
    assert "+79170009999" not in body, "телефон получателя утёк в чек"
    assert "Ленина 12" not in body, "адрес получателя утёк в чек"


def test_buy_and_bring_shows_what_courier_actually_spent(client, user_factory):
    """«Купи и привези»: получатель возвращает фактически потраченное, а не заявленное."""
    sender = user_factory("ParcelRcpSender6")
    courier = user_factory("ParcelRcpCourier6")
    p = _parcel(sender["id"], courier["id"], delivery_type="buy_bring",
                cod_amount_kop=50000, goods_actual_kop=47350)

    d = client.get(f"/parcels/{p.id}/receipt", headers=courier["auth"]).json()
    assert d["goods_kop"] == 47350, "в чеке заявленная сумма вместо фактической"
    assert d["total_kop"] == 47350 + 30000, "итог не сходится: доставка + товар"


def test_returned_parcel_also_has_receipt(client, user_factory):
    """Вернули отправителю — это тоже финал, и он тоже требует документа."""
    sender = user_factory("ParcelRcpSender7")
    courier = user_factory("ParcelRcpCourier7")
    p = _parcel(sender["id"], courier["id"], status="returned")

    r = client.get(f"/parcels/{p.id}/receipt", headers=sender["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "returned"
