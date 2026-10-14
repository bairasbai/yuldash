"""PATH-02 — пассажир попутки: найти рейс → забронировать → дождаться водителя → посадка →
завершение → оценка и чек.

Как это видит пассажир: нашла «Баймак → Уфа», нажала «Забронировать» (связь моргнула — нажала
ещё раз, бронь одна). Пока водитель не подтвердил, его телефона и точки сбора не видно, а чека
ещё нет. Пришло «Бронь подтверждена» — открылись телефон, машина и место встречи, код посадки
совпадает с тем, что видит водитель. «Водитель подъезжает», она села («Я сел»), доехала,
нажала «Завершить», поставила пять звёзд — в чеке маршрут, сумма и её оценка, без телефона.
"""


def test_passenger_finds_books_waits_boards_completes_rates_and_gets_receipt(api, kit):
    driver = kit.login("Ильдар Водитель")
    passenger = kit.login("Гульнара Пассажир")
    car = api.post("/driver/profile", headers=driver.auth, json={
        "car_make": "Lada", "car_model": "Vesta", "car_color": "белая", "car_plate": "А123ВС102", "seats": 4})
    assert car.status_code == 200, car.text
    ride = kit.publish(driver, price=700, seats_total=2)

    # 1. Найти: рейс в ленте по маршруту, карточка открывается.
    seen = kit.find_ride(ride["id"], who=passenger)
    assert seen is not None and seen["seats_left"] == 2 and seen["price"] == 700
    assert api.get(f"/rides/{ride['id']}", headers=passenger.auth).status_code == 200

    # 2. Забронировать; повторное нажатие возвращает ту же бронь, место списано один раз.
    booking = kit.book(passenger, ride["id"])
    bid = booking["id"]
    assert kit.book(passenger, ride["id"])["id"] == bid
    assert booking["status"] == "pending" and booking["price"] == 700
    assert kit.find_ride(ride["id"], who=passenger)["seats_left"] == 1
    mine = api.get("/bookings/mine", headers=passenger.auth).json()
    assert [b["status"] for b in mine if b["id"] == bid] == ["pending"]

    # 3. Ждёт водителя: до подтверждения личное водителя закрыто, чека нет.
    wait = kit.details(passenger, bid)
    assert wait["role"] == "passenger" and wait["status"] == "pending"
    assert wait["contact_unlocked"] is False and wait["driver_phone"] == "" and wait["pickup"] == ""
    assert wait["driver_plate"] == ""
    early = api.get(f"/trips/{bid}/receipt", headers=passenger.auth)
    assert early.status_code == 409, early.text
    kit.confirm(driver, bid)
    assert kit.notified(passenger, "Бронь подтверждена", ref_id=bid)
    open_ = kit.details(passenger, bid)
    assert open_["status"] == "confirmed" and open_["contact_unlocked"] is True
    assert open_["driver_phone"] == driver.phone
    assert open_["pickup"] == "Автовокзал Баймак, у входа" and open_["driver_plate"] == "А123ВС102"
    code = api.get(f"/bookings/{bid}/boarding-code", headers=passenger.auth).json()["code"]
    assert code == api.get(f"/bookings/{bid}/boarding-code", headers=driver.auth).json()["code"]
    assert len(code) == 6

    # 4. Водитель едет к ней: фазы видны пассажиру и приходят уведомлением.
    assert kit.driver_status(driver, bid, "departed").status_code == 200
    assert kit.driver_status(driver, bid, "arriving").status_code == 200
    assert kit.role(passenger, bid)["driver_phase"] == "arriving"
    assert kit.notified(passenger, "Водитель подъезжает", ref_id=bid)

    # 5. Посадка: «Я сел» сохраняется на сервере — переоткрытое приложение и водитель видят
    #    «в пути», а не снова «ждём посадку».
    sat = kit.trip_status(passenger, bid, "sat")
    assert sat.status_code == 200, sat.text
    assert kit.role(passenger, bid)["status"] == "onboard"
    assert kit.details(passenger, bid)["status"] == "onboard"
    assert kit.trip_status(passenger, bid, "sat").status_code == 200   # повтор — без ошибки

    # 6. Завершение пассажиром.
    done = kit.trip_status(passenger, bid, "done")
    assert done.status_code == 200, done.text
    assert kit.role(passenger, bid)["status"] == "done"
    mine = api.get("/bookings/mine", headers=passenger.auth).json()
    assert [b["status"] for b in mine if b["id"] == bid] == ["done"]

    # 7. Оценка и чек.
    rate = api.post(f"/bookings/{bid}/rate", headers=passenger.auth,
                    json={"stars": 5, "text": "", "tags": ""})
    assert rate.status_code == 200 and rate.json()["ratee_id"] == driver.id, rate.text
    rec = api.get(f"/trips/{bid}/receipt", headers=passenger.auth)
    assert rec.status_code == 200, rec.text
    rec = rec.json()
    assert rec["role"] == "passenger" and rec["from_city"] == "Баймак" and rec["to_city"] == "Уфа"
    assert rec["amount"] == 700 and rec["seats"] == 1 and rec["my_stars"] == 5
    assert rec["driver_name"] == "Ильдар Водитель"
    assert driver.phone not in str(rec), "в чеке не должно быть телефона водителя"
    # Чужой человек чек этой поездки не открывает.
    stranger = kit.login("Посторонний")
    assert api.get(f"/trips/{bid}/receipt", headers=stranger.auth).status_code in (403, 404)
