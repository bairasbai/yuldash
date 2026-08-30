# -*- coding: utf-8 -*-
"""Машина показывает себя сама: фотоконтроль раз в две недели (580-ФЗ, 2026-08-30).

БЫЛО. Машину мы видели ОДИН раз — на фото при регистрации. Дальше о ней известно ровно то,
что человек сказал сам: перед выездом он ставит галочку «машина исправна». Разбитый бампер,
лысая резина и салон, куда стыдно посадить ребёнка, выглядят в базе как новая машина.

СТАЛО. Раз в две недели несколько кадров с телефона: такси — четыре стороны кузова и салон,
курьер — две стороны и багажник.

ЧТО ЗДЕСЬ ПРОВЕРЯЕТСЯ, кроме «работает ли». Каждое место, где строгость может ударить по
человеку зря:

  • просрочка бьёт ЛЕСТНИЦЕЙ, а не сразу: 1–3 дня напоминание, 4–7 приоритет вниз, и только
    после недели — пауза;
  • кадры отправлены и лежат у нас на просмотре → человек НЕ ограничен ничем: наша очередь
    это наша проблема;
  • автомат не знает даты съёмки (наше же приложение стирает метаданные) → это «не знаем»,
    а не «обманул»;
  • такси и курьер считаются раздельно: включённый контроль такси не трогает доставку;
  • выключенный флаг не мешает никому и нигде.
"""
import base64
from datetime import timedelta
from io import BytesIO

import pytest
from sqlmodel import Session, select

from app import carphoto as cp
from app import priority as prio
from app import taxi as taxi_mod
from app.config import settings
from app.db import engine
from app.models import (CarPhotoCheck, CourierApplication, Report, TaxiApplication,
                        TaxiApplicationStatus, UserRole)
from app.timeutil import utcnow

PIL = pytest.importorskip("PIL.Image", reason="фотоконтроль разбирает картинки Pillow")


# --------------------------------------------------------------------- вспомогательное
def _сессия() -> Session:
    """Сессия, которая не гасит объекты после commit.

    Иначе строку контроля нельзя прочитать за пределами `with`: SQLAlchemy помечает поля
    протухшими и лезет за ними в закрытую сессию. Тесту нужна не эта механика, а смысл —
    поэтому держим значения загруженными.
    """
    return Session(engine, expire_on_commit=False)


def _картинка(w=1200, h=900, seed=0, fmt="JPEG", exif=None) -> bytes:
    """Настоящий файл-картинка. `seed` меняет рисунок → меняет и отпечаток кадра."""
    from PIL import Image
    мелкая = Image.frombytes(
        "L", (16, 16),
        bytes(((x * 13 + y * 29 + seed * 61) % 256) for y in range(16) for x in range(16)))
    img = мелкая.resize((w, h)).convert("RGB")
    buf = BytesIO()
    if exif is not None:
        img.save(buf, fmt, exif=exif)
    else:
        img.save(buf, fmt)
    return buf.getvalue()


def _exif(снято=None, производитель="Samsung"):
    from PIL import Image
    e = Image.Exif()
    if производитель:
        e[271] = производитель          # Make
    if снято is not None:
        e[306] = снято.strftime("%Y:%m:%d %H:%M:%S")     # DateTime
    return e


@pytest.fixture
def контроль_включён():
    """Фотоконтроль включён для обоих режимов. После теста — как было."""
    было = (settings.car_photo_taxi_enabled, settings.car_photo_courier_enabled)
    settings.car_photo_taxi_enabled = True
    settings.car_photo_courier_enabled = True
    yield
    settings.car_photo_taxi_enabled, settings.car_photo_courier_enabled = было


def _водитель(user_factory, name="Водитель"):
    return user_factory(name, role=UserRole.driver)


def _курьер(user_factory, name="Курьер"):
    u = user_factory(name, role=UserRole.passenger, taxi_approved=False)
    with _сессия() as s:
        s.add(CourierApplication(user_id=u["id"], status="approved", full_name=name,
                                 car_plate="А111АА102", reviewed_at=utcnow()))
        s.commit()
    return u


def _состарить_одобрение(user_id: int, дней: int):
    """Сделать вид, что человека одобрили давно, — он «работает давно»."""
    with _сессия() as s:
        app = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == user_id)).first()
        app.created_at = utcnow() - timedelta(days=дней)
        app.reviewed_at = utcnow() - timedelta(days=дней)
        s.add(app)
        s.commit()


def _сдвинуть_срок(check_id: int, дней: float):
    """Отодвинуть срок контроля назад — так наступает просрочка без ожидания."""
    with _сессия() as s:
        row = s.get(CarPhotoCheck, check_id)
        row.due_at = utcnow() - timedelta(days=дней)
        s.add(row)
        s.commit()
        return row


def _заполнить(check: CarPhotoCheck, seed_from=0):
    """Положить в контроль все нужные кадры, как это делает загрузка."""
    with _сессия() as s:
        row = s.get(CarPhotoCheck, check.id)
        for i, слот in enumerate(cp.slots(row.mode)):
            cp.attach(s, row, слот["code"], f"/secure/carphoto/{row.user_id}_{i}.jpg", "ok",
                      f"{(seed_from + i) * 1111:016x}")
        return s.get(CarPhotoCheck, check.id)


# ==================== 1. Выключено — не мешает никому ====================
def test_disabled_control_touches_nobody(client, user_factory):
    """Флаг выключен: контроля нет, гейт молчит, экран честно говорит «не нужно»."""
    d = _водитель(user_factory, "БезКонтроля")
    with _сессия() as s:
        assert cp.ensure(s, d["id"], cp.TAXI) is None
        assert cp.blocked(s, d["id"], cp.TAXI) is False
        assert cp.slow(s, d["id"], cp.TAXI) is False
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True
        assert cp.payload(s, d["id"], cp.TAXI)["enabled"] is False


# ==================== 2. Кому и когда первый контроль ====================
def test_a_newcomer_gets_three_days(client, user_factory, контроль_включён):
    """Одобрили только что → первый контроль через три дня от одобрения."""
    d = _водитель(user_factory, "Новичок")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    assert проверка is not None and проверка.seq == 1
    осталось = (проверка.due_at - utcnow()).total_seconds() / 86400
    assert 2.5 < осталось <= 3.1, f"новичку дали не три дня, а {осталось:.1f}"


def test_an_old_hand_is_not_put_overdue_retroactively(client, user_factory, контроль_включён):
    """Работает полгода → неделя от СЕГОДНЯ, а не просрочка задним числом.

    Иначе включённый флаг в понедельник утром снял бы с линии всех разом — за то, что
    они не знали о правиле, которого вчера не существовало.
    """
    d = _водитель(user_factory, "Старожил")
    _состарить_одобрение(d["id"], 180)
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
        assert cp.stage(проверка) == "ok"
        assert cp.blocked(s, d["id"], cp.TAXI) is False
    осталось = (проверка.due_at - utcnow()).total_seconds() / 86400
    assert 6.5 < осталось <= 7.1, f"старожилу дали не неделю, а {осталось:.1f}"


def test_a_person_without_approval_owes_nothing(client, user_factory, контроль_включён):
    """Заявки нет — контроля нет: пассажиру нечего показывать."""
    p = user_factory("Пассажир", taxi_approved=False)
    with _сессия() as s:
        assert cp.ensure(s, p["id"], cp.TAXI) is None


def test_taxi_and_courier_are_counted_separately(client, user_factory, контроль_включён):
    """Такси и курьер — разные контроли: у одного салон, у другого багажник."""
    d = _водитель(user_factory, "ИВозитИДоставляет")
    with _сессия() as s:
        s.add(CourierApplication(user_id=d["id"], status="approved", reviewed_at=utcnow()))
        s.commit()
        такси = cp.ensure(s, d["id"], cp.TAXI)
        курьер = cp.ensure(s, d["id"], cp.COURIER)
    assert такси.id != курьер.id
    assert [с["code"] for с in cp.slots(cp.TAXI)] == ["front", "back", "left", "right", "salon"]
    assert [с["code"] for с in cp.slots(cp.COURIER)] == ["front", "back", "trunk"]


# ==================== 3. Лестница просрочки ====================
@pytest.mark.parametrize("дней,ступень", [
    (-0.5, "ok"),       # срок ещё не вышел — полдня в запасе
    (1, "remind"),      # первые сутки просрочки — только напоминание
    (2.9, "remind"),
    (3, "slow"),        # четвёртые сутки — вниз в подборе
    (6.9, "slow"),
    (7, "blocked"),     # после недели — пауза до фото
    (30, "blocked"),
])
def test_the_ladder_is_soft_before_it_is_hard(client, user_factory, контроль_включён,
                                              дней, ступень):
    """Мягкая лестница: напоминание → приоритет вниз → пауза. И ни на ступень быстрее."""
    d = _водитель(user_factory, f"Лестница{дней}")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    строка = _сдвинуть_срок(проверка.id, дней)
    assert cp.stage(строка) == ступень


def test_the_pause_leaves_poputka_alone(client, user_factory, контроль_включён):
    """Пауза снимает ТАКСИ. Попутка не такси — её фотоконтроль не касается."""
    d = _водитель(user_factory, "ПаузаНеПопутка")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _сдвинуть_срок(проверка.id, 10)
    with _сессия() as s:
        assert cp.blocked(s, d["id"], cp.TAXI) is True
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is False
        assert taxi_mod.taxi_car_photo_blocked(s, d["id"]) is True
        # Доставка живёт своей жизнью: у неё свой контроль и свой срок.
        assert cp.blocked(s, d["id"], cp.COURIER) is False


def test_the_courier_pause_closes_the_courier_door(client, user_factory, контроль_включён):
    """У курьера пауза закрывает КУРЬЕРСКУЮ дверь, а не такси и не попутку."""
    было = settings.courier_enabled
    settings.courier_enabled = True
    try:
        к = _курьер(user_factory, "КурьерБезФото")
        with _сессия() as s:
            проверка = cp.ensure(s, к["id"], cp.COURIER)
        _сдвинуть_срок(проверка.id, 10)
        ответ = client.post("/courier/online", headers=к["auth"], json={"zone": "region"})
        assert ответ.status_code == 403, ответ.text
        текст = ответ.json()["detail"]
        текст = текст.get("ru", "") if isinstance(текст, dict) else str(текст)
        assert "фото" in текст.lower(), f"курьеру не сказали, что делать: {текст}"
    finally:
        settings.courier_enabled = было


def test_the_refusal_says_what_to_do(client, user_factory, контроль_включён):
    """Отказ объясняет: сколько кадров, где, и что попутка работает."""
    текст = cp.MSG_BLOCKED[cp.TAXI]["ru"]
    assert "фото" in текст and "профиле" in текст, "человеку не сказали, что делать"
    assert "опутка" in текст, "не сказали, что попутка работает"
    assert cp.MSG_BLOCKED[cp.TAXI]["ba"].strip(), "нет башкирского текста"
    assert cp.MSG_BLOCKED[cp.COURIER]["ba"].strip(), "нет башкирского текста у курьера"


def test_photos_sent_on_time_are_never_punished(client, user_factory, контроль_включён):
    """Кадры отправлены и лежат у нас на просмотре — человек не ограничен ничем.

    Даже если с тех пор прошёл месяц: свою часть он сделал в срок, а скорость нашего
    разбора не должна стоить ему заказов. Тот же принцип, что с молчащим реестром.
    """
    d = _водитель(user_factory, "ЖдётНас")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _сдвинуть_срок(проверка.id, 30)
    полный = _заполнить(проверка)
    with _сессия() as s:
        итог = cp.submit(s, s.get(CarPhotoCheck, полный.id))
        assert итог["status"] == cp.REVIEW, "новичка обязан смотреть человек"
        assert cp.blocked(s, d["id"], cp.TAXI) is False
        assert cp.slow(s, d["id"], cp.TAXI) is False


# ==================== 4. Что автомат может сказать честно ====================
def test_a_tiny_frame_is_refused(client):
    """Мелкий кадр — на нём ничего не разглядеть."""
    итог = cp.inspect(_картинка(320, 240), "jpg")
    assert итог["ok"] is False and итог["reason"] == "too_small"


def test_a_screenshot_is_recognised(client):
    """Экран телефона вытянут сильнее, чем бывает у камеры, — это скриншот."""
    итог = cp.inspect(_картинка(720, 1560, fmt="PNG"), "png")
    assert итог["ok"] is False and итог["reason"] == "screenshot"


def test_a_normal_portrait_photo_is_not_a_screenshot(client):
    """Обычное вертикальное фото с телефона (3:4) скриншотом НЕ считается."""
    кадр = _картинка(900, 1200, exif=_exif(utcnow()))
    assert cp.inspect(кадр, "jpg")["ok"] is True


def test_an_old_shot_is_refused(client):
    """Снимок прошлого года — не «машина сейчас»."""
    кадр = _картинка(exif=_exif(utcnow() - timedelta(days=400)))
    итог = cp.inspect(кадр, "jpg")
    assert итог["ok"] is False and итог["reason"] == "stale"


def test_a_fresh_shot_passes(client):
    """Снятое сегодня проходит."""
    assert cp.inspect(_картинка(exif=_exif(utcnow())), "jpg")["ok"] is True


def test_no_shot_date_is_not_an_accusation(client):
    """Даты съёмки в файле нет — это «не знаем», а не «обманул».

    Наше же приложение пережимает фото и метаданные стирает само. Наказывать человека за
    отсутствие того, что стёрли мы, — верный способ научить его обходить правила.
    """
    итог = cp.inspect(_картинка(), "jpg")     # файл без EXIF вообще
    assert итог["ok"] is True and итог["reason"] == ""


def test_the_same_photo_twice_is_caught(client):
    """Та же фотография во второй раз — единственный обман, который ловится честно."""
    кадр = _картинка(seed=5, exif=_exif(utcnow()))
    первый = cp.inspect(кадр, "jpg")
    assert первый["ok"] is True
    повтор = cp.inspect(кадр, "jpg", known=[первый["hash"]])
    assert повтор["ok"] is False and повтор["reason"] == "duplicate"


def test_two_different_frames_are_not_twins(client):
    """Разные кадры не считаются одним и тем же: обвинить честного дороже, чем пропустить."""
    a = cp.dhash(_картинка(seed=1))
    b = cp.dhash(_картинка(seed=200))
    assert a and b and a != b
    assert cp._distance(a, b) > cp.SAME_PHOTO_BITS


# ==================== 5. Ход контроля ====================
def test_an_incomplete_set_is_not_accepted(client, user_factory, контроль_включён):
    """Не все кадры — не отправка. И сразу видно, чего не хватает."""
    d = _водитель(user_factory, "НеВсёПрислал")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
        cp.attach(s, проверка, "front", "/secure/carphoto/1_a.jpg", "ok", "aaaa")
        итог = cp.submit(s, проверка)
    assert итог["ok"] is False
    assert set(итог["missing"]) == {"back", "left", "right", "salon"}


def test_a_newcomer_is_looked_at_by_a_human(client, user_factory, контроль_включён):
    """Первые контроли смотрит человек: автомат не видит ни грязи, ни вмятины."""
    d = _водитель(user_factory, "Первый")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    assert проверка.manual is True and проверка.manual_reason == "newbie"


def test_a_complaint_sends_the_next_check_to_a_human(client, user_factory, контроль_включён):
    """Пожаловались на грязную машину — следующий контроль смотрит человек.

    Жалоба, после которой ничего не проверяется, не стоит ничего — ни для пассажира,
    ни для водителя.
    """
    d = _водитель(user_factory, "НаНегоЖаловались")
    пассажир = user_factory("Жалобщик", taxi_approved=False)
    with _сессия() as s:
        s.add(Report(reporter_id=пассажир["id"], target_user_id=d["id"], category="dirty_car"))
        s.commit()
        # Номер вне «новичка» — иначе человек смотрел бы и без жалобы.
        нужен, почему = cp._needs_human(s, d["id"], cp.TAXI, seq=9, now=utcnow())
    assert нужен is True and почему == "complaint"


def test_a_passed_check_schedules_the_next_one(client, user_factory, контроль_включён):
    """Пройденный контроль сразу назначает следующий: цепочка не рвётся."""
    d = _водитель(user_factory, "Цепочка")
    with _сессия() as s:
        первый = cp.ensure(s, d["id"], cp.TAXI)
    _заполнить(первый)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, первый.id))
        cp.decide(s, s.get(CarPhotoCheck, первый.id), True, admin_id=None)
        второй = cp.current(s, d["id"], cp.TAXI)
    assert второй is not None and второй.seq == 2
    через = (второй.due_at - utcnow()).total_seconds() / 86400
    assert 2.5 < через <= 3.1, f"второй контроль назначен не через три дня, а через {через:.1f}"


def test_after_the_intro_the_period_becomes_two_weeks(client, user_factory, контроль_включён):
    """После первых двух контролей — раз в две недели, а не каждые три дня."""
    assert cp._period_after(1) == settings.car_photo_intro_days
    assert cp._period_after(2) == settings.car_photo_period_days
    assert cp._period_after(9) == settings.car_photo_period_days


def test_a_rejected_check_comes_back_with_the_same_number(client, user_factory, контроль_включён):
    """Не приняли — переснять тот же контроль, а не «пропустить» его."""
    d = _водитель(user_factory, "Переснять")
    with _сессия() as s:
        первый = cp.ensure(s, d["id"], cp.TAXI)
    _заполнить(первый)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, первый.id))
        закрытый = cp.decide(s, s.get(CarPhotoCheck, первый.id), False,
                             reason="салон не видно", admin_id=None)
        новый = cp.current(s, d["id"], cp.TAXI)
    assert закрытый.status == cp.FAILED and закрытый.reject_reason == "салон не видно"
    assert новый.seq == первый.seq, "отказ не должен сдвигать номер контроля"


def test_the_first_taxi_check_looks_at_the_signs(client, user_factory, контроль_включён):
    """«Шашечки» и фонарь смотрим один раз, на первом контроле, и без лишнего кадра."""
    d = _водитель(user_factory, "Шашечки")
    with _сессия() as s:
        данные = cp.payload(s, d["id"], cp.TAXI)
    assert данные["check_signs"] is True
    к = _курьер(user_factory, "БезШашечек")
    with _сессия() as s:
        assert cp.payload(s, к["id"], cp.COURIER)["check_signs"] is False


# ==================== 6. Приоритет и уведомления ====================
def test_the_middle_step_costs_a_priority_point(client, user_factory, контроль_включён):
    """4–7 дней просрочки: заказы идут, но первым их видит тот, кто машину показал."""
    d = _водитель(user_factory, "Приоритет")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _сдвинуть_срок(проверка.id, 5)
    with _сессия() as s:
        расклад = prio.taxi_points(s, d["id"])
    assert расклад["minus"] >= settings.priority_photo_late_penalty


def test_the_pause_does_not_also_take_points(client, user_factory, контроль_включён):
    """Уже на паузе — баллы не отнимаем: наказывать дважды за одно нечестно."""
    d = _водитель(user_factory, "ДваждыНеНаказываем")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _сдвинуть_срок(проверка.id, 20)
    with _сессия() as s:
        assert prio.taxi_points(s, d["id"])["minus"] == 0


def test_the_reminder_comes_before_the_consequence(client, user_factory, контроль_включён):
    """Человек должен услышать приближение последствия, а не узнать о нём постфактум."""
    d = _водитель(user_factory, "Напоминание")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _сдвинуть_срок(проверка.id, 1)
    with _сессия() as s:
        строка = s.get(CarPhotoCheck, проверка.id)
        assert cp.remind(s, строка) is True
        assert cp.remind(s, s.get(CarPhotoCheck, проверка.id)) is False, "письмо ушло дважды за сутки"


def test_the_nightly_pass_opens_the_first_checks(client, user_factory, контроль_включён):
    """Ночной обход заводит первые контроли тем, у кого их ещё нет."""
    d = _водитель(user_factory, "НочнойОбход")
    with _сессия() as s:
        итог = cp.run_once(s)
    assert d["id"] in итог[cp.TAXI]["created"]
    with _сессия() as s:
        assert cp.current(s, d["id"], cp.TAXI) is not None


# ==================== 7. Через настоящий API ====================
def _прислать(client, auth, кадр: bytes, slot: str, mode: str = cp.TAXI, ext: str = "jpg"):
    return client.post(f"/carphoto/photo?mode={mode}&slot={slot}", headers=auth,
                       json={"photo_b64": base64.b64encode(кадр).decode(), "ext": ext})


def test_the_driver_walks_the_whole_path(client, user_factory, контроль_включён):
    """Путь целиком: узнал что снять → прислал кадры → отправил → ушло на просмотр."""
    d = _водитель(user_factory, "ПутьЦеликом")
    экран = client.get("/carphoto?mode=taxi", headers=d["auth"])
    assert экран.status_code == 200, экран.text
    данные = экран.json()
    assert данные["required"] is True and len(данные["slots"]) == 5

    for i, слот in enumerate(cp.slots(cp.TAXI)):
        r = _прислать(client, d["auth"], _картинка(seed=i + 10, exif=_exif(utcnow())), слот["code"])
        assert r.status_code == 200, r.text
        assert r.json()["ok"] is True, r.json()

    отправка = client.post("/carphoto/submit", headers=d["auth"], json={"mode": "taxi"})
    assert отправка.status_code == 200, отправка.text
    assert отправка.json()["status"] == cp.REVIEW


def test_a_bad_frame_is_explained_right_away(client, user_factory, контроль_включён):
    """Кадр не подошёл — человек узнаёт об этом, пока стоит у машины, а не назавтра."""
    d = _водитель(user_factory, "СразуСказали")
    r = _прислать(client, d["auth"], _картинка(320, 240), "front")
    assert r.status_code == 200, r.text
    тело = r.json()
    assert тело["ok"] is False and тело["reason"] == "too_small"
    assert тело["message"]["ru"] and тело["message"]["ba"], "нет объяснения на двух языках"
    assert "front" in тело["missing"]


def test_someone_elses_frame_is_not_given_away(client, user_factory, контроль_включён):
    """Кадр чужой машины не отдаётся: приватная область, только владелец и админ."""
    d = _водитель(user_factory, "Владелец")
    чужой = user_factory("Любопытный", taxi_approved=False)
    r = _прислать(client, d["auth"], _картинка(seed=3, exif=_exif(utcnow())), "front")
    ссылка = r.json()["url"]
    имя = ссылка.rsplit("/", 1)[-1]
    assert client.get(f"/secure/carphoto/{имя}", headers=чужой["auth"]).status_code == 403
    assert client.get(f"/secure/carphoto/{имя}", headers=d["auth"]).status_code == 200


def test_an_unknown_slot_is_refused(client, user_factory, контроль_включён):
    """Кадр «не из списка» не принимаем: иначе набор соберётся из чего попало."""
    d = _водитель(user_factory, "ЧужойКадр")
    r = _прислать(client, d["auth"], _картинка(exif=_exif(utcnow())), "roof")
    assert r.status_code == 400


def test_the_admin_queue_shows_what_to_look_at(client, user_factory, контроль_включён):
    """Очередь у админа говорит, что именно смотреть: зимой чистоту кузова не спрашиваем."""
    d = _водитель(user_factory, "ВОчереди")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _заполнить(проверка)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, проверка.id))
    админ = user_factory("Админ", role=UserRole.admin, taxi_approved=False)
    очередь = client.get("/admin/carphoto", headers=админ["auth"])
    assert очередь.status_code == 200, очередь.text
    мой = [r for r in очередь.json() if r["user_id"] == d["id"]]
    assert len(мой) == 1
    assert мой[0]["check_clean_body"] is not мой[0]["winter"]
    assert set(мой[0]["photos"]) == {"front", "back", "left", "right", "salon"}


def test_a_refusal_without_a_reason_is_refused(client, user_factory, контроль_включён):
    """Отказ без объяснения — тупик: человек не знает, что переснимать."""
    d = _водитель(user_factory, "ОтказБезПричины")
    with _сессия() as s:
        проверка = cp.ensure(s, d["id"], cp.TAXI)
    _заполнить(проверка)
    with _сессия() as s:
        cp.submit(s, s.get(CarPhotoCheck, проверка.id))
    админ = user_factory("Админ2", role=UserRole.admin, taxi_approved=False)
    r = client.post(f"/admin/carphoto/{проверка.id}/decide", headers=админ["auth"],
                    json={"ok": False, "reason": "  "})
    assert r.status_code == 400
    ок = client.post(f"/admin/carphoto/{проверка.id}/decide", headers=админ["auth"],
                     json={"ok": True})
    assert ок.status_code == 200, ок.text


def test_the_queue_is_closed_for_strangers(client, user_factory, контроль_включён):
    """Чужие фотографии машин видит только админ."""
    d = _водитель(user_factory, "НеАдмин")
    assert client.get("/admin/carphoto", headers=d["auth"]).status_code == 403


# ==================== 8. Хранение ====================
def test_frames_do_not_live_forever(client, user_factory, контроль_включён):
    """Кадры живут 90 дней и уходят, даже когда строка на них ссылается.

    Отличие от документов водителя, которые не чистятся никогда: документ обязан лежать,
    пока человек работает (580-ФЗ), а снимок его машины у подъезда — нет. И «пока есть
    ссылка — храним» здесь означало бы «храним вечно»: строка ссылается на файлы всегда.
    """
    import os
    import time

    from app import cleanup
    from app.storage import PRIVATE_AREAS, get_storage

    assert "carphoto" in PRIVATE_AREAS, "кадры машин обязаны лежать в приватной области"
    d = _водитель(user_factory, "Ретеншен")
    storage = get_storage()
    старый = f"{d['id']}_old_carphoto.jpg"
    свежий = f"{d['id']}_new_carphoto.jpg"
    for имя in (старый, свежий):
        storage.save(f"carphoto/{имя}", bytes([0xFF, 0xD8, 0xFF]) + b"test")
    # Состариваем только один: чистка смотрит на время изменения файла.
    древность = time.time() - (int(settings.car_photo_keep_days) + 5) * 86400
    путь = storage._path(f"carphoto/{старый}")            # локальный диск в тестах
    os.utime(путь, (древность, древность))

    cleanup._clean_carphoto()

    assert not storage.exists(f"carphoto/{старый}"), "снимок старше 90 дней остался навсегда"
    assert storage.exists(f"carphoto/{свежий}"), "чистка съела свежий кадр"


def test_the_record_of_the_check_outlives_the_photos(client):
    """Файлы уходят через 90 дней, СТРОКА живёт дольше — она и есть след для проверки."""
    from app import cleanup

    assert settings.car_photo_keep_days == 90
    assert cleanup.CARPHOTO_DAYS > settings.car_photo_keep_days, (
        "строка о контроле обязана пережить сами снимки: иначе доказать, что контроль был, "
        "станет нечем"
    )
    правила = [r for r in cleanup._rules(utcnow()) if r[1] == "carphotocheck"]
    assert правила, "фотоконтроль не попал в ретеншен — таблица росла бы вечно"
