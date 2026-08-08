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
    # «Хочу показывать» (status) у партнёра не отбираем — это его решение. Не пускает в
    # витрину состояние ПРОВЕРКИ: held = помечено автопроверкой, ждёт человека.
    assert r.json()["status"] == "active"
    assert r.json()["review"] == "held"
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


# ------------------------- очередь модерации: чистое видно, но проверяется -------------------------

def test_clean_coupon_is_visible_and_still_lands_in_the_queue(client, user_factory, monkeypatch):
    """Главная дыра, ради которой всё затевалось: чистый купон не видел НИКТО и НИКОГДА.

    Автопроверка ищет телефоны, ссылки и ругань по шаблонам — «скидка 90% при предоплате
    на карту» проходит её насквозь. Поэтому чистое публикуем сразу (бизнес не тормозим),
    но оно обязано попасть в очередь к человеку.
    """
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, _pid = _paid_partner(client, user_factory, city="Кананикольское")
    cid = _live_coupon(client, owner, city="Кананикольское", title="Скидка 90% при предоплате")

    assert _in_storefront(client, "Кананикольское", cid)          # видно сразу
    q = client.get("/admin/moderation", headers=admin["auth"]).json()
    assert any(c["id"] == cid and c["review"] == "pending" for c in q["coupons"])

    # Александр посмотрел → купон уходит из очереди, из витрины НЕ уходит
    assert client.post(f"/admin/coupons/{cid}/approve", headers=admin["auth"]).status_code == 200
    q2 = client.get("/admin/moderation", headers=admin["auth"]).json()
    assert all(c["id"] != cid for c in q2["coupons"])
    assert _in_storefront(client, "Кананикольское", cid)


def test_held_coupon_is_first_in_the_queue(client, user_factory, monkeypatch):
    """Задержанный автопроверкой блокирует человека — он должен быть выше просто непросмотренных."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, _pid = _paid_partner(client, user_factory, city="Исянгулово")
    clean = _live_coupon(client, owner, city="Исянгулово", title="Чай в подарок")
    held = client.post("/partner/coupons", headers=owner["auth"], json={
        "title": "Скидка", "discount_text": "−10%", "city": "Исянгулово",
        "description": "звони +7 987 000-11-22", "valid_until": _future(),
    }).json()["id"]

    ids = [c["id"] for c in client.get("/admin/moderation", headers=admin["auth"]).json()["coupons"]]
    assert ids.index(held) < ids.index(clean)


def test_admin_can_take_a_coupon_off_and_partner_cannot_switch_it_back(client, user_factory, monkeypatch):
    """Снятое админом партнёр не воскрешает кнопкой «включить» — только правкой текста."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, _pid = _paid_partner(client, user_factory, city="Мраково")
    cid = _live_coupon(client, owner, city="Мраково", title="Мойка −30%")

    r = client.post(f"/admin/coupons/{cid}/block", headers=admin["auth"],
                    json={"reason": "Скидки на деле нет"})
    assert r.status_code == 200 and r.json()["review"] == "blocked"
    assert not _in_storefront(client, "Мраково", cid)

    # «Включить» не помогает — текст тот же, решение человека в силе
    back = client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "active"})
    assert back.status_code == 409, back.text
    assert not _in_storefront(client, "Мраково", cid)

    # А правка текста возвращает купон в очередь — решает снова человек
    client.post(f"/partner/coupons/{cid}", headers=owner["auth"], json={
        "title": "Мойка −15%", "discount_text": "−15%", "city": "Мраково", "valid_until": _future()})
    q = client.get("/admin/moderation", headers=admin["auth"]).json()
    assert any(c["id"] == cid for c in q["coupons"])


# ------------------------- жалоба пользователя -------------------------

def test_report_returns_the_coupon_to_the_queue_but_does_not_hide_it(client, user_factory, monkeypatch):
    """Жалоба ставит купон перед глазами админа, но НЕ снимает: иначе конкурент гасит чужую
    скидку одной кнопкой."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, _pid = _paid_partner(client, user_factory, city="Бурибай")
    cid = _live_coupon(client, owner, city="Бурибай", title="Шаурма −50%")
    assert client.post(f"/admin/coupons/{cid}/approve", headers=admin["auth"]).status_code == 200

    passenger = user_factory(name="Обиженный")
    r = client.post(f"/coupons/{cid}/report", headers=passenger["auth"],
                    json={"reason": "Скидку не дали, сказали что закончилась"})
    assert r.status_code == 200 and r.json()["already"] is False

    assert _in_storefront(client, "Бурибай", cid)          # НЕ сняли
    q = client.get("/admin/moderation", headers=admin["auth"]).json()
    row = next(c for c in q["coupons"] if c["id"] == cid)
    assert row["review"] == "pending" and row["reports_count"] == 1


def test_second_report_from_the_same_person_changes_nothing(client, user_factory, monkeypatch):
    """Один человек — одна жалоба: повторными нажатиями очередь не засыпать."""
    monkeypatch.setattr("app.routers.coupons.notify_admin_telegram", lambda *a, **k: None)
    owner, admin, _pid = _paid_partner(client, user_factory, city="Целинный")
    cid = _live_coupon(client, owner, city="Целинный", title="Кофе −40%")
    passenger = user_factory(name="Настойчивый")

    client.post(f"/coupons/{cid}/report", headers=passenger["auth"], json={"reason": "обман"})
    again = client.post(f"/coupons/{cid}/report", headers=passenger["auth"], json={"reason": "обман"})
    assert again.status_code == 200 and again.json()["already"] is True

    q = client.get("/admin/moderation", headers=admin["auth"]).json()
    assert next(c for c in q["coupons"] if c["id"] == cid)["reports_count"] == 1


def test_moderation_queue_is_admin_only(client, user_factory):
    """Очередь показывает чужие тексты и решения — только админу."""
    stranger = user_factory(name="Посторонний")
    assert client.get("/admin/moderation", headers=stranger["auth"]).status_code == 403
