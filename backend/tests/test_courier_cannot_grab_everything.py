"""Один курьер не может забрать все посылки района.

Аудит 2026-08-06, третья волна. Потолки «сколько висит одновременно» стояли на стороне того,
кто СОЗДАЁТ: у водителя на поездки, у пассажира на заявки, у отправителя на посылки. На стороне
того, кто БЕРЁТ, не стояло ничего.

Чем это плохо. Нажал «взять» — посылка ушла из ленты, другие курьеры её больше не видят.
Отправитель уверен, что она едет, и узнаёт правду, только когда истечёт срок. Один человек мог
так «забрать» весь район, ничего никуда не отвезя. У такси это невозможно (занятому водителю
заказы не предлагают), у попутки ограничивают места в машине — у доставки не ограничивало ничто.

Правило файла: посылок на руках у курьера не больше потолка; доставил — место освободилось;
чужие посылки в мой счёт не идут.
"""
import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture
def low_cap():
    """Тестовый потолок 3 вместо 15 — чтобы не плодить пятнадцать посылок ради одной проверки."""
    prev = settings.flood_carrying_parcels_max
    settings.flood_carrying_parcels_max = 3
    yield 3
    settings.flood_carrying_parcels_max = prev


def _parcel(sender_id: int, *, courier_id=None, status="created") -> int:
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, courier_id=courier_id, status=status,
                           from_city="Баймак", to_city="Сибай", size="medium",
                           receiver_name="Гөлнара", receiver_phone="+79170000401",
                           created_at=utcnow())
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def test_courier_stops_at_the_cap(client, user_factory, low_cap):
    sender = user_factory("GrabSender")
    courier = user_factory("GrabCourier", role=UserRole.driver)

    taken = 0
    for _ in range(low_cap + 2):
        pid = _parcel(sender["id"])
        r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
        if r.status_code == 200:
            taken += 1
        else:
            assert r.status_code == 429, f"ожидали мягкий отказ, получили {r.status_code}: {r.text}"
            break
    assert taken == low_cap, f"курьер набрал {taken} посылок при потолке {low_cap}"


def test_the_refusal_speaks_both_languages(client, user_factory, low_cap):
    """Отказ читает человек, а не разработчик: два языка и понятный совет, что делать."""
    sender = user_factory("GrabSender2")
    courier = user_factory("GrabCourier2", role=UserRole.driver)
    for _ in range(low_cap):
        client.post(f"/parcels/{_parcel(sender['id'])}/accept", headers=courier["auth"])

    r = client.post(f"/parcels/{_parcel(sender['id'])}/accept", headers=courier["auth"])
    assert r.status_code == 429, r.text
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"], f"отказ не двуязычный: {detail}"
    assert detail["ru"] != detail["ba"], "башкирский — копия русского"


def test_delivering_frees_a_slot(client, user_factory, low_cap):
    """Потолок не наказывает того, кто реально возит: довёз — бери следующую."""
    sender = user_factory("GrabSender3")
    courier = user_factory("GrabCourier3", role=UserRole.driver)
    ids = []
    for _ in range(low_cap):
        pid = _parcel(sender["id"])
        assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
        ids.append(pid)

    # Одну доставили — прямо в базе, минуя код подтверждения (проверяем потолок, а не вручение).
    with Session(engine) as s:
        p = s.get(ParcelDelivery, ids[0])
        p.status = "delivered"
        s.add(p)
        s.commit()

    assert client.post(f"/parcels/{_parcel(sender['id'])}/accept",
                       headers=courier["auth"]).status_code == 200, "место после доставки не освободилось"


def test_other_couriers_parcels_do_not_count(client, user_factory, low_cap):
    """Считаем «мои на руках», а не «занятые вообще» — иначе чужая работа блокировала бы мою."""
    sender = user_factory("GrabSender4")
    mine = user_factory("GrabCourier4", role=UserRole.driver)
    other = user_factory("GrabCourier5", role=UserRole.driver)
    for _ in range(low_cap):
        client.post(f"/parcels/{_parcel(sender['id'])}/accept", headers=other["auth"])

    assert client.post(f"/parcels/{_parcel(sender['id'])}/accept",
                       headers=mine["auth"]).status_code == 200, "чужие посылки посчитали моими"
