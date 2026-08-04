"""Контракт «приложение ↔ сервер»: каждый адрес, который зовёт Android, есть на бэкенде.

Зачем этот тест существует.

Клиент и сервер живут в одном репозитории, но собираются раздельно: Kotlin ничего не знает
про FastAPI. Если ручку на сервере переименовали (`/parcels/{id}/release` → `/parcels/{id}/handover`),
а в приложении строку забыли поправить — **всё соберётся и все тесты позеленеют**. Пользователь
узнает об этом первым: кнопка молча вернёт 404. Живой пример класса ошибки: приложение уже
однажды показывало «Заявок пока нет» вместо «сервер недоступен».

Тест читает `ApiClient.kt`, вынимает оттуда все адреса и сверяет со списком маршрутов FastAPI.
Подстановки нормализуются: Kotlin `/rides/$id` и FastAPI `/rides/{ride_id}` — это один адрес.

Если Android-исходников рядом нет (бэкенд выкачен отдельно) — тест пропускается, а не падает.
"""
from __future__ import annotations

import pathlib
import re

import pytest

from app.main import app

# backend/tests/test_api_contract_android.py → корень репозитория на два уровня выше backend/
_REPO = pathlib.Path(__file__).resolve().parents[2]
_API_CLIENT = _REPO / "android" / "app" / "src" / "main" / "java" / "com" / "yuldash" / "app" / "data" / "ApiClient.kt"

# Адреса, которых на бэкенде нет намеренно (внешние сервисы, заглушки).
_ALLOWED_MISSING: set[str] = set()


def _normalize(path: str) -> str:
    """`/rides/$id` и `/rides/{ride_id}` → `/rides/{}` — сравниваем форму, а не имена переменных."""
    path = path.split("?", 1)[0].rstrip("/")
    path = re.sub(r"\$\{[^}]*\}", "{}", path)   # ${bookingId}
    path = re.sub(r"\$[A-Za-z_]\w*", "{}", path)  # $id
    path = re.sub(r"\{[^}]*\}", "{}", path)      # {ride_id} со стороны FastAPI
    # Хвост запроса, приклеенный к последнему сегменту: в Kotlin строку строят как
    # "/driver/rides$q", где $q — это "?limit=10". После замены получается "/driver/rides{}",
    # хотя адрес — "/driver/rides". Отличаем по слэшу: подстановка id всегда идёт после "/".
    path = re.sub(r"(?<=[^/]){}$", "", path)
    return path or "/"


def _android_paths() -> set[str]:
    src = _API_CLIENT.read_text(encoding="utf-8")
    found: set[str] = set()
    # 1) call("GET", "/rides/$id", ...) и callMultipart("POST", "/x", ...)
    for m in re.finditer(r'call(?:Multipart)?\(\s*"(?:GET|POST|PUT|PATCH|DELETE)",\s*"([^"]+)"', src):
        found.add(_normalize(m.group(1)))
    # 2) путь собран заранее: val path = "/coupons" + ...
    for m in re.finditer(r'val path = "(/[^"]*)"', src):
        found.add(_normalize(m.group(1)))
    # 3) WebSocket-адреса (wsBase() + "/ws/...") — на сервере это тоже маршруты
    for m in re.finditer(r'wsBase\(\)\s*\+\s*"([^"]+)"', src):
        found.add(_normalize(m.group(1)))
    return {p for p in found if p.startswith("/")}


def _walk_routes(routes, prefix: str = ""):
    """Обходит маршруты FastAPI вглубь.

    С версии 0.135 `include_router` не копирует маршруты сразу, а кладёт в `app.routes`
    обёртку `_IncludedRouter` — у неё нет своего `path`. Поэтому идём внутрь и по дороге
    накапливаем префикс (у нас каждый роутер подключён дважды: на корень и под `/api/v1`).
    """
    for r in routes:
        path = getattr(r, "path", None)
        if path:
            yield prefix + path
        original = getattr(r, "original_router", None)
        if original is not None:
            ctx = getattr(r, "include_context", None)
            yield from _walk_routes(original.routes, prefix + (getattr(ctx, "prefix", "") or ""))


def _server_paths() -> set[str]:
    return {_normalize(p) for p in _walk_routes(app.routes)}


requires_android = pytest.mark.skipif(
    not _API_CLIENT.exists(),
    reason="Android-исходников рядом нет — контракт проверять не по чему",
)


@requires_android
def test_каждый_адрес_из_приложения_есть_на_сервере():
    """Приложение не должно звать несуществующие ручки: это молчаливый 404 у пользователя."""
    missing = sorted(_android_paths() - _server_paths() - _ALLOWED_MISSING)
    assert not missing, (
        "Приложение зовёт адреса, которых на сервере нет — кнопка вернёт 404:\n  "
        + "\n  ".join(missing)
    )


@requires_android
def test_разбор_адресов_вообще_работает():
    """Страховка от «зелено, потому что ничего не нашли»: если регулярка перестанет
    цеплять вызовы, первый тест позеленеет на пустом множестве и мы этого не заметим."""
    paths = _android_paths()
    assert len(paths) > 150, f"Из ApiClient.kt вынули всего {len(paths)} адресов — разбор сломался"


def test_нормализация_приводит_подстановки_к_одному_виду():
    assert _normalize("/rides/$id") == "/rides/{}"
    assert _normalize("/bookings/${bookingId}/confirm") == "/bookings/{}/confirm"
    assert _normalize("/rides/{ride_id}") == "/rides/{}"
    assert _normalize("/coupons?city=%D0%A3%D1%84%D0%B0") == "/coupons"
