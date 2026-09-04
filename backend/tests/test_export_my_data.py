"""«Скачать мои данные» — файл, который человек откроет и прочитает.

Закон требует выдать человеку его сведения по запросу; формат не назван. JSON выдал бы
их формально: получатель — водитель или пенсионерка, и файл со скобками ответом для них
не является. Поэтому выгрузка — обычный текст.

Главное, что здесь проверяется, — в файле нет чужого. В переписке участвует второй
человек, его слова не наши, чтобы их отдавать; чужие поездки и оценки тоже.
"""

from datetime import timedelta

from sqlmodel import Session

from app.db import engine
from app.models import Booking, DriverProfile, Message, Ride
from app.timeutil import utcnow


def _ride(s, driver_id, frm="Баймаҡ", to="Сибай"):
    r = Ride(driver_id=driver_id, from_city=frm, to_city=to, price=300,
             seats_total=3, seats_left=3, depart_at=utcnow() + timedelta(hours=3))
    s.add(r)
    s.commit()
    s.refresh(r)
    return r


def test_export_is_readable_text_with_a_filename(client, user_factory):
    """Отдаём готовый текст и имя файла — приложению остаётся его сохранить."""
    u = user_factory("ExportNewbie")

    r = client.get("/me/export", headers=u["auth"])
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["filename"].endswith(".txt")
    assert "МОИ ДАННЫЕ В ЮЛДАШЕ" in d["text"]
    assert u["name"] in d["text"] if u.get("name") else True


def test_profile_and_rides_are_inside(client, user_factory):
    """Свои поездки в файле есть — иначе выгрузка бессмысленна."""
    u = user_factory("ExportDriver")
    with Session(engine) as s:
        _ride(s, u["id"], "Учалы", "Өфө")
        dp = DriverProfile(user_id=u["id"], car_make="Lada", car_model="Vesta",
                           car_plate="А123ВС102", docs_status="verified")
        s.add(dp)
        s.commit()

    t = client.get("/me/export", headers=u["auth"]).json()["text"]
    assert "Учалы" in t and "Өфө" in t
    assert "А123ВС102" in t


def test_only_my_own_messages_are_exported(client, user_factory):
    """Слова попутчика в мою выгрузку не попадают: они не мои, чтобы их отдавать."""
    me = user_factory("ExportMe")
    other = user_factory("ExportOther")
    with Session(engine) as s:
        ride = _ride(s, other["id"])
        b = Booking(ride_id=ride.id, passenger_id=me["id"], seats=1, price=300)
        s.add(b)
        s.commit()
        s.refresh(b)
        s.add(Message(booking_id=b.id, sender_id=me["id"], text="моё сообщение"))
        s.add(Message(booking_id=b.id, sender_id=other["id"], text="чужое сообщение"))
        s.commit()

    t = client.get("/me/export", headers=me["auth"]).json()["text"]
    assert "моё сообщение" in t
    assert "чужое сообщение" not in t


def test_other_peoples_rides_are_not_exported(client, user_factory):
    """Чужая поездка в мой файл не попадает."""
    me = user_factory("ExportMine")
    other = user_factory("ExportYours")
    with Session(engine) as s:
        _ride(s, other["id"], "Стәрлетамаҡ", "Ишембай")
        s.commit()

    t = client.get("/me/export", headers=me["auth"]).json()["text"]
    assert "Стәрлетамаҡ" not in t


def test_bashkir_export_is_really_bashkir(client, user_factory):
    """Башкирский — отдельный текст, а не русский с приклеенным переводом."""
    u = user_factory("ExportBa")

    ru = client.get("/me/export", headers=u["auth"]).json()["text"]
    ba = client.get("/me/export?lang=ba", headers=u["auth"]).json()["text"]
    assert "ЮЛДАШТА МИНЕҢ МӘҒЛҮМӘТТӘРЕМ" in ba
    assert "МОИ ДАННЫЕ В ЮЛДАШЕ" not in ba
    assert ru != ba


def test_what_is_not_in_the_file_is_stated(client, user_factory):
    """Файл сам говорит, чего в нём нет: геолокации и данных карты."""
    u = user_factory("ExportAbsent")

    t = client.get("/me/export", headers=u["auth"]).json()["text"]
    assert "ЧЕГО В ФАЙЛЕ НЕТ" in t
    assert "геолокации" in t


def test_export_requires_login(client):
    """Без входа выгрузки нет — иначе её мог бы забрать кто угодно."""
    assert client.get("/me/export").status_code in (401, 403)
