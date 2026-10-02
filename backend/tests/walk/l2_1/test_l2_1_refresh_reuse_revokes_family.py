"""Лист 2.1, Б-3 (независимое ревью): повтор украденного refresh-токена гасит ВСЮ семью сессий.

RFC 9700 §4.14.2: предъявление уже сожжённого (повторно использованного) refresh-токена —
стандартный сигнал кражи. Раньше сервер просто отвечал 401 этой одной попытке, а остальные
сессии человека (и даже только что выпущенный «законный» преемник той же цепочки) продолжали
жить до 90 дней. Если вор успевал продлить сессию ПЕРВЫМ, хозяин получал 401, заходил заново —
а сессия вора оставалась рабочей и необнаруженной.

Сигнал ловим там, где он однозначен: клиент предъявил СВОЙ одноразовый rotation_id (явно пытался
восстановить какую-то КОНКРЕТНУЮ попытку), он не подошёл, И 120-секундное окно восстановления
уже закрылось. Третье условие обязательно: два ЧЕСТНЫХ одновременных запроса одним токеном
(двойная вкладка, авто-ретрай) тоже приходят каждый со своим rotation_id — проигравший получает
«нонс не подошёл» в ту же миллисекунду, что и победитель. Это гонка, а не кража (живой пример —
`test_refresh_replay_postgres.py::test_parallel_recovery_intents_have_one_child[False]`, две
настоящие параллельные транзакции на PostgreSQL). Различить их может только время с момента
ротации — отсюда и третье условие.
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
