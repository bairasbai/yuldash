"""SOS обязан сказать дежурному, С КЕМ человек уехал.

Аудит 2026-08-06, продолжение охоты. Прошлая волна научила SOS говорить, ГДЕ человек.
Осталась вторая половина того же вопроса: с кем. Из такси контекст уходил с самого начала
(маршрут заказа и вторая сторона), из попутки — нет: поле брони в запросе существовало,
приложение его никогда не заполняло, а сервер полученный номер только проверял и выбрасывал.
Дежурный видел «Марат, +7…, категория other» — и всё, хотя человек в этот момент едет
в чужой машине с незнакомым водителем.

Правило файла: пришёл номер брони — в сообщении дежурному есть маршрут, имя водителя и
машина. Не пришёл — сигнал всё равно уходит (жизнь дороже полноты карточки).
"""
from datetime import timedelta

from sqlmodel import Session

import app.routers.safety as safety
from app.db import engine
from app.models import Booking, BookingStatus, DriverProfile, Ride, RideStatus, UserRole
from app.timeutil import utcnow


def _capture_tg(monkeypatch):
    """Перехватываем сообщение дежурному: важен именно ТЕКСТ, который он прочтёт ночью."""
    msgs = []
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda m: msgs.append(m))
    return msgs


def _live_trip(driver_id: int, passenger_id: int, *, car: bool = True):
    """Живая попутка: пассажир едет прямо сейчас — момент, когда и жмут SOS."""
    with Session(engine) as s:
        if car:
            s.add(DriverProfile(user_id=driver_id, car_model="Лада Гранта",
                                car_color="белая", car_plate="А123БВ102"))
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow() - timedelta(minutes=20),
                    seats_total=3, seats_left=2, price=300, status=RideStatus.active)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1,
                    price=300, status=BookingStatus.onboard)
        s.add(b)
        s.commit()
        s.refresh(b)
        return ride, b


def test_family_gets_the_car_but_not_the_driver_personally(client, user_factory, monkeypatch):
    """Черта, по которой делим данные водителя (решение 2026-08-06).

    Родным — то, что ВИДНО СНАРУЖИ МАШИНЫ: цвет, модель, госномер. Они самые быстрые
    помощники, и первое, что спросит полиция, — номер машины. Имя и телефон водителя им
    действовать не помогают, поэтому туда не идут: это уже данные из анкеты, они только
    дежурному.
    """
    drv = user_factory("SosCarDrv", role=UserRole.driver)
    pax = user_factory("SosCarPax")
    _ride, b = _live_trip(drv["id"], pax["id"])
    client.post("/trusted-contacts", headers=pax["auth"],
                json={"name": "Мама", "relation": "мама", "phone": "+79170000301"})
    sent = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sent.append((list(phones), text)))

    r = client.post("/sos", headers=pax["auth"],
                    json={"category": "other", "note": "", "booking_id": b.id,
                          "lat": 52.59, "lng": 58.31})
    assert r.status_code == 200, r.text

    to_family = [t for phones, t in sent if "+79170000301" in phones]
    assert to_family, "близким SMS не ушло"
    text = to_family[0]
    assert "А123БВ102" in text, f"нет госномера — родным некого искать на дороге: {text}"
    assert "Лада Гранта" in text and "белая" in text, f"нет описания машины: {text}"
    assert "SosCarDrv" not in text, f"имя водителя утекло родным пассажира: {text}"
    assert "tg-test" not in text, f"телефон водителя утёк родным пассажира: {text}"


def test_family_sms_without_a_trip_has_no_car(client, user_factory, monkeypatch):
    """SOS с улицы: машины нет — и выдумывать её нечего."""
    u = user_factory("SosCarAlone")
    client.post("/trusted-contacts", headers=u["auth"],
                json={"name": "Брат", "relation": "брат", "phone": "+79170000302"})
    sent = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sent.append((list(phones), text)))

    assert client.post("/sos", headers=u["auth"], json={"category": "medical"}).status_code == 200
    text = [t for phones, t in sent if "+79170000302" in phones][0]
    assert "Машина:" not in text, f"приписали машину там, где поездки нет: {text}"


def test_sos_from_a_rideshare_names_the_driver_and_the_car(client, user_factory, monkeypatch):
    drv = user_factory("SosWhoDrv", role=UserRole.driver)
    pax = user_factory("SosWhoPax")
    ride, b = _live_trip(drv["id"], pax["id"])
    msgs = _capture_tg(monkeypatch)

    r = client.post("/sos", headers=pax["auth"],
                    json={"category": "other", "note": "страшно", "booking_id": b.id})
    assert r.status_code == 200, r.text

    assert msgs, "дежурному не ушло вообще ничего"
    text = msgs[0]
    assert "Баймак" in text and "Сибай" in text, f"нет маршрута попутки: {text}"
    assert "SosWhoDrv" in text, f"нет имени водителя — не понять, с кем человек: {text}"
    assert "А123БВ102" in text, f"нет машины — некого искать на дороге: {text}"


def test_sos_without_a_trip_still_goes_out(client, user_factory, monkeypatch):
    """Контроль: SOS с улицы (без поездки) работает как раньше — сигнал важнее контекста."""
    u = user_factory("SosWhoAlone")
    msgs = _capture_tg(monkeypatch)

    r = client.post("/sos", headers=u["auth"], json={"category": "medical", "note": "плохо"})
    assert r.status_code == 200, r.text
    assert msgs and "SosWhoAlone" in msgs[0]
    assert "Попутка:" not in msgs[0], "приписали поездку там, где её нет"


def test_sos_context_is_only_for_my_own_trip(client, user_factory, monkeypatch):
    """Чужой номер брони не должен вытаскивать чужого водителя и его телефон."""
    drv = user_factory("SosWhoDrv2", role=UserRole.driver)
    pax = user_factory("SosWhoPax2")
    stranger = user_factory("SosWhoStranger")
    _ride, b = _live_trip(drv["id"], pax["id"])
    _capture_tg(monkeypatch)

    r = client.post("/sos", headers=stranger["auth"],
                    json={"category": "other", "note": "", "booking_id": b.id})
    assert r.status_code in (403, 404), f"чужая бронь отдалась постороннему: {r.status_code}"


def test_driver_can_press_sos_from_the_same_trip(client, user_factory, monkeypatch):
    """Безопасность двусторонняя: водителю тоже бывает нужен SOS в своей же поездке."""
    drv = user_factory("SosWhoDrv3", role=UserRole.driver)
    pax = user_factory("SosWhoPax3")
    _ride, b = _live_trip(drv["id"], pax["id"], car=False)
    msgs = _capture_tg(monkeypatch)

    r = client.post("/sos", headers=drv["auth"],
                    json={"category": "other", "note": "", "booking_id": b.id})
    assert r.status_code == 200, r.text
    assert "Баймак" in msgs[0], f"водителю контекст не собрался: {msgs[0]}"
