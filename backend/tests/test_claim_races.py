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
