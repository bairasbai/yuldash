"""Между «курьер не застал» и «плати за возврат» появилась ступенька (2026-08-29).

ЗАЧЕМ. После того как возврат стал платным для отправителя, обнажилась дыра рядом. Человек
вышел в магазин на два часа — и это стоило отправителю почти полной стоимости доставки,
потому что других вариантов у него не было: либо чудом вручить, либо везти обратно.

У Royal Mail в этом месте восемнадцать дней и заказ передоставки, у UPS — платный повторный
выезд. Мы берём от UPS главное правило: платит тот, кто ПОПРОСИЛ изменение. Отправитель
нажимает «получатель уже дома, заедь ещё раз» — заезд оплачивается как половина маршрута.
Курьер, заехавший по своей инициативе, счётчик не двигает: иначе попытки крутятся в одиночку,
а платит отправитель.

И потолок, которого нет ни у кого на рынке: вся компенсация не дороже самой доставки.
UPS в той же ситуации берёт вдвое — новую отправку плюс 100% исходной.

Здесь проверяем:
  • попросил заехать → курьеру доплата за половину маршрута;
  • не просил → доплаты нет, сколько бы курьер ни ездил сам;
  • одна просьба на одну попытку и не больше предела;
  • просить может только отправитель и только после неудачной попытки;
  • компенсация никогда не дороже доставки;
  • оценка возврата видна ДО заказа — без неё плата юридически висит в воздухе.
"""
import pytest

from app.config import settings
from app.routers import courier as cr


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


from test_courier_c4 import _make_courier, _order  # noqa: F401,E402 — общие помощники
from test_courier_catches_up import _посылка  # noqa: F401,E402
from test_return_pays_the_road import _до_двери, _вернуть  # noqa: F401,E402


def _не_застал(client, courier, pid, причина="Никого нет дома"):
    r = client.post(f"/parcels/{pid}/attempt-failed", headers=courier["auth"],
                    json={"reason": причина})
    assert r.status_code == 200, r.text
    return r


def _проси_заехать(client, кто, pid, текст="Он уже дома"):
    return client.post(f"/parcels/{pid}/redeliver-request", headers=кто["auth"],
                       json={"reason": текст})


# ==================== 1. Попросил заехать — заезд оплачивается ====================
def test_a_requested_second_trip_is_paid(client, user_factory):
    """Отправитель попросил заехать ещё раз — курьеру доплачивают за этот крюк.

    Раньше три заезда оплачивались как один: `route_kop` считался по маршруту и не зависел
    от числа поездок. Курьер, съездивший дважды по просьбе, вёз второй конец за свой счёт.
    """
    courier = _make_courier(client, user_factory, name="КурьерЗаездОплата")
    sender = user_factory("ОтпрЗаездОплата")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)

    было = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert было["redeliver_kop"] == 0, "заездов ещё не просили, а доплата уже есть"

    assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid, "и во второй раз никого")

    стало = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert стало["redeliveries"] == 1
    ожидаем = round(стало["route_kop"] * settings.courier_redeliver_km_k)
    assert стало["redeliver_kop"] == ожидаем, "второй заезд оплачен не по половине маршрута"
    assert стало["total_kop"] > было["total_kop"], "курьер съездил дважды, а получил как за раз"


def test_the_courier_cannot_pay_himself_by_driving_again(client, user_factory):
    """Курьер ездит сам — доплаты нет. Просьба отправителя, а не поездка, открывает деньги.

    Иначе «заехал ещё разок» накручивается в одиночку, а счёт приходит отправителю.
    """
    courier = _make_courier(client, user_factory, name="КурьерСамЕздит")
    sender = user_factory("ОтпрСамЕздит")
    pid = _до_двери(client, courier, sender)
    for _ in range(3):
        _не_застал(client, courier, pid)

    части = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert части["attempts"] == 3
    assert части["redeliveries"] == 0 and части["redeliver_kop"] == 0, (
        "курьер накрутил попытки сам, а платит за них отправитель"
    )


# ==================== 2. Границы просьбы ====================
def test_one_request_per_failed_attempt(client, user_factory):
    """Одна просьба на одну попытку: кнопку нельзя нажать десять раз, пока курьер едет."""
    courier = _make_courier(client, user_factory, name="КурьерОднаПросьба")
    sender = user_factory("ОтпрОднаПросьба")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)

    assert _проси_заехать(client, sender, pid).status_code == 200
    второй = _проси_заехать(client, sender, pid)
    assert второй.status_code == 409, "просьбу приняли дважды на одну попытку"
    assert _посылка(pid).redeliver_requests == 1


def test_asking_before_the_courier_arrived_makes_no_sense(client, user_factory):
    """Курьер ещё не приезжал — просить «заедь ещё раз» не о чем."""
    courier = _make_courier(client, user_factory, name="КурьерРаноПросить")
    sender = user_factory("ОтпрРаноПросить")
    pid = _до_двери(client, courier, sender)
    assert _проси_заехать(client, sender, pid).status_code == 409


def test_there_is_a_ceiling_on_paid_trips(client, user_factory):
    """Бесконечно ездить никто не обязан: сверх предела остаётся только возврат."""
    courier = _make_courier(client, user_factory, name="КурьерПределЗаездов")
    sender = user_factory("ОтпрПределЗаездов")
    pid = _до_двери(client, courier, sender)
    предел = int(settings.courier_redeliver_max)

    for i in range(предел):
        _не_застал(client, courier, pid, f"попытка {i + 1}")
        assert _проси_заехать(client, sender, pid).status_code == 200

    _не_застал(client, courier, pid, "последняя")
    сверх = _проси_заехать(client, sender, pid)
    assert сверх.status_code == 409, "предел повторных заездов не работает"
    assert _посылка(pid).redeliver_requests == предел


def test_only_the_sender_may_ask(client, user_factory):
    """Просит тот, кто платит. Курьер и посторонний — мимо."""
    courier = _make_courier(client, user_factory, name="КурьерЧужаяПросьба")
    sender = user_factory("ОтпрЧужаяПросьба")
    чужой = user_factory("ПостороннийПросьба")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)

    assert _проси_заехать(client, courier, pid).status_code == 404
    assert _проси_заехать(client, чужой, pid).status_code == 404
    assert _посылка(pid).redeliver_requests == 0


# ==================== 3. Потолок ====================
def test_compensation_never_costs_more_than_the_delivery(client, user_factory):
    """Сколько бы заездов ни было — отправитель не платит больше, чем стоила бы доставка.

    У UPS в этой же ситуации счёт вдвое больше исходного: возврат тарифицируется как новая
    отправка плюс 100% первой. Потолок «не дороже самой доставки» — то, чего нет ни у кого.
    """
    courier = _make_courier(client, user_factory, name="КурьерПотолок")
    sender = user_factory("ОтпрПотолок")
    pid = _до_двери(client, courier, sender)
    for _ in range(int(settings.courier_redeliver_max)):
        _не_застал(client, courier, pid)
        assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid)

    p = _посылка(pid)
    части = cr.courier_return_fee_parts_kop(p)
    сырое = (части["route_kop"] + части["redeliver_kop"]
             + части["pickup_kop"] + части["waiting_kop"])
    assert сырое > p.delivery_price_kop, "тест не проверяет потолок: сумма и так мала"
    assert части["total_kop"] == p.delivery_price_kop
    assert части["capped_kop"] == сырое - p.delivery_price_kop, "срезанное не показано человеку"

    _вернуть(client, courier, pid)
    assert _посылка(pid).return_fee_kop == p.delivery_price_kop


def test_the_cap_does_not_eat_an_honest_single_trip(client, user_factory):
    """Одна поездка без просьб в потолок не упирается — иначе он резал бы честную дорогу."""
    courier = _make_courier(client, user_factory, name="КурьерПотолокЧестный")
    sender = user_factory("ОтпрПотолокЧестный")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)

    части = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert части["capped_kop"] == 0
    assert части["total_kop"] < части["cap_kop"]


# ==================== 4. Предупреждение ДО заказа ====================
def test_the_sender_is_warned_before_he_orders(client, user_factory):
    """Сколько будет стоить возврат — видно ДО заказа, а не после.

    Конституционный суд (декабрь 2022) признал недопустимым брать плату за возврат
    с человека, которого о ней заранее не предупредили. Без этой строки на экране заказа
    наша компенсация юридически висит в воздухе.
    """
    sender = user_factory("ОтпрПредупреждён")
    r = client.get("/courier/estimate", headers=sender["auth"], params={
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "urgency": "bypath",
    })
    assert r.status_code == 200, r.text
    ответ = r.json()
    разбор = ответ["breakdown"]
    оценка = разбор["return_fee_estimate_kop"]
    assert оценка > 0, "человек заказывает, не зная цены возврата"
    assert оценка <= ответ["price_kop"], "оценка возврата дороже самой доставки"
    assert оценка == min(разбор["distance_kop"] + разбор["pickup_kop"], ответ["price_kop"])


# ==================== 5. Что видят клиенты ====================
def test_the_button_opens_and_closes_at_the_right_time(client, user_factory):
    """Кнопку «заедь ещё раз» показывает СЕРВЕР: у клиента нет ни попыток, ни предела."""
    courier = _make_courier(client, user_factory, name="КурьерКнопка")
    sender = user_factory("ОтпрКнопка")
    pid = _до_двери(client, courier, sender)

    def карточка():
        r = client.get("/parcels/mine", headers=sender["auth"])
        assert r.status_code == 200, r.text
        тело = r.json()
        строки = тело if isinstance(тело, list) else тело.get("items", [])
        return next((x for x in строки if x.get("id") == pid), None)

    до = карточка()
    assert до is not None and до["can_request_redelivery"] is False, (
        "кнопка открыта, хотя курьер ещё не приезжал"
    )
    assert до["redeliver_max"] == int(settings.courier_redeliver_max)

    _не_застал(client, courier, pid)
    assert карточка()["can_request_redelivery"] is True

    assert _проси_заехать(client, sender, pid).status_code == 200
    после = карточка()
    assert после["can_request_redelivery"] is False, "можно попросить дважды на одну попытку"
    assert после["redeliver_requests"] == 1


def test_the_receipt_shows_whose_decision_it_was(client, user_factory):
    """В чеке видно, что заезды заказал сам отправитель — это не наша надбавка."""
    courier = _make_courier(client, user_factory, name="КурьерЧекЗаезды")
    sender = user_factory("ОтпрЧекЗаезды")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid)
    _вернуть(client, courier, pid)

    чек = client.get(f"/parcels/{pid}/receipt", headers=sender["auth"]).json()
    assert чек["redeliver_requests"] == 1
    assert чек["delivery_attempts_final"] >= 2
    assert чек["return_fee_kop"] == _посылка(pid).return_fee_kop


def test_the_courier_is_told_the_trip_is_paid(client, user_factory):
    """Курьер видит доплату в своей карточке — иначе он читает просьбу как «съезди даром»."""
    courier = _make_courier(client, user_factory, name="КурьерВидитДоплату")
    sender = user_factory("ОтпрВидитДоплату")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid)

    r = client.get("/parcels/carrying", headers=courier["auth"])
    тело = r.json()
    строки = тело if isinstance(тело, list) else тело.get("items", [])
    моя = next((x for x in строки if x.get("id") == pid), None)
    assert моя is not None
    части = моя["return_fee_parts"]
    assert части["redeliveries"] == 1 and части["redeliver_kop"] > 0


def test_a_request_without_a_second_trip_is_not_paid(client, user_factory):
    """Попросил заехать, но курьер повёз обратно, не поехав — доплаты нет.

    Платим за пересечение просьб и реальных поездок, а не за нажатую кнопку. Иначе
    отправитель платил бы за заезд, которого не было.
    """
    courier = _make_courier(client, user_factory, name="КурьерПросилНеЕздил")
    sender = user_factory("ОтпрПросилНеЕздил")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)
    assert _проси_заехать(client, sender, pid).status_code == 200

    части = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert части["redeliveries"] == 0 and части["redeliver_kop"] == 0, (
        "заплатили за заезд, которого не было"
    )

    _вернуть(client, courier, pid)
    p = _посылка(pid)
    assert p.redeliver_requests == 1, "просьба должна остаться в истории"
    assert p.return_fee_kop == части["total_kop"]


def test_the_dialog_knows_the_price_of_the_next_trip(client, user_factory):
    """Во сколько обойдётся СЛЕДУЮЩИЙ заезд — считает сервер, а не телефон.

    Это ДОПЛАТА к счёту, а не цена строки: у самого потолка половина маршрута в счёт уже
    не влезает, и человеку надо показать то, что он реально доплатит. Клиент, посчитавший
    это сам, показал бы половину маршрута — и ошибся бы ровно там, где цена важнее всего.
    """
    courier = _make_courier(client, user_factory, name="КурьерЦенаЗаезда")
    sender = user_factory("ОтпрЦенаЗаезда")
    pid = _до_двери(client, courier, sender)
    _не_застал(client, courier, pid)

    было = cr.courier_return_fee_parts_kop(_посылка(pid))
    доплата = было["next_redeliver_kop"]
    assert доплата > 0, "человеку нечего показать в диалоге подтверждения"
    assert доплата <= round(было["route_kop"] * settings.courier_redeliver_km_k)
    assert доплата <= было["cap_kop"] - было["total_kop"], "обещали больше, чем пустит потолок"

    assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid)
    стало = cr.courier_return_fee_parts_kop(_посылка(pid))
    assert стало["total_kop"] - было["total_kop"] == доплата, (
        "показали одну доплату, начислили другую"
    )


def test_the_ceiling_closes_the_next_trip_price(client, user_factory):
    """Предел заездов исчерпан — цена следующего ноль, кнопки в клиенте нет."""
    courier = _make_courier(client, user_factory, name="КурьерЦенаПосле")
    sender = user_factory("ОтпрЦенаПосле")
    pid = _до_двери(client, courier, sender)
    for _ in range(int(settings.courier_redeliver_max)):
        _не_застал(client, courier, pid)
        assert _проси_заехать(client, sender, pid).status_code == 200
    _не_застал(client, courier, pid)

    assert cr.courier_return_fee_parts_kop(_посылка(pid))["next_redeliver_kop"] == 0
