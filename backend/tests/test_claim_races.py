"""Что можно взять один раз — нельзя взять дважды. И это должно быть ПРОВЕРЯЕМО.

Общая картина. В Юлдаше несколько мест, где ресурс один, а желающих несколько: посылка,
которую забирает курьер; код приглашения с ограниченным числом использований; место в
поездке. Все они защищены блокировкой строки (`with_for_update`), и на боевом сервере
(PostgreSQL) это работает.

Дырявым было другое: **SQLite игнорирует `FOR UPDATE`**. На SQLite работают тесты, локальная
разработка и демо-база эмулятора. Там защиты не было вовсе — а значит:

* поведение приложения на этих базах отличалось от боевого, и проверять его было негде;
* саму защиту не проверял НИ ОДИН тест — строку `with_for_update()` можно было стереть при
  рефакторинге, и все тесты остались бы зелёными.

Что сделано: условие «ресурс ещё свободен» переехало ВНУТРЬ `UPDATE`. Это работает на обеих
базах, и его наконец можно проверить. Блокировки строк оставлены на месте — они сериализуют
остальные проверки.

Тесты бьют одновременными запросами из нескольких потоков. На старом коде каждый из них
получает два успеха вместо одного, то есть дефект воспроизводит.
"""
from __future__ import annotations

import threading

from sqlmodel import Session, select

from app.db import engine
from app.models import InviteCode, ParcelDelivery, Trust, UserRole


def _together(fn, items: list) -> list:
    """Все участники жмут кнопку в один момент: барьер держит потоки до последнего."""
    out: list = []
    lock = threading.Lock()
    barrier = threading.Barrier(len(items))

    def run(item):
        barrier.wait()
        r = fn(item)
        with lock:
            out.append(r)

    threads = [threading.Thread(target=run, args=(i,)) for i in items]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return out


# ─────────────────────────── посылка: один курьер ───────────────────────────

def _parcel(client, sender) -> int:
    r = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "receiver_name": "Гульнара", "receiver_phone": "+79990001133",
        "size": "small", "price": 200, "rules_accepted": True,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_посылку_не_забирают_два_курьера(client, user_factory):
    sender = user_factory("Отправитель")
    parcel_id = _parcel(client, sender)
    couriers = [user_factory("Курьер1"), user_factory("Курьер2")]

    codes = _together(
        lambda c: client.post(f"/parcels/{parcel_id}/accept", headers=c["auth"], json={}).status_code,
        couriers,
    )

    assert 200 in codes, f"ни один курьер не смог взять посылку: {codes}"
    assert codes.count(200) == 1, (
        "посылку «взяли» двое — первый повезёт её, а в базе курьером записан другой "
        "(ответы: %s)" % codes
    )
    with Session(engine) as s:
        p = s.get(ParcelDelivery, parcel_id)
        assert p.status == "accepted"
        assert p.courier_id in [c["id"] for c in couriers]


def test_свободную_посылку_берут_как_обычно(client, user_factory):
    """Защита от гонки не должна мешать обычному случаю."""
    sender = user_factory("Отправитель")
    parcel_id = _parcel(client, sender)
    courier = user_factory("Курьер")
    r = client.post(f"/parcels/{parcel_id}/accept", headers=courier["auth"], json={})
    assert r.status_code == 200, r.text
    r2 = client.post(f"/parcels/{parcel_id}/accept", headers=user_factory("Курьер3")["auth"], json={})
    assert r2.status_code == 409, "вторую попытку должны отбить понятным отказом"


# ─────────────────────── код приглашения: одно использование ───────────────────────

def _invite_with_one_use(client, owner) -> str:
    r = client.post("/invites", headers=owner["auth"])
    assert r.status_code == 200, r.text
    code = r.json()["code"]
    with Session(engine) as s:
        inv = s.exec(select(InviteCode).where(InviteCode.code == code)).first()
        inv.uses_left = 1          # оставляем ровно одно — за него и будет драка
        s.add(inv)
        s.commit()
    return code


def test_последнее_использование_кода_достаётся_одному(client, user_factory):
    """Код приглашения вводит в круг «своих» — это доступ к поездкам «только для проверенных».
    Лишний человек в круге обесценивает обещание безопасности, ради которого круг и заведён."""
    owner = user_factory("Пригласивший", role=UserRole.driver)
    # круг своих раздаёт только проверенный — поднимаем уровень напрямую
    with Session(engine) as s:
        row = s.exec(select(Trust).where(Trust.user_id == owner["id"])).first()
        if row is None:
            row = Trust(user_id=owner["id"], level=3)
        else:
            row.level = 3
        s.add(row)
        s.commit()
    code = _invite_with_one_use(client, owner)
    guests = [user_factory("Гость1"), user_factory("Гость2")]

    codes = _together(
        lambda g: client.post("/invites/redeem", headers=g["auth"], json={"code": code}).status_code,
        guests,
    )

    assert codes.count(200) <= 1, (
        "последнее использование кода досталось двоим — в круг «своих» попал лишний "
        "человек (ответы: %s)" % codes
    )
    with Session(engine) as s:
        inv = s.exec(select(InviteCode).where(InviteCode.code == code)).first()
        assert inv.uses_left >= 0, "счётчик использований ушёл в минус"


# ─────────────────── одноразовый refresh-токен: второе использование не проходит ───────────────────

def test_один_refresh_нельзя_обменять_дважды(client, user_factory):
    """Смысл одноразового refresh — поймать кражу: если токен утёк, ВТОРОЕ его использование
    обязано провалиться. Пока условие «ещё не погашен» проверялось отдельно от записи, два
    одновременных обмена проходили оба, и укравший работал наравне с хозяином, не оставляя следа.
    """
    from app.security import issue_tokens

    u = user_factory("Хозяин токена")
    with Session(engine) as s:
        refresh = issue_tokens(s, u["id"])["refresh_token"]

    codes = _together(
        lambda _: client.post("/auth/refresh", json={"refresh_token": refresh}).status_code,
        [1, 2],
    )

    assert codes.count(200) == 1, (
        "один refresh обменяли дважды — кража такого токена осталась бы незамеченной "
        "(ответы: %s)" % codes
    )
    assert 401 in codes, f"второе использование должно отдавать 401, а пришло {codes}"


# ─────────────────── заявка пассажира: один принятый отклик ───────────────────

def test_заявку_нельзя_закрыть_двумя_откликами(client, user_factory):
    """Два одновременных «принять отклик» (пассажир из приложения и админ из Telegram-кнопки)
    создавали ДВЕ поездки на одну заявку: за пассажиром выезжали два водителя, каждый по своей
    договорённости о цене. Отменить лишнюю — значит подвести того, кто уже собрался ехать."""
    from app.models import RideRequest

    pax = user_factory("Пассажир")
    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "max_price": 500,
    })
    assert r.status_code == 200, r.text
    req_id = r.json()["id"]

    resp_ids = []
    for name in ("Водитель1", "Водитель2"):
        drv = user_factory(name, role=UserRole.driver)
        rr = client.post(f"/requests/{req_id}/respond", headers=drv["auth"], json={"price": 400})
        assert rr.status_code == 200, rr.text
        resp_ids.append(rr.json()["id"])

    codes = _together(
        lambda rid: client.post(f"/responses/{rid}/accept", headers=pax["auth"]).status_code,
        resp_ids,
    )

    assert codes.count(200) == 1, (
        "заявку закрыли двумя откликами — за пассажиром поедут два водителя (ответы: %s)" % codes
    )
    with Session(engine) as s:
        assert s.get(RideRequest, req_id).status == "matched"


# ─────────────────── промокод: лимит кампании — это деньги платформы ───────────────────

def test_последний_промокод_достаётся_одному(client, user_factory):
    """Скидку по промокоду оплачивает Юлдаш, водитель получает своё полностью. Значит лимит
    кампании — это прямые деньги Александра: «сто активаций» должно означать ровно сто.

    «Один код на человека» защищено уникальным индексом. А вот общий лимит проверялся отдельно
    от записи: два РАЗНЫХ человека, активирующих последний купон одновременно, оба проходили
    проверку — и кампания тихо раздавала больше, чем в неё заложили.
    """
    from app.models import PromoCode

    admin = user_factory("Админ", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "RACE1", "title": "Гонка", "kind": "welcome", "limit_total": 1,
    })
    assert r.status_code == 200, r.text

    guests = [user_factory("Гость-A"), user_factory("Гость-B")]
    codes = _together(
        lambda g: client.post("/promo/apply", headers=g["auth"], json={"code": "RACE1"}).status_code,
        guests,
    )

    assert codes.count(200) == 1, (
        "лимит кампании пробит — платформа раздала больше скидок, чем заложила "
        "(ответы: %s)" % codes
    )
    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "RACE1")).first()
        assert promo.redeemed_count <= promo.limit_total, "счётчик активаций перескочил лимит"
