"""Регрессия BE03: выход должен отключать push на всех устройствах пользователя."""

from sqlmodel import Session, select

from app.db import engine
from app.models import DeviceToken, WebPushSubscription


def test_logout_removes_own_fcm_and_web_push_but_keeps_foreign(client, user_factory):
    owner = user_factory("BE03 owner")
    neighbor = user_factory("BE03 neighbor")

    with Session(engine) as session:
        session.add(DeviceToken(
            user_id=owner["id"], token="be03-owner-fcm", device_id="be03-owner-device",
        ))
        session.add(WebPushSubscription(
            user_id=owner["id"], endpoint="https://push.example/be03-owner",
            p256dh="owner-key", auth="owner-auth", device_id="be03-owner-device",
        ))
        session.add(DeviceToken(
            user_id=neighbor["id"], token="be03-neighbor-fcm", device_id="be03-neighbor-device",
        ))
        session.add(WebPushSubscription(
            user_id=neighbor["id"], endpoint="https://push.example/be03-neighbor",
            p256dh="neighbor-key", auth="neighbor-auth", device_id="be03-neighbor-device",
        ))
        session.commit()

    response = client.post("/auth/logout", headers=owner["auth"])
    assert response.status_code == 200, response.text

    with Session(engine) as session:
        owner_fcm = session.exec(
            select(DeviceToken).where(DeviceToken.user_id == owner["id"])
        ).all()
        owner_web = session.exec(
            select(WebPushSubscription).where(WebPushSubscription.user_id == owner["id"])
        ).all()
        neighbor_fcm = session.exec(
            select(DeviceToken).where(DeviceToken.user_id == neighbor["id"])
        ).all()
        neighbor_web = session.exec(
            select(WebPushSubscription).where(WebPushSubscription.user_id == neighbor["id"])
        ).all()

    assert owner_fcm == [], "logout left the user's FCM token active"
    assert owner_web == [], "logout left the user's browser push subscription active"
    assert len(neighbor_fcm) == 1, "logout removed another user's FCM token"
    assert len(neighbor_web) == 1, "logout removed another user's browser subscription"
