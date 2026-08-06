"""Открытое поле проверяется во ВСЕХ сценариях, а не только в попутке.

Аудит 2026-08-06, продолжение охоты. Проверка текста стояла на комментарии к заявке,
на отклике и на отзыве о приложении. И не стояла ровно там, где на кону деньги:
в объявлении водителя (его видит весь район), в комментарии к заказу такси и в описании
посылки — самом просторном поле приложения, 2000 знаков, которое читает каждый курьер.
А комиссия берётся именно в такси и доставке: «звони мне на +7…» в этих полях — не
по-соседски обменялись номерами, а увод сделки мимо приложения, то есть мимо SOS, чека
и разбора спора.

Правило файла: у каждого открытого поля есть проверка. Она НЕ режет текст и НЕ роняет
запрос — только помечает, решает по-прежнему человек. Поэтому сторожим сам факт вызова:
поле, добавленное завтра без проверки, уронит этот файл.
"""
from datetime import timedelta

import app.antifraud as af
import app.routers.instant as instant_router
import app.routers.parcels as parcels_router
import app.routers.rides as rides_router
from app.models import UserRole
from app.timeutil import utcnow


def _watch(monkeypatch, module):
    """Считаем, какие тексты роутер отдал на проверку."""
    seen = []
    real = af.moderate_open_text

    def spy(text, user_id, **kw):
        seen.append(text or "")
        return real(text, user_id, **kw)

    monkeypatch.setattr(module, "moderate_open_text", spy)
    return seen


def _depart():
    """Завтрашний выезд. Наивное время сервер читает как УФИМСКОЕ и переводит в UTC (−5ч),
    поэтому «сейчас» уехало бы в прошлое и публикация упала бы на другой проверке."""
    return (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat()


def test_driver_ad_comment_is_checked(client, user_factory, monkeypatch):
    """Объявление водителя — такое же открытое поле, как заявка пассажира, только с другой стороны."""
    drv = user_factory("ModRide", role=UserRole.driver)
    seen = _watch(monkeypatch, rides_router)

    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": _depart(),
        "seats_total": 3, "price": 300, "comment": "еду мимо, пишите",
    })
    assert r.status_code in (200, 201), r.text
    assert "еду мимо, пишите" in seen, "комментарий объявления ушёл в ленту без проверки"


def test_editing_the_ad_is_checked_too(client, user_factory, monkeypatch):
    """Иначе правило обходится в два тапа: опубликовал чистое, потом вписал что угодно."""
    drv = user_factory("ModRideEdit", role=UserRole.driver)
    created = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "depart_at": _depart(),
        "seats_total": 3, "price": 500, "comment": "",
    })
    assert created.status_code in (200, 201), created.text
    ride_id = created.json()["id"]

    seen = _watch(monkeypatch, rides_router)
    r = client.post(f"/rides/{ride_id}/edit", headers=drv["auth"],
                    json={"comment": "звоните на другой номер"})
    assert r.status_code == 200, r.text
    assert "звоните на другой номер" in seen, "правку объявления никто не смотрит"


def test_taxi_order_comment_is_checked(client, user_factory, monkeypatch):
    """Комментарий к заказу читает каждый водитель, кому ушёл оффер. Тут есть комиссия."""
    pax = user_factory("ModTaxi")
    seen = _watch(monkeypatch, instant_router)

    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": 52.59, "from_lng": 58.31, "to_lat": 52.72, "to_lng": 58.66,
        "from_text": "Баймак", "to_text": "Сибай",
        "comment": "у синих ворот, звони +7 917 000-00-00",
    })
    assert r.status_code == 200, r.text
    # Статус заказа тут неважен (свободных водителей в тестовой базе нет — заказ истечёт).
    # Важно одно: текст ушёл на проверку до того, как его увидели водители.
    assert seen, "комментарий заказа ушёл водителям без проверки"
    assert "звони" in seen[0]


def test_parcel_description_is_checked(client, user_factory, monkeypatch):
    """Описание посылки — 2000 знаков в открытой ленте курьеров."""
    sender = user_factory("ModParcel")
    seen = _watch(monkeypatch, parcels_router)

    r = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "receiver_name": "Айгуль", "receiver_phone": "+79170000009",
        "rules_accepted": True, "description": "документы, договоримся напрямую",
    })
    assert r.status_code == 200, r.text
    assert seen, "описание посылки ушло курьерам без проверки"
    assert "договоримся напрямую" in seen[0]


def test_moderation_never_blocks_the_person(client, user_factory, monkeypatch):
    """Главное правило: метка — сигнал админу, а не наказание. Текст сохраняется как есть."""
    drv = user_factory("ModSoft", role=UserRole.driver)
    text = "еду в 8, звони 89170000000"

    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Сибай", "to_city": "Баймак", "depart_at": _depart(),
        "seats_total": 2, "price": 200, "comment": text,
    })
    assert r.status_code in (200, 201), f"проверка уронила публикацию — так нельзя: {r.text}"
    assert r.json()["comment"] == text, "текст порезали, а человека не предупредили"


def test_the_check_itself_still_sees_a_phone_and_abuse():
    """Контроль самой проверки — иначе тесты выше зелены при сломанных регулярках."""
    assert af.moderate_text("позвони на 89170000000") == af.MESSAGE_FLAG_CONTACT
    assert af.moderate_text("позвони на 89170000000", check_contact=False) == ""
    assert af.moderate_text("обычный текст про поездку") == ""
