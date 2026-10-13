"""Водитель узнаёт итог проверки документов — из любой двери.

Найдено сквозной проверкой волны 1 (13.10.2026, PATH-12). Экран проверки на Android обещает
«Обычно занимает немного времени. Сообщим о результате.» — а сообщения не было ни из одной
двери: ни из кнопки в админке, ни из кнопок ✅/❌ в телеграм-боте (ими Александр и одобряет),
ни из авто-проверки прав. Одобренный водитель не знал, что бейдж уже есть; отклонённый — что
надо переснять фото, и ждал ответа, которого не будет.
"""
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import DriverProfile, Notification, User, UserRole


def _titles(user_id: int) -> list[str]:
    with Session(engine) as s:
        return [n.title_ru for n in s.exec(select(Notification).where(Notification.user_id == user_id)).all()]


def _admin(user_factory):
    adm = user_factory("Verdict Admin", role=UserRole.admin)
    with Session(engine) as s:
        u = s.get(User, adm["id"])
        u.role = UserRole.admin
        s.add(u)
        s.commit()
    return adm


def _pending_driver(user_factory):
    drv = user_factory("Verdict Driver", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], docs_status="pending"))
        s.commit()
    return drv


def test_admin_approval_tells_driver_once(client, user_factory):
    adm, drv = _admin(user_factory), _pending_driver(user_factory)
    for _ in range(2):   # второе нажатие — не новость, второго сообщения нет
        r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=adm["auth"], json={"approve": True})
        assert r.status_code == 200 and r.json()["verified"] is True
    assert _titles(drv["id"]).count("Документы проверены") == 1
    with Session(engine) as s:
        n = s.exec(select(Notification).where(Notification.user_id == drv["id"])).first()
        assert n.title_ba and n.body_ba          # два языка — §3
        assert n.type == "docs"                  # приватно на заблокированном экране, как прочие о документах


def test_admin_rejection_tells_driver_what_to_do(client, user_factory):
    adm, drv = _admin(user_factory), _pending_driver(user_factory)
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=adm["auth"], json={"approve": False})
    assert r.status_code == 200 and r.json()["docs_status"] == "rejected"
    assert _titles(drv["id"]) == ["Документы не приняты"]


def test_telegram_button_approval_tells_driver(client, user_factory, monkeypatch):
    """Кнопка ✅ под сообщением бота — основная дверь владельца."""
    monkeypatch.setattr(settings, "telegram_webhook_secret", "secret")
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "5141534025")
    monkeypatch.setattr("app.routers.auth._telegram_api", lambda method, payload: None)
    drv = _pending_driver(user_factory)
    r = client.post("/telegram/webhook", headers={"x-telegram-bot-api-secret-token": "secret"}, json={
        "callback_query": {"id": "cb-verdict", "from": {"id": 5141534025}, "data": f"drv:ok:{drv['id']}",
                           "message": {"message_id": 77, "chat": {"id": 5141534025}}}})
    assert r.status_code == 200
    with Session(engine) as s:
        assert s.get(User, drv["id"]).verified is True
    assert _titles(drv["id"]) == ["Документы проверены"]


def test_autocheck_verdict_tells_driver(client, user_factory, monkeypatch):
    """Авто-проверка прав (если включат авто-отказ) — третья дверь, то же сообщение."""
    import base64

    monkeypatch.setattr(settings, "driver_autocheck_enabled", True)
    monkeypatch.setattr(settings, "driver_autoreject_enabled", True)
    monkeypatch.setattr("app.driver_check.check_driver_docs", lambda *_a, **_k: {
        "result": "reject", "score": 0.1, "data": {"reasons": ["not_a_license"]}})
    drv = user_factory("Autocheck Driver", role=UserRole.driver)
    urls = []
    for tag in (b"lic", b"car"):
        b64 = base64.b64encode(b"\xff\xd8\xff" + tag).decode()
        up = client.post("/upload/photo", headers=drv["auth"], json={"photo_b64": b64, "ext": "jpg"})
        assert up.status_code == 200, up.text
        urls.append(up.json()["url"])
    r = client.post("/driver/verify", headers=drv["auth"], json={"license_url": urls[0], "car_photo_url": urls[1]})
    assert r.status_code == 200 and r.json()["docs_status"] == "rejected", r.text
    assert _titles(drv["id"]) == ["Документы не приняты"]


def test_every_verdict_door_tells_the_driver():
    """Сторож: каждая дверь, которая выносит вердикт, после commit говорит итог водителю.

    Сообщение нельзя слать изнутри `set_driver_docs_verdict`: это до commit двери — на SQLite
    отдельная сессия уведомления упиралась в блокировку записи и молча терялась, а на PostgreSQL
    водитель узнал бы «проверены» раньше, чем это записано. Значит, звать должна сама дверь.
    """
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    doors = [p for p in root.rglob("*.py")
             if p.name != "services.py" and "set_driver_docs_verdict(" in p.read_text(encoding="utf-8")]
    assert doors, "двери вердикта не найдены — сторож больше ничего не сторожит"
    silent = [p.name for p in doors if "tell_driver_docs_verdict(" not in p.read_text(encoding="utf-8")]
    assert not silent, f"вердикт выносится, а водителю не говорят: {silent}"
