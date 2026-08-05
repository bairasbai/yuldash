"""Закрыта ли КАЖДАЯ админская ручка от обычного пользователя.

Почему это отдельный сторож. Проверка «ты админ?» стоит не в одном месте, а внутри каждой
ручки по отдельности — таких ручек под сотню. Забыть одну строку легко, и снаружи это никак
не видно: экран у обычного человека админку не показывает, приложение туда не ходит, все
тесты зелёные. Но адрес открыт, и запрос по нему шлётся из чего угодно.

Цена ошибки максимальная. За админскими адресами лежат: паспортные селфи и справки о
несудимости водителей, все жалобы и SOS с координатами, разбор споров, ручное одобрение
таксистов, деньги (кошельки, долги, выплаты), рассылки. Одна забытая строка = чужой человек
одобряет себе таксиста, читает документы соседей и закрывает жалобы на себя.

Тест не проверяет конкретные ручки списком — он САМ находит все адреса, начинающиеся
на `/admin`, и проверяет каждый. Новая админская ручка попадает под охрану автоматически,
без правки этого файла.

Две проверки, потому что одной мало:
  • живая — обычный пользователь дёргает адрес и не должен получить 200;
  • по исходникам — в теле обработчика обязана быть проверка роли. Нужна потому, что
    FastAPI проверяет тело запроса ДО кода ручки: без неё пустое тело даёт 422, и живая
    проверка зеленеет, не дойдя до самой проверки роли.
"""
from __future__ import annotations

import inspect
import re

import pytest

from app.main import app

# Как в этом проекте пишут «только для админа». Единого хелпера нет: где-то сравнение роли
# прямо в ручке, где-то локальная функция-страж (`_require_admin`, `_guard_admin`). Ловим оба
# вида по форме, а не списком имён — иначе новый роутер со своим стражем даст ложное падение.
_ADMIN_CHECK = re.compile(r"UserRole\.admin|\b_?(?:require|guard|ensure)_admin\s*\(")

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
        if 200 <= r.status_code < 300:
            opened.append(f"{method} {path} → {r.status_code} {r.text[:120]}")
    assert not opened, (
        "Обычный пользователь попал в админку — это чужие документы, жалобы и деньги:\n  "
        + "\n  ".join(opened)
    )


def test_контроль_админ_в_админку_попадает(client, user_factory):
    """Контрольный случай к тесту выше. Без него проверка ничего не стоит: если бы админские
    адреса были сломаны для всех, «обычный не получает 200» было бы верно по неправильной причине."""
    from app.models import UserRole
    admin = user_factory("RealAdmin", role=UserRole.admin)
    r = client.get("/admin/drivers/pending", headers=admin["auth"])
    assert r.status_code == 200, (
        f"админ не смог открыть админку: {r.status_code} {r.text[:200]} — "
        "значит проверка «обычный не может» ничего не доказывает"
    )


@pytest.mark.parametrize(
    "method, path, endpoint",
    ADMIN_ROUTES,
    ids=[f"{m} {p}" for m, p, _ in ADMIN_ROUTES],
)
def test_в_каждой_админской_ручке_есть_проверка_роли(method, path, endpoint):
    """Проверка по исходникам — она ловит то, что живая пропускает.

    FastAPI разбирает тело запроса ДО того, как начнёт выполняться код ручки. Значит ручка
    без проверки роли, но с обязательным телом, ответит обычному пользователю 422, и живой
    тест сочтёт её закрытой. Здесь смотрим прямо в код: строка про роль обязана быть."""
    if path in _INTENTIONALLY_OPEN:
        pytest.skip(_INTENTIONALLY_OPEN[path])
    assert endpoint is not None, f"{method} {path}: у маршрута нет обработчика — разбор сломался"
    src = inspect.getsource(endpoint)
    assert _ADMIN_CHECK.search(src), (
        f"{method} {path}: в обработчике `{endpoint.__name__}` нет проверки роли админа. "
        f"Ждали сравнение с UserRole.admin или вызов *_admin(...). Пока её нет, адрес открыт "
        "всем — а за ним документы водителей, жалобы и деньги."
    )
