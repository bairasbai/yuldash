"""«Куда именно», а не только город: точка забора/вручения у посылки + её приватность.

Раньше у заявки были только from_city/to_city: курьер брал заказ и ехал «в Баймак» — ни дома,
ни квартиры, ни ориентира. У заказа ТАКСИ это давно решено полем comment («за магазином, синие
ворота»); здесь та же идиома для посылок. Поле — СВОБОДНЫЙ текст, а не улица/дом/квартира:
в башкирском селе адрес чаще ориентир («у мечети», «синие ворота»), чем табличка с номером.

Приватность (CLAUDE.md §8) — правило ровно то же, что уже действует для receiver_phone:
- открытый список доступных заказов — адресов НЕТ вообще (его видит любой, кто заказ ещё не взял);
- принявший курьер — оба адреса (ему туда ехать);
- отправитель в своих посылках — оба (он их сам вводил);
- получатель по трек-ссылке — только to_address (его собственный адрес), from_address никогда;
- админ — оба (поддержка и разбор спора, там же где receiver_phone и confirm_code).
"""
from app.models import UserRole

from test_parcels import _create_parcel

_FROM = "ул. Ленина 12, кв. 5, синие ворота"
_TO = "у мечети, дом с зелёной крышей, спросить Гүзәл"


def _create_with_address(client, sender, **overrides):
    body = {"from_address": _FROM, "to_address": _TO}
    body.update(overrides)
    return _create_parcel(client, sender, **body)


# ============================ Создание ============================

def test_addresses_saved_on_create(client, user_factory):
    """Адреса сохраняются как есть, пробелы по краям обрезаем, поля опциональны (старый
    клиент их не шлёт → пустые строки, без 422), длиннее 200 символов — 422."""
    sender = user_factory(name="АдрОтпр")

    p = _create_with_address(client, sender).json()
    assert p["from_address"] == _FROM
    assert p["to_address"] == _TO

    trimmed = _create_with_address(
        client, sender, from_address="   за магазином  ", to_address="  у мечети   ",
    ).json()
    assert trimmed["from_address"] == "за магазином"
    assert trimmed["to_address"] == "у мечети"

    old_client = _create_parcel(client, sender)   # без адресов вообще
    assert old_client.status_code == 200, old_client.text
    assert old_client.json()["from_address"] == ""
    assert old_client.json()["to_address"] == ""

    too_long = _create_with_address(client, sender, to_address="я" * 201)
    assert too_long.status_code == 422, too_long.text


# ============================ Приватность по ролям ============================

def test_available_list_has_no_addresses(client, user_factory):
    """Открытый список заявок: адресов НЕТ вообще. Его видит любой курьер, ещё не взявший
    заказ, — точка «у мечети, спросить Гүзәл» до принятия не должна утекать никому."""
    sender = user_factory(name="АдрОтпр2")
    courier = user_factory(name="АдрКурьер2", role=UserRole.driver)
    pid = _create_with_address(client, sender, from_city="Сибай", to_city="Баймак").json()["id"]

    ra = client.get("/parcels/available", headers=courier["auth"])
    assert ra.status_code == 200, ra.text
    row = next(x for x in ra.json() if x["id"] == pid)
    assert "from_address" not in row
    assert "to_address" not in row
    # и не просочились ни в одно другое поле карточки (description, city, …)
    assert _FROM not in str(row)
    assert _TO not in str(row)


def test_courier_sees_addresses_after_accept(client, user_factory):
    """Принявшему курьеру адреса открываются — там же, где receiver_phone/sender_phone:
    в ответе /accept и в списке «я везу»."""
    sender = user_factory(name="АдрОтпр3")
    courier = user_factory(name="АдрКурьер3", role=UserRole.driver)
    pid = _create_with_address(client, sender, from_city="Сибай", to_city="Баймак").json()["id"]

    rac = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert rac.status_code == 200, rac.text
    assert rac.json()["from_address"] == _FROM
    assert rac.json()["to_address"] == _TO

    rc = client.get("/parcels/carrying", headers=courier["auth"])
    assert rc.status_code == 200, rc.text
    carry = next(x for x in rc.json() if x["id"] == pid)
    assert carry["from_address"] == _FROM
    assert carry["to_address"] == _TO


def test_sender_sees_own_addresses(client, user_factory):
    """Отправитель видит оба адреса в своих посылках — он их сам и вводил."""
    sender = user_factory(name="АдрОтпр4")
    pid = _create_with_address(client, sender).json()["id"]

    rm = client.get("/parcels/mine", headers=sender["auth"])
    assert rm.status_code == 200, rm.text
    mine = next(x for x in rm.json() if x["id"] == pid)
    assert mine["from_address"] == _FROM
    assert mine["to_address"] == _TO


def test_track_link_shows_only_receiver_address(client, user_factory):
    """Трекинг-ссылка публична (кто угодно с токеном): получателю отдаём ТОЛЬКО его
    собственный адрес вручения. Адрес забора — чужие персональные данные, наружу не идёт."""
    sender = user_factory(name="ТрекАдрОтпр")
    # receiver_phone пустой — SMS-шлюз в этом тесте не дёргаем, ссылку отдают руками.
    pid = _create_with_address(client, sender, receiver_phone="").json()["id"]

    rl = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"])
    assert rl.status_code == 200, rl.text
    token = rl.json()["token"]

    st = client.get(f"/t/{token}/state.json")
    assert st.status_code == 200, st.text
    body = st.json()
    assert body["to_address"] == _TO
    assert "from_address" not in body
    assert _FROM not in st.text        # и нигде в теле ответа


def test_admin_sees_both_addresses(client, user_factory):
    """Админ видит оба адреса — там же, где уже видит телефон получателя и код вручения
    (поддержка и разбор спора «не довёз / не открыли»)."""
    sender = user_factory(name="АдрОтпр5")
    admin = user_factory(name="Админ", role=UserRole.admin)
    pid = _create_with_address(client, sender).json()["id"]

    ra = client.get("/admin/parcels", headers=admin["auth"])
    assert ra.status_code == 200, ra.text
    row = next(x for x in ra.json()["parcels"] if x["id"] == pid)
    assert row["from_address"] == _FROM
    assert row["to_address"] == _TO
