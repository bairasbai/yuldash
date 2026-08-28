"""У каждого тарифа своё время подачи — иначе витрина обещает то, чего нет.

История. Экран заказа показывает четыре тарифа в ряд: Эконом, Комфорт, Бизнес, Минивэн.
Человек выбирает между ними по двум числам — сколько стоит и через сколько приедет. Второе
число сервер знал только одно на всех: «ближайшая машина за N минут», без разбора класса.

Поставить эту цифру на все четыре карточки — значит пообещать подачу за тот класс, машин
которого рядом может не быть вовсе: бизнес-седан один на район, а карточка обещает те же
две минуты, что и эконом. Человек выбирает Бизнес, ждёт, и заказ истекает «рядом никого».

Правило: минуты класса считаются по машинам ЭТОГО класса, и класс берётся тот же, по
которому работает подбор (доступные машине ∩ включённые водителем). Нет таких машин рядом —
поле пустое, и клиент молчит, а не показывает чужое число.
"""
from __future__ import annotations

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import DriverProfile, UserRole

# Баймак — точка А. Дальняя точка примерно в 10 км от неё: разница в минутах видна.
ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)
FAR10 = (52.681, 58.317)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _classes(user_id: int, available: str, enabled: str) -> None:
    """Проставляет водителю классы машины напрямую — заявка и модерация тут ни при чём."""
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
        assert p is not None, "у водителя нет профиля — тест проверял бы не то"
        p.car_classes_available = available
        p.car_classes_enabled = enabled
        s.add(p)
        s.commit()


def _online_at(client, user_factory, coord, *, available: str, enabled: str, name: str):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    _classes(d["id"], available, enabled)
    r = client.post("/instant/presence", headers=d["auth"],
                    json={"lat": coord[0], "lng": coord[1]})
    assert r.status_code == 200, r.text
    return d


def _estimate(client, pax):
    r = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert r.status_code == 200, r.text
    return {o["category"]: o for o in r.json()["options"]}


def test_эконом_рядом_бизнес_далеко_минуты_разные(client, user_factory, fake_redis):
    """Ровно та ситуация, ради которой всё делалось: рядом эконом, бизнес — за городом."""
    _online_at(client, user_factory, ORIG, available="economy", enabled="economy", name="Рядом")
    _online_at(client, user_factory, FAR10, available="economy,business",
               enabled="business", name="Далеко")
    pax = user_factory("PaxEta")

    opts = _estimate(client, pax)
    близко = opts["standard"]["pickup_eta_min"]
    далеко = opts["business"]["pickup_eta_min"]
    assert близко is not None and далеко is not None, opts
    assert близко < далеко, f"эконом рядом должен подъехать раньше бизнеса за городом: {opts}"


def test_класса_рядом_нет_минут_не_выдумываем(client, user_factory, fake_redis):
    """Пустое поле честнее чужого числа: иначе человек ждёт машину, которой нет."""
    _online_at(client, user_factory, ORIG, available="economy", enabled="economy", name="ТолькоЭконом")
    pax = user_factory("PaxNoMinivan")

    opts = _estimate(client, pax)
    assert opts["standard"]["pickup_eta_min"] is not None
    assert opts["minivan"]["pickup_eta_min"] is None, "минивэна рядом нет — минут быть не должно"
    assert opts["business"]["pickup_eta_min"] is None, "бизнеса рядом нет — минут быть не должно"


def test_выключенный_класс_минут_не_даёт(client, user_factory, fake_redis):
    """Водитель может возить Комфорт, но выключил его у себя — офферы по нему не придут.

    Значит и обещать подачу по этому классу нельзя: витрина обязана показывать ту же
    картину мира, что и подбор, иначе человек ждёт машину, которой заказ не отправят.
    """
    _online_at(client, user_factory, ORIG, available="economy,comfort",
               enabled="economy", name="КомфортВыключен")
    pax = user_factory("PaxOff")

    opts = _estimate(client, pax)
    assert opts["standard"]["pickup_eta_min"] is not None
    assert opts["comfort"]["pickup_eta_min"] is None, (
        "класс выключен водителем — подбор его не берёт, витрина обещать не может"
    )


def test_без_редиса_поле_просто_пустое(client, user_factory):
    """Redis лёг — экран работает дальше, только без минут. Ронять заказ из-за подсказки нельзя."""
    isv._redis_override = None
    pax = user_factory("PaxNoRedis")
    opts = _estimate(client, pax)
    assert all(o["pickup_eta_min"] is None for o in opts.values()), opts


def test_остановок_не_больше_трёх(client, user_factory):
    """Каждая остановка удлиняет поездку и цену, а водитель на четвёртой начинает отказываться.

    Лимит держит сервер, а не только экран: правило про деньги и логистику не может жить
    в кнопке — её у стороннего клиента просто нет.
    """
    pax = user_factory("PaxStops")
    точки = [{"lat": 52.60 + i / 100, "lng": 58.32, "text": f"Остановка {i}"} for i in range(4)]
    r = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "waypoints": точки,
    })
    assert r.status_code == 422, f"четвёртая остановка должна быть отклонена: {r.text}"

    r3 = client.post("/instant/estimate", headers=pax["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1],
        "to_lat": DEST[0], "to_lng": DEST[1],
        "waypoints": точки[:3],
    })
    assert r3.status_code == 200, f"три остановки должны считаться: {r3.text}"
    assert r3.json()["waypoints_count"] == 3


def test_на_карте_у_машины_есть_класс(client, user_factory, fake_redis):
    """Метка на карте должна выглядеть как та машина, которая приедет.

    Раньше все точки рисовались одной жёлтой машинкой: человек выбирал Бизнес, смотрел на
    карту и не понимал, где эти бизнес-машины. Класс кузова — это «какая машина», а не «кто
    за рулём»: точка остаётся анонимной, ни id, ни имени, ни телефона в ответе нет.
    """
    d = _online_at(client, user_factory, ORIG, available="economy,comfort,business",
                   enabled="business", name="КартаБизнес")
    # Ищем профиль ПО ЭТОМУ водителю: база у тестов общая, и поиск «любой с бизнесом»
    # находил водителя соседнего теста — проверка шла бы не про наш случай.
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        p.car_class = "business"
        s.add(p)
        s.commit()
    pax = user_factory("PaxMap")

    r = client.get("/instant/nearby-drivers", headers=pax["auth"],
                   params={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 200, r.text
    машины = r.json()["drivers"]
    assert машины, "водитель на линии рядом — точка обязана быть"
    точка = машины[0]
    assert точка["category"] == "business", точка
    # Анонимность: наружу уходят только координаты, минуты и класс кузова.
    assert set(точка) <= {"lat", "lng", "eta_min", "category"}, точка
