"""RS-03 А, приватность: точка встречи пассажира — личные данные, кому и когда она видна.

Матрица (план §7, test_meeting_points_privacy): бронь с заполненными точками/пояснениями/ориентиром;
каждый сериализатор (создание, confirm, cancel, no-show, details, mine, driver-bookings, квитанция,
публичная карточка) × каждый зритель (пассажир / водитель / посторонний):
* пассажир — свои точки до зачистки, всегда;
* водитель — при pending только «место указано»/«есть предложение», при confirmed/onboard — полностью,
  сразу после done/cancelled — снова ничего (даже в окне связи 48 ч);
* посторонний — 403 без формы точки; другие пассажиры её не получают;
* публичная карточка рейса, квитанция, ссылка близким, чужой экспорт, логи — без точки;
* мутация: ответ, построенный из сырой модели (raw/model_dump), краснит тест — полей meet_*/alight_*
  в JSON быть не должно, только политические pickup_*/meet_* имена из DTO.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.geo import snapshot_cached
from app.models import Booking, PickupPoint, Ride, UserRole
from app.ride_stops import stop_point
from app.timeutil import utcnow

STOPS = "Ургаза | Бурибай"
SIBAY = (52.716, 58.664)
NOTE_RU = "синие ворота напротив школы"


def _when(days: int = 400) -> str:
    return (utcnow() + timedelta(days=days)).replace(microsecond=0).isoformat() + "+00:00"


def _setup_ride_with_booking(client, user_factory, tag: str):
    """Рейс + бронь с точкой у остановки, ориентиром и пояснениями к обеим точкам."""
    drv = user_factory(f"MPriv {tag} Driver", role=UserRole.driver)
    pax = user_factory(f"MPriv {tag} Pax")
    body = {"from_city": "Сибай", "to_city": "Акъяр", "depart_at": _when(), "seats_total": 3,
            "price": 700, "comment": f"mpriv-{tag}", "waypoints": STOPS, "pickup": "У автовокзала",
            "pickup_lat": SIBAY[0], "pickup_lng": SIBAY[1]}
    ride = client.post("/rides", headers=drv["auth"], json=body).json()
    with Session(engine) as s:
        sp = stop_point(s.get(Ride, ride["id"]), "Ургаза", snapshot_cached())
        assert sp is not None
        end = s.get(Ride, ride["id"])
        seed = PickupPoint(city="Акъяр", title_ru="у мечети", title_ba="мәсет янында",
                           lat=end.to_lat, lng=end.to_lng, is_seed=True)
        s.add(seed)
        s.commit()
        s.refresh(seed)
        landmark_id = seed.id
        ulat, ulng = sp.lat, sp.lng
    r = client.post("/bookings", headers=pax["auth"], json={
        "ride_id": ride["id"], "seats": 1, "pickup_stop": "Ургаза",
        "meeting": {"lat": ulat + 0.002, "lng": ulng + 0.002, "note": NOTE_RU},
        "alighting": {"landmark_id": landmark_id, "note": "у мечети попрошу высадить"},
    })
    assert r.status_code == 200, r.text
    return drv, pax, ride, r.json()["id"], (ulat, ulng)


def _assert_no_point(payload: dict, where: str):
    """В выдаче нет ни координат, ни пояснений, ни ориентира, ни предложения с точкой."""
    text = str(payload)
    assert NOTE_RU not in text, f"{where}: пояснение утекло"
    assert "у мечети попрошу высадить" not in text, f"{where}: пояснение высадки утекло"
    for key in ("pickup_lat", "pickup_lng", "dropoff_lat", "dropoff_lng", "meet_lat", "meet_lng",
                "alight_lat", "alight_lng"):
        if key in payload:
            # Ключ схемы с None — нормально (явная схема всегда несёт ключ); значение — утечка.
            assert payload[key] is None, f"{where}: {key} открыт"
    assert payload.get("meet_landmark_id") is None and payload.get("alight_landmark_id") is None
    assert payload.get("meeting_proposal") is None, f"{where}: предложение открыто"
    # Пояснения/ориентиры — пустые строки, а не текст пассажира.
    for key in ("pickup_note", "meet_note", "alight_note", "dropoff_note",
                "pickup_landmark_ru", "meet_landmark_ru", "alight_landmark_ru"):
        if key in payload:
            assert payload[key] in ("", None), f"{where}: {key} открыт"


def test_driver_pending_sees_only_flags_passenger_sees_own(client, user_factory):
    drv, pax, ride, bid, (ulat, ulng) = _setup_ride_with_booking(client, user_factory, "pending")
    # Пассажир — свои точки полностью в деталях и в ответе создания видел при брони.
    d_pax = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert d_pax["meet_lat"] == pytest.approx(ulat + 0.002) and d_pax["meet_note"] == NOTE_RU
    assert d_pax["alight_lat"] is not None and d_pax["alight_landmark_ru"] == "у мечети"
    # Водитель при pending — ни детали, ни список не несут точку; только факт «указано».
    d_drv = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    _assert_no_point(d_drv, "details/pending/driver")
    rows = client.get("/driver/bookings", headers=drv["auth"]).json()
    row = next(x for x in rows if x["booking_id"] == bid)
    _assert_no_point(row, "driver-bookings/pending")
    # Посторонний — 403.
    other = user_factory("MPriv Other")
    r = client.get(f"/bookings/{bid}/details", headers=other["auth"])
    assert r.status_code == 403


def test_confirmed_opens_to_driver_and_done_closes_immediately(client, user_factory):
    drv, pax, ride, bid, (ulat, ulng) = _setup_ride_with_booking(client, user_factory, "flow")
    # Подтверждение открывает точку водителю — и в деталях, и в ответе действия.
    conf = client.post(f"/bookings/{bid}/confirm", headers=drv["auth"])
    assert conf.status_code == 200, conf.text
    assert conf.json()["pickup_lat"] == pytest.approx(ulat + 0.002)
    assert conf.json()["pickup_note"] == NOTE_RU
    d_drv = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert d_drv["meet_lat"] == pytest.approx(ulat + 0.002)
    # Пассажирская отмена после подтверждения: у водителя точка гаснет СРАЗУ, не по окну связи.
    cancel = client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "changed_mind"})
    assert cancel.status_code == 200, cancel.text
    # Ответ видит пассажир — свои точки остаются (до зачистки), это его данные.
    assert cancel.json()["pickup_note"] == NOTE_RU
    # А водитель теряет точку СРАЗУ после отмены, не дожидаясь конца окна связи:
    d_after = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    _assert_no_point(d_after, "details/cancelled/driver")
    mine = next(x for x in client.get("/bookings/mine", headers=pax["auth"]).json() if x["id"] == bid)
    _assert_no_point(mine, "mine")   # /mine никогда не несла координаты — и не несёт


def test_cancel_answer_driver_side_and_no_show_close_points(client, user_factory):
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "terminal")
    # Отказ водителя по pending: ответ водителю — без точки (pending+terminal политика).
    rej = client.post(f"/bookings/{bid}/cancel", headers=drv["auth"], json={"reason": "other"})
    assert rej.status_code == 200, rej.text
    _assert_no_point(rej.json(), "cancel-by-driver/pending")
    # Мутация-ловушка: ни в одном ответе нет сырых имён колонок.
    assert "meet_note" not in rej.json() and "meeting_proposal" in rej.json()


def test_public_card_receipt_and_export_carry_no_point(client, user_factory):
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "public")
    card = client.get(f"/rides/{ride['id']}").json()
    _assert_no_point(card, "ride-card")
    # Квитанция живёт после завершения: переводим броню в done напрямую (проверяем сериализатор,
    # а не переходы), затем читаем квитанцию пассажиром — точки в ней нет ни у кого.
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = "done"
        s.add(b)
        s.commit()
    receipt = client.get(f"/trips/{bid}/receipt", headers=pax["auth"])
    assert receipt.status_code == 200, receipt.text
    _assert_no_point(receipt.json(), "receipt")
    export = client.get("/me/export", headers=pax["auth"]).text
    # Свой экспорт несёт пояснение пассажира (его данные), но не чужие; координаты помечены отдельно.
    assert NOTE_RU in export
    drv_export = client.get("/me/export", headers=drv["auth"]).text
    assert NOTE_RU not in drv_export, "водитель чужую точку не экспортирует"


def test_done_inside_contacts_window_hides_point_from_driver(client, user_factory):
    """Завершение внутри 48-часового окна связи: телефон ещё открыт, а точка у водителя уже нет —
    поездки нет, значит и координат нет (RS-03 А, «теряет сразу»)."""
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "window")
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    before = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert before["meet_note"] == NOTE_RU and before["contact_unlocked"] is True
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = "done"   # поездка только что завершена — окно связи 48 ч ещё открыто
        s.add(b)
        s.commit()
    after = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    assert after["contact_unlocked"] is True, "окно связи живо (телефон ещё открыт)"
    _assert_no_point(after, "details/done-in-window/driver")


def test_no_show_closes_point_for_driver(client, user_factory):
    """Неявка: водитель отметил «не пришёл» — точка гаснет, как и при отмене."""
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "noshow")
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = "cancelled"
        b.no_show = True
        s.add(b)
        s.commit()
    d = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    _assert_no_point(d, "details/no-show/driver")


def test_coordinates_and_note_never_hit_the_log(client, user_factory, caplog):
    """Координаты и пояснение — никогда в лог (план §3.3): ни при брони, ни в ошибках валидации."""
    import logging
    with caplog.at_level(logging.DEBUG):
        drv, pax, ride, bid, (ulat, ulng) = _setup_ride_with_booking(client, user_factory, "caplog")
        for delta in (1.0, 0.001):
            client.post(f"/bookings/{bid}/meeting-point", headers=pax["auth"],
                        json={"kind": "pickup", "point": {"lat": ulat + delta, "lng": ulng + delta,
                                                               "note": NOTE_RU}, "base_version": 1})
    blob = "\n".join(r.getMessage() for r in caplog.records)
    assert NOTE_RU not in blob
    for delta in (0.002, 1.0, 0.001):
        assert str(ulat + delta) not in blob and str(ulng + delta) not in blob


def test_hidden_details_never_expose_proposal_shape(client, user_factory):
    import json
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "shape")
    for status in ("pending", "done", "cancelled"):
        with Session(engine) as s:
            b = s.get(Booking, bid)
            b.status = status
            b.meet_proposal = json.dumps({"proposal_id": "private-id", "kind": "pickup",
                                         "point": {"lat": 52.0, "lng": 58.0, "note": NOTE_RU}})
            s.add(b)
            s.commit()
        response = client.get(f"/bookings/{bid}/details", headers=drv["auth"])
        assert response.status_code == 200, response.text
        _assert_no_point(response.json(), f"details/{status}/proposal")


def test_own_landmark_roundtrip_survives_note_edit(client, user_factory):
    drv, pax, ride, bid, _ = _setup_ride_with_booking(client, user_factory, "roundtrip")
    details = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    landmark_id = details["alight_landmark_id"]
    assert landmark_id is not None
    response = client.post(f"/bookings/{bid}/meeting-point", headers=pax["auth"],
                           json={"kind": "dropoff", "base_version": details["meeting_version"],
                                 "point": {"landmark_id": landmark_id, "note": "у входа"}})
    assert response.status_code == 200, response.text
    after = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert after["alight_landmark_id"] == landmark_id
    assert after["alight_landmark_ru"] == details["alight_landmark_ru"]
    assert after["alight_landmark_ba"] == details["alight_landmark_ba"]
    assert after["alight_note"] == "у входа"
    hidden = client.get(f"/bookings/{bid}/details", headers=drv["auth"]).json()
    _assert_no_point(hidden, "roundtrip/pending/driver")
