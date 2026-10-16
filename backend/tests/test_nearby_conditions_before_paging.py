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

import json

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
    # «Проверкино» — места с таким именем нет ни в справочнике, ни в других тестах: база общая на
    # весь прогон, и чужие поездки из настоящего города ломали точное сравнение порции и count.
    _публикую(client, обычный, 3, город="Проверкино")
    нужные = _публикую(client, проверенный, 1, город="Проверкино")

    тело = client.get("/rides/near?from_city=Проверкино&limit=2&verified_only=true").json()

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


# ── Фильтр «Откуда» / «Куда» по городам (просьба Александра 16.10.2026) ──
#
# «Мне нужны только поездки из Баймака или из Сибая в любую сторону». Сервер умел один город
# «откуда», а приложение — только готовые пары маршрутов из карусели. Теперь лента принимает
# несколько городов «откуда» и «куда» (`from_cities`, `to_cities`), а `/rides/cities` отдаёт
# города, где поездки сейчас есть, — на обоих языках, без чисел.

def _из(client, водитель, откуда: str, куда: str) -> int:
    when = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(microsecond=0)
    r = client.post("/rides", headers=водитель["auth"], json={
        "from_city": откуда, "to_city": куда, "seats_total": 3, "price": 400,
        "depart_at": when.isoformat(), "comment": f"{откуда}-{куда}",
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_из_двух_городов_в_любую_сторону(client, user_factory):
    """«Нужны только поездки из Баймака или из Сибая в любую сторону» (Александр, 16.10.2026).

    Города — Белебей и Давлеканово: их нет в других тестах, а база общая на весь прогон. С Баймаком
    на Postgres чужие поездки выталкивали наши за порцию — тест падал при верном коде.
    """
    водитель = user_factory("ГородаВодитель", role=UserRole.driver)
    из_белебея = _из(client, водитель, "Белебей", "Уфа")
    из_давлеканово = _из(client, водитель, "Давлеканово", "Магнитогорск")
    из_учалов = _из(client, водитель, "Учалы", "Уфа")

    тело = client.get("/rides/near?from_cities=Белебей,Давлеканово&limit=200").json()
    ids = {x["id"] for x in тело["items"]}

    assert {из_белебея, из_давлеканово} <= ids, f"пропали поездки из выбранных городов: {ids}"
    assert из_учалов not in ids, "поездка из Учалов попала в «из Белебея или Давлеканово»"


def test_куда_несколько_городов_и_вместе_с_откуда(client, user_factory):
    водитель = user_factory("ГородаКуда", role=UserRole.driver)
    в_уфу = _из(client, водитель, "Белорецк", "Уфа")
    в_мелеуз = _из(client, водитель, "Белорецк", "Мелеуз")
    в_учалы = _из(client, водитель, "Белорецк", "Учалы")

    тело = client.get("/rides/near?from_cities=Белорецк&to_cities=Уфа,Мелеуз&limit=50").json()
    ids = {x["id"] for x in тело["items"]}

    assert ids == {в_уфу, в_мелеуз}, f"«откуда Белорецк, куда Уфа или Мелеуз» отдал не то: {ids}"
    assert в_учалы not in ids


def test_башкирское_написание_города_находит_русское(client, user_factory):
    """Волна 92: «Күмертау» и «Кумертау» — один город. Фильтр «Откуда» обязан это помнить.

    Кумертау, а не Баймак: его нет в других тестах (база общая на весь прогон).
    """
    водитель = user_factory("ГородаБаш", role=UserRole.driver)
    рейс = _из(client, водитель, "Кумертау", "Сибай")

    тело = client.get("/rides/near?from_cities=Күмертау&limit=200").json()

    assert рейс in {x["id"] for x in тело["items"]}, "башкирское написание не нашло поездку из Кумертау"


def test_список_городов_с_поездками_на_двух_языках(client, user_factory, monkeypatch):
    # База общая на весь прогон: чужие поездки не должны вытеснять наш город из «топ-12».
    monkeypatch.setattr("app.routers.rides._MAX_FILTER_CITIES", 10_000)
    водитель = user_factory("ГородаСписок", role=UserRole.driver)
    _из(client, водитель, "Баймак", "Уфа")
    _из(client, водитель, "Баймак", "Сибай")

    r = client.get("/rides/cities")
    assert r.status_code == 200, r.text
    тело = r.json()
    откуда = {c["ru"]: c for c in тело["from"]}
    куда = {c["ru"] for c in тело["to"]}

    assert "Баймак" in откуда, f"Баймака нет среди «Откуда»: {list(откуда)}"
    assert откуда["Баймак"]["ba"] == "Баймаҡ", f"нет башкирского написания: {откуда['Баймак']}"
    assert {"Уфа", "Сибай"} <= куда, f"не все «Куда»: {куда}"
    assert "count" not in json.dumps(тело), "витрине городов числа ни к чему — сколько у кого поездок"


def test_список_городов_не_берёт_закрытые_поездки(client, user_factory, monkeypatch):
    # «Закрытово» — места с таким именем нет ни в справочнике, ни в других тестах (база общая на весь
    # прогон), а потолок снят: иначе тест прошёл бы и с ошибкой — город не влез бы в «топ-12».
    monkeypatch.setattr("app.routers.rides._MAX_FILTER_CITIES", 10_000)
    водитель = user_factory("ГородаЗакрыт", role=UserRole.driver)
    рейс = _из(client, водитель, "Закрытово", "Сибай")
    from app.models import Ride, RideStatus
    with Session(engine) as s:
        ride = s.get(Ride, рейс)
        ride.status = RideStatus.cancelled
        s.add(ride)
        s.commit()

    тело = client.get("/rides/cities").json()

    assert "Закрытово" not in {c["ru"] for c in тело["from"]}, "город отменённой поездки висит в «Откуда»"


def test_снятое_подтверждение_убирает_поездку_из_фильтра_для_женщин(client, user_factory):
    """Приёмка PR147, P1: отметка «только женщины» в базе осталась, а подтверждение сняли.

    Карточка такую поездку уже не помечает, основная лента `/rides?women_only=1` её не отдаёт —
    и лента «рядом» не должна: иначе женщина, которой небезопасно с незнакомым мужчиной,
    получает в ответ на «только женщины» непроверенного водителя.
    """
    водительница = user_factory("ГорелоеПодтверждение", role=UserRole.driver)
    with Session(engine) as s:
        u = s.get(User, водительница["id"])
        u.gender = "female"
        s.add(u)
        s.add(DriverProfile(user_id=водительница["id"], gender_verified=True))
        s.commit()
    рейс = _публикую(client, водительница, 1, город="Янаул", women_only=True)
    with Session(engine) as s:                               # модератор снял подтверждение
        from sqlmodel import select as sm_select
        prof = s.exec(sm_select(DriverProfile).where(DriverProfile.user_id == водительница["id"])).first()
        prof.gender_verified = False
        s.add(prof)
        s.commit()

    тело = client.get("/rides/near?from_city=Янаул&limit=50&conditions=women").json()

    assert рейс[0] not in {x["id"] for x in тело["items"]} and тело["count"] == 0, (
        f"поездка водителя без действующего подтверждения попала в «только женщины»: {тело}")


def test_список_городов_прячет_то_же_что_лента(client, user_factory, monkeypatch):
    """Поездка «только для своих» и поездка заблокированного не дают города в шторку.

    Иначе человек выбирал город и получал «поездок нет», а посторонний узнавал, что оттуда
    кто-то едет «для своих». Межгорье и Благовещенск — города, которых нет в других тестах.
    """
    monkeypatch.setattr("app.routers.rides._MAX_FILTER_CITIES", 10_000)
    свой = user_factory("ГородаДляСвоих", role=UserRole.driver)
    r = client.post("/rides", headers=свой["auth"], json={
        "from_city": "Межгорье", "to_city": "Уфа", "seats_total": 3, "price": 400,
        "depart_at": (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(microsecond=0).isoformat(),
        "only_trusted": True,
    })
    assert r.status_code == 200, r.text
    assert r.json().get("only_trusted") is True, "поездка не стала «только для своих» — тест ничего не проверит"
    неприятный = user_factory("ГородаЗаблокирован", role=UserRole.driver)
    _из(client, неприятный, "Благовещенск", "Уфа")
    пассажирка = user_factory("ГородаБлокирует")
    assert client.post("/blocks", headers=пассажирка["auth"],
                       json={"blocked_user_id": неприятный["id"]}).status_code in (200, 201)

    гость = {c["ru"] for c in client.get("/rides/cities").json()["from"]}
    assert "Межгорье" not in гость, "город поездки «только для своих» виден посторонним"
    assert "Благовещенск" in гость, "контроль: обычную поездку гость видит"

    она = {c["ru"] for c in client.get("/rides/cities", headers=пассажирка["auth"]).json()["from"]}
    assert "Благовещенск" not in она, "город заблокированного водителя висит в шторке"


def test_город_с_запятой_не_попадает_в_шторку(client, user_factory, monkeypatch):
    """Фильтр передаёт города через запятую: «Аскарово, ул. Ленина» сервер разрезал бы на два
    города и показал бы лишнее. Такого имени в шторке быть не должно (приёмка PR147, P2)."""
    monkeypatch.setattr("app.routers.rides._MAX_FILTER_CITIES", 10_000)
    водитель = user_factory("ГородСЗапятой", role=UserRole.driver)
    _из(client, водитель, "Аскарово, ул. Ленина", "Уфа")

    откуда = {c["ru"] for c in client.get("/rides/cities").json()["from"]}

    assert not any("," in name for name in откуда), f"имя с запятой в шторке: {откуда}"

