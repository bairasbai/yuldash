"""Условия пассажира применяются ДО разбивки на порции — иначе лента лжёт «поездок нет».

Приёмка волны 1 (16.10.2026), DESIGN023. Лента «Ближайшие поездки» получала с сервера порцию
(«показать ещё» — следующая), а условия («детское кресло», «с животными», «только проверенные»…)
телефон применял уже к этой порции. Подходящие поездки лежали дальше по списку, кнопка «Показать
ещё» при включённом условии пряталась — и мама с ребёнком видела «поездок нет», хотя машина
с креслом была, просто двенадцатой.

Теперь `/rides/near` принимает условия сам (`conditions`, `verified_only`) и считает `count`
уже по ним: первая порция — подходящие поездки, `count` — сколько их всего.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import DriverProfile, User, UserRole
from app.timeutil import utcnow


def _публикую(client, водитель, n: int, город: str = "Баймак", **поля) -> list[int]:
    ids = []
    for i in range(n):
        when = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1, minutes=i)).replace(microsecond=0)
        r = client.post("/rides", headers=водитель["auth"], json={
            "from_city": город, "to_city": "Уфа", "seats_total": 3, "price": 300 + i,
            "depart_at": when.isoformat(), **поля,
        })
        assert r.status_code == 200, r.text
        ids.append(r.json()["id"])
    return ids


def test_кресло_дальше_первой_порции_находится(client, user_factory):
    водитель = user_factory("ПорцияВодитель", role=UserRole.driver)
    _публикую(client, водитель, 12)                                 # ранние — без кресла
    с_креслом = _публикую(client, водитель, 2, child_seat=True)    # поздние — с креслом

    r = client.get("/rides/near?from_city=Баймак&limit=5&conditions=child")

    assert r.status_code == 200, r.text
    тело = r.json()
    ids = [x["id"] for x in тело["items"]]
    assert set(ids) == set(с_креслом), (
        f"первая порция не нашла поездки с креслом ({ids}) — мама увидит «поездок нет»")
    assert тело["count"] == 2, f"count должен считать только подходящие: {тело['count']}"


def test_без_условий_лента_как_раньше(client, user_factory):
    водитель = user_factory("ПорцияКонтроль", role=UserRole.driver)
    _публикую(client, водитель, 3, город="Сибай")
    _публикую(client, водитель, 1, город="Сибай", pets_allowed=True)

    тело = client.get("/rides/near?from_city=Сибай&limit=50").json()

    assert тело["count"] >= 4 and len(тело["items"]) >= 4, тело["count"]


def test_несколько_условий_сразу_и_некурящий(client, user_factory):
    водитель = user_factory("ПорцияНесколько", role=UserRole.driver)
    _публикую(client, водитель, 2, город="Учалы", baggage=True, smoking=True)
    нужные = _публикую(client, водитель, 1, город="Учалы", baggage=True, smoking=False)

    тело = client.get("/rides/near?from_city=Учалы&limit=50&conditions=baggage,nosmoke").json()

    assert [x["id"] for x in тело["items"]] == нужные, тело["items"]


def test_только_проверенные_водители(client, user_factory):
    обычный = user_factory("ПорцияНепроверен", role=UserRole.driver)
    проверенный = user_factory("ПорцияПроверен", role=UserRole.driver)
    with Session(engine) as s:                       # фабрика создаёт всех проверенными — явно снимаем
        u = s.get(User, обычный["id"])
        u.verified = False
        s.add(u)
        s.commit()
    _публикую(client, обычный, 3, город="Мелеуз")
    нужные = _публикую(client, проверенный, 1, город="Мелеуз")

    тело = client.get("/rides/near?from_city=Мелеуз&limit=2&verified_only=true").json()

    assert [x["id"] for x in тело["items"]] == нужные and тело["count"] == 1, тело


def test_женщинам_подходят_только_проверенные_женщины_за_рулём(client, user_factory):
    """Как на телефоне: «только для женщин» или проверенная модератором женщина за рулём.

    Самодекларации мало (`is_verified_female_driver`): поставить себе «женский» может кто угодно,
    поэтому водительница без подтверждения в этот фильтр не попадает.
    """
    мужчина = user_factory("ПорцияМужчина", role=UserRole.driver)
    женщина = user_factory("ПорцияЖенщина", role=UserRole.driver)          # женщина, пол не подтверждён
    водительница = user_factory("ПорцияВодительница", role=UserRole.driver)  # подтверждена модератором
    with Session(engine) as s:
        for uid, подтверждена in ((женщина["id"], False), (водительница["id"], True)):
            u = s.get(User, uid)
            u.gender = "female"
            u.verified = True
            s.add(u)
            s.add(DriverProfile(user_id=uid, gender_verified=подтверждена))
        s.commit()
    _публикую(client, мужчина, 3, город="Туймазы")
    _публикую(client, женщина, 1, город="Туймазы")
    женская = _публикую(client, водительница, 1, город="Туймазы", women_only=True)
    обычная_у_неё = _публикую(client, водительница, 1, город="Туймазы")

    тело = client.get("/rides/near?from_city=Туймазы&limit=50&conditions=women").json()
    ids = {x["id"] for x in тело["items"]}

    assert ids == set(женская) | set(обычная_у_неё) and тело["count"] == 2, (
        f"для женщин нужны ровно поездки подтверждённой водительницы: {ids}")


def test_незнакомое_условие_не_ломает_ленту(client, user_factory):
    """Новая версия приложения может прислать условие, которого сервер ещё не знает."""
    водитель = user_factory("ПорцияНовое", role=UserRole.driver)
    _публикую(client, водитель, 2, город="Стерлитамак")

    r = client.get("/rides/near?from_city=Стерлитамак&limit=50&conditions=hover,child")

    assert r.status_code == 200, r.text
