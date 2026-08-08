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
from sqlmodel import Session, select

from app.config import _phone_key, settings
from app.db import engine
from app.models import OtpCode, User, UserRole
from app.timeutil import utcnow
from datetime import timedelta

from conftest import upload_doc


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


# --------------------- 12. «Только женщины» на попутке: гейт — водитель ---------------------

def test_women_only_ride_is_not_enforced_by_the_server_but_the_driver_is_warned(client, user_factory):
    """Честная фиксация того, как оно устроено НА САМОМ ДЕЛЕ.

    Пола пассажира на сервере нет вообще — ни поля, ни вопроса при регистрации, значит
    проверить отметку «только женщины» на попутке нечем (в такси иначе: там сверяется пол
    ВОДИТЕЛЯ, и он opt-in). Настоящий гейт — подтверждение брони водителем, поэтому в пуше
    ему явно сказано, что поездка помечена (аудит 2026-08-08).

    Тест держит два факта сразу: бронь не отклоняется (иначе сломали бы поездки людям,
    у которых пол неизвестен) И водитель получает предупреждение.
    """
    from app.models import Notification

    drv = user_factory("Женщина за рулём", role=UserRole.driver)
    ride = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert ride.status_code == 200 and ride.json()["women_only"] is True

    pax = user_factory("Пассажир без пола")
    booked = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride.json()["id"], "seats": 1})
    assert booked.status_code == 200, booked.text          # сервер НЕ отклоняет: проверять нечем

    with Session(engine) as s:
        notes = s.exec(
            select(Notification).where(Notification.user_id == drv["id"],
                                       Notification.type == "booking")
        ).all()
    assert notes, "водителю не пришло уведомление о брони"
    assert any("только женщины" in (n.body_ru or "") for n in notes), \
        "водителя не предупредили, что поездка помечена «только женщины»"
