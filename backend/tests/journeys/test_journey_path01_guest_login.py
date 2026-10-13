"""PATH-01 — гость → закрытое действие → вход → возврат к тому же действию → выход.

Как это видит человек: открыл приложение без входа, листает попутки, нашёл рейс «Баймак → Уфа»
и жмёт «Забронировать». Приложение просит войти. Он входит через Telegram (по пути один раз
ошибся в коде), возвращается к тому же рейсу, бронь проходит. Потом выходит — и старый ключ
входа больше ничего не открывает, а лента снова видна как гостю. Повторный вход — тот же
аккаунт, бронь на месте.
"""


def test_guest_finds_ride_logs_in_returns_to_booking_and_logs_out(api, kit):
    # Водитель уже опубликовал рейс (его путь — PATH-03).
    driver = kit.login("Ильдар Водитель")
    ride = kit.publish(driver)

    # 1. Публичный экран: гость видит рейс без входа, но без личного (точка сбора, телефон).
    seen = kit.find_ride(ride["id"])
    assert seen is not None, "гость не видит опубликованный рейс в ленте"
    assert seen["pickup"] == "" and seen["pickup_lat"] is None and seen["pickup_lng"] is None
    card = api.get(f"/rides/{ride['id']}")
    assert card.status_code == 200, card.text
    assert card.json()["pickup"] == ""
    assert driver.phone not in card.text

    # 2. Закрытое действие гостем — отказ «нужен вход», брони не появилось.
    blocked = api.post("/bookings", json={"ride_id": ride["id"], "seats": 1})
    assert blocked.status_code == 401, blocked.text

    # 3. Вход через Telegram-бота. Обычная ошибка: неверный код → 400, повтор тем же запросом.
    phone, tg_id, device = kit.new_phone(), kit.new_tg_id(), "guest-phone-1"
    rid = kit.tg_start()
    code = kit.tg_bot_start(rid, tg_id, "Гульнара")
    kit.tg_share_phone(tg_id, phone)
    wrong = "000000" if code != "000000" else "111111"
    bad = kit.tg_verify(rid, wrong, device)
    assert bad.status_code == 400, bad.text
    assert bad.json()["detail"]["ru"] == "Неверный код"
    ok = kit.tg_verify(rid, code, device)
    assert ok.status_code == 200, ok.text
    guest = kit._person("Гульнара", phone, tg_id, device, ok.json())
    assert guest.user["phone"] == phone and guest.user["telegram_id"] == str(tg_id)

    # 4. Возврат к тому же действию: тот же рейс, та же кнопка — бронь проходит.
    booking = kit.book(guest, ride["id"])
    assert booking["status"] == "pending" and booking["ride_id"] == ride["id"]
    mine = api.get("/bookings/mine", headers=guest.auth)
    assert mine.status_code == 200
    assert [b["id"] for b in mine.json()] == [booking["id"]]
    me = api.get("/me", headers=guest.auth)
    assert me.status_code == 200 and me.json()["id"] == guest.id

    # 5. Выход. Старый ключ входа и ключ обновления больше ничего не открывают.
    out = api.post("/auth/logout", headers=guest.auth)
    assert out.status_code == 200, out.text
    assert api.get("/me", headers=guest.auth).status_code == 401
    assert api.get("/bookings/mine", headers=guest.auth).status_code == 401
    assert api.post("/bookings", json={"ride_id": ride["id"], "seats": 1},
                    headers=guest.auth).status_code == 401
    assert api.post("/auth/refresh", json={"refresh_token": guest.refresh}).status_code == 401
    # …а публичная лента снова открыта ему как гостю.
    assert kit.find_ride(ride["id"]) is not None

    # 6. Повторный вход — тот же аккаунт и та же бронь (выход ничего не стёр).
    again = kit.login("Гульнара", phone=phone, tg_id=tg_id, device=device)
    assert again.id == guest.id
    mine = api.get("/bookings/mine", headers=again.auth)
    assert [b["id"] for b in mine.json()] == [booking["id"]]
