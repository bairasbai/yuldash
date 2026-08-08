"""Аудит безопасности 2026-08-08 — регрессия на найденные дыры.

Каждый тест здесь стоит на конкретной находке: убери фикс — тест покраснеет.

1. Селфи курьера принималось ЛЮБОЙ ссылкой. Очередь модерации грузит его с заголовком
   `Authorization: Bearer <токен админа>` → ссылка на чужой сервер уводила админку.
2. Роль администратора выдавалась по совпадению ПОСЛЕДНИХ 10 цифр телефона, без кода страны:
   иностранный номер с теми же цифрами получал админку при обычном входе через Telegram.
3. Счётчик неудачных попыток кода жил на строке кода, а проверялся всегда свежий код →
   запросив новый код, перебирающий обнулял себе счётчик.
4. Исключение вебхука Telegram из лимитера стояло на несуществующем пути.
"""
import base64
import pathlib

import pytest
from sqlmodel import Session

from app.config import _phone_key, settings
from app.db import engine
from app.models import OtpCode, User, UserRole
from app.timeutil import utcnow
from datetime import timedelta

from conftest import upload_doc, upload_evidence


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


@pytest.fixture(autouse=True)
def _no_telegram(monkeypatch):
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


# --------------------- 1. Селфи курьера: только свой загруженный файл ---------------------

def test_courier_selfie_rejects_foreign_host(client, user_factory):
    """Ссылка на чужой сервер в заявке = токен админа уезжает туда при открытии модерации."""
    u = user_factory("Курьер с чужой ссылкой")
    r = client.post("/courier/apply", headers=u["auth"],
                    json={"transport": "car", "selfie_url": "https://evil.example/pixel.jpg"})
    assert r.status_code == 403, r.text


def test_courier_selfie_rejects_someone_elses_upload(client, user_factory):
    """Чужой (пусть и наш) документ подставить тоже нельзя — файл привязан к загрузившему."""
    victim = user_factory("Владелец документа")
    attacker = user_factory("Чужой курьер")
    stolen = upload_doc(client, victim["auth"])
    r = client.post("/courier/apply", headers=attacker["auth"],
                    json={"transport": "car", "selfie_url": stolen})
    assert r.status_code == 403, r.text


def test_courier_selfie_accepts_own_upload(client, user_factory):
    """Штатный путь (загрузил сам через /upload/photo) продолжает работать."""
    u = user_factory("Честный курьер")
    r = client.post("/courier/apply", headers=u["auth"],
                    json={"transport": "car", "selfie_url": upload_doc(client, u["auth"])})
    assert r.status_code == 200, r.text


def test_docs_check_asks_storage_not_the_disk(client, user_factory):
    """Проверка «файл существует» идёт в ХРАНИЛИЩЕ, а не прямо на диск.

    При STORAGE_BACKEND=s3 файла на диске нет вовсе, и прямая проверка диска отвечала бы
    «не найден» на каждую заявку водителя, таксиста и курьера. Мина не срабатывала только
    потому, что сегодня включён локальный диск (аудит 2026-08-08).
    """
    from app.storage import Storage, get_storage, reset_storage

    class RemoteOnly(Storage):
        """Как S3: файлы есть в хранилище, на диске — ничего."""
        is_remote = True

        def __init__(self):
            self.keys: set[str] = set()

        def save(self, key: str, data: bytes) -> None:
            self.keys.add(key)

        def load(self, key: str) -> bytes:
            if key not in self.keys:
                raise FileNotFoundError(key)
            return b"\xff\xd8\xfftest-jpeg"

        def exists(self, key: str) -> bool:
            return key in self.keys

        def delete(self, key: str) -> None:
            self.keys.discard(key)

        def url(self, key: str) -> str:
            return f"https://s3.example/{key}"

    previous = get_storage()
    reset_storage(RemoteOnly())
    try:
        u = user_factory("Курьер в облаке")
        url = upload_doc(client, u["auth"])          # уходит только в «облако», диск пуст
        r = client.post("/courier/apply", headers=u["auth"],
                        json={"transport": "car", "selfie_url": url})
        assert r.status_code == 200, r.text
    finally:
        reset_storage(previous)


# --------------------- 2. Ключ телефона: код страны входит в сравнение ---------------------

def test_phone_key_keeps_russian_forms_equal():
    """Ради чего правило и делалось: 8XXX, +7XXX и запись без кода — один человек."""
    assert _phone_key("+79991234567") == _phone_key("89991234567") == _phone_key("9991234567")
    assert _phone_key("+7 (999) 123-45-67") == _phone_key("+79991234567")


def test_phone_key_separates_countries():
    """Иностранный номер с теми же последними десятью цифрами — ДРУГОЙ человек."""
    assert _phone_key("+19991234567") != _phone_key("+79991234567")


def test_foreign_lookalike_number_does_not_get_admin(client, user_factory, monkeypatch):
    """Вход номером-двойником из другой страны не выдаёт роль администратора."""
    monkeypatch.setattr(settings, "admin_phones", "+79991234567")
    u = user_factory("Двойник")
    with Session(engine) as s:
        row = s.get(User, u["id"])
        row.phone = "+19991234567"          # тот же «хвост», другая страна
        s.add(row)
        s.commit()
    from app.routers.auth import _maybe_promote_admin
    with Session(engine) as s:
        row = s.get(User, u["id"])
        _maybe_promote_admin(s, row)
        s.commit()
        assert s.get(User, u["id"]).role != UserRole.admin


# --------------------- 3. Попытки кода считаются на НОМЕР, а не на код ---------------------

def test_otp_attempts_are_counted_per_phone(client):
    """Новый код больше не обнуляет счётчик: потолок общий на все живые коды номера."""
    from app.routers.auth import MAX_OTP_ATTEMPTS_PER_PHONE
    phone = "+79990009090"
    with Session(engine) as s:
        # Три живых кода номера, у каждого уже есть неудачные попытки — суммарно выше потолка.
        per_row = (MAX_OTP_ATTEMPTS_PER_PHONE // 3) + 1
        for i in range(3):
            s.add(OtpCode(phone=phone, code=f"11111{i}", attempts=per_row,
                          expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()
    r = client.post("/auth/verify", json={"phone": phone, "code": "000000"})
    assert r.status_code == 429, r.text


# --------------------- 4. Вебхук Telegram освобождён от лимитера ---------------------

def test_webhook_exempt_path_matches_the_real_route(client):
    """Путь в списке исключений и путь маршрута — одна строка, а не две похожие.

    Раньше в исключениях стоял «/auth/telegram/webhook», которого в приложении нет: список
    ссылался на опечатку, а настоящий вебхук считался обычным трафиком пользователя.
    """
    from app.middleware import _EXEMPT_PREFIXES

    assert client.post("/telegram/webhook", json={}).status_code != 404   # маршрут существует
    assert any("/telegram/webhook".startswith(p) for p in _EXEMPT_PREFIXES)
    assert not any(p.startswith("/auth/telegram") for p in _EXEMPT_PREFIXES)


# --------------------- 5. Живая страница поездки: подпись у внешних файлов ---------------------

def test_live_page_pins_cdn_files_with_integrity():
    """На публичной странице с координатами внешний скрипт обязан быть проверен по хешу."""
    from app.routers.share import _PAGE_HTML
    assert _PAGE_HTML.count("integrity=\"sha384-") == 2
    assert _PAGE_HTML.count("crossorigin=\"anonymous\"") == 2


# --------------------- 6. Модерация чата смотрит и расшифровку голосового ---------------------

def test_chat_moderation_covers_the_voice_transcript():
    """Расшифровку голосового показывают собеседнику наравне с текстом, а проверялся только
    `text` — номер телефона достаточно было положить в `transcript`, и он приезжал без метки."""
    from app.antifraud import MESSAGE_FLAG_CONTACT
    from app.routers.chat import MessageIn, _flag_for

    hidden = MessageIn(text="давай спишемся", transcript="мой номер +7 999 123-45-67")
    assert _flag_for(hidden, check_contact=True) == MESSAGE_FLAG_CONTACT
    # В попутке обмен номерами — норма (комиссии нет): метку не ставим, поведение прежнее.
    assert _flag_for(hidden, check_contact=False) == ""
    assert _flag_for(MessageIn(text="еду, буду через пять минут"), check_contact=True) == ""


def test_every_public_free_text_field_reaches_moderation():
    """Открытые поля проверяются ЦЕЛИКОМ, а не по одному «главному».

    Три места, где проверка смотрела одно поле из нескольких видимых, и обойти её можно было,
    переложив текст в соседнее (аудит 2026-08-08). Тест держит сами вызовы: если кто-то снова
    сузит проверку до одного поля, здесь станет красно.
    """

    root = pathlib.Path(__file__).resolve().parents[1] / "app" / "routers"

    def moderated_lines(name: str) -> list[str]:
        src = (root / name).read_text(encoding="utf-8")
        return [ln for ln in src.split("\n") if "moderate_open_text(" in ln and "def " not in ln]

    # Заявка: комментарий + расшифровка голосовой + имя близкого — одним текстом.
    assert any("_visible_request_text(body)" in ln for ln in moderated_lines("requests.py"))
    # Посылка и курьерский заказ: описание И имя получателя (оба едут в открытую ленту).
    assert any("receiver_name" in ln for ln in moderated_lines("parcels.py"))
    assert any("receiver_name" in ln for ln in moderated_lines("courier.py"))
    # Расписание водителя: комментарий отдаётся вообще без входа.
    assert any("body.comment" in ln for ln in moderated_lines("driver_schedule.py"))
    # Витрина купонов открыта без входа. Двери: бизнес — создание и правка; купон — создание
    # и правка. У купона проверка идёт через `_apply_review`: он не только помечает, но и
    # решает, выпускать ли текст в витрину (очередь модерации, 2026-08-08).
    coupons_src = (root / "coupons.py").read_text(encoding="utf-8")
    assert coupons_src.count("_apply_review(coupon, user.id") == 2    # купон: создание + правка
    assert coupons_src.count("_moderate_storefront(user.id") == 2     # бизнес: создание + правка


def test_geocode_query_is_clamped():
    """Запрос уезжает в КЛЮЧ кеша Redis — необрезанный мегабайт с суточным TTL там не нужен."""
    src = (pathlib.Path(__file__).resolve().parents[1] / "app" / "routers" / "discovery.py").read_text(encoding="utf-8")
    assert 'query = (q or "").strip()[:200]' in src


# --------------------- 7. Витрина водителя — только для водителей ---------------------

def test_driver_card_hides_plain_passengers(client, user_factory):
    """Витрина открыта без входа. По номеру обычного пассажира она отдавала его имя, фото,
    дату регистрации и отзывы — то есть позволяла перебрать всю базу по возрастанию id."""
    passenger = user_factory("Обычный пассажир", taxi_approved=False)
    r = client.get(f"/drivers/{passenger['id']}/public")
    assert r.status_code == 404, r.text


def test_driver_card_still_opens_for_a_real_driver(client, user_factory):
    """Штатный путь (тап по карточке поездки) не сломался."""
    drv = user_factory("Настоящий водитель", role=UserRole.driver)
    published = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        # Клиент шлёт МЕСТНОЕ время (сервер вычтет часовой пояс Уфы) — берём запас с избытком.
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    })
    assert published.status_code == 200, published.text
    r = client.get(f"/drivers/{drv['id']}/public")
    assert r.status_code == 200, r.text
    assert r.json()["id"] == drv["id"]


# --------------------- 7. Телеметрия без входа — свой бюджет ---------------------

def test_events_have_their_own_rate_budget(client, monkeypatch):
    """Единственная ручка, пишущая в базу без входа, не должна забивать диск."""
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(settings, "rate_limit_per_min", 10_000)   # общий заведомо не мешает
    monkeypatch.setattr(settings, "rate_limit_events_per_min", 3)
    ip = {"X-Real-IP": "203.0.113.77"}
    codes = [client.post("/events", json={"event": "ping"}, headers=ip).status_code
             for _ in range(6)]
    assert 429 in codes, codes


# --------------------- 8. Секретная ссылка не уезжает в Sentry ---------------------

def test_sentry_scrub_masks_the_live_link_token():
    """Токен в пути /t/{token} — ключ к живым координатам поездки. Sentry прикладывает
    полный адрес запроса сам, и `send_default_pii=False` от этого не спасает."""
    from app.observability import scrub_text
    dirty = "GET https://yulbash.ru/t/Aa1Bb2Cc3Dd4Ee5Ff6Gg7/state.json → 500"
    clean = scrub_text(dirty)
    assert "Aa1Bb2Cc3Dd4Ee5Ff6Gg7" not in clean
    assert "/t/***" in clean


# --------------------- 9. Ключ геокодера не уезжает в приложение ---------------------

def test_apk_does_not_ship_geocoder_key():
    """Ключ платного геокодера живёт только на сервере (клиент ходит через /geocode)."""

    gradle = pathlib.Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"
    if not gradle.exists():          # облачная сессия без папки android — пропускаем
        pytest.skip("android/ недоступна")
    text = gradle.read_text(encoding="utf-8")
    assert 'buildConfigField("String", "YANDEX_GEOCODER_KEY"' not in text


# base64 нужен conftest.upload_doc; держим импорт «живым» для линтера
assert base64 is not None


# --------------------- 10. Лист ожидания: телефон с сайта — тоже перс.данные ---------------------
# Форма «ранний доступ» на лендинге собирает НОМЕР у человека, у которого нет аккаунта.
# Таблицы не было ни в ретеншене, ни в удалении аккаунта, и удалить строку было нечем —
# номер лежал вечно (аудит 2026-08-08, 152-ФЗ ст. 5 п. 7 и ст. 14).

def test_admin_can_remove_a_number_from_the_waitlist(client, user_factory):
    """Человек просит убрать номер — у Александра должна быть кнопка, а не поход в базу."""
    admin = user_factory("АдминЛиста", role=UserRole.admin)
    assert client.post("/waitlist", json={"phone": "+79990007777", "city": "Баймак"}).status_code == 200
    rows = client.get("/admin/waitlist", headers=admin["auth"]).json()["items"]
    row = next(r for r in rows if r["phone"] == "+79990007777")

    assert client.delete(f"/admin/waitlist/{row['id']}", headers=admin["auth"]).status_code == 200
    left = client.get("/admin/waitlist", headers=admin["auth"]).json()["items"]
    assert all(r["phone"] != "+79990007777" for r in left)
    # Идемпотентно: повторное удаление не 404 — «уже удалили» это успех, а не ошибка.
    assert client.delete(f"/admin/waitlist/{row['id']}", headers=admin["auth"]).status_code == 200


def test_waitlist_removal_is_admin_only(client, user_factory):
    """Чужой номер из списка посторонний удалить не может."""
    stranger = user_factory("Посторонний")
    assert client.delete("/admin/waitlist/1", headers=stranger["auth"]).status_code == 403


def test_waitlist_has_a_retention_rule():
    """Список не хранится вечно: у обеих веток (позвали / так и не позвали) есть срок."""
    from app.cleanup import WAITLIST_INVITED_DAYS, WAITLIST_STALE_DAYS, _ALLOWED_TABLES, _rules
    from app.timeutil import utcnow

    assert "waitlistentry" in _ALLOWED_TABLES
    labels = [label for label, table, _w, _p in _rules(utcnow()) if table == "waitlistentry"]
    assert len(labels) == 2, labels
    assert 0 < WAITLIST_INVITED_DAYS < WAITLIST_STALE_DAYS


# --------------------- 11. Жалоба на человека: поток к админу ограничен ---------------------

def test_reports_have_a_ceiling_per_person(client, user_factory, monkeypatch):
    """Тяжёлая жалоба дёргает Telegram Александра. Дедуп держит «один автор — одна жалоба
    по одному поводу», но повод включает ЦЕЛЬ: меняя target_user_id, один человек создаёт
    сколько угодно разных жалоб — и столько же сообщений (аудит 2026-08-08)."""
    from app.routers.safety import MAX_REPORTS_PER_HOUR

    sent = []
    monkeypatch.setattr("app.services.notify_admin_telegram", lambda *a, **k: sent.append(1))
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    author = user_factory("Жалобщик на всех")
    targets = [user_factory(f"Цель{i}")["id"] for i in range(MAX_REPORTS_PER_HOUR + 3)]

    codes = [client.post("/reports", headers=author["auth"],
                         json={"target_user_id": t, "category": "safety_threat",
                               "reason": "проверка"}).status_code
             for t in targets]
    assert codes.count(200) == MAX_REPORTS_PER_HOUR, codes
    assert 429 in codes, codes


def test_repeat_tap_on_the_same_report_does_not_eat_the_budget(client, user_factory, monkeypatch):
    """Повторный тап по той же жалобе идемпотентен и бюджет не тратит — иначе человек на
    слабой связи (жмёт второй раз, «ничего не произошло») ловил бы 429 за свою же жалобу."""
    monkeypatch.setattr("app.services.notify_admin_telegram", lambda *a, **k: None)
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    author = user_factory("Настойчивый заявитель")
    target = user_factory("Одна цель")["id"]
    body = {"target_user_id": target, "category": "rude", "reason": "нагрубил"}

    first = client.post("/reports", headers=author["auth"], json=body)
    assert first.status_code == 200, first.text
    for _ in range(30):                       # много раз больше потолка — всё та же жалоба
        again = client.post("/reports", headers=author["auth"], json=body)
        assert again.status_code == 200, again.text
        assert again.json()["id"] == first.json()["id"]


# --------------------- 12. «Только женщины» — теперь ПРАВИЛО, а не пожелание ---------------------
# Волна 7 зафиксировала факт: отметку никто не проверял, потому что пола пассажира на сервере
# не было вовсе. Александр выбрал «проверять по-настоящему» — пол переехал на User, и правило
# стоит у обеих сторон. Эти тесты держат ОБЕЩАНИЕ целиком: за рулём женщина И в салоне женщины.

def _women_ride(client, drv) -> int:
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_woman_can_book_a_women_only_ride(client, user_factory):
    """Ради кого всё делалось: женщина бронирует и едет."""
    drv = user_factory("Водитель-женщина", role=UserRole.driver, gender="female")
    rid = _women_ride(client, drv)
    pax = user_factory("Пассажирка", gender="female")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text


def test_man_cannot_book_a_women_only_ride(client, user_factory):
    """То, что раньше проходило с кодом 200."""
    drv = user_factory("Водитель-женщина 2", role=UserRole.driver, gender="female")
    rid = _women_ride(client, drv)
    man = user_factory("Мужчина", gender="male")
    b = client.post("/bookings", headers=man["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 403, b.text
    assert b.json()["detail"]["ru"] and b.json()["detail"]["ba"]


def test_unknown_gender_gets_a_way_out_not_a_wall(client, user_factory):
    """Пол не указан → не пускаем (иначе обещание пустое), но говорим, ЧТО СДЕЛАТЬ.

    И проверяем весь путь: человек указывает пол в профиле — и бронь проходит.
    """
    drv = user_factory("Водитель-женщина 3", role=UserRole.driver, gender="female")
    rid = _women_ride(client, drv)
    pax = user_factory("Без пола")

    denied = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert denied.status_code == 403
    assert "профил" in denied.json()["detail"]["ru"].lower()

    assert client.post("/me/update", headers=pax["auth"], json={"gender": "female"}).status_code == 200
    ok = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert ok.status_code == 200, ok.text


def test_only_a_woman_can_respond_to_a_women_only_request(client, user_factory):
    """Зеркало правила со стороны пассажира: заявка «только женщины» — отклик от женщины."""
    pax = user_factory("Пассажирка с заявкой", gender="female")
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "women_only": True,
    })
    assert req.status_code == 200, req.text
    rid = req.json()["id"]

    man = user_factory("Водитель-мужчина", role=UserRole.driver, gender="male")
    bad = client.post(f"/requests/{rid}/respond", headers=man["auth"], json={"price": 300})
    assert bad.status_code == 403, bad.text

    woman = user_factory("Водитель-женщина 4", role=UserRole.driver, gender="female")
    good = client.post(f"/requests/{rid}/respond", headers=woman["auth"], json={"price": 300})
    assert good.status_code == 200, good.text


def test_gender_never_leaks_to_other_people(client, user_factory):
    """Пол — личное. Наружу идёт только сигнал «женщина за рулём», сам пол не отдаём никому."""
    drv = user_factory("Водитель-женщина 5", role=UserRole.driver, gender="female")
    rid = _women_ride(client, drv)
    stranger = user_factory("Посторонний", gender="male")

    card = client.get(f"/rides/{rid}", headers=stranger["auth"])
    assert card.status_code == 200
    body = card.json()
    assert "gender" not in body and "driver_gender" not in body
    assert body.get("driver_is_woman") is True          # полезный сигнал остаётся

    public = client.get(f"/drivers/{drv['id']}/public")
    assert public.status_code == 200
    assert "gender" not in public.json()

# --------------------- 13. Волна 9: чужое фото-доказательство читалось посторонним ---------------------
# Найдено запросом, а не глазами. Фото-доказательства — самое чувствительное в проекте: лица,
# травмы, номера машин. Читать их можно сторонам спора, к которому файл приложен. Но на ВХОДЕ
# проверялось только «ссылка на наш хост», а не «файл твой». Полная цепочка, доступная любому:
#   1) забронировать любую поездку любого водителя (это открыто всем);
#   2) подать по ней спор, вписав в evidence_urls ЧУЖОЕ имя файла;
#   3) стать «стороной спора с этим файлом» → GET /secure/evidence/{name} отвечает 200.
# Побочно страдал и невиновный водитель: против него оставался выдуманный спор.

def _ride_and_booking(client, user_factory, pax):
    """Настоящая совместная поездка: ровно то, что делает любой пассажир."""
    drv = user_factory("Водитель для спора", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    })
    assert r.status_code == 200, r.text
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": r.json()["id"], "seats": 1})
    assert b.status_code == 200, b.text
    return drv, b.json()["id"]


def test_evidence_upload_is_bound_to_the_uploader(client, user_factory):
    """Имя приватного снимка начинается с id загрузившего — по нему сервер и отличает своё."""
    u = user_factory("Загрузил фото")
    name = upload_evidence(client, u["auth"]).rsplit("/", 1)[-1]
    assert name.startswith(f"{u['id']}_"), name


def test_stranger_cannot_attach_and_read_someone_elses_photo(client, user_factory):
    """Та самая цепочка целиком: сорваться должна на подаче спора, а файл остаться закрытым."""
    victim = user_factory("Жертва фото")
    url = upload_evidence(client, victim["auth"])
    name = url.rsplit("/", 1)[-1]

    stranger = user_factory("Посторонний")
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403

    drv, booking_id = _ride_and_booking(client, user_factory, stranger)
    denied = client.post("/incidents", headers=stranger["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": booking_id,
        "description": "Повод выдуман — нужен доступ к чужому фото",
        "evidence_urls": [url],
    })
    assert denied.status_code == 403, denied.text
    assert denied.json()["detail"]["ru"] and denied.json()["detail"]["ba"]

    # И главное: файл по-прежнему закрыт.
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403


def test_own_photo_still_works_end_to_end(client, user_factory):
    """Починка не должна ломать нормальный разбор: своё фото прикладывается и открывается."""
    pax = user_factory("Пассажир со снимком")
    drv, booking_id = _ride_and_booking(client, user_factory, pax)
    url = upload_evidence(client, pax["auth"])

    inc = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": booking_id,
        "description": "Нахамил в дороге", "evidence_urls": [url],
    })
    assert inc.status_code == 200, inc.text
    name = url.rsplit("/", 1)[-1]
    assert client.get(f"/secure/evidence/{name}", headers=pax["auth"]).status_code == 200

    # Обвинённый видит приложенное к спору фото — это и есть смысл двустороннего разбора.
    assert client.get(f"/secure/evidence/{name}", headers=drv["auth"]).status_code == 200

    # А своё объяснение он вправе подкрепить СВОИМ снимком — и только своим.
    his = upload_evidence(client, drv["auth"])
    ok = client.post(f"/incidents/{inc.json()['id']}/respond", headers=drv["auth"],
                     json={"statement": "Было не так", "evidence_urls": [his]})
    assert ok.status_code == 200, ok.text
    bad = client.post(f"/incidents/{inc.json()['id']}/respond", headers=drv["auth"],
                      json={"statement": "И вот ещё", "evidence_urls": [upload_evidence(client, pax["auth"])]})
    assert bad.status_code == 403, bad.text


def test_courier_cannot_pass_off_a_foreign_photo_as_proof(client, user_factory):
    """Фото «взял целой» — граница ответственности. Чужой снимок в неё не встаёт.

    Тут вред другой: админ (он видит любые файлы) в разборе смотрел бы на снимок,
    который курьер не делал.
    """
    from test_courier import _make_courier, _order
    courier = _make_courier(client, user_factory)
    outsider = user_factory("Чужой снимок")
    foreign = upload_evidence(client, outsider["auth"])

    sender = user_factory("Отправитель")
    order = _order(client, sender)
    assert order.status_code == 200, order.text
    pid = order.json()["id"]

    denied = client.post(f"/parcels/{pid}/accept", headers=courier["auth"],
                         json={"pickup_photo_url": foreign})
    assert denied.status_code == 403, denied.text
