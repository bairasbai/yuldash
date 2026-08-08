# -*- coding: utf-8 -*-
"""«Тот ли это человек и та ли машина» — две проверки перед посадкой (разбор конкурентов 2026-08-07).

Обе взяты из живых историй, а не придуманы.

① Госномер в попутке. Пост в r/hrvatska на 1836 голосов: девушка забронировала место, а на
   встречу приехала ДРУГАЯ машина, за рулём ДРУГОЙ человек («поведёт вот он»), задние двери на
   детском замке. Сверить машину с объявлением было нечем. У нас в такси госномер уже отдаётся,
   в попутке — не отдавался, хотя риск там выше: машину пассажир выбирает сам.
   Приватность: номер и цвет — только участникам брони и только после подтверждения.

② «Женщина за рулём» подтверждает модератор. У Uber копятся жалобы «заказала женщину-водителя —
   приехал муж». Раньше водитель сам ставил себе пол, и бейдж/фильтр верили ему на слово.
   Теперь витрина показывает только подтверждённое по фото прав.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, User, UserRole


def _car(user_id: int, **fields) -> None:
    """Заполнить карточку машины водителя напрямую (онбординг тут не важен)."""
    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
        if dp is None:
            dp = DriverProfile(user_id=user_id)
        for k, v in fields.items():
            setattr(dp, k, v)
        s.add(dp)
        s.commit()


def _publish(client, drv):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 3, "price": 300})
    assert r.status_code == 200, r.text
    return r.json()


# ----------------------------- ① госномер в попутке -----------------------------

def test_plate_hidden_until_booking_confirmed(client, user_factory):
    """До подтверждения брони номер и цвет не отдаём — это ПДн, а бронь ещё не состоялась."""
    drv = user_factory("PlateDrv", role=UserRole.driver)
    pax = user_factory("PlatePax")
    _car(drv["id"], car_make="Lada", car_model="Vesta", car_color="белая", car_plate="А123ВС102")
    ride = _publish(client, drv)
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    d = client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()
    assert d["contact_unlocked"] is False
    assert d["driver_plate"] == ""
    assert d["driver_car_color"] == ""


def test_plate_visible_after_confirm(client, user_factory):
    """Бронь подтверждена → пассажир видит номер и цвет и может сверить машину у подъезда."""
    drv = user_factory("PlateDrv2", role=UserRole.driver)
    pax = user_factory("PlatePax2")
    _car(drv["id"], car_make="Lada", car_model="Vesta", car_color="белая", car_plate="А123ВС102")
    ride = _publish(client, drv)
    booking = client.post("/bookings", headers=pax["auth"],
                          json={"ride_id": ride["id"], "seats": 1}).json()
    assert client.post(f"/bookings/{booking['id']}/confirm", headers=drv["auth"]).status_code == 200
    d = client.get(f"/bookings/{booking['id']}/details", headers=pax["auth"]).json()
    assert d["contact_unlocked"] is True
    assert d["driver_plate"] == "А123ВС102"
    assert d["driver_car_color"] == "белая"


def test_plate_not_in_public_ride_listing(client, user_factory):
    """В открытой выдаче номера нет: объявление видят все, машину знать посторонним незачем."""
    drv = user_factory("PlateDrv3", role=UserRole.driver)
    _car(drv["id"], car_make="Lada", car_model="Granta", car_color="белая", car_plate="Х777УУ102")
    _publish(client, drv)
    rides = client.get("/rides", params={"from_city": "Баймак"}).json()
    assert rides, "поездка должна быть в выдаче"
    for r in rides:
        assert "Х777УУ102" not in str(r)


# ------------------- ② «женщина за рулём» подтверждает модератор -------------------

def test_self_declared_female_does_not_show_the_badge(client, user_factory):
    """Водитель отметил «женщина» сам → в витрине бейджа НЕТ, пока модератор не сверил."""
    drv = user_factory("GenderDrv", role=UserRole.driver)
    assert client.post("/driver/gender", headers=drv["auth"],
                       json={"gender": "female"}).status_code == 200
    _publish(client, drv)
    mine = [r for r in client.get("/rides", params={"from_city": "Баймак"}).json()
            if r["driver_id"] == drv["id"]]
    assert mine and mine[0]["driver_is_woman"] is False


def test_moderator_confirms_and_badge_appears(client, user_factory):
    """Модератор сверил с фото прав → бейдж включился."""
    drv = user_factory("GenderDrv2", role=UserRole.driver)
    admin = user_factory("GenderAdmin", role=UserRole.admin)
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "female"})
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                    json={"approve": True, "gender_verified": True})
    assert r.status_code == 200, r.text
    assert r.json()["gender_verified"] is True
    _publish(client, drv)
    mine = [x for x in client.get("/rides", params={"from_city": "Баймак"}).json()
            if x["driver_id"] == drv["id"]]
    assert mine and mine[0]["driver_is_woman"] is True


def test_changing_gender_drops_the_confirmation(client, user_factory):
    """Заявил другое → прежнее подтверждение слетает. Иначе подтверждение «женщины» осталось бы
    висеть на аккаунте, который потом переписали, — дыра ровно того же размера."""
    drv = user_factory("GenderDrv3", role=UserRole.driver)
    admin = user_factory("GenderAdmin3", role=UserRole.admin)
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "female"})
    client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                json={"approve": True, "gender_verified": True})
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "male"})
    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        assert dp.gender_verified is False


def test_empty_gender_cannot_be_confirmed(client, user_factory):
    """Подтверждать нечего, если водитель ничего не заявил — «подтверждённая пустота» бессмысленна."""
    drv = user_factory("GenderDrv4", role=UserRole.driver)
    admin = user_factory("GenderAdmin4", role=UserRole.admin)
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                    json={"approve": True, "gender_verified": True})
    assert r.status_code == 200, r.text
    assert r.json()["gender_verified"] is False


def test_rejecting_docs_removes_the_confirmation(client, user_factory):
    """Документы отклонили → подтверждать по ним больше нечего, бейдж снимается."""
    drv = user_factory("GenderDrv5", role=UserRole.driver)
    admin = user_factory("GenderAdmin5", role=UserRole.admin)
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "female"})
    client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                json={"approve": True, "gender_verified": True})
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                    json={"approve": False})
    assert r.json()["gender_verified"] is False


def test_old_client_without_the_field_still_works(client, user_factory):
    """Старая админка шлёт только approve — она не должна ни падать, ни менять подтверждение."""
    drv = user_factory("GenderDrv6", role=UserRole.driver)
    admin = user_factory("GenderAdmin6", role=UserRole.admin)
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "female"})
    client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                json={"approve": True, "gender_verified": True})
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                    json={"approve": True})
    assert r.status_code == 200, r.text
    assert r.json()["gender_verified"] is True     # не тронули


def test_moderation_queue_shows_what_driver_claimed(client, user_factory):
    """Модератору видно, что именно заявил водитель, — иначе подтверждать вслепую."""
    drv = user_factory("GenderDrv7", role=UserRole.driver)
    admin = user_factory("GenderAdmin7", role=UserRole.admin)
    client.post("/driver/gender", headers=drv["auth"], json={"gender": "female"})
    _car(drv["id"], docs_status="pending")
    queue = client.get("/admin/drivers/pending", headers=admin["auth"]).json()
    row = next((x for x in queue if x["user_id"] == drv["id"]), None)
    assert row is not None
    assert row["gender_claimed"] == "female"
    assert row["gender_verified"] is False
