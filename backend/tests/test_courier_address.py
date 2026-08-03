"""«Куда именно» у заказов профессионального курьера (courier | buy_bring).

Адреса завели у обычной посылки (test_parcel_address.py), но заказы платного режима создаются
своим телом — CourierOrderIn, POST /courier/orders. Полей там не было, и заказ всегда сохранялся
с пустым адресом: курьер брал ПЛАТНУЮ доставку и ехал «в Стерлитамак» — ни дома, ни подъезда,
ни ориентира. Здесь это нужнее, чем в попутке: человек платит именно за «привези на этот адрес».

Приватность — та же, что у посылок (CLAUDE.md §8), правило одно на оба входа:
- /courier/available (открытый список, его видит любой курьер на линии) — адресов НЕТ вообще;
- отправитель в своих заказах — оба (он их сам вводил);
- принявший курьер, получатель по трек-ссылке и админ — уже покрыты общими сериализаторами.
"""
import pytest

from app.config import settings
from app.models import UserRole

from test_courier import _make_courier, _order

_FROM = "ул. Гагарина 7, подъезд 2, домофон 15"
_TO = "за автостанцией, синий дом, спросить Илдара"


@pytest.fixture(autouse=True)
def _courier_on():
    """Режим курьера включён: без него POST /courier/orders отдаёт 403 (гейт режима)."""
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _order_with_address(client, sender, **overrides):
    body = {"from_address": _FROM, "to_address": _TO}
    body.update(overrides)
    return _order(client, sender, **body)


# ============================ Создание ============================

def test_addresses_saved_on_courier_order(client, user_factory):
    """Адреса сохраняются как есть, пробелы по краям обрезаем, поля опциональны (старый клиент
    их не шлёт → пустые строки, без 422), длиннее 200 символов — 422."""
    sender = user_factory(name="КурАдрОтпр")

    r = _order_with_address(client, sender)
    assert r.status_code == 200, r.text
    assert r.json()["from_address"] == _FROM
    assert r.json()["to_address"] == _TO

    trimmed = _order_with_address(
        client, sender, from_address="   у школы  ", to_address="  третий подъезд   ",
    ).json()
    assert trimmed["from_address"] == "у школы"
    assert trimmed["to_address"] == "третий подъезд"

    old_client = _order(client, sender)   # без адресов вообще
    assert old_client.status_code == 200, old_client.text
    assert old_client.json()["from_address"] == ""
    assert old_client.json()["to_address"] == ""

    too_long = _order_with_address(client, sender, to_address="я" * 201)
    assert too_long.status_code == 422, too_long.text


def test_addresses_saved_on_buy_bring_order(client, user_factory):
    """«Купи и привези» — то же тело, адрес нужен ровно так же: курьер везёт покупку домой."""
    sender = user_factory(name="КурАдрОтпрBB")
    r = _order_with_address(client, sender, delivery_type="buy_bring", cod_amount_kop=50_000,
                            shopping_list="хлеб, молоко")
    assert r.status_code == 200, r.text
    assert r.json()["from_address"] == _FROM
    assert r.json()["to_address"] == _TO


# ============================ Приватность по ролям ============================

def test_courier_available_has_no_addresses(client, user_factory):
    """Открытый список курьер-заказов: адресов НЕТ вообще. Его видит любой курьер на линии,
    ещё не взявший заказ, — «синий дом, спросить Илдара» до принятия не утекает никому."""
    sender = user_factory(name="КурАдрОтпр2")
    courier = _make_courier(client, user_factory)
    pid = _order_with_address(client, sender).json()["id"]

    ra = client.get("/courier/available", headers=courier["auth"])
    assert ra.status_code == 200, ra.text
    row = next(x for x in ra.json() if x["id"] == pid)
    assert "from_address" not in row
    assert "to_address" not in row
    # и не просочились ни в одно другое поле карточки (description, city, …)
    assert _FROM not in str(row)
    assert _TO not in str(row)


def test_sender_sees_own_courier_addresses(client, user_factory):
    """Отправитель видит оба адреса в своих заказах — он их сам и вводил."""
    sender = user_factory(name="КурАдрОтпр3")
    pid = _order_with_address(client, sender).json()["id"]

    rm = client.get("/parcels/mine", headers=sender["auth"])
    assert rm.status_code == 200, rm.text
    mine = next(x for x in rm.json() if x["id"] == pid)
    assert mine["from_address"] == _FROM
    assert mine["to_address"] == _TO
