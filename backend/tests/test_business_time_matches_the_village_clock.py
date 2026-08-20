"""Кафе объявляло скидку «до 20 августа», а она работала до пяти утра 21-го.

Юлдаш живёт по Уфе (UTC+5), и время от человека проходит через общий переводчик — так чинили
время выезда поездки. Но у купонов, промокампаний, рекламы и подписки на маршрут его забыли
подключить: строка от клиента ложилась в базу как есть и считалась мировым временем
(аудит 2026-08-08, волна 152).

Пять часов разницы — это по-человечески:

* скидка «до 20 августа 23:59» гаснет в 05:00 21-го — лишние пять часов за счёт кафе и его
  лимита купонов;
* акция «с 1 августа» не работает первые пять часов первого дня — люди приходят, а скидки нет;
* оплаченный день рекламы сдвинут на пять часов относительно суток, за которые заплатили;
* подписка «карауль маршрут на 15-е», отправленная вечером, караулила 16-е.

И обратная беда: если аккуратный клиент присылал время С поясом, сервер пояс молча выбрасывал —
то есть тот, кто сделал правильно, получал результат хуже, чем тот, кто сделал небрежно.

---

**Заодно про отзывы о приложении.** Проверку открытого текста навесили на сам отзыв и забыли
на соседнее поле «город»: `«Сибай тел 89991112233»` уезжал на публичный лендинг без входа
и без метки админу — бесплатная рекламная строка с чужим телефоном на сайте Юлдаша. И потолка
на темп у отзывов не было вовсе: один человек клал в очередь модерации пятнадцать штук подряд,
а настоящие отзывы соседей в этом тонули.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlmodel import Session, select

from app.db import engine
from app.models import AppReview, Coupon, Partner, PromoCode, UserRole
from app.timeutil import local_date, utcnow


def _кафе(client, user_factory, имя: str = "ВремяКафе"):
    владелец = user_factory(имя)
    админ = user_factory(имя + "Админ", role=UserRole.admin)
    pid = client.post("/partner", headers=владелец["auth"], json={
        "name": "Кафе", "category": "food", "city": "Баймак",
        "address": "ул. Мира 1", "description": "Чай",
    }).json()["id"]
    client.post(f"/admin/partners/{pid}/approve", headers=админ["auth"])
    with Session(engine) as s:
        p = s.get(Partner, pid)
        p.subscription_until = utcnow() + timedelta(days=30)
        p.status = "active"
        s.add(p)
        s.commit()
    return владелец, админ, pid


def test_скидка_гаснет_в_ту_же_ночь_а_не_под_утро(client, user_factory):
    """Главное: «до 20 августа» для кафе — это по Уфе, а не по мировому времени."""
    владелец, _, _ = _кафе(client, user_factory)

    r = client.post("/partner/coupons", headers=владелец["auth"], json={
        "title": "Кофе -20%", "description": "На кофе", "discount_text": "-20%",
        "valid_until": "2030-08-20T23:59:00",
    })

    assert r.status_code == 200, r.text
    with Session(engine) as s:
        купон = s.exec(select(Coupon).where(Coupon.id == r.json()["id"])).first()
    # В базе время мировое; для человека в Уфе это должен быть всё ещё 20-е.
    assert local_date(купон.valid_until) == datetime(2030, 8, 20).date(), (
        f"скидка «до 20 августа» в местных сутках гаснет {local_date(купон.valid_until)}: "
        "лишние часы идут за счёт кафе и его лимита купонов"
    )


def test_акция_начинается_с_первой_минуты_дня(client, user_factory):
    """«С 1 августа» — значит с первой минуты первого дня, а не с пяти утра."""
    владелец, _, _ = _кафе(client, user_factory, "ВремяКафе2")

    r = client.post("/partner/coupons", headers=владелец["auth"], json={
        "title": "Завтрак", "description": "Утро", "discount_text": "-10%",
        "valid_from": "2030-08-01T00:00:00",
    })

    with Session(engine) as s:
        купон = s.exec(select(Coupon).where(Coupon.id == r.json()["id"])).first()
    assert local_date(купон.valid_from) == datetime(2030, 8, 1).date(), (
        f"акция «с 1 августа» в местных сутках стартует {local_date(купон.valid_from)}: "
        "первые часы люди приходят, а скидки нет"
    )


def test_аккуратный_клиент_не_наказан(client, user_factory):
    """Обратная сторона: кто прислал время с поясом, должен получить именно этот момент.

    Раньше пояс молча выбрасывался — и тот, кто сделал правильно, получал результат хуже
    небрежного.
    """
    владелец, _, _ = _кафе(client, user_factory, "ВремяКафе3")

    r = client.post("/partner/coupons", headers=владелец["auth"], json={
        "title": "Ужин", "description": "Вечер", "discount_text": "-15%",
        "valid_until": "2030-08-20T23:59:00+05:00",
    })

    with Session(engine) as s:
        купон = s.exec(select(Coupon).where(Coupon.id == r.json()["id"])).first()
    ожидаемо = datetime(2030, 8, 20, 23, 59, tzinfo=timezone(timedelta(hours=5)))
    ожидаемо_utc = ожидаемо.astimezone(timezone.utc).replace(tzinfo=None)
    assert abs((купон.valid_until - ожидаемо_utc).total_seconds()) < 60, (
        f"явно указанный пояс проигнорирован: {купон.valid_until} вместо {ожидаемо_utc}"
    )


def test_срок_промокампании_тоже_по_уфе(client, user_factory):
    """Кампания «до конца августа» не должна тянуться лишние пять часов."""
    админ = user_factory("ВремяПромоАдмин", role=UserRole.admin)

    r = client.post("/admin/promo", headers=админ["auth"], json={
        "code": "AVGUST", "title": "Август", "kind": "welcome",
        "valid_until": "2030-08-31T23:59:00",
    })

    assert r.status_code == 200, r.text
    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "AVGUST")).first()
    assert local_date(promo.valid_until) == datetime(2030, 8, 31).date(), (
        f"кампания в местных сутках кончается {local_date(promo.valid_until)}"
    )


def test_телефон_в_поле_города_не_уезжает_на_сайт(client, user_factory):
    """Проверку навесили на текст отзыва и забыли на соседнее поле."""
    человек = user_factory("ГородОтзыв")

    r = client.post("/reviews", headers=человек["auth"], json={
        "stars": 5, "text": "Отличное приложение, всем советую",
        "city": "Сибай тел 89991112233",
    })

    assert r.status_code == 200, r.text
    with Session(engine) as s:
        отзыв = s.get(AppReview, r.json()["id"])
    # Модерация помечает, а не режет — но пометка обязана появиться, иначе админ не узнает.
    from app.models import TextFlag
    with Session(engine) as s:
        метки = s.exec(select(TextFlag).where(TextFlag.user_id == человек["id"])).all()
    assert метки, (
        f"город с чужим телефоном ({отзыв.city!r}) уехал на публичный лендинг без единой "
        "пометки — админ об этом не узнает"
    )


def test_обычный_город_проходит(client, user_factory):
    """Обратная сторона: человек просто пишет, откуда он."""
    человек = user_factory("ГородОбычный")

    r = client.post("/reviews", headers=человек["auth"], json={
        "stars": 5, "text": "Хорошее приложение, ездим всей семьёй", "city": "Баймак",
    })

    assert r.status_code == 200, f"обычный город не прошёл: {r.text[:120]}"
    with Session(engine) as s:
        assert s.get(AppReview, r.json()["id"]).city == "Баймак"


def test_очередь_модерации_не_топится_одним_человеком(client, user_factory):
    """Иначе настоящие отзывы соседей тонут, и админ перестаёт открывать очередь."""
    человек = user_factory("ОтзывСпамер")

    коды = [client.post("/reviews", headers=человек["auth"], json={
        "stars": 5, "text": f"Отзыв номер {i}, всё нравится очень", "city": "Баймак",
    }).status_code for i in range(15)]

    assert 429 in коды, (
        f"пятнадцать отзывов подряд от одного человека прошли без ограничения: {коды}"
    )
    assert коды[0] == 200, "первый честный отзыв тоже не прошёл — сломали обычный сценарий"
