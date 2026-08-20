"""Ночью сервер жил по другому дню — и это возвращало на линию просроченную страховку.

Юлдаш живёт по Уфе (UTC+5). Время в базе мировое, а «сегодня» для человека — местное, и это
правило заведено давно: ночная задача снимает таксиста с линии, когда у него кончился полис,
считая день по-башкирски.

Дверь «обновить документы» считала по-мировому (аудит 2026-08-08, волна 144). С полуночи
до пяти утра по Уфе два «сегодня» расходятся на день. Значит водитель, у которого полис кончился
в местную полночь, открывал профиль, жал «Сохранить» с той же самой старой датой — и сервер
возвращал его на линию.

Это ровно те пять часов ночной смены, ради которых проверка документов и делалась. Случись
авария в три часа ночи — страховки нет ни у пассажира, ни у встречной машины.

---

**Вторая находка того же дня: телефон водителя в попутке был виден вечно.**

У такси окно есть с прошлого аудита — 48 часов после поездки, с прямым доводом: «водитель
вечно видел имя и номер пассажирки, которую вёз год назад». У попутки, самого старого сценария
сервиса, окна не было вовсе: поездка 400 дней назад, а номер открыт как в день выезда.

В райцентре, где все друг друга знают, это не строчка в базе: один раз проехал — номер человека
остался у тебя навсегда.

Срок взят тот же, что у чата после поездки: два разных окна для связи по одной поездке однажды
разъедутся. «Забыл вещь» по-прежнему продлевает связь — пока идёт поиск, номер нужен обоим.
"""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Ride, TaxiApplication, UserRole
from app.routers import taxi as taxi_mod
from app.timeutil import local_date, utcnow

from test_api import _ride

APP = Path(__file__).resolve().parents[1] / "app"


def test_ночью_сервер_считает_день_одинаково_везде():
    """Главное: «сегодня» должно быть одно на весь сервер, иначе правила расходятся.

    Берём момент, когда в Уфе уже 15 августа, а по мировому времени ещё 14-е.
    """
    ночь = datetime(2030, 8, 14, 21, 0, tzinfo=timezone.utc)   # 15 августа 02:00 по Уфе

    местный = local_date(ночь)
    мировой = ночь.date()

    assert местный != мировой, "проверять нечего — момент выбран неудачно"
    assert местный == date(2030, 8, 15), местный


def test_просроченный_полис_ночью_не_возвращает_на_линию(client, user_factory, monkeypatch):
    """Водитель не должен возить людей без страховки — даже пять часов до рассвета."""
    водитель = user_factory("НочьТаксист", role=UserRole.driver)
    ночь = datetime(2030, 8, 14, 21, 0, tzinfo=timezone.utc)   # 15 августа 02:00 по Уфе
    вчера = date(2030, 8, 14)                                  # полис кончился «вчера» по Уфе

    monkeypatch.setattr(taxi_mod, "utcnow", lambda: ночь)
    with Session(engine) as s:
        app_row = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
        app_row.osago_until = вчера
        app_row.docs_expired = True                            # ночная задача уже сняла с линии
        s.add(app_row)
        s.commit()

    r = client.post("/taxi/documents", headers=водитель["auth"],
                    json={"osago_until": вчера.isoformat()})

    with Session(engine) as s:
        снова = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
    assert снова.docs_expired is True, (
        f"просроченный полис принят ночью (ответ {r.status_code}) и водитель снова на линии: "
        "в местных сутках этот полис уже недействителен, а сервер посчитал день по-мировому"
    )


def test_свежий_полис_ночью_принимается(client, user_factory, monkeypatch):
    """Обратная сторона: честному водителю нельзя мешать выйти на смену."""
    водитель = user_factory("НочьТаксист2", role=UserRole.driver)
    ночь = datetime(2030, 8, 14, 21, 0, tzinfo=timezone.utc)
    monkeypatch.setattr(taxi_mod, "utcnow", lambda: ночь)
    with Session(engine) as s:
        app_row = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
        app_row.osago_until = date(2030, 8, 14)
        app_row.permit_until = date(2031, 1, 1)
        app_row.inspection_until = date(2031, 1, 1)
        app_row.docs_expired = True
        s.add(app_row)
        s.commit()

    client.post("/taxi/documents", headers=водитель["auth"],
                json={"osago_until": "2031-06-01"})

    with Session(engine) as s:
        снова = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
    assert снова.docs_expired is False, (
        "водитель принёс действующий полис, а его не пустили на линию — он потеряет смену"
    )


# Строка кода, где день берут по мировому времени. Ищем ПРИЗНАК, а не написание: раньше сторож
# знал ровно одну форму — `utcnow().date()` — и спокойно пропускал `approved_at.date()`,
# `now.date()`, `reviewed.date()`. Три такие строки прожили под ним до волны 155 и тихо раздавали
# промо запуска тем, кто опоздал на ночь. Сторож, который ищет знакомое написание, стережёт
# написание, а не поведение.
_ДЕНЬ_ОТ_ВЫЗОВА = re.compile(r"\)\.date\(\)")                            # utcnow().date()
_ДЕНЬ_ОТ_ИМЕНИ = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\.date\(\)")     # approved_at.date()


def _мировой_день(ln: str) -> bool:
    """Строка берёт календарный день, не проходя через местное время?"""
    код = ln.split("#", 1)[0]
    if ".date()" not in код:
        return False
    if "local_date" in код or "local_now" in код:
        return False                                  # уже переведено в местное
    if _ДЕНЬ_ОТ_ВЫЗОВА.search(код):
        return True
    return any("local" not in имя for имя in _ДЕНЬ_ОТ_ИМЕНИ.findall(код))


def test_день_считается_по_уфе_во_всём_коде():
    """Сторож на класс: одно «сегодня» на весь сервер.

    Дыра была не в логике, а в том, что рядом жили две линейки времени. Пока обе существуют,
    следующая правка снова возьмёт не ту — и расхождение вернётся в другом месте.
    """
    нарушители = []
    for файл in list(APP.glob("*.py")) + list((APP / "routers").glob("*.py")):
        if файл.name in ("timeutil.py", "workday.py"):
            continue                                  # здесь местный день и определяют
        for i, ln in enumerate(файл.read_text(encoding="utf-8").splitlines(), 1):
            if _мировой_день(ln):
                нарушители.append(f"{файл.name}:{i}")

    assert not нарушители, (
        f"день берётся по мировому времени: {нарушители}. Для человека в Уфе это другой день "
        "с полуночи до пяти утра — правила ночью разъезжаются. Нужно local_date(utcnow())"
    )


def test_сторож_дня_узнаёт_дыру_в_любом_написании():
    """Проверка самого сторожа: он обязан ловить признак, а не знакомую строчку.

    Прошлая версия знала одну форму записи и пропускала три остальные — именно поэтому дыра
    прожила под ней несколько волн.
    """
    дыры = [
        "    today = utcnow().date()",
        "    if approved_at.date() > promo_until:",
        "    return now.date() <= until",
        "    day = (utcnow() + timedelta(days=1)).date()",
    ]
    честные = [
        "    today = local_date(utcnow())",
        "    day = local_now(now).date()",
        "    # раньше тут стояло utcnow().date() — местный день считаем иначе",
        "    осталось = (срок - today).days",
    ]

    пропущены = [ln for ln in дыры if not _мировой_день(ln)]
    ложные = [ln for ln in честные if _мировой_день(ln)]

    assert not пропущены, f"сторож не увидел мировой день в строках: {пропущены}"
    assert not ложные, f"сторож ругается на честный местный день: {ложные}"


def test_тесты_тоже_считают_день_по_уфе():
    """Тест, который берёт «сегодня» по мировому времени, зелёный утром и красный вечером.

    Это хуже, чем просто красный: мигающему файлу перестают верить целиком, вместе со всеми
    настоящими его проверками. Поймано на сроках документов таксиста (волна 154): «200 дней
    до истечения» после 19:00 UTC превращалось в 199.
    """
    свои = Path(__file__).name
    нарушители = []
    for файл in sorted(Path(__file__).parent.glob("test_*.py")):
        if файл.name == свои:
            continue                                  # здесь дыру описывают словами
        for i, ln in enumerate(файл.read_text(encoding="utf-8").splitlines(), 1):
            код = ln.split("#", 1)[0]
            if "utcnow().date()" in код and "timedelta" not in код:
                нарушители.append(f"{файл.name}:{i}")

    assert not нарушители, (
        f"тест берёт «сегодня» по мировому времени: {нарушители}. С 19:00 UTC (полночь в Уфе) "
        "он живёт во вчерашнем дне — прогон краснеет вечером и зеленеет утром. "
        "Нужно local_date(utcnow())"
    )


@pytest.fixture
def давняя_поездка(client, user_factory):
    """Женщина один раз проехала с этим водителем — больше года назад."""
    водитель = user_factory("ПопуткаВодитель", role=UserRole.driver)
    пассажирка = user_factory("ПопуткаПассажирка")
    ride_id = _ride(client, водитель, comment="прошлым летом")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(days=400)
        s.add(b)
        s.add(r)
        s.commit()
    return водитель, пассажирка, bid


def test_телефон_не_остаётся_открытым_навсегда(client, давняя_поездка):
    """Главное: один раз проехал — не значит, что номер человека твой навсегда."""
    _, пассажирка, bid = давняя_поездка

    r = client.get(f"/bookings/{bid}/details", headers=пассажирка["auth"])

    assert r.status_code == 200, r.text
    ответ = r.json()
    assert ответ["contact_unlocked"] is False, (
        "поездка была больше года назад, а телефон второй стороны всё ещё открыт"
    )
    assert ответ["driver_phone"] == "", f"номер водителя виден через год: {ответ['driver_phone']}"


def test_сразу_после_поездки_связь_есть(client, user_factory):
    """Обратная сторона: забыл вещь, не рассчитался, что-то уточнить — связь нужна."""
    водитель = user_factory("ПопуткаВодитель2", role=UserRole.driver)
    пассажир = user_factory("ПопуткаПассажир2")
    ride_id = _ride(client, водитель, comment="вчера")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=3)
        s.add(b)
        s.add(r)
        s.commit()

    ответ = client.get(f"/bookings/{bid}/details", headers=пассажир["auth"]).json()

    assert ответ["contact_unlocked"] is True, (
        "поездка кончилась три часа назад, а связаться уже нельзя — забытую вещь не вернуть"
    )
    assert ответ["driver_phone"], "номер водителя пропал сразу после поездки"


def test_забытая_вещь_продлевает_связь(client, давняя_поездка):
    """Пока идёт поиск вещи, номер нужен обеим сторонам — даже если поездка давняя."""
    _, пассажирка, bid = давняя_поездка
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.lost_item_until = utcnow() + timedelta(hours=10)
        s.add(b)
        s.commit()

    ответ = client.get(f"/bookings/{bid}/details", headers=пассажирка["auth"]).json()

    assert ответ["contact_unlocked"] is True, (
        "человек заявил о забытой вещи, а связаться с водителем не может"
    )


def test_едущим_прямо_сейчас_связь_не_рвётся(client, user_factory):
    """Самое важное исключение: пока едут, телефон обязан быть открыт."""
    водитель = user_factory("ПопуткаВодитель3", role=UserRole.driver)
    пассажир = user_factory("ПопуткаПассажир3")
    ride_id = _ride(client, водитель, comment="едем")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:                       # выехали два часа назад, ещё в дороге
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=2)
        s.add(r)
        s.commit()

    ответ = client.get(f"/bookings/{bid}/details", headers=пассажир["auth"]).json()

    assert ответ["contact_unlocked"] is True, "связь оборвалась посреди поездки"


def test_окно_связи_одно_с_чатом():
    """Сторож: два разных срока для связи по одной поездке однажды разъедутся.

    Чат после поездки и телефон после поездки — про одно и то же: сколько людям ещё нужно
    друг с другом связываться. Пусть считаются от одной настройки.
    """
    src = (APP / "visibility.py").read_text(encoding="utf-8")
    начало = src.index("def booking_contacts_open")

    assert "chat_after_trip_hours" in src[начало:], (
        "срок видимости телефона считается от своего числа, а не от общей настройки окна "
        "связи после поездки — однажды они разъедутся, и никто этого не заметит"
    )
