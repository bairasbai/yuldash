"""Первый запуск: у человека ещё ничего нет — и всё должно работать.

Самый частый первый опыт: человек скачал приложение, вошёл по номеру и открывает вкладки.
Поездок нет, броней нет, заявок нет, уведомлений нет, рейтинга нет. Каждый личный список
обязан ответить «пусто» — спокойно и успешно.

Почему это не мелочь. Ошибка вместо пустого списка на первом же экране читается как
«приложение сломано», и человек уходит навсегда — он ещё ничего не вложил, чтобы разбираться.
А написать список так, что он падает на пустоте, легче всего: разработчик всегда смотрит
на свои данные, у него-то поездки есть.

Тест сам находит личные списки (адреса без номера внутри, требующие входа) и зовёт каждый
от имени человека, у которого нет вообще ничего.
"""
from __future__ import annotations

import inspect

import pytest

from app.main import app
from app.models import UserRole

# Ручки, которые новичку законно отвечают отказом, с причиной. Каждая строка — обдуманное
# исключение: «нет прав» и «нет объекта» это нормальные ответы, а не поломка.
_OK_TO_REFUSE = {
    "/driver/debt", "/driver/earnings",        # деньги таксиста — только одобренному таксисту
    "/instant/workday", "/instant/driver/offer",
    "/courier/available", "/courier/me", "/courier/earnings", "/courier/application",
    "/courier/priority",         # приоритет курьера — только курьеру, как и всё остальное в режиме
    "/wallet", "/wallet/payout/requisite",
    "/ads/stats",                # статистика рекламы — только рекламодателю
    "/instant/demand",           # спрос виден водителю на линии (иначе это карта чужого спроса)
    "/taxi/application",         # заявки таксиста ещё нет → 404, приложение это понимает
}


def _walk(routes, prefix: str = ""):
    for r in routes:
        path = getattr(r, "path", None)
        if path:
            yield prefix + path, r
        original = getattr(r, "original_router", None)
        if original is not None:
            ctx = getattr(r, "include_context", None)
            yield from _walk(original.routes, prefix + (getattr(ctx, "prefix", "") or ""))


def _personal_lists() -> list[str]:
    """GET-адреса без номера в пути, требующие входа — то есть «мои» списки и экраны."""
    seen: set[str] = set()
    out: list[str] = []
    for path, route in _walk(app.routes):
        bare = path[len("/api/v1"):] if path.startswith("/api/v1") else path
        if bare in seen or bare.startswith("/admin") or "{" in bare:
            continue
        if "GET" not in (getattr(route, "methods", None) or set()):
            continue
        seen.add(bare)
        endpoint = getattr(route, "endpoint", None)
        if endpoint is None:
            continue
        try:
            src = inspect.getsource(endpoint)
        except (OSError, TypeError):
            continue
        if "current_user" not in src:      # публичная витрина — не про первый запуск
            continue
        # Ручки с ОБЯЗАТЕЛЬНЫМИ параметрами запроса (координаты, номер заявки) — это не
        # «мой список», а расчёт по входным данным. Звать их без параметров бессмысленно:
        # 422 будет означать «тест позвал неправильно», а не «экран сломался у новичка».
        try:
            sig = inspect.signature(endpoint)
        except (ValueError, TypeError):
            continue
        required = [
            name for name, prm in sig.parameters.items()
            if prm.default is inspect.Parameter.empty and name not in ("request", "background")
        ]
        if required:
            continue
        out.append(bare)
    return sorted(out)


PERSONAL = _personal_lists()


def test_личные_списки_вообще_нашлись():
    assert len(PERSONAL) >= 10, f"Нашли всего {len(PERSONAL)} личных адресов — разбор сломался"


@pytest.mark.parametrize("path", PERSONAL, ids=lambda p: p)
def test_новичку_личный_экран_не_ломается(client, user_factory, path):
    """У человека нет ничего. Список обязан ответить успешно (пусто) либо честным отказом
    из заранее объяснённого списка — но не ошибкой сервера."""
    newbie = user_factory("FirstLaunch")
    r = client.get(path, headers=newbie["auth"])
    assert r.status_code < 500, (
        f"{path} упал на человеке без данных: {r.status_code} {r.text[:200]}"
    )
    if path in _OK_TO_REFUSE:
        return
    assert r.status_code == 200, (
        f"{path} ответил новичку {r.status_code} — на первом запуске это читается как "
        f"«приложение сломано». Тело: {r.text[:200]}"
    )


def test_главные_ленты_новичку_отвечают_пустотой_а_не_ошибкой(client, user_factory):
    """Отдельно и явно — экраны, которые человек видит в первую минуту."""
    newbie = user_factory("FirstLaunchMain")
    for path in ("/bookings/mine", "/requests/mine", "/notifications", "/me"):
        r = client.get(path, headers=newbie["auth"])
        assert r.status_code == 200, f"{path}: {r.status_code} {r.text[:200]}"


def test_новичок_водитель_тоже_не_ломается(client, user_factory):
    """Роль водителя без единой поездки и без заявки таксиста — тоже первый запуск."""
    driver = user_factory("FirstLaunchDrv", role=UserRole.driver, taxi_approved=False)
    for path in ("/driver/rides", "/driver/status", "/notifications"):
        r = client.get(path, headers=driver["auth"])
        assert r.status_code < 500, f"{path} упал у нового водителя: {r.status_code} {r.text[:200]}"


def test_у_новичка_нет_чужих_данных(client, user_factory):
    """Контроль здравого смысла: пустой список должен быть действительно пустым, а не чужим."""
    newbie = user_factory("FirstLaunchClean")
    for path in ("/bookings/mine", "/requests/mine"):
        r = client.get(path, headers=newbie["auth"])
        assert r.status_code == 200, r.text
        data = r.json()
        items = data.get("items", data) if isinstance(data, dict) else data
        assert items == [] or len(items) == 0, f"{path} показал новичку чужое: {str(items)[:200]}"
