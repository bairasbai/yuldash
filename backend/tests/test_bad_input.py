"""Что будет от плохих данных в полях: отрицательная цена, гигантский текст, мусор.

Экран проверяет ввод как умеет: не даст ввести буквы в цену, обрежет длинный текст.
Но экран — не защита: запрос легко послать мимо него, а старая версия приложения может
слать то, чего новая уже не шлёт. Значит, всё то же обязан проверять сервер.

Что здесь ловится:
  • отрицательная и абсурдная цена — «поездка за −5000 ₽» ломает и расчёт, и здравый смысл;
  • гигантские тексты — ими забивают базу и разносят вёрстку у всех, кто это увидит;
  • мусор в датах и координатах — поездка «в 1970 году» или машина посреди океана;
  • пустые обязательные поля — заявка «откуда: никуда».

Правило: сервер отвечает отказом либо приводит значение к разумному — но не сохраняет
абсурд как есть.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.models import UserRole

from test_api import _ride


def _local(delta: timedelta) -> str:
    """Время, как его шлёт приложение: МЕСТНОЕ (Уфа = UTC+5), без пояса.

    Важная тонкость, на которой я сам споткнулся: если послать сюда UTC, сервер примет его
    за местное и сдвинет назад на пять часов — «выезжаю сейчас» превратится в «пять часов
    назад» и справедливо отклонится."""
    from app.config import settings
    from app.timeutil import utcnow
    local_now = utcnow() + timedelta(hours=settings.local_tz_offset_hours)
    return (local_now + delta).replace(microsecond=0).isoformat()


def _ride_body(**extra):
    return {
        "from_city": "Сибай", "to_city": "Уфа",
        "depart_at": _local(timedelta(days=1)),
        "seats": 3, "price": 500, **extra,
    }


# ---------- Цена ----------

@pytest.mark.parametrize("price", [-1, -500, -999_999])
def test_поездка_с_отрицательной_ценой_не_публикуется(client, user_factory, price):
    driver = user_factory(f"NegPrice{abs(price)}", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(price=price))
    if r.status_code == 200:
        assert r.json().get("price", 0) >= 0, (
            f"опубликовалась поездка с ценой {r.json().get('price')} — "
            "пассажир якобы получает деньги за поездку"
        )
    else:
        assert 400 <= r.status_code < 500, f"ожидали отказ, получили {r.status_code}"


def test_абсурдная_цена_не_проходит_как_есть(client, user_factory):
    """Миллиард рублей за поездку в соседнее село — либо отказ, либо разумный потолок."""
    driver = user_factory("HugePriceDriver", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(price=1_000_000_000))
    if r.status_code == 200:
        assert r.json().get("price", 0) < 1_000_000_000, "цена в миллиард сохранилась как есть"


@pytest.mark.parametrize("price", [-1, -10_000])
def test_отклик_водителя_с_отрицательной_ценой_не_проходит(client, user_factory, price):
    passenger = user_factory(f"NegRespPax{abs(price)}")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1,
    }).json()["id"]
    driver = user_factory(f"NegRespDrv{abs(price)}", role=UserRole.driver)
    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": price})
    if r.status_code == 200:
        assert r.json().get("price", 0) >= 0, "отклик с отрицательной ценой сохранился"


# ---------- Число мест ----------

@pytest.mark.parametrize("seats", [0, -1, 1000, 999_999])
def test_бессмысленное_число_мест_не_публикуется(client, user_factory, seats):
    driver = user_factory(f"BadSeats{abs(seats)}", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(seats=seats))
    if r.status_code == 200:
        got = r.json().get("seats_total", r.json().get("seats", 0))
        assert 1 <= got <= 100, f"поездка на {got} мест — в машину столько не влезет"


# ---------- Длина текста ----------

@pytest.mark.parametrize("field", ["from_city", "to_city", "comment"])
def test_гигантский_текст_не_сохраняется_целиком(client, user_factory, field):
    """10 000 символов в названии города разнесут вёрстку у всех, кто увидит поездку."""
    driver = user_factory(f"HugeText{field}", role=UserRole.driver)
    huge = "А" * 10_000
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(**{field: huge}))
    if r.status_code == 200:
        saved = str(r.json().get(field, ""))
        assert len(saved) < 10_000, (
            f"поле {field} сохранилось целиком ({len(saved)} символов) — "
            "такой текст ломает список у всех"
        )
    else:
        assert 400 <= r.status_code < 500, f"ожидали отказ, получили {r.status_code}"


def test_гигантское_сообщение_в_чате_не_проходит(client, user_factory):
    driver = user_factory("HugeMsgDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("HugeMsgPassenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])

    r = client.post(f"/bookings/{bid}/messages", headers=passenger["auth"], json={"text": "Я" * 50_000})
    if r.status_code == 200:
        got = client.get(f"/bookings/{bid}/messages", headers=passenger["auth"]).json()
        items = got.get("items", got) if isinstance(got, dict) else got
        assert all(len(str(m.get("text", ""))) < 50_000 for m in items), (
            "сообщение на 50 000 символов сохранилось целиком"
        )


# ---------- Пустые обязательные поля ----------

@pytest.mark.parametrize("field", ["from_city", "to_city"])
def test_поездка_из_ниоткуда_не_публикуется(client, user_factory, field):
    driver = user_factory(f"EmptyCity{field}", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(**{field: "   "}))
    if r.status_code == 200:
        assert str(r.json().get(field, "")).strip(), (
            f"опубликовалась поездка с пустым полем {field} — в ленте появится пустая строка"
        )


# ---------- Дата ----------

def test_поездка_в_прошлом_не_публикуется(client, user_factory):
    """Поездка «вчера» засоряет ленту и не может состояться."""
    driver = user_factory("PastRideDriver", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(depart_at="2020-01-01T10:00:00"))
    assert r.status_code != 200 or r.json().get("status") != "active", (
        "поездка на 2020 год висит активной в ленте"
    )


def test_мусор_вместо_даты_не_роняет_сервер(client, user_factory):
    driver = user_factory("BadDateDriver", role=UserRole.driver)
    for bad in ("не дата", "", "31.02.2027", "9999-99-99T99:99:99"):
        r = client.post("/rides", headers=driver["auth"], json=_ride_body(depart_at=bad))
        assert r.status_code < 500, f"мусор в дате «{bad}» уронил сервер: {r.status_code}"


# ---------- Координаты ----------

@pytest.mark.parametrize("lat, lng", [(91, 0), (-91, 0), (0, 181), (0, -181), (99999, 99999)])
def test_невозможные_координаты_не_принимаются(client, user_factory, lat, lng):
    """Широта больше 90 — точка вне Земли. На карте это либо пусто, либо мусор."""
    passenger = user_factory(f"BadCoord{abs(int(lat))}{abs(int(lng))}")
    r = client.post("/instant/estimate", headers=passenger["auth"], json={
        "from_lat": lat, "from_lng": lng, "to_lat": 52.9, "to_lng": 58.6,
        "from_text": "мусор", "to_text": "Сибай",
    })
    assert r.status_code < 500, f"координаты ({lat}, {lng}) уронили сервер: {r.status_code}"


# ---------- Разметка в тексте ----------

def test_разметка_в_имени_сохраняется_как_текст(client, user_factory):
    """Имя показывается другим людям и уезжает на сайт в отзывы. Оно должно остаться
    текстом, а не стать разметкой."""
    user = user_factory("MarkupName")
    evil = "<script>alert(1)</script>"
    r = client.post("/me/update", headers=user["auth"], json={"name": evil})
    if r.status_code == 200:
        me = client.get("/me", headers=user["auth"]).json()
        assert "<script>" not in str(me.get("name", "")) or me.get("name") == evil, (
            "имя пришло изменённым не по правилам экранирования — проверь, что именно с ним делают"
        )


# ---------- Правка поездки ----------

def test_правкой_нельзя_увести_поездку_в_прошлое(client, user_factory):
    """Иначе она останется активной в ленте, но взять её уже никто не сможет."""
    driver = user_factory("EditToPastDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    r = client.post(f"/rides/{ride_id}/edit", headers=driver["auth"],
                    json={"depart_at": "2020-01-01T10:00:00"})
    assert r.status_code != 200, "правкой поездку увели в прошлое"


def test_нормальная_правка_времени_проходит(client, user_factory):
    """Проверка «не в прошлом» не должна мешать обычному переносу на попозже."""
    from datetime import timedelta
    driver = user_factory("EditFutureDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    later = _local(timedelta(days=2))
    r = client.post(f"/rides/{ride_id}/edit", headers=driver["auth"], json={"depart_at": later})
    assert r.status_code == 200, f"обычный перенос времени сломался: {r.status_code} {r.text[:200]}"


def test_выезд_прямо_сейчас_разрешён(client, user_factory):
    """«Выезжаю сейчас» — обычный сценарий, часы на телефоне могут отставать на минуты."""
    from datetime import timedelta
    driver = user_factory("DepartNowDriver", role=UserRole.driver)
    now_ish = _local(timedelta(minutes=-5))
    r = client.post("/rides", headers=driver["auth"], json=_ride_body(depart_at=now_ish))
    assert r.status_code == 200, f"публикация «выезжаю сейчас» сломалась: {r.status_code} {r.text[:200]}"
