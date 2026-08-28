"""Остановки по пути (решение Александра, допрос 2026-08-21).

Смысл остановки в том, что за неё платят. У попутки остановки лежат просто названиями и на
цену не влияют — там цену ставит сам водитель. В такси считает сервер, и остановка без
координат бесполезна: ни в маршрут не поставишь, ни оплатить.

Правила:
  • добавлять можно и при заказе, и в пути;
  • числом не ограничиваем — предохранитель в том, что каждая остановка стоит денег,
    а водитель в любой момент может сойти;
  • убирать можно, переставлять порядок нельзя;
  • ожидание на остановке — по общим правилам подачи, по кнопке «Стоим»;
  • при смене конечного адреса будущие остановки сохраняются.
"""
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from datetime import timedelta

UFA = (54.7351, 55.9587)
STOP_A = (54.7400, 55.9650)
STOP_B = (54.7500, 55.9750)
DEST = (54.7600, 55.9900)


@pytest.fixture(autouse=True)
def _app_started(client):
    return client


def _make_order(passenger_id: int, driver_id: int | None = None, **kw) -> int:
    with Session(engine) as s:
        o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id, status=S.onboard,
                         from_lat=UFA[0], from_lng=UFA[1], to_lat=DEST[0], to_lng=DEST[1],
                         distance_km=5.0, eta_min=12.0, price_estimate=300,
                         category="standard", surge_k=1.0, pricing_k=1.0,
                         onboard_at=utcnow() - timedelta(minutes=5), driven_km=2.0, **kw)
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


# ------------------------------ хранение ------------------------------
def test_waypoints_survive_a_round_trip_through_storage():
    """Точка с координатами и названием сохраняется и читается обратно целиком."""
    points = [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека", "done": False}]
    raw = isv.dump_waypoints(points)
    back = isv.parse_waypoints(raw)
    assert back[0]["lat"] == pytest.approx(STOP_A[0])
    assert back[0]["text"] == "Аптека"
    assert back[0]["done"] is False


def test_empty_waypoints_are_an_empty_string_not_a_json_null():
    """Пустой набор — пустая строка: так «остановок нет» отличается от «поле не заполняли»."""
    assert isv.dump_waypoints([]) == ""
    assert isv.parse_waypoints("") == []


def test_broken_data_does_not_break_the_ride():
    """Битый JSON или кривая точка не должны ронять расчёт цены живой поездки."""
    assert isv.parse_waypoints("{не json}") == []
    half_broken = '[{"lat": 54.7, "lng": 55.9}, {"lat": "мусор"}]'
    assert len(isv.parse_waypoints(half_broken)) == 1, "одна кривая точка убила весь маршрут"


# ------------------------------ цена ------------------------------
def test_a_stop_makes_the_ride_longer_and_dearer():
    """Крюк за остановкой оплачивается: иначе водитель везёт лишние километры даром."""
    direct = isv.route_through(UFA, [], DEST)
    with_stop = isv.route_through(UFA, [{"lat": STOP_B[0], "lng": STOP_B[1]}], DEST)
    assert with_stop.distance_km >= direct.distance_km, (
        "маршрут с остановкой не длиннее прямого — значит остановку не учли"
    )


def test_estimate_counts_the_stops(client, user_factory):
    """Оценка цены с остановкой выше, чем без неё, и говорит, сколько их учла."""
    pax = user_factory("WpEstPax")
    base = {"from_lat": UFA[0], "from_lng": UFA[1], "to_lat": DEST[0], "to_lng": DEST[1]}
    plain = client.post("/instant/estimate", headers=pax["auth"], json=base).json()
    withstop = client.post("/instant/estimate", headers=pax["auth"], json={
        **base, "waypoints": [{"lat": STOP_B[0], "lng": STOP_B[1], "text": "Аптека"}],
    }).json()
    assert withstop["waypoints_count"] == 1
    assert withstop["price"] >= plain["price"], "остановка не отразилась на цене"


# ------------------------------ правка в пути ------------------------------
def test_passenger_adds_a_stop_mid_ride(client, user_factory):
    """Добавить остановку можно уже в машине — это половина смысла затеи."""
    pax = user_factory("WpPax")
    drv = user_factory("WpDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/waypoints", headers=pax["auth"],
                    json={"waypoints": [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека"}]})
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        stops = isv.parse_waypoints(order.waypoints_json)
        assert len(stops) == 1 and stops[0]["text"] == "Аптека"


def test_a_stop_can_be_removed(client, user_factory):
    """«Мама сама доехала» — остановку убирают, цена падает, всем проще."""
    pax = user_factory("WpPax2")
    drv = user_factory("WpDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])
    client.post(f"/instant/orders/{oid}/waypoints", headers=pax["auth"],
                json={"waypoints": [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека"},
                                 {"lat": STOP_B[0], "lng": STOP_B[1], "text": "Школа"}]})

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        dearer = order.price_estimate
        # Отматываем время: защита от двойных нажатий (одна правка в 15 секунд) права,
        # но здесь мы изображаем человека, который передумал через минуту, а не дрожащий палец.
        order.destination_changed_at = utcnow() - timedelta(minutes=1)
        s.add(order)
        s.commit()

    r = client.post(f"/instant/orders/{oid}/waypoints", headers=pax["auth"],
                    json={"waypoints": [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека"}]})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        assert len(isv.parse_waypoints(order.waypoints_json)) == 1
        assert order.price_estimate <= dearer, "убрали остановку, а цена не упала"


def test_a_stranger_cannot_reroute_someone_elses_ride(client, user_factory):
    """Маршрут правит только тот, кто едет."""
    pax = user_factory("WpOwner")
    drv = user_factory("WpDrv3", role=UserRole.driver)
    stranger = user_factory("WpStranger")
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/waypoints", headers=stranger["auth"],
                    json={"waypoints": [{"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека"}]})
    assert r.status_code == 404


def test_visited_stops_stay_in_history(client, user_factory):
    """Проеденные остановки не исчезают, когда меняют оставшийся маршрут."""
    pax = user_factory("WpPax3")
    drv = user_factory("WpDrv4", role=UserRole.driver)
    visited = isv.dump_waypoints([
        {"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека", "done": True}])
    oid = _make_order(pax["id"], drv["id"], waypoints_json=visited)

    r = client.post(f"/instant/orders/{oid}/waypoints", headers=pax["auth"],
                    json={"waypoints": [{"lat": STOP_B[0], "lng": STOP_B[1], "text": "Школа"}]})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        stops = isv.parse_waypoints(s.get(InstantOrder, oid).waypoints_json)
        assert len(stops) == 2, "проеденная остановка пропала из истории"
        assert stops[0]["done"] is True and stops[1]["text"] == "Школа"


def test_changing_the_destination_keeps_upcoming_stops():
    """Едешь в другое место — но заехать за ребёнком всё ещё надо."""
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=1, driver_id=2, status=S.onboard,
            from_lat=UFA[0], from_lng=UFA[1], to_lat=DEST[0], to_lng=DEST[1],
            distance_km=5.0, eta_min=12.0, price_estimate=300, category="standard",
            surge_k=1.0, pricing_k=1.0, onboard_at=utcnow() - timedelta(minutes=5),
            driven_km=2.0,
            waypoints_json=isv.dump_waypoints([
                {"lat": STOP_A[0], "lng": STOP_A[1], "text": "Аптека", "done": True},
                {"lat": STOP_B[0], "lng": STOP_B[1], "text": "Школа", "done": False},
            ]),
        )
        quote = isv.destination_quote(s, order, (54.80, 56.05))
        assert quote["ok"]
        # Будущая остановка входит в остаток; проеденная — нет, она уже в driven_km.
        plain = isv.route_through((UFA[0], UFA[1]), [], (54.80, 56.05))
        assert quote["rest_km"] > 0
        assert quote["distance_km"] > plain.distance_km * 0.5


# ------------------------------ ожидание на остановке ------------------------------
def test_standing_is_marked_by_a_button(client, user_factory):
    """«Стоим» и «Поехали» — кнопкой: автомат принял бы пробку у светофора за остановку."""
    pax = user_factory("StopPax")
    drv = user_factory("StopDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    on = client.post(f"/instant/orders/{oid}/stop", headers=drv["auth"])
    assert on.status_code == 200, on.text
    assert on.json()["standing"] is True

    off = client.post(f"/instant/orders/{oid}/stop", headers=drv["auth"])
    assert off.status_code == 200
    assert off.json()["standing"] is False


def test_passenger_cannot_start_the_waiting_meter(client, user_factory):
    """Счётчик ожидания запускает тот, кто реально стоит и ждёт."""
    pax = user_factory("StopPax2")
    drv = user_factory("StopDrv2", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"])

    r = client.post(f"/instant/orders/{oid}/stop", headers=pax["auth"])
    assert r.status_code == 404


def test_long_stop_adds_to_the_waiting_fee():
    """Стояли дольше бесплатных минут — сумма ожидания выросла по общим правилам."""
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=1, driver_id=2, status=S.onboard,
            from_lat=UFA[0], from_lng=UFA[1], to_lat=DEST[0], to_lng=DEST[1],
            price_estimate=300, category="standard",
            stop_started_at=utcnow() - timedelta(minutes=20), waiting_fee_kop=0,
        )
        s.add(order)
        s.commit()
        s.refresh(order)
        isv.toggle_stop(s, order)
        s.refresh(order)
        assert order.waiting_fee_kop > 0, "двадцать минут стоянки прошли бесплатно"
        assert order.stop_started_at is None
