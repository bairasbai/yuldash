"""Фото «взял целой» на переходе accepted→in_transit.

Зачем момент именно такой. Поле `pickup_photo_url` сервер принимал только в `/parcels/{id}/accept`,
но «взять заказ» и «стоять рядом с посылкой» — разные моменты: заявку берут заранее, а к
отправителю курьер приезжает позже. Снимок в момент принятия физически невозможен, поэтому поле
и оставалось пустым всегда. Теперь фото принимается там, где курьер реально держит посылку в
руках — при переходе «забрал и повёз».

Почему это важно: без снимка на ГРАНИЦЕ ответственности спор «было битое / стало битое»
упирается в слово против слова. Фото при вручении уже было — теперь есть обе точки.

Приватность: принимаем ТОЛЬКО собственный медиа-URL. Чужая ссылка при открытии у оппонента
слила бы его IP, поэтому она молча игнорируется (то же правило, что у фото вручения).
"""
from test_parcels import _create_parcel

_OWN_PHOTO = "/media/parcel_pickup_1.jpg"
_ALIEN_PHOTO = "https://evil.example.com/track.jpg"


def _accepted_parcel(client, sender, courier):
    """Заявка, взятая курьером: следующий шаг — «забрал и повёз»."""
    pid = _create_parcel(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    return pid


def _to_transit(client, courier, pid, photo=None):
    body = {"status": "in_transit"}
    if photo is not None:
        body["pickup_photo_url"] = photo
    return client.post(f"/parcels/{pid}/status", headers=courier["auth"], json=body)


def test_pickup_photo_saved_on_transit(client, user_factory):
    """Свой URL сохраняется и виден обеим сторонам: курьеру и отправителю."""
    sender, courier = user_factory(name="ФотоОтпр"), user_factory(name="ФотоКурьер")
    pid = _accepted_parcel(client, sender, courier)

    r = _to_transit(client, courier, pid, _OWN_PHOTO)
    assert r.status_code == 200
    assert r.json()["pickup_photo_url"] == _OWN_PHOTO

    # Отправитель видит снимок у себя — это его доказательство не меньше, чем курьера.
    mine = client.get("/parcels/mine", headers=sender["auth"]).json()
    row = next(p for p in mine if p["id"] == pid)
    assert row["pickup_photo_url"] == _OWN_PHOTO


def test_pickup_photo_optional(client, user_factory):
    """Снимок необязателен: без него переход в путь работает как раньше, поле остаётся пустым.
    Курьер без камеры под рукой не должен застревать на середине доставки."""
    sender, courier = user_factory(name="БезФотоОтпр"), user_factory(name="БезФотоКурьер")
    pid = _accepted_parcel(client, sender, courier)

    r = _to_transit(client, courier, pid)
    assert r.status_code == 200
    assert r.json()["pickup_photo_url"] == ""


def test_alien_photo_url_ignored(client, user_factory):
    """Чужой хост не принимаем: открытие такой ссылки у оппонента слило бы его IP.
    Игнорируем молча — сам переход в путь ломать из-за этого нельзя."""
    sender, courier = user_factory(name="ЧужойОтпр"), user_factory(name="ЧужойКурьер")
    pid = _accepted_parcel(client, sender, courier)

    r = _to_transit(client, courier, pid, _ALIEN_PHOTO)
    assert r.status_code == 200
    assert r.json()["pickup_photo_url"] == ""


def test_pickup_photo_still_accepted_on_accept(client, user_factory):
    """Старый путь не сломан: кто прикладывает фото прямо при взятии заявки — по-прежнему может."""
    sender, courier = user_factory(name="ПриВзятииОтпр"), user_factory(name="ПриВзятииКурьер")
    pid = _create_parcel(client, sender).json()["id"]

    r = client.post(
        f"/parcels/{pid}/accept", headers=courier["auth"],
        json={"pickup_photo_url": _OWN_PHOTO},
    )
    assert r.status_code == 200
    assert r.json()["pickup_photo_url"] == _OWN_PHOTO


def test_pickup_photo_not_leaked_to_strangers(client, user_factory):
    """Снимок — часть доказательной базы конкретной доставки, а не публичная картинка:
    в открытом списке свободных заказов его быть не может (там ещё и курьера-то нет)."""
    sender, courier = user_factory(name="ЛентаОтпр"), user_factory(name="ЛентаКурьер")
    pid = _accepted_parcel(client, sender, courier)
    _to_transit(client, courier, pid, _OWN_PHOTO)

    stranger = user_factory(name="Посторонний")
    available = client.get("/parcels/available", headers=stranger["auth"]).json()
    assert pid not in [p["id"] for p in available]   # взятая посылка вообще не в открытой ленте
