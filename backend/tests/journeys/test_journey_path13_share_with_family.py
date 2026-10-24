"""PATH-13 — поделиться поездкой с близким → ссылка → только разрешённое → конец поездки
закрывает доступ.

Как это видит семья: Гульнара едет в Уфу и делится поездкой с мамой. Мама открывает ссылку
в браузере, без приложения и без входа: видит «Машина едет к точке посадки», маршрут и только
имя дочери — ни телефонов, ни фамилии. Дочь села — у мамы «В пути». Ссылку, отправленную по
ошибке брату, дочь отзывает — у брата «ссылка не найдена». Поездка закончилась — у мамы
«Поездка завершена ✅», без маршрута и координат.
"""


def test_share_link_shows_only_allowed_and_closes_when_trip_ends(api, kit):
    driver = kit.login("Ильдар Водитель")
    passenger = kit.login("Гульнара Хасанова")
    ride = kit.publish(driver)
    bid = kit.book(passenger, ride["id"])["id"]
    kit.confirm(driver, bid)

    # 1. Близкие в списке доверенных; делится поездкой с двумя.
    mom = api.post("/trusted-contacts", headers=passenger.auth,
                   json={"name": "Мама", "relation": "мама", "phone": "+79991112233"})
    bro = api.post("/trusted-contacts", headers=passenger.auth,
                   json={"name": "Брат", "relation": "брат", "phone": "+79991112244"})
    assert mom.status_code == 200 and bro.status_code == 200, (mom.text, bro.text)
    share_mom = api.post(f"/bookings/{bid}/share", headers=passenger.auth, json={"contact_id": mom.json()["id"]})
    share_bro = api.post(f"/bookings/{bid}/share", headers=passenger.auth, json={"contact_id": bro.json()["id"]})
    assert share_mom.status_code == 200 and share_bro.status_code == 200, (share_mom.text, share_bro.text)
    t_mom, t_bro = share_mom.json()["token"], share_bro.json()["token"]
    assert len(t_mom) >= 16 and t_mom != t_bro
    shares = api.get(f"/bookings/{bid}/shares", headers=passenger.auth).json()
    assert {s["id"] for s in shares} == {share_mom.json()["id"], share_bro.json()["id"]}
    # Делиться может только сам пассажир; водитель и посторонний — нет.
    assert api.post(f"/bookings/{bid}/share", headers=driver.auth,
                    json={"contact_id": mom.json()["id"]}).status_code == 403
    stranger = kit.login("Посторонний")
    assert api.get(f"/bookings/{bid}/shares", headers=stranger.auth).status_code in (403, 404)

    # 2. Мама открывает ссылку без входа: только разрешённое.
    page = api.get(f"/t/{t_mom}")
    assert page.status_code == 200 and "noindex" in page.headers.get("x-robots-tag", "")
    assert "no-store" in page.headers.get("cache-control", "")
    st = api.get(f"/t/{t_mom}/state.json")
    assert st.status_code == 200, st.text
    # Водитель ещё ничего не нажал — «Поездка скоро начнётся», машины нет (приёмка PR179, P2-2).
    assert st.json()["status"] == "wait" and st.json()["car"] is None, st.text
    assert kit.driver_status(driver, bid, "departed").status_code == 200
    st = api.get(f"/t/{t_mom}/state.json")
    assert st.status_code == 200, st.text
    state = st.json()
    assert state["status"] == "to_pickup"
    assert state["passenger_first_name"] == "Гульнара"
    assert state["from"]["text"] == "Баймак" and state["to"]["text"] == "Уфа"
    raw = st.text
    for secret in ("Хасанова", passenger.phone, driver.phone, "+79991112233", "Автовокзал"):
        assert secret not in raw, f"по ссылке близкого утекло: {secret}"

    # 3. Дочь села, водитель отметил «Сел» — у мамы «В пути».
    assert kit.driver_status(driver, bid, "boarded").status_code == 200
    state = api.get(f"/t/{t_mom}/state.json").json()
    assert state["status"] == "onboard", state
    assert state["phase_text"]["ru"] == "В пути"

    # 4. Отзыв ссылки брата: у него «не найдено», у мамы ссылка живёт.
    rv = api.delete(f"/bookings/{bid}/share/{share_bro.json()['id']}", headers=passenger.auth)
    assert rv.status_code == 200, rv.text
    assert api.get(f"/t/{t_bro}/state.json").status_code == 404
    assert api.get(f"/t/{t_bro}").status_code == 404
    assert api.get(f"/t/{t_mom}/state.json").status_code == 200
    # Подобрать ссылку нельзя: короткая и выдуманная — «не найдено».
    assert api.get("/t/abc/state.json").status_code == 404
    assert api.get(f"/t/{t_mom[:-2]}zz/state.json").status_code == 404

    # 5. Конец поездки закрывает доступ к маршруту и координатам.
    assert kit.driver_status(driver, bid, "done").status_code == 200
    fin = api.get(f"/t/{t_mom}/state.json")
    assert fin.status_code == 200
    fin = fin.json()
    assert fin["status"] == "finished" and fin["car"] is None
    assert "from" not in fin and "to" not in fin
    assert fin["phase_text"]["ru"] == "Поездка завершена ✅"
