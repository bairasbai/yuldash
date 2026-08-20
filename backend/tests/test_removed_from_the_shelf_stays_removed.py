"""«Снять с витрины» означало «спрятать полку», а не «выключить».

Три находки роя, один куст: то, что убрали за обман, возвращалось обратно.

**Снятый купон продолжал выдавать коды.** Админ снимает купон — с полки он исчезает, прямая
ссылка отдаёт «не найдено». А кнопка «Получить код» работала: активация проверяла партнёра
и статус купона, но не спрашивала того же, что спрашивает витрина, — прошёл ли купон проверку
(аудит 2026-08-08, волна 145).

Человек приходит с этим кодом в кафе, кафе его принимает, счётчик погашений растёт, и платформа
выставляет бизнесу счёт за скидки, которые он уже отменил.

**Оплата возвращала в эфир снятое.** Волна 125 запретила воскрешать «отклонённое». Но снимают
рекламу по жалобе ДРУГОЙ кнопкой — «приостановить», — и её подтверждение старого счёта спокойно
возвращало в ленту района.

То же у бизнеса, только хуже: правка текста карточки снимает её с витрины обратно на проверку —
это и есть защита от подмены после одобрения. А оплата делала карточку активной из любого
состояния. Значит бизнес нажимал «продлить», потом переписывал текст на «БЫСТРЫЕ ДЕНЬГИ ПОД 0%,
пиши 8 917…», и админ, подтверждая ПОСТУПЛЕНИЕ ДЕНЕГ, одним тапом публиковал непрочитанное.

Деньги и модерация — разные решения. Оплата продлевает срок всегда; в витрину возвращает только
то, что оттуда не убирали.

**Ссылка объявления не проверялась вообще.** У картинки проверка была с самого начала — чужой
хост собирает IP, город и время просмотра всех, кто увидел объявление. У ссылки, по которой
человек жмёт САМ, не было ничего: сервер принимал `javascript:`, `data:`, `intent://` дословно
и отдавал их в ленту, в том числе гостям без входа.

Чужие сайты остаются разрешёнными — реклама кафе ведёт на сайт кафе. Отсекаем только то, что
вообще не является «открыть страницу».
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.antifraud import safe_link
from app.db import engine
from app.models import Ad, Partner, Payment, UserRole


@pytest.fixture
def кафе(client, user_factory):
    """Одобренный бизнес с оплаченной подпиской и живым купоном."""
    владелец = user_factory("ВитринаКафе")
    админ = user_factory("ВитринаАдмин", role=UserRole.admin)
    p = client.post("/partner", headers=владелец["auth"], json={
        "name": "Кафе У дороги", "category": "food", "city": "Баймак",
        "address": "ул. Мира 1", "description": "Горячий чай",
    })
    assert p.status_code == 200, p.text
    pid = p.json()["id"]
    client.post(f"/admin/partners/{pid}/approve", headers=админ["auth"])
    with Session(engine) as s:                       # подписка оплачена
        from app.timeutil import utcnow
        from datetime import timedelta
        partner = s.get(Partner, pid)
        partner.subscription_until = utcnow() + timedelta(days=30)
        partner.status = "active"
        s.add(partner)
        s.commit()
    c = client.post("/partner/coupons", headers=владелец["auth"], json={
        "title": "Кофе -20%", "description": "На любой кофе", "discount_text": "-20%",
    })
    assert c.status_code == 200, c.text
    cid = c.json()["id"]
    # Купон заводится черновиком — публикуем, иначе на витрине его нет и проверять нечего.
    опубликован = client.post(f"/partner/coupons/{cid}/status", headers=владелец["auth"],
                              json={"status": "active"})
    assert опубликован.status_code == 200, опубликован.text
    return владелец, админ, pid, cid


def test_снятый_купон_перестаёт_выдавать_коды(client, кафе, user_factory):
    """Главное: убрали за обман — значит не работает, а не «не видно на полке»."""
    _, админ, _, cid = кафе
    покупатель = user_factory("ВитринаПокупатель")
    снят = client.post(f"/admin/coupons/{cid}/block", headers=админ["auth"],
                       json={"reason": "обещали скидку, не дают"})
    assert снят.status_code == 200, снят.text

    полка = client.get("/coupons")
    код = client.post(f"/coupons/{cid}/activate", headers=покупатель["auth"])

    assert not [c for c in полка.json() if c.get("id") == cid], "снятый купон остался на полке"
    assert код.status_code == 404, (
        f"снятый за обман купон всё ещё выдаёт код ({код.status_code}): человек придёт с ним "
        f"в кафе, а бизнесу выставят счёт за скидку, которую он отменил — {код.text[:100]}"
    )


def test_живой_купон_по_прежнему_работает(client, кафе, user_factory):
    """Обратная сторона: обычный купон должен выдавать код без всяких сложностей."""
    _, _, _, cid = кафе
    покупатель = user_factory("ВитринаПокупатель2")

    код = client.post(f"/coupons/{cid}/activate", headers=покупатель["auth"])

    assert код.status_code == 200, f"честный купон перестал работать: {код.text[:120]}"
    assert код.json().get("code"), "код не выдан"


def test_оплата_не_возвращает_в_ленту_снятую_рекламу(client, user_factory):
    """Админ снял рекламу по жалобе — подтверждение старого счёта не должно её воскрешать."""
    партнёр = user_factory("РекламаПартнёр")
    админ = user_factory("РекламаАдмин", role=UserRole.admin)
    a = client.post("/admin/ads", headers=админ["auth"], json={
        "partner_name": "Шиномонтаж", "title": "Меняем резину", "text": "Быстро",
        "button": "Открыть", "target": "https://shina.example", "period_days": 30,
    })
    assert a.status_code == 200, a.text
    ad_id = a.json()["id"]
    with Session(engine) as s:                       # висит неоплаченный счёт
        pay = Payment(user_id=партнёр["id"], purpose="ad", ad_id=ad_id,
                      amount_kop=100000, status="pending", tier="renew")
        s.add(pay)
        s.commit()
        s.refresh(pay)
        pay_id = pay.id
    client.post(f"/admin/ads/{ad_id}/status", headers=админ["auth"], json={"status": "paused"})

    client.post(f"/admin/payments/{pay_id}/confirm", headers=админ["auth"])

    with Session(engine) as s:
        ad = s.get(Ad, ad_id)
    assert ad.status != "active", (
        f"снятая по жалобе реклама вернулась в ленту района через подтверждение оплаты "
        f"(статус {ad.status!r}). Деньги и модерация — разные решения"
    )


def test_оплата_не_публикует_непрочитанный_текст(client, кафе, user_factory):
    """Правка текста снимает карточку на проверку — оплата не должна её публиковать."""
    владелец, админ, pid, _ = кафе
    with Session(engine) as s:
        pay = Payment(user_id=владелец["id"], purpose="partner_sub", partner_id=pid,
                      amount_kop=99000, status="pending", tier="basic")
        s.add(pay)
        s.commit()
        s.refresh(pay)
        pay_id = pay.id
        срок_до = s.get(Partner, pid).subscription_until
    подмена = client.post(f"/partner/{pid}", headers=владелец["auth"], json={
        "name": "Кафе У дороги", "category": "food", "city": "Баймак",
        "address": "ул. Мира 1", "description": "БЫСТРЫЕ ДЕНЬГИ ПОД 0%",
    })
    assert подмена.status_code == 200, подмена.text

    client.post(f"/admin/payments/{pay_id}/confirm", headers=админ["auth"])

    with Session(engine) as s:
        partner = s.get(Partner, pid)
    assert partner.status != "active", (
        f"подтверждение ПОСТУПЛЕНИЯ ДЕНЕГ опубликовало текст, которого никто не читал "
        f"(статус {partner.status!r}). Админ подтверждает оплату, а не карточку"
    )
    assert partner.subscription_until > срок_до, (
        f"оплата не продлила срок ({срок_до} → {partner.subscription_until}): деньги ушли, "
        "а подписка не выросла. Заодно это значит, что проверять статус тут было не на чем"
    )


@pytest.mark.parametrize("опасная", [
    "javascript:fetch(document.cookie)",
    "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
    "intent://evil#Intent;scheme=zzz;end",
    "file:///etc/passwd",
])
def test_в_объявление_нельзя_вставить_не_ссылку(client, user_factory, опасная: str):
    """По ней человек жмёт сам — сервер обязан отсечь то, что не является «открыть страницу»."""
    админ = user_factory(f"СсылкаАдмин{abs(hash(опасная)) % 1000}", role=UserRole.admin)

    r = client.post("/admin/ads", headers=админ["auth"], json={
        "partner_name": "Тест", "title": "Тест", "text": "Тест", "button": "Открыть",
        "target": опасная, "period_days": 7,
    })

    assert r.status_code == 400, (
        f"сервер принял {опасная!r} как ссылку объявления (ответ {r.status_code}) — "
        "она уедет в ленту, в том числе гостям без входа"
    )


def test_обычная_ссылка_кафе_работает(client, user_factory):
    """Обратная сторона: реклама кафе ведёт на сайт кафе — это нормально и должно проходить."""
    админ = user_factory("СсылкаАдмин2", role=UserRole.admin)

    r = client.post("/admin/ads", headers=админ["auth"], json={
        "partner_name": "Кафе", "title": "Скидка", "text": "Заходи", "button": "Открыть",
        "target": "https://kafe-baymak.ru/sale", "period_days": 7,
    })

    assert r.status_code == 200, f"обычная ссылка на сайт кафе не прошла: {r.text[:120]}"


def test_адрес_без_протокола_человеку_прощается():
    """Люди пишут «kafe.ru/sale» — отказывать за это значит ломать работу на ровном месте."""
    assert safe_link("kafe-baymak.ru/sale") == "https://kafe-baymak.ru/sale"
    assert safe_link("") == "", "пустая ссылка — не ошибка, поле необязательное"
    assert safe_link("tel:+79991234567").startswith("tel:"), "позвонить в кафе — нормальная ссылка"
