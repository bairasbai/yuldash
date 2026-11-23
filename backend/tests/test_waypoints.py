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


def test_changing_the_destination_keeps_upcoming_stops(client, user_factory):
    """Едешь в другое место — но заехать за ребёнком всё ещё надо."""
    pax = user_factory("WpDestPax")
    drv = user_factory("WpDestDrv", role=UserRole.driver)
    with Session(engine) as s:
        order = InstantOrder(
            passenger_id=pax["id"], driver_id=drv["id"], status=S.onboard,
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


def test_long_stop_adds_to_the_waiting_fee(client, user_factory):
    """Стояли дольше бесплатных минут — сумма ожидания выросла по общим правилам."""
    pax = user_factory("StopLongPax")
    drv = user_factory("StopLongDrv", role=UserRole.driver)
    oid = _make_order(pax["id"], drv["id"],
                      stop_started_at=utcnow() - timedelta(minutes=20), waiting_fee_kop=0)
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        isv.toggle_stop(s, order)
        s.refresh(order)
        assert order.waiting_fee_kop > 0, "двадцать минут стоянки прошли бесплатно"
        assert order.stop_started_at is None


# --------------------------- TF04: protected edit ---------------------------
@pytest.fixture
def protected_waypoints(user_factory, monkeypatch):
    pax = user_factory("WpConsentOwner")
    drv = user_factory("WpConsentDriver", role=UserRole.driver)
    seen = {"quotes": 0, "pushes": [], "price": 250, "needs_driver_ok": False}

    def quote(session, order, destination, *, now, waypoints_override):
        seen["quotes"] += 1
        override = seen.get("during_quote")
        if override:
            override()
        return {"ok": True, "price": seen["price"], "base_price": 220,
                "old_price": isv.order_ride_price(order), "distance_km": 6.4,
                "eta_min": 14.0, "tariff_id": None,
                "needs_driver_ok": seen["needs_driver_ok"]}

    def push(session, recipient, category, ru, ba, ru_body, ba_body, **refs):
        seen["pushes"].append((recipient, refs))

    monkeypatch.setattr(isv, "destination_quote", quote)
    monkeypatch.setattr("app.services.push_notification", push)
    order_id = _make_order(pax["id"], drv["id"], ride_price=225, pickup_fee_kop=7500,
                           promo_discount_kop=18000)
    return pax, drv, order_id, seen


def _protected_request(client, pax, order_id, stage, points, **extra):
    return client.post(f"/instant/orders/{order_id}/waypoints/{stage}", headers=pax["auth"],
                       json={"waypoints": points, **extra})


def _protected_points(point=STOP_A):
    return [{"lat": point[0], "lng": point[1], "text": "Аптека"}]


def _persisted_route(order_id):
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        return (o.waypoints_json, o.ride_price, o.price_estimate,
                o.promo_discount_kop, o.destination_changed_at)


def test_protected_preview_has_no_order_mutation_and_exact_payable(client, protected_waypoints):
    from app import promo_ride
    pax, _, order_id, seen = protected_waypoints
    original = _persisted_route(order_id)
    preview = _protected_request(client, pax, order_id, "preview", _protected_points())
    assert preview.status_code == 200, preview.text
    assert preview.headers["cache-control"] == "no-store"
    body = preview.json()
    assert body["previous_ride_price_kop"] == 22500
    assert body["ride_price_kop"] == 25000
    assert body["previous_payable_estimate_kop"] == 12000
    assert body["payable_estimate_kop"] == 32500 - promo_ride.cap_for_price(18000, 250)
    assert body["schema"] == "taxi-waypoints-preview-v1"
    assert body["confirmation_available"] is False
    assert "quote_token" not in body
    assert _persisted_route(order_id) == original
    assert seen["pushes"] == []


def test_protected_preview_fails_closed_for_driver_and_wrong_user(client, protected_waypoints, user_factory):
    pax, _, order_id, seen = protected_waypoints
    stranger = user_factory("WpConsentStranger")
    original = _persisted_route(order_id)
    assert _protected_request(client, stranger, order_id, "preview", _protected_points()).status_code == 404
    assert seen["quotes"] == 0
    seen["needs_driver_ok"] = True
    result = _protected_request(client, pax, order_id, "preview", _protected_points())
    assert result.status_code == 409
    assert result.json()["detail"]["code"] == "taxi_waypoints_driver_consent_required"
    assert _persisted_route(order_id) == original
    assert seen["pushes"] == []


@pytest.mark.parametrize("updated", [
    {"round_trip": True}, {"pending_to_lat": STOP_A[0]}, {"status": S.searching},
])
def test_protected_preview_rejects_unsupported_route_without_pricing(client, protected_waypoints, updated):
    pax, _, order_id, seen = protected_waypoints
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        for key, value in updated.items():
            setattr(o, key, value)
        s.commit()
    result = _protected_request(client, pax, order_id, "preview", _protected_points())
    assert result.status_code == 409
    assert result.json()["detail"]["code"] == "taxi_waypoints_unavailable"
    assert seen["quotes"] == 0 and not seen["pushes"]


def test_protected_preview_requires_exact_request_shape(client, protected_waypoints):
    pax, _, order_id, seen = protected_waypoints
    res = _protected_request(client, pax, order_id, "preview", _protected_points(), price_kop=1)
    assert res.status_code == 422
    res = _protected_request(client, pax, order_id, "preview", [{**_protected_points()[0], "done": True}])
    assert res.status_code == 422
    assert seen["quotes"] == 0


def test_preview_stage_does_not_register_a_confirm_mutation(client, protected_waypoints):
    pax, _, order_id, seen = protected_waypoints
    points = _protected_points()
    before = _persisted_route(order_id)
    offer = _protected_request(client, pax, order_id, "preview", points)
    assert offer.status_code == 200, offer.text
    missing = _protected_request(client, pax, order_id, "confirm", points,
                                 quote_token="not-issued")
    assert missing.status_code == 404
    assert _persisted_route(order_id) == before
    assert seen["quotes"] == 1 and not seen["pushes"]


def test_protected_preview_reuses_actual_estimate_budget_after_owner_fence(
        client, protected_waypoints, user_factory, monkeypatch):
    from app.middleware import settings, user_over_limit
    pax, drv, order_id, seen = protected_waypoints
    stranger = user_factory("WpBudgetStranger")
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(settings, "rate_limit_per_min", 100_000)
    monkeypatch.setattr(settings, "rate_limit_auth_per_min", 100_000)
    monkeypatch.setattr(settings, "rate_limit_estimate_per_min", 100_000)
    monkeypatch.setattr(settings, "rate_limit_estimate_per_user_per_min", 1)

    checked = []

    def observe_bucket(name, user_id, limit):
        checked.append((name, user_id, limit))
        return user_over_limit(name, user_id, limit)

    monkeypatch.setattr("app.routers.instant.user_over_limit", observe_bucket)
    for actor in (drv, stranger):
        result = _protected_request(client, actor, order_id, "preview", _protected_points())
        assert result.status_code == 404
    assert checked == [] and seen["quotes"] == 0

    before = _persisted_route(order_id)
    first = _protected_request(client, pax, order_id, "preview", _protected_points())
    second = _protected_request(client, pax, order_id, "preview", _protected_points(STOP_B))
    # The original estimate endpoint must share the same bucket. No external
    # estimate/path is possible because both repeat calls are rejected first.
    ordinary = client.post("/instant/estimate", headers=pax["auth"],
                           json={"from_lat": UFA[0], "from_lng": UFA[1],
                                 "to_lat": DEST[0], "to_lng": DEST[1]})
    assert [first.status_code, second.status_code, ordinary.status_code] == [200, 429, 429]
    assert checked == [("estimate", pax["id"], 1)] * 3
    assert seen["quotes"] == 1 and not seen["pushes"]
    assert _persisted_route(order_id) == before


@pytest.mark.parametrize("field,value", [
    ("status", S.arriving),
    ("driver_id", None),
    ("passenger_id", None),
    ("to_lat", 54.768),
    ("waypoints_json", isv.dump_waypoints([
        {"lat": STOP_B[0], "lng": STOP_B[1], "text": "Школа", "done": True},
    ])),
    ("pending_to_lat", 54.768),
    ("waiting_fee_kop", 100),
    ("payment_method", "sbp"),
    ("pickup_fee_kop", 7600),
    ("promo_discount_kop", 10000),
])
def test_protected_preview_drops_estimate_after_concurrent_order_change(
        client, protected_waypoints, field, value):
    pax, drv, order_id, seen = protected_waypoints
    if field == "passenger_id":
        value = drv["id"]

    def writer_during_quote():
        with Session(engine) as writer:
            changed = writer.get(InstantOrder, order_id)
            setattr(changed, field, value)
            writer.commit()

    before = _persisted_route(order_id)
    seen["during_quote"] = writer_during_quote
    response = _protected_request(client, pax, order_id, "preview", _protected_points())
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_waypoints_quote_conflict"
    assert "payable_estimate_kop" not in response.json()
    with Session(engine) as observer:
        assert getattr(observer.get(InstantOrder, order_id), field) == value
    if field not in {"waypoints_json", "promo_discount_kop"}:
        assert _persisted_route(order_id) == before
    assert seen["quotes"] == 1 and not seen["pushes"]


def test_protected_preview_rejects_overflow_in_persisted_stop_before_budget(
        client, protected_waypoints, monkeypatch):
    pax, _, order_id, seen = protected_waypoints
    with Session(engine) as writer:
        changed = writer.get(InstantOrder, order_id)
        changed.waypoints_json = '[{"lat":' + "1" + "0" * 400 + ',"lng":55.0,"done":true}]'
        writer.commit()

    def must_not_charge(user_id):
        pytest.fail("corrupted order reached budget admission")

    monkeypatch.setattr("app.routers.instant.guard_estimate_budget", must_not_charge)
    before = _persisted_route(order_id)
    response = _protected_request(client, pax, order_id, "preview", _protected_points())
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_waypoints_unavailable"
    assert seen["quotes"] == 0 and not seen["pushes"]
    assert _persisted_route(order_id) == before
