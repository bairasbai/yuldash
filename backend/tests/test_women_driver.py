"""F9 «Женщинам — водитель-женщина»: пол водителя — строго opt-in.

Контракты:
1. opt-in по умолчанию: водитель без указанного пола не считается «женщиной» и
   не подмешивается в фильтр women_only только из-за пола.
2. Фильтр women_only находит поездку женщины-водителя, даже если у самой поездки
   флаг women_only НЕ выставлен — но ТОЛЬКО после подтверждения модератором (см. ниже).
3. Приватность: мужской пол наружу не выпячивается — витрина мужчины неотличима
   от «не указан» (driver_is_woman=False), и мужчина не попадает в women_only.
4. С 2026-08-07 самодекларации НЕДОСТАТОЧНО. Раньше водитель сам ставил себе «женщина»,
   и этого хватало для бейджа и фильтра — то есть фича безопасности женщин держалась на
   честном слове (у Uber ровно отсюда растут жалобы «заказала женщину — приехал муж»).
   Теперь витрина верит только паре «заявлено + подтверждено модератором по фото прав».
   Подробнее — models.DriverProfile.gender_verified и test_car_and_gender_proof.py.
"""
from datetime import timedelta

from app.timeutil import utcnow
from app.models import UserRole


def _confirm_gender(client, admin, drv):
    """Модератор сверил пол с фото прав — без этого шага бейдж не появится."""
    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"],
                    json={"approve": True, "gender_verified": True})
    assert r.status_code == 200, r.text
    assert r.json()["gender_verified"] is True


def _publish(client, drv, frm, to="Сибай", **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _set_gender(client, drv, gender):
    r = client.post("/driver/gender", headers=drv["auth"], json={"gender": gender})
    assert r.status_code == 200, r.text
    return r.json()


# 1. opt-in по умолчанию ----------------------------------------------------
def test_gender_optin_default_not_woman(client, user_factory):
    """Без указанного пола водитель НЕ помечается женщиной и НЕ попадает в women_only."""
    drv = user_factory("PlainDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="НейтралГрад")

    rows = client.get("/rides", params={"from_city": "НейтралГрад"}).json()
    row = next(r for r in rows if r["id"] == ride["id"])
    assert row["driver_is_woman"] is False          # опт-ин: пол не указан → не «женщина»

    # поездка без флага women_only и без пола → фильтр women_only её не показывает
    wonly = client.get("/rides", params={"from_city": "НейтралГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)


# 2. Фильтр находит женщину-водителя ---------------------------------------
def test_women_only_filter_finds_female_driver(client, user_factory):
    """Подтверждённая женщина-водитель без флага women_only на поездке — видна в фильтре."""
    drv = user_factory("FemDrv", role=UserRole.driver)
    admin = user_factory("FemAdmin", role=UserRole.admin)
    _set_gender(client, drv, "female")
    _confirm_gender(client, admin, drv)
    ride = _publish(client, drv, frm="ЖенГрад")     # women_only НЕ выставлен намеренно

    wonly = client.get("/rides", params={"from_city": "ЖенГрад", "women_only": True}).json()
    match = next((r for r in wonly if r["id"] == ride["id"]), None)
    assert match is not None                         # найдена по полу водителя
    assert match["driver_is_woman"] is True          # бейдж «Водитель-женщина»


def test_unconfirmed_female_is_not_in_women_filter(client, user_factory):
    """Заявил «женщина», модератор не сверил → в фильтр «только женщины» НЕ попадает.

    Это и есть суть правки: женщина, выбравшая фильтр ради безопасности, не должна получить
    непроверенного водителя. Пока не сверили — не показываем, даже ценой пустой выдачи.
    """
    drv = user_factory("UnconfirmedFemDrv", role=UserRole.driver)
    _set_gender(client, drv, "female")
    ride = _publish(client, drv, frm="НепроверГрад")

    wonly = client.get("/rides", params={"from_city": "НепроверГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)
    rows = client.get("/rides", params={"from_city": "НепроверГрад"}).json()
    assert next(r for r in rows if r["id"] == ride["id"])["driver_is_woman"] is False


def test_verification_does_not_survive_a_gender_switch(client, user_factory):
    """Мужчина, которому подтвердили пол по правам, «переобулся» в профиле — женские поездки
    ему это не открывает.

    Дырка была в том, что заявление и подтверждение живут в разных местах: пол — у человека,
    галочка модератора — у водителя. Модератор честно подтверждает «male» (он и правда мужчина),
    после чего в настройках профиля пол меняется на «female» — и пара «female + подтверждено»
    складывалась сама собой. Женщина, открывшая фильтр ради безопасности, видела его как
    проверенную женщину за рулём. Обе двери к полу (`/driver/gender` и `/me/update`) теперь
    сбрасывают подтверждение.
    """
    drv = user_factory("SwitchDrv", role=UserRole.driver)
    admin = user_factory("SwitchAdmin", role=UserRole.admin)
    _set_gender(client, drv, "male")
    _confirm_gender(client, admin, drv)              # модератор сверил: мужчина, всё честно

    # Дверь вторая: пол меняется в общих настройках профиля.
    r = client.post("/me/update", headers=drv["auth"], json={"gender": "female"})
    assert r.status_code == 200, r.text
    assert r.json()["gender"] == "female"            # заявление поменялось — это законно

    ride = _publish(client, drv, frm="ПереобулсяГрад")
    wonly = client.get("/rides", params={"from_city": "ПереобулсяГрад", "women_only": True}).json()
    assert all(x["id"] != ride["id"] for x in wonly), "непроверенный попал в фильтр «только женщины»"
    rows = client.get("/rides", params={"from_city": "ПереобулсяГрад"}).json()
    assert next(x for x in rows if x["id"] == ride["id"])["driver_is_woman"] is False

    # Дверь первая — та же история: сменил заявление, подтверждение недействительно.
    drv2 = user_factory("SwitchDrv2", role=UserRole.driver)
    _set_gender(client, drv2, "male")
    _confirm_gender(client, admin, drv2)
    _set_gender(client, drv2, "female")
    ride2 = _publish(client, drv2, frm="ПереобулсяГрад2")
    wonly2 = client.get("/rides", params={"from_city": "ПереобулсяГрад2", "women_only": True}).json()
    assert all(x["id"] != ride2["id"] for x in wonly2)


def test_women_only_flag_ride_stays_in_the_filter(client, user_factory):
    """Поездка с флагом women_only остаётся в фильтре — регресс.

    История этого теста показательна. Сначала он ставил флаг водителю БЕЗ пола: отметка была
    пожеланием. С 2026-08-08 «только женщины» стало правилом, и тест переписали на женщину
    за рулём — с припиской «подтверждение модератора тут не нужно, пол проверяют при публикации».

    Волна 168 показала, что эта приписка и была дырой: публикация проверяла лишь строчку
    в профиле, которую человек пишет себе сам. Мужчина ставил «female», получал отметку —
    и попадал в фильтр, который открывают женщины, чтобы не ехать с незнакомым мужчиной.
    Теперь и отметка, и фильтр требуют подтверждения модератора, поэтому водитель здесь
    подтверждённый.
    """
    drv = user_factory("FlagDrv", role=UserRole.driver, gender="female")
    _confirm_gender(client, user_factory("FlagAdmin", role=UserRole.admin), drv)
    ride = _publish(client, drv, frm="ФлагГрад", women_only=True)
    wonly = client.get("/rides", params={"from_city": "ФлагГрад", "women_only": True}).json()
    assert any(r["id"] == ride["id"] for r in wonly)


def test_women_only_flag_requires_a_female_driver(client, user_factory):
    """Мужчина за рулём не может пометить поездку «только женщины».

    Иначе отметка — приманка: женщина садится в машину, думая, что проверено.
    Не указавшему пол отвечаем не отказом, а просьбой заполнить профиль.
    """
    male = user_factory("FlagMale", role=UserRole.driver, gender="male")
    r = client.post("/rides", headers=male["auth"], json={
        "from_city": "ФлагГрад2", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r.status_code == 403, r.text

    unknown = user_factory("FlagNoGender", role=UserRole.driver)
    r2 = client.post("/rides", headers=unknown["auth"], json={
        "from_city": "ФлагГрад3", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r2.status_code == 403
    assert "профил" in r2.json()["detail"]["ru"].lower()   # просим заполнить, а не отказываем молча


# 3. Приватность: мужской пол не выпячиваем --------------------------------
def test_male_gender_not_surfaced(client, user_factory):
    """Мужчина-водитель: driver_is_woman=False, витрина не раскрывает «male»,
    и он НЕ попадает в фильтр women_only (без флага на поездке)."""
    drv = user_factory("MaleDrv", role=UserRole.driver)
    _set_gender(client, drv, "male")
    ride = _publish(client, drv, frm="МужГрад")

    rows = client.get("/rides", params={"from_city": "МужГрад"}).json()
    row = next(r for r in rows if r["id"] == ride["id"])
    assert row["driver_is_woman"] is False
    # витрина не должна содержать сырое поле пола — наружу уходит только бинарный сигнал
    assert "gender" not in row and "driver_gender" not in row

    wonly = client.get("/rides", params={"from_city": "МужГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)


def test_gender_own_profile_visible_and_validated(client, user_factory):
    """Свой пол водитель видит в /driver/status; недопустимое значение — 400."""
    drv = user_factory("StatusDrv", role=UserRole.driver)
    _set_gender(client, drv, "female")
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender"] == "female"
    # можно снять (opt-out)
    _set_gender(client, drv, "")
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender"] == ""
    # мусорное значение отклоняется
    assert client.post("/driver/gender", headers=drv["auth"], json={"gender": "other"}).status_code == 400
    # нужен токен
    assert client.post("/driver/gender", json={"gender": "female"}).status_code == 401


def test_status_tells_the_driver_whether_gender_is_confirmed(client, user_factory):
    """Кабинет водителя обязан знать, подтверждён ли пол, — иначе он врёт.

    Экран показывал тумблер «Я — женщина за рулём» с подписью «пассажирки увидят бейдж и
    смогут заказать такси только к женщине за рулём» сразу после нажатия. На деле и бейдж,
    и женские заказы включает модератор, сверив права: женщина ждала заказы, которых не
    будет. Чтобы текст был честным, статус отдаёт `gender_verified` (живая проверка на
    эмуляторе 2026-08-12).
    """
    drv = user_factory("VerifiedFemDrv", role=UserRole.driver)
    admin = user_factory("VerifiedFemAdmin", role=UserRole.admin)

    # пола нет — подтверждать нечего
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender_verified"] is False

    # заявила «женщина» — это ещё не подтверждение
    _set_gender(client, drv, "female")
    st = client.get("/driver/status", headers=drv["auth"]).json()
    assert st["gender"] == "female" and st["gender_verified"] is False

    # модератор сверил права — теперь бейдж настоящий, и кабинет об этом узнаёт
    _confirm_gender(client, admin, drv)
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender_verified"] is True

    # сняла пол → подтверждение сгорает, и статус говорит об этом сразу
    _set_gender(client, drv, "")
    st2 = client.get("/driver/status", headers=drv["auth"]).json()
    assert st2["gender"] == "" and st2["gender_verified"] is False


def test_profile_gender_change_burns_confirmation(client, user_factory):
    """Дыра: подтверждение пола переживало смену пола через профиль.

    Модератор подтверждает пол и мужчине (это законно — он сверяет права). Дальше водитель
    менял пол на «женщина» в профиле (`POST /me/update`, а не тумблером `/driver/gender`) —
    подтверждение оставалось, и он получал публичный бейдж «женщина за рулём» и заказы
    «только к женщине». То есть проверку, введённую ровно против «заказала женщину —
    приехал мужчина», можно было обойти в два тапа.
    """
    drv = user_factory("SwapDrv", role=UserRole.driver)
    admin = user_factory("SwapAdmin", role=UserRole.admin)
    _set_gender(client, drv, "male")
    _confirm_gender(client, admin, drv)

    # смена пола через профиль — тот же самый пол, другая дверь
    r = client.post("/me/update", headers=drv["auth"], json={"gender": "female"})
    assert r.status_code == 200 and r.json()["gender"] == "female"

    st = client.get("/driver/status", headers=drv["auth"]).json()
    assert st["gender"] == "female"
    assert st["gender_verified"] is False, "смена пола обязана гасить подтверждение"

    # и наружу бейджа нет: витрина верит только паре «заявлено + подтверждено»
    ride = _publish(client, drv, frm="ПодменаГрад")
    rows = client.get("/rides", params={"from_city": "ПодменаГрад"}).json()
    assert next(r for r in rows if r["id"] == ride["id"])["driver_is_woman"] is False
    wonly = client.get("/rides", params={"from_city": "ПодменаГрад", "women_only": True}).json()
    assert all(r["id"] != ride["id"] for r in wonly)


def test_gender_writes_go_through_one_door():
    """Сторож класса ошибки: пол пишется только в `services.set_user_gender`.

    Правило «сменил пол — сгорело подтверждение» уже один раз разъехалось по двум ручкам.
    Появится третья (регистрация, импорт, админка) — тест упадёт здесь, а не всплывёт
    жалобой «заказала женщину — приехал мужчина».
    """
    import pathlib
    import re

    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    hits = []
    for path in root.rglob("*.py"):
        if path.name == "services.py":
            continue                      # единственная законная дверь
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"\buser\.gender\s*=(?!=)|\.gender\s*=\s*g\b", line):
                hits.append(f"{path.name}:{i}: {line.strip()}")
    assert not hits, "пол пишут мимо set_user_gender:\n" + "\n".join(hits)


def _upload_doc(client, drv):
    """Загрузка фото документа — ссылку выдаёт сервер, чужую он не примет."""
    import base64
    r = client.post("/upload/photo", headers=drv["auth"],
                    json={"photo_b64": base64.b64encode(bytes([0xFF, 0xD8, 0xFF]) + b"fake-jpeg").decode(), "ext": "jpg"})
    assert r.status_code == 200, r.text
    return r.json()["url"]


def _submit_docs(client, drv, lic=None, car=None):
    r = client.post("/driver/verify", headers=drv["auth"],
                    json={"license_url": lic or _upload_doc(client, drv),
                          "car_photo_url": car or _upload_doc(client, drv)})
    assert r.status_code == 200, r.text
    return r.json()


def test_rejected_docs_burn_gender_confirmation(client, user_factory):
    """Отклонили документы — бейдж «женщина за рулём» обязан погаснуть.

    Пол подтверждают ПО ФОТО ПРАВ. Если права признали негодными, подтверждать по ним нечего.
    Правило было написано только в кнопке админки; через телеграм-бота и авто-проверку
    водитель с отклонёнными документами сохранял бейдж и женские заказы.
    """
    drv = user_factory("RejectedFemDrv", role=UserRole.driver)
    admin = user_factory("RejectedFemAdmin", role=UserRole.admin)
    _set_gender(client, drv, "female")
    _confirm_gender(client, admin, drv)
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender_verified"] is True

    r = client.post(f"/admin/drivers/{drv['id']}/moderate", headers=admin["auth"], json={"approve": False})
    assert r.status_code == 200, r.text
    st = client.get("/driver/status", headers=drv["auth"]).json()
    assert st["docs_status"] == "rejected"
    assert st["gender_verified"] is False, "отклонённые документы обязаны гасить подтверждение пола"


def test_new_license_photo_burns_gender_confirmation(client, user_factory):
    """Прислала ДРУГИЕ права — подтверждение сгорает: старое фото стирается насовсем.

    Иначе подтверждение висит на документе, которого больше нет, и сверить его уже нельзя.
    Переотправка той же пары фото (человек нажал «отправить» дважды) подтверждение НЕ трогает.
    """
    drv = user_factory("NewLicFemDrv", role=UserRole.driver)
    admin = user_factory("NewLicFemAdmin", role=UserRole.admin)
    _submit_docs(client, drv)
    _set_gender(client, drv, "female")
    _confirm_gender(client, admin, drv)

    # та же пара фото — подтверждение остаётся
    st = client.get("/driver/status", headers=drv["auth"]).json()
    _submit_docs(client, drv, lic=st["license_url"], car=st["car_photo_url"])
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender_verified"] is True

    # другое фото прав — подтверждение гаснет
    _submit_docs(client, drv, lic=_upload_doc(client, drv), car=st["car_photo_url"])
    assert client.get("/driver/status", headers=drv["auth"]).json()["gender_verified"] is False


def test_docs_verdicts_go_through_one_door():
    """Сторож класса ошибки: вердикт по документам ставит только `set_driver_docs_verdict`.

    Тот же урок, что и с полом: правило, написанное внутри обработчика, молча не работает
    в остальных дверях (админка, телеграм-бот, авто-проверка OCR).
    """
    import pathlib
    import re

    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    hits = []
    for path in root.rglob("*.py"):
        if path.name == "services.py":
            continue                      # единственная законная дверь
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"""\.docs_status\s*=\s*["'](verified|rejected)["']|\.verified\s*=(?!=)""", line):
                hits.append(f"{path.name}:{i}: {line.strip()}")
    assert not hits, "вердикт по документам ставят мимо set_driver_docs_verdict:\n" + "\n".join(hits)
