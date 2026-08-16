"""Боковые двери такси и доставки: пуш, ночной робот и старая доставка.

Зона такси и доставки — единственная, которую рой аудиторов не успел проверить: его агент
упал с сетевой ошибкой. Прогнали отдельно, и три находки оказались одной и той же ошибкой,
повторённой трижды: у события есть главный выход (экран, карточка, список) и боковой (пуш,
SMS, ночной робот). Правило прикрутили к главному и забыли про боковой.

**1. Пуш выдавал адрес, который карточка прячет.** Гульнара ночью вызывает такси домой.
Карточка заказа честно режет адрес до улицы, пока водитель не согласился везти: отказаться
можно бесплатно и сколько угодно раз, иначе адреса ночных пассажирок собирались бы отказами.
А пуш «Новый заказ» писал «Гагарина, 5к2» целиком — да ещё поверх погасшего экрана, где его
читает любой, кто стоит рядом с телефоном на стоянке (аудит 2026-08-08, волна 128).

**2. Телефон и адрес получателя жили в телефоне курьера вечно.** Айгуль из Темясово Юлдашем
не пользуется — её имя, телефон и адрес «дом 7, синие ворота» вписал отправитель. Через месяц
после доставки курьер открывает «Что я везу», листает до завершённых — и всё это по-прежнему
там, в один тап, у человека, который к ней домой уже приезжал. В такси правило есть давно:
связь гаснет через 48 часов после поездки. В доставке его не было вовсе.

**3. Ночной робот закрывал зависший заказ молча для близких.** Зухра едет ночью Сибай — Уфа
и делится поездкой с мамой: маме приходит SMS «села в такси». У водителя садится телефон,
«Завершена» никто не нажимает, и через шесть часов робот закрывает заказ. Пассажиру
и водителю мы пишем, маме — нет. Для неё последняя новость так и остаётся «села в такси»,
семь часов назад. Функция «поделиться поездкой» существует ровно ради этого случая.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.instant_service as isv
import app.services as svc
from app.db import engine
from app.models import InstantOrder, ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture
def пуши(monkeypatch):
    поймано: list[tuple[str, str, dict]] = []

    def ловим(session, uid, title, body, data=None, data_only=False):
        поймано.append((title, body, data or {}))

    monkeypatch.setattr(svc, "send_push", ловим)
    monkeypatch.setattr(isv, "send_push", ловим)
    return поймано


@pytest.fixture
def смс(monkeypatch):
    поймано: list[tuple[str, str]] = []
    monkeypatch.setattr(svc, "send_text", lambda ph, t: поймано.append((ph, t)))
    monkeypatch.setattr(isv, "send_text", lambda ph, t: поймано.append((ph, t)))
    return поймано


def test_пуш_не_выдаёт_номер_дома(client, user_factory, пуши):
    """Главное: то, что карточка прячет, пуш прятать обязан тоже."""
    гульнара = user_factory("БоковаяГульнара")
    oid = client.post("/instant/orders", headers=гульнара["auth"], json={
        "from_text": "Сибай, Ленина 1", "from_lat": 52.9, "from_lng": 58.66,
        "to_text": "Сибай, ул. Гагарина, 5к2", "to_lat": 52.92, "to_lng": 58.70,
    }).json()["id"]
    водитель = user_factory("БоковаяВодитель", role=UserRole.driver)
    пуши.clear()

    with Session(engine) as s:
        isv._push_offer(s, s.get(InstantOrder, oid), водитель["id"])

    оффер = [p for p in пуши if p[0] == "Новый заказ"]
    assert оффер, f"оффер не ушёл: {пуши}"
    title, body, data = оффер[-1]
    assert "5к2" not in body, (
        f"номер дома в тексте пуша: {body}. Карточка его прячет, а уведомление всплывает "
        "поверх погасшего экрана — его читает любой, кто рядом с телефоном"
    )
    assert "5к2" not in str(data.get("to", "")), f"номер дома в служебном поле: {data.get('to')}"
    assert "Гагарина" in body, f"улицу оставить надо — по ней водитель решает, брать ли заказ: {body}"


def test_карточка_и_пуш_говорят_одно_и_то_же(client, user_factory, пуши):
    """Сторож на будущее: две двери должны показывать один и тот же адрес."""
    гульнара = user_factory("БоковаяГульнара2")
    oid = client.post("/instant/orders", headers=гульнара["auth"], json={
        "from_text": "Сибай, Ленина 1", "from_lat": 52.9, "from_lng": 58.66,
        "to_text": "Сибай, ул. Гагарина, 5к2", "to_lat": 52.92, "to_lng": 58.70,
    }).json()["id"]
    водитель = user_factory("БоковаяВодитель2", role=UserRole.driver)
    пуши.clear()

    with Session(engine) as s:
        заказ = s.get(InstantOrder, oid)
        isv._push_offer(s, заказ, водитель["id"])
        # Карточку до принятия смотреть нельзя (403 — «нет доступа к заказу»), поэтому
        # сравниваем с той же обрезалкой, которой пользуется карточка.
        как_в_карточке = isv.street_only(заказ.to_text)

    из_пуша = [p for p in пуши if p[0] == "Новый заказ"][-1][2].get("to", "")
    assert из_пуша == как_в_карточке, (
        f"карточка показала бы «{как_в_карточке}», а пуш отдал «{из_пуша}»"
    )


@pytest.fixture
def доставка(client, user_factory):
    """Курьер довёз посылку до Айгуль и вручил."""
    отправитель = user_factory("БоковойОтправитель")
    курьер = user_factory("БоковойКурьер", role=UserRole.driver)
    pid = client.post("/parcels", headers=отправитель["auth"], json={
        "from_city": "Сибай", "to_city": "Темясово", "size": "small", "rules_accepted": True,
        "receiver_name": "Айгуль", "receiver_phone": "+79990000128",
        "to_address": "дом 7, синие ворота", "price": 300,
    }).json()["id"]
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.courier_id = курьер["id"]
        p.status = "delivered"
        p.delivered_at = utcnow()
        s.add(p)
        s.commit()
    return курьер, pid


def _карточка_курьера(pid: int) -> dict:
    from app.routers.parcels import _parcel_for_courier

    with Session(engine) as s:
        return _parcel_for_courier(s.get(ParcelDelivery, pid), s)


def test_сразу_после_вручения_связь_ещё_открыта(client, доставка):
    """Обратная сторона: «а куда вы это оставили» случается в первые часы."""
    _, pid = доставка

    карточка = _карточка_курьера(pid)

    assert карточка["receiver_phone"], "телефон погас сразу после вручения — недовоз не обсудить"
    assert карточка.get("to_address"), "адрес погас сразу — курьер не объяснит, где оставил"


def test_через_месяц_телефон_и_адрес_погасли(client, доставка):
    _, pid = доставка
    with Session(engine) as s:                       # прошёл месяц
        p = s.get(ParcelDelivery, pid)
        p.delivered_at = utcnow() - timedelta(days=30)
        s.add(p)
        s.commit()

    карточка = _карточка_курьера(pid)

    assert not карточка["receiver_phone"], (
        "телефон получательницы лежит в чужом телефоне месяц спустя — а она Юлдашем "
        "и не пользуется, её данные вписал отправитель"
    )
    assert not карточка.get("to_address"), "точный адрес «дом 7, синие ворота» остался у курьера"
    assert not карточка["sender_phone"], "телефон отправителя тоже должен погаснуть"


def test_пока_везёт_видно_всё(client, user_factory):
    """И главное: у живой доставки связь обязана работать."""
    отправитель = user_factory("БоковойОтправитель2")
    курьер = user_factory("БоковойКурьер2", role=UserRole.driver)
    pid = client.post("/parcels", headers=отправитель["auth"], json={
        "from_city": "Сибай", "to_city": "Темясово", "size": "small", "rules_accepted": True,
        "receiver_name": "Айгуль", "receiver_phone": "+79990000129",
        "to_address": "дом 7", "price": 300,
    }).json()["id"]
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.courier_id = курьер["id"]
        p.status = "in_transit"
        s.add(p)
        s.commit()

    карточка = _карточка_курьера(pid)

    assert карточка["receiver_phone"] and карточка.get("to_address"), карточка


def test_робот_сообщает_близким_о_закрытии(client, user_factory, смс):
    """Зависший заказ закрывают — мама должна узнать, а не остаться с «села в такси»."""
    зухра = user_factory("БоковаяЗухра")
    водитель = user_factory("БоковойВодитель3", role=UserRole.driver)
    мама = client.post("/trusted-contacts", headers=зухра["auth"],
                       json={"name": "Мама", "phone": "+79990000130"}).json()
    oid = client.post("/instant/orders", headers=зухра["auth"], json={
        "from_text": "Сибай", "from_lat": 52.9, "from_lng": 58.66,
        "to_text": "Уфа", "to_lat": 54.7, "to_lng": 55.9,
    }).json()["id"]
    assert client.post(f"/instant/orders/{oid}/share", headers=зухра["auth"],
                       json={"contact_id": мама["id"]}).status_code == 200
    with Session(engine) as s:                       # человек в машине, связь пропала 7 часов назад
        o = s.get(InstantOrder, oid)
        o.driver_id = водитель["id"]
        o.status = isv.S.onboard
        o.created_at = utcnow() - timedelta(hours=7)
        o.onboard_at = utcnow() - timedelta(hours=7)
        s.add(o)
        s.commit()
    смс.clear()

    from app import taxi_worker
    with Session(engine) as s:
        taxi_worker.run_once(s)

    тексты = " ".join(t for _, t in смс)
    assert смс and "отмен" in тексты.lower(), (
        f"робот закрыл заказ, а маме не написал: {смс}. Для неё последняя новость так "
        "и осталась «села в такси» — семь часов назад"
    )


def test_обе_двери_к_финалу_зовут_одну_рассылку():
    """Сторож: обычная отмена и аварийное закрытие не должны разойтись."""
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[1] / "app"
    робот = (app_dir / "taxi_worker.py").read_text(encoding="utf-8")
    сервис = (app_dir / "instant_service.py").read_text(encoding="utf-8")
    assert "_notify_order_shares" in робот, (
        "ночной робот снова закрывает заказ молча для близких — а это ровно тот случай, "
        "ради которого человек и делился поездкой"
    )
    assert "_notify_order_shares" in сервис, "обычная отмена перестала оповещать близких"
