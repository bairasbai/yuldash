"""Кнопка «застрял на трассе» должна быть во всех трёх сценариях, а не в двух.

Зимой трасса Сибай–Уфа — это четыре часа. Машина встала, связь плохая, на улице минус
двадцать. Для этого есть кнопка мягче красного SOS: координаты уходят близким, событие —
в ленту админа.

У попутки она была сразу. Для такси её добавили отдельно (аудит 2026-07-26 — тогда же
написали, что «застрявшему в такси идти было некуда»). А курьеру не добавили: он едет
по той же трассе, зимой, и везёт чужую вещь.

Здесь проверяется, что кнопка есть во всех трёх сценариях и что она доступна только
участникам. Отдельно — что отправитель узнаёт: его посылка не движется, и сказать об этом
должны мы, а не получатель через неделю.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Notification, UserRole

from test_api import _ride

_WHERE = {"lat": 52.7, "lng": 58.4, "note": "заглох на подъёме"}


def _notes_count(user_id: int) -> int:
    with Session(engine) as s:
        return len(list(s.exec(select(Notification).where(
            Notification.user_id == user_id)).all()))


@pytest.fixture
def courier_on(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    yield


def _parcel_in_transit(client, user_factory, tag):
    sender = user_factory(f"{tag}Sender")
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009941", "rules_accepted": True,
    }).json()["id"]
    courier = user_factory(f"{tag}Courier", role=UserRole.driver)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    return sender, courier, pid


def test_контроль_в_попутке_кнопка_есть(client, user_factory):
    """Без контроля проверка про доставку ничего не докажет: надо знать, что механика жива."""
    driver = user_factory("RoadRideDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    pax = user_factory("RoadRidePax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])

    r = client.post(f"/bookings/{bid}/stuck", headers=driver["auth"], json=_WHERE)
    assert r.status_code == 200, f"«застрял» в попутке не работает: {r.status_code} {r.text[:200]}"


def test_курьер_может_позвать_на_помощь(client, user_factory, courier_on):
    """Главная проверка: у курьера на зимней трассе должна быть та же кнопка."""
    sender, courier, pid = _parcel_in_transit(client, user_factory, "RoadParcel")
    r = client.post(f"/parcels/{pid}/stuck", headers=courier["auth"], json=_WHERE)
    assert r.status_code == 200, (
        f"курьер не может позвать на помощь по доставке: {r.status_code} {r.text[:200]}"
    )


def test_отправитель_узнаёт_что_курьер_застрял(client, user_factory, courier_on):
    """Посылка не движется. Сказать об этом должны мы, а не получатель через неделю."""
    sender, courier, pid = _parcel_in_transit(client, user_factory, "RoadNotify")
    before = _notes_count(sender["id"])
    assert client.post(f"/parcels/{pid}/stuck", headers=courier["auth"],
                       json=_WHERE).status_code == 200
    assert _notes_count(sender["id"]) > before, (
        "курьер застрял, а отправитель не узнал — он продолжает ждать доставку как обычно"
    )


def test_посторонний_не_зовёт_помощь_по_чужой_доставке(client, user_factory, courier_on):
    """Иначе кто угодно поднимает тревогу по чужой посылке и засоряет ленту сигналов."""
    sender, courier, pid = _parcel_in_transit(client, user_factory, "RoadForeign")
    outsider = user_factory("RoadOutsider")
    r = client.post(f"/parcels/{pid}/stuck", headers=outsider["auth"], json=_WHERE)
    assert r.status_code in (403, 404), (
        f"посторонний поднял тревогу по чужой доставке: {r.status_code} {r.text[:200]}"
    )


def test_отправитель_тоже_может_нажать(client, user_factory, courier_on):
    """Отправитель — участник доставки: если он видит, что что-то не так, кнопка нужна и ему."""
    sender, courier, pid = _parcel_in_transit(client, user_factory, "RoadSender")
    r = client.post(f"/parcels/{pid}/stuck", headers=sender["auth"], json=_WHERE)
    assert r.status_code == 200, (
        f"отправитель не может позвать на помощь по своей доставке: {r.status_code} {r.text[:200]}"
    )


def test_в_событии_видно_о_какой_доставке_речь(client, user_factory, courier_on):
    """Админ должен понимать, что случилось, не переспрашивая: номер и маршрут — в тексте."""
    from app.models import SosEvent

    sender, courier, pid = _parcel_in_transit(client, user_factory, "RoadContext")
    assert client.post(f"/parcels/{pid}/stuck", headers=courier["auth"],
                       json=_WHERE).status_code == 200
    with Session(engine) as s:
        events = list(s.exec(select(SosEvent).where(SosEvent.user_id == courier["id"])).all())
    assert events, "событие «застрял» не записалось"
    note = events[-1].note or ""
    assert f"#{pid}" in note and "Баймак" in note, (
        f"в событии не видно, о какой доставке речь: {note[:200]}"
    )


# ---------- Сумма отмены известна ДО решения ----------
# У такси сумма отмены приходит клиенту заранее (cancel_fee_now_kop). У доставки её не было:
# диалог честно предупреждал «будет компенсация», но саму цифру показывал уже ПОСЛЕ отмены —
# человек соглашался на деньги вслепую (аудит 2026-08-06).

def test_отправитель_видит_сумму_отмены_заранее(client, user_factory, courier_on):
    sender, courier, pid = _parcel_in_transit(client, user_factory, "FeePreview")
    mine = client.get("/parcels/mine", headers=sender["auth"])
    assert mine.status_code == 200, mine.text
    row = next((x for x in mine.json() if x.get("id") == pid), None)
    assert row is not None, "своей посылки нет в списке"
    assert row.get("cancel_fee_preview_kop", 0) > 0, (
        "курьер уже везёт, а сумма отмены человеку не показана — он решает вслепую"
    )


def test_пока_курьера_нет_отмена_бесплатна_и_это_видно(client, user_factory, courier_on):
    """Обратная сторона: пугать суммой там, где её не будет, — тоже враньё."""
    sender = user_factory("FeeFreeSender")
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009951", "rules_accepted": True,
    }).json()["id"]
    row = next(x for x in client.get("/parcels/mine", headers=sender["auth"]).json()
               if x["id"] == pid)
    assert row.get("cancel_fee_preview_kop", -1) == 0, (
        f"посылку ещё никто не взял, а отмена показана платной: {row.get('cancel_fee_preview_kop')}"
    )
