"""Строгий режим витрины «Скидки по пути» (решение Александра, 2026-08-08).

Одобрение админом закрывало только ПЕРВЫЙ показ: карточку, одобренную чистой, владелец
переписывал во что угодно, и она уходила в витрину сразу. Теперь:

  • бизнес: правка ВИДИМОГО ТЕКСТА у одобренного → карточка снова `pending`, купоны уходят
    из витрины до нового одобрения (как правка рекламы у `_own_editable_ad`);
  • купон: бизнес публикует сам (их до 50 на бизнес), поэтому строгость другая —
    помеченный проверкой текст в витрину не выпускается.
"""
from datetime import datetime, timedelta

from app.models import UserRole


def _future():
    return (datetime.utcnow() + timedelta(days=30)).replace(microsecond=0).isoformat()


def _paid_partner(client, user_factory, city="Баймак"):
    """Одобренный бизнес с оплаченной подпиской (минимальная версия хелпера journey-теста)."""
    owner = user_factory(name="Владелец")
    admin = user_factory(name="Админ", role=UserRole.admin)
    pid = client.post("/partner", headers=owner["auth"], json={
        "name": "Шиномонтаж у моста", "city": city, "phone": "+7 900 111-22-33",
        "category": "tire", "description": "Быстро и честно",
    }).json()["id"]
    assert client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"]).status_code == 200
    payment_id = client.post("/partner/subscribe", headers=owner["auth"],
                             json={"plan": "standard"}).json()["payment_id"]
    assert client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"]).status_code == 200
    return owner, admin, pid


def _live_coupon(client, owner, city="Баймак", title="Шиномонтаж −20%"):
    cid = client.post("/partner/coupons", headers=owner["auth"], json={
        "title": title, "discount_text": "−20%", "city": city, "valid_until": _future(),
    }).json()["id"]
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"],
                       json={"status": "active"}).status_code == 200
    return cid


def _in_storefront(client, city, coupon_id) -> bool:
    return any(c["id"] == coupon_id for c in client.get("/coupons", params={"city": city}).json())


# ------------------------- бизнес: правка текста → снова на модерацию -------------------------

def test_editing_visible_text_pulls_the_card_back_to_moderation(client, user_factory, monkeypatch):
    """Подмена после проверки: одобренную карточку переписали → она уходит с витрины."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, pid = _paid_partner(client, user_factory, city="Сибай")
    cid = _live_coupon(client, owner, city="Сибай")
    assert _in_storefront(client, "Сибай", cid)

    r = client.post(f"/partner/{pid}", headers=owner["auth"], json={
        "name": "Шиномонтаж у моста", "city": "Сибай", "phone": "+7 900 111-22-33",
        "category": "tire", "description": "СОВСЕМ ДРУГОЙ ТЕКСТ",
    })
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "pending"
    assert not _in_storefront(client, "Сибай", cid)      # купоны скрыты до нового одобрения

    # админ проверил и вернул → витрина восстановилась, ничего не потерялось
    assert client.post(f"/admin/partners/{pid}/approve", headers=admin["auth"]).status_code == 200
    assert _in_storefront(client, "Сибай", cid)


def test_saving_the_same_form_does_not_pull_the_card(client, user_factory, monkeypatch):
    """Повторное сохранение той же формы (обычное поведение приложения) карточку не роняет."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, _admin, pid = _paid_partner(client, user_factory, city="Учалы")
    same = {"name": "Шиномонтаж у моста", "city": "Учалы", "phone": "+7 900 111-22-33",
            "category": "tire", "description": "Быстро и честно"}
    r = client.post(f"/partner/{pid}", headers=owner["auth"], json=same)
    assert r.status_code == 200 and r.json()["status"] == "active"


def test_moving_the_pin_does_not_pull_the_card(client, user_factory, monkeypatch):
    """Координаты и категория — не текст объявления: бизнес не должен пропадать из-за пина."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, _admin, pid = _paid_partner(client, user_factory, city="Ургаза")
    r = client.post(f"/partner/{pid}", headers=owner["auth"], json={
        "name": "Шиномонтаж у моста", "city": "Ургаза", "phone": "+7 900 111-22-33",
        "category": "service", "description": "Быстро и честно", "lat": 52.85, "lng": 58.30,
    })
    assert r.status_code == 200 and r.json()["status"] == "active"


# ------------------------- купон: помеченный текст в витрину не идёт -------------------------

def test_flagged_edit_takes_the_coupon_off_the_storefront(client, user_factory, monkeypatch):
    """Живой купон переписали телефоном в тексте → уходит в черновик, витрина его не видит."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, _admin, _pid = _paid_partner(client, user_factory, city="Темясово")
    cid = _live_coupon(client, owner, city="Темясово")
    assert _in_storefront(client, "Темясово", cid)

    r = client.post(f"/partner/coupons/{cid}", headers=owner["auth"], json={
        "title": "Шиномонтаж −20%", "discount_text": "−20%", "city": "Темясово",
        "description": "Звони напрямую 8 987 123-45-67, дешевле",
        "valid_until": _future(),
    })
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "draft"
    assert not _in_storefront(client, "Темясово", cid)


def test_flagged_coupon_cannot_be_switched_on(client, user_factory, monkeypatch):
    """Обход «сохранил черновиком, потом включил» закрыт: включение тоже проверяет текст."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, _admin, _pid = _paid_partner(client, user_factory, city="Акъяр")
    cid = client.post("/partner/coupons", headers=owner["auth"], json={
        "title": "Скидка", "discount_text": "−10%", "city": "Акъяр",
        "description": "пиши на +7 987 000-11-22", "valid_until": _future(),
    }).json()["id"]
    r = client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "active"})
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert detail.get("ru") and detail.get("ba")        # двуязычно, как все 4xx пользователю
    assert not _in_storefront(client, "Акъяр", cid)


def test_clean_coupon_publishes_as_before(client, user_factory, monkeypatch):
    """Честный бизнес ничего не заметил: чистый текст включается сразу."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, _admin, _pid = _paid_partner(client, user_factory, city="Зилаир")
    cid = _live_coupon(client, owner, city="Зилаир", title="Кофе в подарок")
    assert _in_storefront(client, "Зилаир", cid)
