"""Сервер сам ужимает фото — правило не должно жить только в приложении.

История. Снимок с камеры весит семь-восемь мегабайт. Наше приложение пережимает его перед
отправкой (волна 88), и это правильно. Но правило, которое живёт только в клиенте, защищает
лишь тех, кто обновился: веб-версия шлёт файл как есть, старая сборка тоже, а к API можно
прийти и напрямую.

Сервер принимал такие файлы и отдавал ровно такими же — до десяти мегабайт (аудит 2026-08-08,
волна 97). Дальше фото едет в ленту, в чат, в карточку профиля: качает его каждый, кто открыл
экран, а не только тот, кто отправил. На сельском интернете это чужой трафик и зависший экран.

Так уже было решено с метаданными снимка (волна 21): «наш Android их срезает, но правило
должно жить на сервере». С весом — та же история.

Границы: пережатие не должно портить документы (модератор читает номер и даты) и не должно
трогать то, что уже маленькое.
"""
from __future__ import annotations

import io

import pytest
from PIL import Image

from app.models import UserRole


def _photo(width: int, height: int, quality: int = 95) -> bytes:
    """Похоже на снимок с камеры: шум, чтобы JPEG не схлопнулся в килобайт."""
    img = Image.new("RGB", (width, height))
    for x in range(0, width, 7):
        for y in range(0, height, 7):
            img.putpixel((x, y), ((x * 7) % 256, (y * 13) % 256, (x + y) % 256))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def _upload(client, who, data: bytes, name: str = "photo.jpg"):
    return client.post("/upload/chat-photo", headers=who["auth"],
                       files={"file": (name, data, "image/jpeg")})


def _fetch(client, url: str) -> tuple[int, tuple[int, int]]:
    got = client.get(url)
    assert got.status_code == 200, got.status_code
    with Image.open(io.BytesIO(got.content)) as img:
        return len(got.content), img.size


@pytest.fixture
def driver(user_factory):
    return user_factory("ФотоВодитель", role=UserRole.driver)


def test_снимок_с_камеры_ужимается_на_сервере(client, driver):
    raw = _photo(4000, 3000)
    assert len(raw) > 3 * 1024 * 1024, "тестовый снимок оказался слишком лёгким"

    r = _upload(client, driver, raw)
    assert r.status_code == 200, r.text

    size, (w, h) = _fetch(client, r.json()["url"])
    assert size < len(raw) / 3, f"фото почти не ужалось: было {len(raw)}, стало {size}"
    assert max(w, h) <= 1600, f"осталось {w}x{h} — в ленте это лишние мегабайты у каждого"


def test_документ_остаётся_читаемым(client, driver):
    """Модератор должен разобрать номер и даты в правах — сильнее ужимать нельзя."""
    r = _upload(client, driver, _photo(2400, 1800), name="license.jpg")
    assert r.status_code == 200, r.text

    _, (w, h) = _fetch(client, r.json()["url"])
    assert max(w, h) >= 1400, f"документ ужали до {w}x{h} — мелкий шрифт поплывёт"


def test_маленькое_фото_не_трогаем(client, driver):
    """Аватар 600x600 пережимать незачем: только потеряем качество на ровном месте."""
    small = _photo(600, 600, quality=90)

    r = _upload(client, driver, small)
    assert r.status_code == 200, r.text

    size, (w, h) = _fetch(client, r.json()["url"])
    assert (w, h) == (600, 600), f"маленькое фото зачем-то пересобрали: {w}x{h}"
    assert size == len(small), "байты изменились, хотя пережимать было нечего"


def test_битый_файл_не_роняет_загрузку(client, driver):
    """Не разобрали картинку — сохраняем как есть: загрузка важнее идеального веса."""
    broken = bytes.fromhex("ffd8ffe000104a46494600010100000100010000") + b"\x00" * 5000

    r = _upload(client, driver, broken)

    assert r.status_code in (200, 400), r.status_code   # либо приняли как есть, либо честно отвергли
