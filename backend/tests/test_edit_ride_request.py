"""F3: редактирование поездки (PATCH /rides/{id}) и заявки (PATCH /requests/{id})."""

from app.models import UserRole

from test_flows import _book, _publish


def test_edit_ride_free_when_no_bookings(client, user_factory):
    drv = user_factory("EditDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="ПравкаА", to="ПравкаБ", price=500, seats=3)
    # чужой не может
    outsider = user_factory("EditOutsider", role=UserRole.driver)
    assert client.patch(f"/rides/{ride['id']}", headers=outsider["auth"], json={"price": 400}).status_code == 403
    # без броней можно всё: цена вверх, время, места
    # Время шлём С ПОЯСОМ, как теперь делает приложение: 08:00 по Уфе = 03:00 UTC, и в БД
    # обязан лежать UTC. Раньше тест закреплял ошибку — ждал, что 08:00 сохранится как 08:00,
    # то есть что поездка «уедет» на 5 часов вперёд (разбор №2, миграция y_utc_depart).
    r = client.patch(f"/rides/{ride['id']}", headers=drv["auth"], json={
        "price": 700, "depart_at": "2030-02-01T08:00:00+05:00", "seats_total": 4, "comment": "заеду через Темясово",
    })
    assert r.status_code == 200
    body = r.json()
    assert body["price"] == 700 and body["seats_total"] == 4 and body["seats_left"] == 4
    assert body["comment"] == "заеду через Темясово"
    assert body["depart_at"].startswith("2030-02-01T03:00")


def test_edit_ride_restricted_with_bookings(client, user_factory):
    drv = user_factory("EditBookedDrv", role=UserRole.driver)
    pax = user_factory("EditBookedPax")
    ride = _publish(client, drv, frm="ПравкаВ", to="ПравкаГ", price=500)
    _book(client, pax, ride["id"])
    # цену вверх нельзя (условия купленного не ухудшаем), вниз — можно
    assert client.patch(f"/rides/{ride['id']}", headers=drv["auth"], json={"price": 600}).status_code == 409
    assert client.patch(f"/rides/{ride['id']}", headers=drv["auth"], json={"price": 400}).status_code == 200
    # время и места с бронями не меняют
    assert client.patch(f"/rides/{ride['id']}", headers=drv["auth"],
                        json={"depart_at": "2030-03-01T08:00:00"}).status_code == 409
    assert client.patch(f"/rides/{ride['id']}", headers=drv["auth"], json={"seats_total": 5}).status_code == 409
    # комментарий — можно всегда
    assert client.patch(f"/rides/{ride['id']}", headers=drv["auth"], json={"comment": "жду у моста"}).status_code == 200


def test_edit_request_active_only(client, user_factory):
    pax = user_factory("EditReqPax")
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1, "comment": "утром"}).json()
    # чужой не может
    outsider = user_factory("EditReqOutsider")
    assert client.patch(f"/requests/{req['id']}", headers=outsider["auth"], json={"seats": 2}).status_code == 403
    # владелец правит маршрут/места/время
    r = client.patch(f"/requests/{req['id']}", headers=pax["auth"], json={
        "to_city": "Магнитогорск", "seats": 2, "desired_at": "2030-01-05T09:00:00"})
    assert r.status_code == 200
    assert r.json()["to_city"] == "Магнитогорск" and r.json()["seats"] == 2
    # отменённую править нельзя
    client.post(f"/requests/{req['id']}/cancel", headers=pax["auth"])
    assert client.patch(f"/requests/{req['id']}", headers=pax["auth"], json={"seats": 3}).status_code == 400


def test_edit_post_aliases_for_android(client, user_factory):
    """Android HttpURLConnection не умеет PATCH → POST /…/edit делает то же самое."""
    drv = user_factory("AliasDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="АлиасА", to="АлиасБ", price=500)
    r = client.post(f"/rides/{ride['id']}/edit", headers=drv["auth"], json={"price": 450})
    assert r.status_code == 200 and r.json()["price"] == 450
    pax = user_factory("AliasPax")
    req = client.post("/requests", headers=pax["auth"], json={"from_city": "АлиасА", "to_city": "АлиасБ"}).json()
    r2 = client.post(f"/requests/{req['id']}/edit", headers=pax["auth"], json={"comment": "после обеда"})
    assert r2.status_code == 200 and r2.json()["comment"] == "после обеда"
