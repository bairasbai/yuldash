"""PATH-03 — водитель попутки: опубликовать рейс → увидеть бронь → подтвердить → поездка → завершение.

Как это видит водитель: заполнил машину, опубликовал «Баймак → Уфа» (палец дрогнул — нажал
«Опубликовать» дважды, рейс всё равно один). Пришло «Новая бронь», в списке броней пассажир.
Подтвердил — открылись телефон пассажира и его бронь. «Я выехал» → «Подъезжаю», пассажир сел,
«Завершить» — бронь закрыта, рейс завершён и пропал из ленты, водитель оценил пассажира и
видит квитанцию. Вторая сторона — настоящий второй аккаунт пассажира.
"""


def test_driver_publishes_sees_booking_confirms_drives_and_completes(api, kit):
    driver = kit.login("Ильдар Водитель")
    passenger = kit.login("Гульнара Пассажир")

    # 1. Машина в профиле — по ней пассажир узнаёт водителя.
    car = api.post("/driver/profile", headers=driver.auth, json={
        "car_make": "Lada", "car_model": "Vesta", "car_color": "белая", "car_plate": "А123ВС102", "seats": 4})
    assert car.status_code == 200, car.text

    # 2. Публикация; двойное нажатие не создаёт второй рейс.
    body = {"from_city": "Баймак", "to_city": "Уфа", "depart_at": kit.depart_in(20), "seats_total": 3,
            "price": 900, "pickup": "Автовокзал Баймак", "pickup_lat": 52.5912, "pickup_lng": 58.3110}
    ride = kit.publish(driver, **body)
    twin = kit.publish(driver, **body)
    assert twin["id"] == ride["id"]
    assert ride["status"] == "active" and ride["seats_left"] == 3
    mine = api.get("/driver/rides", headers=driver.auth)
    assert mine.status_code == 200
    assert [r["id"] for r in mine.json()].count(ride["id"]) == 1
    assert kit.find_ride(ride["id"]) is not None, "рейс не попал в общую ленту"

    # 3. Пассажир бронирует (его путь — PATH-02) → водитель ВИДИТ бронь.
    booking = kit.book(passenger, ride["id"])
    bid = booking["id"]
    assert kit.notified(driver, "Новая бронь", ref_id=bid), "водителю не пришло «Новая бронь»"
    listed = api.get("/driver/bookings", headers=driver.auth).json()
    row = next((b for b in listed if b["booking_id"] == bid), None)
    assert row and row["status"] == "pending" and row["passenger_name"] == "Гульнара Пассажир"
    before = kit.details(driver, bid)
    assert before["role"] == "driver" and before["status"] == "pending"
    assert before["contact_unlocked"] is False

    # 4. Подтверждение; повторное нажатие — без ошибки и без второго уведомления.
    assert kit.confirm(driver, bid)["status"] == "confirmed"
    assert kit.confirm(driver, bid)["status"] == "confirmed"
    after = kit.details(driver, bid)
    assert after["status"] == "confirmed" and after["contact_unlocked"] is True
    code = api.get(f"/bookings/{bid}/boarding-code", headers=driver.auth)
    assert code.status_code == 200 and code.json()["code"] == booking["boarding_code"]
    confirmed_pushes = [n for n in kit.notifications(passenger)
                        if n["title_ru"] == "Бронь подтверждена" and n.get("ref_id") == bid]
    assert len(confirmed_pushes) == 1

    # 5. Поездка: «Я выехал» → «Подъезжаю» (пассажир видит фазу), пассажир сел.
    for phase in ("departed", "arriving"):
        r = kit.driver_status(driver, bid, phase)
        assert r.status_code == 200, r.text
        assert kit.role(passenger, bid)["driver_phase"] == phase
    assert kit.trip_status(passenger, bid, "sat").status_code == 200
    # Водитель видит, что человек в машине: бронь «в пути», а не просто «подтверждена».
    assert kit.role(driver, bid)["status"] == "onboard"
    # Неявку по брони «в пути» не отметить (решение 14.10.2026): понятный отказ, а в списке
    # водителя — вторая сторона для «Сообщить о проблеме» (разбор).
    refused = api.post(f"/bookings/{bid}/no-show", headers=driver.auth)
    assert refused.status_code == 409 and refused.json()["detail"]["code"] == "passenger_onboard", refused.text
    riding = next(b for b in api.get("/driver/bookings", headers=driver.auth).json() if b["booking_id"] == bid)
    assert riding["status"] == "onboard" and riding["passenger_id"] == passenger.id

    # 6. Завершение водителем; повтор — тот же итог, без ошибки.
    done = kit.driver_status(driver, bid, "done")
    assert done.status_code == 200 and done.json()["status"] == "done", done.text
    assert kit.driver_status(driver, bid, "done").status_code == 200
    assert kit.role(driver, bid)["status"] == "done"
    assert kit.notified(passenger, "Поездка завершена", ref_id=bid)
    # Рейс целиком: закрываем, из ленты он уходит.
    closed = api.post(f"/rides/{ride['id']}/complete", headers=driver.auth)
    assert closed.status_code == 200 and closed.json()["status"] == "done", closed.text
    assert kit.find_ride(ride["id"]) is None
    assert api.post(f"/rides/{ride['id']}/complete", headers=driver.auth).status_code == 200

    # 7. После поездки: оценка пассажира и квитанция водителя.
    rate = api.post(f"/bookings/{bid}/rate", headers=driver.auth, json={"stars": 5, "tags": ""})
    assert rate.status_code == 200 and rate.json()["ratee_id"] == passenger.id, rate.text
    rec = api.get(f"/trips/{bid}/receipt", headers=driver.auth)
    assert rec.status_code == 200, rec.text
    rec = rec.json()
    assert rec["role"] == "driver" and rec["amount"] == 900 and rec["my_stars"] == 5
    assert rec["counterparty_name"] == "Гульнара Пассажир"
    assert passenger.phone not in str(rec)
