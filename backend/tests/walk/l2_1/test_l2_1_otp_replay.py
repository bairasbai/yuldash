"""Лист 2.1: один и тот же код входа нельзя предъявить дважды.

Правильный код один раз открывает сессию и сам «сгорает» в ТОЙ ЖЕ транзакции, что выдача ключей
(`_verify_sms_login` в auth.py: `UPDATE ... SET code='', expires_at=utcnow() WHERE code=<код>`).
Проверяем по-человечески: ввёл код, вошёл; затем ещё раз предъявил ТОТ ЖЕ код (дважды нажал
«Подтвердить» на медленной сети, код подсмотрели через плечо, предсказуемая реплей-атака) —
второй раз должен получить честный отказ, а не вторую сессию на чужом устройстве.
"""
from __future__ import annotations


def test_same_code_cannot_log_in_twice(client):
    phone = "+79995551212"
    issued = client.post("/auth/request-code", json={"phone": phone})
    assert issued.status_code == 200, issued.text
    code = issued.json()["dev_code"]

    first = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Повтор"})
    assert first.status_code == 200, first.text

    second = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert second.status_code == 400, (
        f"код повторно открыл сессию вместо честного отказа: {second.status_code} {second.text}"
    )
