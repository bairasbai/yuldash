"""Лист 2.1: «Выйти со всех устройств» обязан остановить и пуши, не только ключи входа.

Сценарий из комментария к /auth/logout (auth.py) — общий телефон в семье: отец вышел, зашёл сын.
Если FCM-токен или веб-подписка остаются привязаны к прежнему юзеру, уведомления о чужих поездках,
бронях и сигналах SOS продолжат приходить на экран, который теперь смотрит сын. Проверяем РЕЗУЛЬТАТ
(а не то, что запрос не упал): после logout строк DeviceToken/WebPushSubscription этого человека
в базе быть не должно.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app import models as M
from app.db import engine


def test_logout_removes_device_and_web_push_tokens(client, user_factory):
    u = user_factory("ВышелССПушем")
    assert client.post("/push/register", headers=u["auth"], json={"token": "fcm-l2-1-x"}).status_code == 200
    assert client.post("/push/web/subscribe", headers=u["auth"], json={
        "endpoint": "https://push.example/l2-1-x",
        "keys": {"p256dh": "k", "auth": "a"},
    }).status_code == 200

    with Session(engine) as s:
        assert s.exec(select(M.DeviceToken).where(M.DeviceToken.user_id == u["id"])).first() is not None
        assert s.exec(
            select(M.WebPushSubscription).where(M.WebPushSubscription.user_id == u["id"])
        ).first() is not None

    assert client.post("/auth/logout", headers=u["auth"]).status_code == 200

    with Session(engine) as s:
        assert s.exec(select(M.DeviceToken).where(M.DeviceToken.user_id == u["id"])).first() is None, (
            "FCM-токен пережил выход — чужие пуши продолжат приходить на этот телефон"
        )
        assert s.exec(
            select(M.WebPushSubscription).where(M.WebPushSubscription.user_id == u["id"])
        ).first() is None, "подписка браузера пережила выход — чужие уведомления продолжат приходить"
