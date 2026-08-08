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

import pytest
from sqlmodel import Session

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


# --------------------- 6. Ключ геокодера не уезжает в приложение ---------------------

def test_apk_does_not_ship_geocoder_key():
    """Ключ платного геокодера живёт только на сервере (клиент ходит через /geocode)."""
    import pathlib
    gradle = pathlib.Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"
    if not gradle.exists():          # облачная сессия без папки android — пропускаем
        pytest.skip("android/ недоступна")
    text = gradle.read_text(encoding="utf-8")
    assert 'buildConfigField("String", "YANDEX_GEOCODER_KEY"' not in text


# base64 нужен conftest.upload_doc; держим импорт «живым» для линтера
assert base64 is not None
