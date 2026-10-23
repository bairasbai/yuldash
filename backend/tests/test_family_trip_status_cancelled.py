# -*- coding: utf-8 -*-
"""Закрытая поездка отвечает «Поездка не активна», а не подсказкой про посадку (ревью PR190, 23.10.2026).

В `family.set_trip_status` проверка «бронь отменена» стоит ПЕРВОЙ, под локами. Статус ответа
при её отсутствии не меняется — остальные проверки («Доехал» только из машины, «Завершил» только
после посадки) тоже отвечают 409, — поэтому прежние тесты на «только 409» её не охраняли. Ответ
различается словами: пассажир отменённой брони должен услышать, что поездки нет, а не «дождись, пока
водитель отметит посадку» (которой уже не будет), и очередь без сети у старого клиента — понять, что
сообщение близким не уйдёт.
"""
import pytest

from test_location_privacy import _confirmed_booking

NOT_ACTIVE = {"ru": "Поездка не активна", "ba": "Сәфәр актив түгел"}


def _status(client, bid, actor, status):
    return client.post(f"/bookings/{bid}/trip-status", headers=actor["auth"], json={"status": status})


@pytest.mark.parametrize("status", ["arrived", "done"])
@pytest.mark.parametrize("canceller", ["passenger", "driver"])
def test_cancelled_booking_answers_trip_is_not_active(client, user_factory, canceller, status):
    driver, passenger, bid = _confirmed_booking(client, user_factory, f"FamCancel{canceller}{status}")
    actor = passenger if canceller == "passenger" else driver
    cancelled = client.post(f"/bookings/{bid}/cancel", headers=actor["auth"], json={"reason": "changed_mind"})
    assert cancelled.status_code == 200, cancelled.text

    for _ in range(2):                                   # и повторный запрос из очереди — тот же ответ
        refused = _status(client, bid, passenger, status)
        assert refused.status_code == 409, refused.text
        assert refused.json()["detail"] == NOT_ACTIVE


def test_live_booking_gets_the_boarding_hint_not_the_cancelled_answer(client, user_factory):
    """Контраст: у живой (не отменённой) брони отказ другой — значит, тест выше не тривиален."""
    _driver, passenger, bid = _confirmed_booking(client, user_factory, "FamLive")
    refused = _status(client, bid, passenger, "arrived")
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] != NOT_ACTIVE
