"""Проверяем все /admin-маршруты через зависимость Telegram-администратора."""
from __future__ import annotations

import inspect
import re

import pytest
from sqlmodel import Session

from app.db import engine
from app.main import app
from app.models import AuthSession, UserRole
from app.security import _decode, make_token, require_telegram_admin
from app.timeutil import utcnow

# Ручки под /admin, которым проверка роли НЕ нужна, с объяснением почему.
# Пусто — и пусть таким остаётся: каждая запись здесь это дырка, открытая осознанно.
_INTENTIONALLY_OPEN: dict[str, str] = {}


def _walk(routes, prefix: str = ""):
    """Обход маршрутов вглубь: с FastAPI 0.135 `include_router` кладёт обёртку без `path`."""
    for r in routes:
        path = getattr(r, "path", None)
        if path:
            yield prefix + path, r
        original = getattr(r, "original_router", None)
        if original is not None:
            ctx = getattr(r, "include_context", None)
            yield from _walk(original.routes, prefix + (getattr(ctx, "prefix", "") or ""))


def _admin_routes() -> list[tuple[str, str, object]]:
    """(метод, адрес, обработчик) для всех ручек, начинающихся на /admin. Без дублей:
    каждый роутер подключён дважды — на корень и под /api/v1, ручка та же самая."""
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str, object]] = []
    for path, route in _walk(app.routes):
        bare = path[len("/api/v1"):] if path.startswith("/api/v1") else path
        if not bare.startswith("/admin"):
            continue
        endpoint = getattr(route, "endpoint", None)
        for method in sorted(getattr(route, "methods", None) or []):
            if method in ("HEAD", "OPTIONS"):
                continue
            key = (method, bare)
            if key in seen:
                continue
            seen.add(key)
            out.append((method, bare, endpoint))
    return out


ADMIN_ROUTES = _admin_routes()


def test_админские_адреса_вообще_нашлись():
    """Страховка от «зелено, потому что список пустой»: если обход маршрутов сломается,
    обе проверки ниже пройдут по нулю ручек и мы этого не заметим."""
    assert len(ADMIN_ROUTES) >= 50, (
        f"Нашли всего {len(ADMIN_ROUTES)} админских ручек — обход маршрутов сломался, "
        "проверки ниже ничего не сторожат"
    )


def test_обычный_пользователь_не_получает_200_ни_на_одной_админской_ручке(client, user_factory):
    """Живая проверка: заходим обычным человеком и стучимся во все админские двери подряд."""
    user = user_factory("NotAnAdmin")
    opened: list[str] = []
    for method, path, _ in ADMIN_ROUTES:
        if path in _INTENTIONALLY_OPEN:
            continue
        url = re.sub(r"\{[^}]*\}", "1", path)      # /admin/drivers/{user_id}/moderate → /admin/drivers/1/moderate
        r = client.request(method, url, headers=user["auth"], json={})
        if r.status_code != 403:
            opened.append(f"{method} {path} → {r.status_code} {r.text[:120]}")
    assert not opened, (
        "Обычный пользователь попал в админку — это чужие документы, жалобы и деньги:\n  "
        + "\n  ".join(opened)
    )


def test_контроль_админ_в_админку_попадает(client, user_factory):
    """Контрольный случай к тесту выше. Без него проверка ничего не стоит: если бы админские
    адреса были сломаны для всех, «обычный не получает 200» было бы верно по неправильной причине."""
    admin = user_factory("RealAdmin", role=UserRole.admin)
    r = client.get("/admin/drivers/pending", headers=admin["auth"])
    assert r.status_code == 200, (
        f"админ не смог открыть админку: {r.status_code} {r.text[:200]} — "
        "значит проверка «обычный не может» ничего не доказывает"
    )


def test_sms_сессия_админа_не_проходит_ни_одну_админскую_ручку(client, user_factory):
    admin = user_factory("AdminWithoutTelegramProof", role=UserRole.admin)
    with Session(engine) as session:
        login = session.get(AuthSession, _decode(admin["token"])["sid"])
        token = make_token(admin["id"], auth_session=login, auth_method="sms", auth_time=utcnow())
    unexpected = []
    for method, path, _ in ADMIN_ROUTES:
        if path in _INTENTIONALLY_OPEN:
            continue
        response = client.request(method, re.sub(r"\{[^}]*\}", "1", path),
                                  headers={"Authorization": f"Bearer {token}"}, json={})
        if response.status_code != 403 or response.json()["detail"]["code"] != "telegram_login_required":
            unexpected.append((method, path, response.status_code))
    assert not unexpected


@pytest.mark.parametrize(
    "method, path, endpoint",
    ADMIN_ROUTES,
    ids=[f"{m} {p}" for m, p, _ in ADMIN_ROUTES],
)
def test_каждая_админская_ручка_требует_telegram(method, path, endpoint):
    if path in _INTENTIONALLY_OPEN:
        pytest.skip(_INTENTIONALLY_OPEN[path])
    assert endpoint is not None, (method, path)
    assert any(
        getattr(parameter.default, "dependency", None) is require_telegram_admin
        for parameter in inspect.signature(endpoint).parameters.values()
    ), f"{method} {path}: нужна зависимость require_telegram_admin"
