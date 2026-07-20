"""Уведомления: новые push-события (подтверждение/отмена брони, модерация водителя),
двуязычие push (RU/BA по User.language) и алерт админу о жалобе.

Ловим на низком уровне `services.send_push` — через него проходят ВСЕ двуязычные пуши
(`send_push_bi` уже выбрал язык), поэтому один перехват покрывает все роутеры.
"""
import pytest
from sqlmodel import Session

from app import services
from app.db import engine
from app.models import User, UserRole


def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _book(client, pax, ride_id, seats=1):
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": seats})
    assert r.status_code == 200, r.text
    return r.json()


def _set_lang(user_id, lang):
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.language = lang
        s.add(u)
        s.commit()


@pytest.fixture
def pushes(monkeypatch):
    """Перехват низкоуровневого send_push → (user_id, title, body). Язык уже выбран send_push_bi."""
    sent = []
    monkeypatch.setattr(services, "send_push", lambda session, user_id, title, body: sent.append((user_id, title, body)))
    return sent


def test_booking_confirmed_pushes_passenger(client, user_factory, pushes):
    drv = user_factory("CfDrv", role=UserRole.driver)
    pax = user_factory("CfPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    pushes.clear()                                   # сброс пуша «Новая бронь» водителю
    r = client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"])
    assert r.status_code == 200
    assert any(uid == pax["id"] and title == "Бронь подтверждена" for uid, title, _ in pushes)


def test_cancel_notifies_other_party(client, user_factory, pushes):
    drv = user_factory("CxDrv", role=UserRole.driver)
    pax = user_factory("CxPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    pushes.clear()
    # пассажир отменяет → «Бронь отменена» уходит ВОДИТЕЛЮ (не отменившему)
    r = client.post(f"/bookings/{booking['id']}/cancel", headers=pax["auth"])
    assert r.status_code == 200
    assert any(uid == drv["id"] and title == "Бронь отменена" for uid, title, _ in pushes)
    assert not any(uid == pax["id"] and title == "Бронь отменена" for uid, title, _ in pushes)


def test_new_booking_push_is_bilingual(client, user_factory, pushes):
    """Водитель на башкирском → заголовок пуша приходит по-башкирски (двуязычие §3)."""
    drv = user_factory("BiDrv", role=UserRole.driver)
    pax = user_factory("BiPax")
    _set_lang(drv["id"], "ba")
    ride = _publish(client, drv)
    _book(client, pax, ride["id"])
    assert any(uid == drv["id"] and title == "Яңы бронь" for uid, title, _ in pushes)
    assert not any(uid == drv["id"] and title == "Новая бронь" for uid, title, _ in pushes)


def test_driver_moderation_pushes_driver(client, user_factory, pushes):
    admin = user_factory("ModAdmin", role=UserRole.admin)
    drv = user_factory("ModDrv", role=UserRole.driver)
    approved = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"], json={"approve": True})
    assert approved.status_code == 200
    assert any(uid == drv["id"] and title == "Проверка пройдена" for uid, title, _ in pushes)

    rejected = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"], json={"approve": False})
    assert rejected.status_code == 200
    assert any(uid == drv["id"] and title == "Проверка не пройдена" for uid, title, _ in pushes)


def test_ws_chat_message_fires_push(client, user_factory, pushes):
    """Регресс: WS-обработчик чата ссылался на send_push (не импортирован) → NameError на проде,
    push при сообщении по WebSocket молча не доставлялся, а обычные тесты этот путь не гоняли.
    Здесь гоним реальный WS-путь и проверяем, что push уходит второй стороне без ошибки."""
    import json
    drv = user_factory("WsPushDrv", role=UserRole.driver)
    pax = user_factory("WsPushPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    pushes.clear()
    with client.websocket_connect(f"/ws/bookings/{booking['id']}") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": pax["token"]}))
        ws.send_text(json.dumps({"type": "message", "text": "Я на месте"}))
        ws.receive_text()   # эхо-broadcast первого сообщения
        # второй раунд гарантирует, что push первого (в threadpool ПОСЛЕ broadcast) уже завершился
        ws.send_text(json.dumps({"type": "message", "text": "Жду у подъезда"}))
        ws.receive_text()
    assert any(uid == drv["id"] for uid, _t, _b in pushes), "WS-чат не отправил push (регресс NameError?)"


def test_rate_reminder_notifies_only_unrated_party(client, user_factory, pushes):
    """Завершённую поездку один участник оценил, другой нет → напоминание уходит ТОЛЬКО не оценившему.
    Повторный проход не спамит (флаг rate_reminded)."""
    from app.rate_reminder import rate_reminder_once
    drv = user_factory("RateRemDrv", role=UserRole.driver)
    pax = user_factory("RateRemPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    client.post(f"/bookings/{booking['id']}/trip-status", headers=pax["auth"], json={"status": "done"})
    client.post(f"/bookings/{booking['id']}/rate", headers=pax["auth"], json={"stars": 5})   # пассажир оценил
    pushes.clear()
    with Session(engine) as s:
        reminded = rate_reminder_once(s)
    assert any(uid == drv["id"] and title == "Оцените поездку" for uid, title, _ in pushes)   # водителю — да
    assert not any(uid == pax["id"] for uid, _t, _b in pushes)                                # пассажиру — нет
    assert (booking["id"], drv["id"]) in reminded
    # второй проход — бронь уже помечена, никого не дёргаем
    pushes.clear()
    with Session(engine) as s:
        assert rate_reminder_once(s) == []
    assert not pushes


def test_rate_reminder_disabled(client, monkeypatch):
    from app.config import settings
    from app.rate_reminder import rate_reminder_once
    monkeypatch.setattr(settings, "rate_reminder_enabled", False)
    with Session(engine) as s:
        assert rate_reminder_once(s) == []


def test_me_update_sets_language(client, user_factory):
    """Клиент задаёт язык на сервере → двуязычные push пойдут на этом языке. Мусор игнорируется."""
    u = user_factory("LangUser")
    assert client.post("/me/update", headers=u["auth"], json={"language": "ba"}).status_code == 200
    assert client.get("/me", headers=u["auth"]).json()["language"] == "ba"
    client.post("/me/update", headers=u["auth"], json={"language": "xx"})   # неподдерживаемое → игнор
    assert client.get("/me", headers=u["auth"]).json()["language"] == "ba"


def test_push_unregister_removes_own_token_only(client, user_factory):
    """Выход снимает СВОЙ push-токен (приватность на общем телефоне), но не чужой."""
    from sqlmodel import select as _select
    from app.models import DeviceToken
    u = user_factory("PushUser")
    client.post("/push/register", headers=u["auth"], json={"token": "tok-OWN"})
    with Session(engine) as s:
        assert s.exec(_select(DeviceToken).where(DeviceToken.token == "tok-OWN")).first() is not None
    # чужой не может снять мой токен
    other = user_factory("PushOther")
    client.post("/push/unregister", headers=other["auth"], json={"token": "tok-OWN"})
    with Session(engine) as s:
        assert s.exec(_select(DeviceToken).where(DeviceToken.token == "tok-OWN")).first() is not None
    # владелец — снимает
    assert client.post("/push/unregister", headers=u["auth"], json={"token": "tok-OWN"}).status_code == 200
    with Session(engine) as s:
        assert s.exec(_select(DeviceToken).where(DeviceToken.token == "tok-OWN")).first() is None


def test_report_alerts_admin(client, user_factory, monkeypatch):
    """Жалоба на пользователя → алерт админу в Telegram (раньше молчал)."""
    alerts = []
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram",
                        lambda text, reply_markup=None: alerts.append(text))
    a = user_factory("RepReporter")
    b = user_factory("RepTarget")
    r = client.post("/reports", headers=a["auth"], json={"target_user_id": b["id"], "reason": "опасное вождение"})
    assert r.status_code == 200
    assert alerts and "Жалоба" in alerts[0]
