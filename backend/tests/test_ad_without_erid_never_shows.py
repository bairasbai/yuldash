"""Реклама без маркировки (erid из ОРД) не показывается — ни одной дверью.

Закон о рекламе не знает состояния «крутим, номер вот-вот присвоят»: до присвоения erid
объявление показывать нельзя вообще (ст. 14.3 КоАП — до 500 000 ₽ юрлицу). Раньше сервер
пускал в эфир и пустой erid, и заглушку: гейт стоял только в форме админа на Android,
а форма — не единственный вход (аудит монетизации 2026-08-31, §4.1).
"""
from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Ad, UserRole
from app.routers.ads import erid_ok
from app.timeutil import utcnow


def _payload(**over):
    body = {
        "partner_name": "Аптека",
        "partner_contact": "+79990000000",
        "title": "Скидка 10%",
        "text": "Для поездок в больницу",
        "button": "Открыть",
        "target": "https://example.test",
        "erid": "2VtzqwH7uMn",
        "plan": "standard",
        "placements": "profile",
        "cities": "Сибай",
        "starts_at": (utcnow() - timedelta(minutes=1)).isoformat(),
        "ends_at": (utcnow() + timedelta(days=1)).isoformat(),
    }
    body.update(over)
    return body


def test_placeholder_is_not_an_erid():
    """Заглушка, пустота и кириллица — не маркировка. Настоящий номер ОРД — латиница+цифры."""
    assert erid_ok("2VtzqwH7uMn")
    assert erid_ok("erid-test")
    assert not erid_ok("")
    assert not erid_ok("   ")
    assert not erid_ok("ожидает присвоения")
    assert not erid_ok("нет")


def test_ad_without_erid_never_reaches_the_feed(client, user_factory):
    """Главное обещание: без номера ОРД объявление не увидит НИ ОДИН человек.

    main сторожит не дверь, а витрину: статус `active` проставить можно (его ставит и
    подтверждение оплаты, мимо ручек админа), но `_is_live` перед каждой выдачей
    перепроверяет маркировку. Ветка-источник вдобавок запрещала сам статус; для закона
    это ничего не меняет — важно, что показа нет. Тест держит именно показ, чтобы
    проверка не зависела от того, через какую дверь объявление стало активным.
    """
    admin = user_factory("EridAdmin", role=UserRole.admin)

    created = client.post("/admin/ads", headers=admin["auth"], json=_payload(erid="ожидает присвоения"))
    assert created.status_code == 200, created.text
    ad_id = created.json()["id"]

    # Самый недоверчивый путь: статус выставлен прямо в базе, мимо всех проверок ручек.
    with Session(engine) as session:
        ad = session.get(Ad, ad_id)
        ad.status = "active"
        session.add(ad)
        session.commit()

    feed = client.get("/ads")
    assert feed.status_code == 200
    assert all(item["id"] != str(ad_id) for item in feed.json()), (
        "объявление без маркировки ОРД попало в выдачу: показ без erid — нарушение "
        "закона о рекламе (ст. 14.3 КоАП, до 500 000 ₽ юрлицу)"
    )
    client.delete(f"/admin/ads/{ad_id}", headers=admin["auth"])


def test_admin_sees_that_the_marking_is_missing(client, user_factory):
    """Админ должен ВИДЕТЬ, что объявление немаркированное, — иначе он не поймёт, почему тихо."""
    admin = user_factory("EridWatcher", role=UserRole.admin)
    ad_id = client.post("/admin/ads", headers=admin["auth"],
                        json=_payload(erid="ожидает присвоения")).json()["id"]

    # `/admin/ads` отдаёт не список, а объект: внутри `items` и счётчик founder-слотов.
    ответ = client.get("/admin/ads", headers=admin["auth"]).json()
    # id в кабинете приходит строкой, как и в публичной выдаче.
    наша = [a for a in ответ["items"] if a["id"] == str(ad_id)]
    assert наша, "объявление пропало из кабинета админа"
    assert наша[0]["erid_missing"] is True, "кабинет не показывает, что маркировки нет"
    client.delete(f"/admin/ads/{ad_id}", headers=admin["auth"])


def test_ad_with_real_erid_goes_live(client, user_factory):
    """Контроль: с настоящим номером всё работает как раньше."""
    admin = user_factory("EridAdminOk", role=UserRole.admin)
    created = client.post("/admin/ads", headers=admin["auth"], json=_payload())
    ad_id = created.json()["id"]

    published = client.post(f"/admin/ads/{ad_id}/status", headers=admin["auth"], json={"status": "active"})
    assert published.status_code == 200, published.text

    feed = client.get("/ads").json()
    assert any(item["id"] == str(ad_id) for item in feed)

    # Убираем за собой: БД одна на весь прогон, живое объявление сломало бы соседние тесты.
    client.delete(f"/admin/ads/{ad_id}", headers=admin["auth"])


def test_approve_takes_erid_from_the_form(client, user_factory):
    """Админ вставляет номер из ОРД прямо при одобрении — объявление становится живым."""
    admin = user_factory("EridApprover", role=UserRole.admin)
    created = client.post("/admin/ads", headers=admin["auth"], json=_payload(erid=""))
    ad_id = created.json()["id"]

    ok = client.post(f"/admin/ads/{ad_id}/approve", headers=admin["auth"], json={"erid": "2Vfnxw9KLmZ"})
    assert ok.status_code == 200, ok.text
    assert ok.json()["erid"] == "2Vfnxw9KLmZ"
    client.delete(f"/admin/ads/{ad_id}", headers=admin["auth"])   # за собой убираем
