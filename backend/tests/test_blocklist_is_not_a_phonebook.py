"""Чёрный список не должен работать справочником жителей района.

Что было. Заблокировать можно любого по номеру — и это правильно: закрыться заранее, ещё
до поездки, право человека. Но список «кого я заблокировал» отдавал ИМЕНА. Значит достаточно
было пройти циклом «заблокировать 1, 2, 3…» и прочитать свой же список — получался справочник
«номер → имя» всех, кто есть в приложении района (аудит 2026-08-08, волна 120).

Незаметно вдвойне: человек не узнаёт, что его заблокировали (так задумано, волна 107), —
то есть выгрузка проходила совсем бесследно.

Первая попытка починки была строже: запретить блокировать тех, с кем не пересекался. От неё
я отказался — она отбирает у человека возможность закрыться заранее, от соседа, с которым
ещё не ездил, и роняет девятнадцать чужих проверок. Отобрали ровно то, ради чего перебор
и затевался: чужие имена. Имя видит только тот, кто с этим человеком действительно
пересекался — ехал, вёз посылку или торговался по заявке.
"""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride


def test_перебор_не_выгружает_имена(client, user_factory):
    """Главное: цикл по номерам больше не собирает справочник."""
    жители = [user_factory(f"СписокЖитель{i}") for i in range(1, 6)]
    чужак = user_factory("СписокЧужак")

    for ж in жители:
        client.post("/blocks", headers=чужак["auth"], json={"blocked_user_id": ж["id"]})

    имена = [x["name"] for x in client.get("/blocks", headers=чужак["auth"]).json()]
    assert all(и == "Пользователь" for и in имена), (
        f"через чёрный список выгружен справочник «номер → имя»: {имена}. "
        "За час так собирается весь район, и никто об этом не узнаёт"
    )


def test_закрыться_заранее_по_прежнему_можно(client, user_factory):
    """Обратная сторона: это защита человека, ограничивать её нельзя."""
    сосед = user_factory("СписокСосед")
    осторожная = user_factory("СписокОсторожная")

    r = client.post("/blocks", headers=осторожная["auth"],
                    json={"blocked_user_id": сосед["id"]})

    assert r.status_code in (200, 201), f"человек не может закрыться заранее: {r.text}"
    assert сосед["id"] in [x["blocked_user_id"] for x in
                           client.get("/blocks", headers=осторожная["auth"]).json()]


def test_имя_попутчика_в_списке_видно(client, user_factory):
    """С кем ехал — того человек должен узнавать в списке, иначе список бесполезен."""
    водитель = user_factory("СписокВодитель", role=UserRole.driver)
    пассажирка = user_factory("СписокГульнара")
    ride_id = _ride(client, водитель, comment="ехали вместе")
    assert client.post("/bookings", headers=пассажирка["auth"],
                       json={"ride_id": ride_id, "seats": 1}).status_code == 200
    client.post("/blocks", headers=пассажирка["auth"], json={"blocked_user_id": водитель["id"]})

    строка = client.get("/blocks", headers=пассажирка["auth"]).json()[0]

    assert строка["name"] == "СписокВодитель", (
        f"имя попутчика скрыто ({строка['name']}) — человек не поймёт, кого он заблокировал"
    )


def test_имя_видно_и_водителю_про_пассажира(client, user_factory):
    """Правило работает в обе стороны."""
    водитель = user_factory("СписокВодитель2", role=UserRole.driver)
    пассажир = user_factory("СписокПассажир2")
    ride_id = _ride(client, водитель, comment="ехали вместе")
    client.post("/bookings", headers=пассажир["auth"], json={"ride_id": ride_id, "seats": 1})
    client.post("/blocks", headers=водитель["auth"], json={"blocked_user_id": пассажир["id"]})

    строка = client.get("/blocks", headers=водитель["auth"]).json()[0]

    assert строка["name"] == "СписокПассажир2", строка


def test_после_торга_по_заявке_имя_тоже_видно(client, user_factory):
    """Торг — это уже разговор, и он бывает неприятным."""
    пассажир = user_factory("СписокЗаявитель")
    водитель = user_factory("СписокТорговец", role=UserRole.driver)
    rid = client.post("/requests", headers=пассажир["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-11-01T08:00:00", "seats": 1, "max_price": 400,
    }).json()["id"]
    assert client.post(f"/requests/{rid}/respond", headers=водитель["auth"],
                       json={"price": 400}).status_code == 200
    client.post("/blocks", headers=пассажир["auth"], json={"blocked_user_id": водитель["id"]})

    строка = client.get("/blocks", headers=пассажир["auth"]).json()[0]

    assert строка["name"] == "СписокТорговец", строка


def test_отменённая_поездка_тоже_считается_знакомством(client, user_factory):
    """Сделка не состоялась — но столкнуться в чате они успели."""
    водитель = user_factory("СписокВодитель3", role=UserRole.driver)
    пассажир = user_factory("СписокПассажир3")
    ride_id = _ride(client, водитель, comment="не сложилось")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/cancel", headers=пассажир["auth"])
    client.post("/blocks", headers=пассажир["auth"], json={"blocked_user_id": водитель["id"]})

    строка = client.get("/blocks", headers=пассажир["auth"]).json()[0]

    assert строка["name"] == "СписокВодитель3", (
        f"после отменённой поездки имя скрыто ({строка['name']}), хотя нахамить можно было и по ней"
    )


def test_выдуманный_номер_в_список_не_попадает(client, user_factory):
    """Иначе в базе копятся записи о людях, которых нет, — и список врёт своему хозяину."""
    чужак = user_factory("СписокЧужак2")

    r = client.post("/blocks", headers=чужак["auth"], json={"blocked_user_id": 999_999})

    assert r.status_code == 404, f"заблокирован несуществующий человек: {r.status_code}"
    assert client.get("/blocks", headers=чужак["auth"]).json() == []
