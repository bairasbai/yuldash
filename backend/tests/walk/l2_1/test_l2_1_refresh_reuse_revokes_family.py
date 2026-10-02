"""Лист 2.1, Б-3 (независимое ревью): повтор украденного refresh-токена гасит ВСЮ семью сессий.

RFC 9700 §4.14.2: предъявление уже сожжённого (повторно использованного) refresh-токена —
стандартный сигнал кражи. Раньше сервер просто отвечал 401 этой одной попытке, а остальные
сессии человека (и даже только что выпущенный «законный» преемник той же цепочки) продолжали
жить до 90 дней. Если вор успевал продлить сессию ПЕРВЫМ, хозяин получал 401, заходил заново —
а сессия вора оставалась рабочей и необнаруженной.

Сигнал ловим там, где он однозначен: токен несёт историю НАСТОЯЩЕЙ ротации (`rotated_at` не
пуст — задним числом его ставит только успешное продление, НЕ logout и НЕ обычный повтор без
rotation_id), предъявленный rotation_id НЕ СОВПАДАЕТ с сохранённым, и 120-секундное окно
восстановления уже закрылось. Все три условия обязательны (повторное независимое ревью, Н-1):
  • без истории ротации — это logout/обычный повтор, самый частый случай, не кража (вышел на
    телефоне → вошёл заново → ВТОРОЕ устройство честно продлевает СВОЮ, уже погашенную выходом,
    сессию с rotation_id — раньше это ошибочно гасило НОВУЮ сессию телефона и слало тревогу);
  • нонс совпал, но поздно — потерянный ответ, честный повтор СВОИМ же нонсом, не кража;
  • нонс не совпал, но мы ВНУТРИ окна — два честных одновременных запроса одним токеном
    (двойная вкладка, авто-ретрай) тоже шлют каждый свой rotation_id, проигравший получает
    «нонс не подошёл» в ту же миллисекунду, что и победитель (живой пример —
    `test_refresh_replay_postgres.py::test_parallel_recovery_intents_have_one_child[False]`,
    две настоящие параллельные транзакции на PostgreSQL).
Отдельно: повтор ОДНОГО И ТОГО ЖЕ уже обнаруженного украденного токена не шлёт тревогу повторно —
иначе любой старый сожжённый токен превращается в бесплатный рубильник «выкинуть человека
отовсюду» на каждом повторе (DoS).
"""
from __future__ import annotations

from app import security


def _login(client, phone: str, name: str = "U") -> dict:
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    r = client.post("/auth/verify", json={"phone": phone, "code": code, "name": name})
    assert r.status_code == 200, r.text
    return r.json()


def test_replay_with_a_wrong_rotation_id_outside_the_window_revokes_the_family(client, monkeypatch):
    # Окно обнуляем: В ТУ ЖЕ миллисекунду после ротации уже «вне окна» — проверяем именно
    # факт отзыва семьи, не гонясь за настоящими 120 секундами реального времени в тесте.
    monkeypatch.setattr(security, "REFRESH_RECOVERY_SECONDS", 0, raising=False)
    phone = "+79995553011"
    first = _login(client, phone, "Б3Жертва")
    # Вторая, полностью независимая сессия того же человека (второй логин тем же номером —
    # это второе устройство, вторая пара ключей, без выхода из первой).
    second = _login(client, phone)

    good_nonce = "a" * 64
    rotated = client.post("/auth/refresh",
                          json={"refresh_token": first["refresh_token"], "rotation_id": good_nonce})
    assert rotated.status_code == 200, rotated.text
    new_access = rotated.json()["access_token"]

    wrong_nonce = "b" * 64
    replay = client.post("/auth/refresh",
                         json={"refresh_token": first["refresh_token"], "rotation_id": wrong_nonce})
    assert replay.status_code == 401, replay.text

    # Семья отозвана целиком: совсем другая сессия того же человека тоже гаснет...
    other_session = client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert other_session.status_code == 401, (
        f"чужая (для этого токена) сессия того же человека пережила обнаруженную кражу: "
        f"{other_session.status_code} {other_session.text}"
    )
    # ...и даже только что выпущенный «законный» преемник первой сессии перестаёт работать.
    assert client.get("/me", headers={"Authorization": f"Bearer {new_access}"}).status_code == 401, (
        "свежий access-токен из той же, казалось бы, легитимной ротации пережил отзыв семьи"
    )


def test_plain_replay_without_a_rotation_id_does_not_touch_other_sessions(client):
    """Отрицательный случай: обычный повтор без доказательства — НЕ семейная поломка."""
    phone = "+79995553012"
    first = _login(client, phone, "ОбычныйПовтор")
    second = _login(client, phone)

    rotated = client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert rotated.status_code == 200, rotated.text

    replay = client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert replay.status_code == 401, replay.text

    # Другая сессия и свежий преемник остаются рабочими — обычный повтор не бьёт по семье.
    still_alive = client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert still_alive.status_code == 200, (
        f"обычный повтор без rotation_id погасил чужую сессию: {still_alive.status_code} {still_alive.text}"
    )
    assert client.get(
        "/me", headers={"Authorization": f"Bearer {rotated.json()['access_token']}"}
    ).status_code == 200


def test_a_wrong_nonce_inside_the_recovery_window_is_not_treated_as_theft(client):
    """Сторож придирчивого сторожа: внутри окна «нонс не подошёл» — это может быть честная
    гонка двух запросов одного клиента (см. докстринг модуля), не кража. Семью не гасим."""
    phone = "+79995553013"
    first = _login(client, phone, "ЧестнаяГонка")
    second = _login(client, phone)

    good_nonce = "c" * 64
    rotated = client.post("/auth/refresh",
                          json={"refresh_token": first["refresh_token"], "rotation_id": good_nonce})
    assert rotated.status_code == 200, rotated.text

    # Реальное окно (120с) ещё открыто — никакого monkeypatch на REFRESH_RECOVERY_SECONDS здесь.
    wrong_nonce = "d" * 64
    replay = client.post("/auth/refresh",
                         json={"refresh_token": first["refresh_token"], "rotation_id": wrong_nonce})
    assert replay.status_code == 401, replay.text

    still_alive = client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert still_alive.status_code == 200, (
        f"нонс внутри окна восстановления погасил чужую сессию, хотя это не кража: "
        f"{still_alive.status_code} {still_alive.text}"
    )


def test_logout_then_relogin_then_a_second_devices_refresh_does_not_alarm(client):
    """Н-1 (повторное независимое ревью): logout гасит refresh БЕЗ rotated_at — это не ротация.
    Второе устройство, честно продлевающее СВОЮ (уже погашенную чужим logout) сессию с
    rotation_id, — обычный и частый случай, не кража. Раньше это ошибочно гасило НОВУЮ сессию,
    открытую на первом устройстве ПОСЛЕ выхода."""
    phone = "+79995553014"
    phone_session = _login(client, phone, "ДваУстройства")
    tablet_session = _login(client, phone)  # второе устройство, тот же человек

    assert client.post("/auth/logout", headers={
        "Authorization": f"Bearer {phone_session['access_token']}"
    }).status_code == 200

    new_phone_session = _login(client, phone)  # телефон зашёл заново после выхода

    # Планшет не знает о выходе — честно пытается продлить СВОЮ сессию с нонсом, как и положено
    # настоящему клиенту (Android/PWA всегда шлют rotation_id).
    tablet_refresh = client.post("/auth/refresh", json={
        "refresh_token": tablet_session["refresh_token"], "rotation_id": "e" * 64,
    })
    assert tablet_refresh.status_code == 401, tablet_refresh.text

    assert client.get("/me", headers={
        "Authorization": f"Bearer {new_phone_session['access_token']}"
    }).status_code == 200, "выход с планшета (чужой logout) погасил новую сессию телефона"


def test_same_nonce_replayed_after_the_window_closes_does_not_alarm(client, monkeypatch):
    """Н-1: потерянный ответ — клиент честно повторяет ТОТ ЖЕ нонс, просто опоздал (окно уже
    закрылось). Нонс совпадает — значит это не чужая попытка, тревоги быть не должно."""
    phone = "+79995553015"
    first = _login(client, phone, "ПотерянныйОтвет")
    second = _login(client, phone)

    nonce = "f" * 64
    rotated = client.post("/auth/refresh", json={
        "refresh_token": first["refresh_token"], "rotation_id": nonce,
    })
    assert rotated.status_code == 200, rotated.text

    # Реальных 120с не ждём — сокращаем окно, чтобы повтор оказался «поздним» немедленно.
    monkeypatch.setattr(security, "REFRESH_RECOVERY_SECONDS", 0, raising=False)
    late_retry = client.post("/auth/refresh", json={
        "refresh_token": first["refresh_token"], "rotation_id": nonce,
    })
    assert late_retry.status_code == 401, late_retry.text

    still_alive = client.post("/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert still_alive.status_code == 200, (
        f"честный повтор ТЕМ ЖЕ нонсом после окна погасил чужую сессию: "
        f"{still_alive.status_code} {still_alive.text}"
    )


def test_the_same_stolen_token_does_not_alarm_twice(client, monkeypatch):
    """Н-1: повторный replay ОДНОГО И ТОГО ЖЕ украденного токена не шлёт тревогу заново —
    иначе любой старый сожжённый токен превращается в бесплатный рубильник, которым вор может
    раз за разом выкидывать человека отовсюду (DoS)."""
    phone = "+79995553016"
    monkeypatch.setattr(security, "REFRESH_RECOVERY_SECONDS", 0, raising=False)
    first = _login(client, phone, "ПовторнаяКража")

    rotated = client.post("/auth/refresh", json={
        "refresh_token": first["refresh_token"], "rotation_id": "1" * 64,
    })
    assert rotated.status_code == 200, rotated.text

    wrong_nonce = "2" * 64
    first_theft_reply = client.post("/auth/refresh", json={
        "refresh_token": first["refresh_token"], "rotation_id": wrong_nonce,
    })
    assert first_theft_reply.status_code == 401, first_theft_reply.text

    # Человек заходит заново ПОСЛЕ первой тревоги...
    relogged = _login(client, phone)

    # ...а вор повторяет ТОТ ЖЕ самый старый (уже учтённый) токен ещё раз.
    second_theft_reply = client.post("/auth/refresh", json={
        "refresh_token": first["refresh_token"], "rotation_id": wrong_nonce,
    })
    assert second_theft_reply.status_code == 401, second_theft_reply.text

    # Вторая попытка тем же токеном не должна была погасить вход, открытый ПОСЛЕ первой тревоги.
    assert client.get("/me", headers={
        "Authorization": f"Bearer {relogged['access_token']}"
    }).status_code == 200, (
        "повтор ОДНОГО и того же украденного токена погасил сессию, открытую после первой тревоги"
    )
