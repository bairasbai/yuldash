"""Такси: «только женщина за рулём» — тот же выбор, что в попутке.

Аудит 2026-08-06 нашёл асимметрию: в попутках фильтр «только женщины» был с самого начала,
а в такси его не было — хотя ночью в машину к незнакомому человеку садятся именно здесь.

Главное правило файла: фильтр ЖЁСТКИЙ. Подставить мужчину, «раз женщин рядом нет», —
значит обмануть в том единственном, ради чего галочку и ставили. Никого нет — заказ
честно не подбирается, и человек сам решает, искать ли шире.
"""
from app.instant_service import eligible
from app.models import DriverProfile, InstantOrder, User, UserRole
from sqlmodel import Session, select

from app.db import engine


def _driver(user_factory, gender: str, name="WomenOnlyDrv"):
    d = user_factory(name, role=UserRole.driver)
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        if p is None:
            p = DriverProfile(user_id=d["id"])
        p.online = True
        s.add(p)
        # Кандидатов фильтруем по verified — иначе отсеются раньше нашей проверки.
        u = s.get(User, d["id"])
        u.verified = True
        # Пол живёт на ЧЕЛОВЕКЕ, а не на профиле водителя: он нужен и пассажиру, потому что
        # «только женщины» проверяется у обеих сторон (аудит 2026-08-08).
        u.gender = gender
        s.add(u)
        s.commit()
    return d


def _order(passenger_id: int, *, women_only: bool) -> InstantOrder:
    return InstantOrder(
        passenger_id=passenger_id, women_only=women_only,
        from_lat=53.0, from_lng=58.0, to_lat=53.1, to_lng=58.1,
    )


def test_women_only_order_skips_male_driver(client, user_factory):
    pax = user_factory("WomenOnlyPax")
    male = _driver(user_factory, "male", "WOMale")
    with Session(engine) as s:
        picked = eligible(s, [male["id"]], _order(pax["id"], women_only=True))
    assert picked == [], "заказ «только женщина» ушёл бы мужчине-водителю"


def test_women_only_order_keeps_female_driver(client, user_factory):
    """Контроль: тот же водитель-женщина проходит — значит отсеивает именно пол, а не всё подряд."""
    pax = user_factory("WomenOnlyPax2")
    female = _driver(user_factory, "female", "WOFemale")
    with Session(engine) as s:
        picked = eligible(s, [female["id"]], _order(pax["id"], women_only=True))
    assert picked == [female["id"]], "женщина-водитель не прошла собственный фильтр"


def test_driver_without_stated_gender_is_not_assumed_female(client, user_factory):
    """Пол — opt-in. Не указан — не выдаём за женщину: обещание должно быть проверяемым."""
    pax = user_factory("WomenOnlyPax3")
    unknown = _driver(user_factory, "", "WOUnknown")
    with Session(engine) as s:
        picked = eligible(s, [unknown["id"]], _order(pax["id"], women_only=True))
    assert picked == [], "водителя без указанного пола выдали за женщину"


def test_ordinary_order_is_not_narrowed(client, user_factory):
    """Обычный заказ фильтр не сужает — иначе мы молча урезали бы всем выбор машин."""
    pax = user_factory("WomenOnlyPax4")
    male = _driver(user_factory, "male", "WOMale2")
    with Session(engine) as s:
        picked = eligible(s, [male["id"]], _order(pax["id"], women_only=False))
    assert picked == [male["id"]], "обычный заказ потерял водителя-мужчину"


def test_flag_survives_order_creation_and_is_visible(client, user_factory, monkeypatch):
    """Галочка доезжает от экрана до заказа и возвращается обратно: экран должен
    уметь объяснить, почему машину не нашли."""
    pax = user_factory("WomenOnlyCreate")
    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": 53.0, "from_lng": 58.0, "to_lat": 53.1, "to_lng": 58.1,
        "from_text": "Сибай", "to_text": "Уфа", "women_only": True,
    })
    assert r.status_code in (200, 201), r.text
    assert r.json().get("women_only") is True, "выбор «только женщина» потерялся по дороге"


def test_default_is_off(client, user_factory):
    """По умолчанию фильтра нет: сужать круг машин без просьбы человека нельзя."""
    pax = user_factory("WomenOnlyDefault")
    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": 53.0, "from_lng": 58.0, "to_lat": 53.1, "to_lng": 58.1,
        "from_text": "Сибай", "to_text": "Уфа",
    })
    assert r.status_code in (200, 201), r.text
    assert r.json().get("women_only") is False
