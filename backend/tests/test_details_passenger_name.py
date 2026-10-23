"""Водитель на экране поездки видит, кого везёт (приёмка 18.10, п.14).

Детали брони отдавали только поля водителя (`driver_*`), и водительский экран рисовал вверху
карточку «Водитель» — то есть самого себя без имени. Имя пассажира водитель и так видит в списке
броней (`/driver/bookings`), поэтому в деталях оно не раскрывает ничего нового. Телефон пассажира
сюда НЕ добавляем: это отдельное решение о приватности.
"""
from app.models import UserRole

from test_flows import _book, _publish


def test_details_name_the_passenger_for_the_driver(client, user_factory):
    drv = user_factory("Детали Водитель", role=UserRole.driver)
    pax = user_factory("Гульшат")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])

    for who in (drv, pax):
        r = client.get(f"/bookings/{booking['id']}/details", headers=who["auth"])
        assert r.status_code == 200, r.text
        assert r.json()["passenger_name"] == "Гульшат"
    driver_view = client.get(f"/bookings/{booking['id']}/details", headers=drv["auth"]).json()
    assert "passenger_phone" not in driver_view
