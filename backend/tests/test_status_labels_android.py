"""Каждое состояние, которое умеет вернуть сервер, приложение должно уметь назвать.

Что происходит, когда не умеет. Сервер завёл новое состояние — например «время вышло,
никто не поехал». Приложение про него не знает: в списке активных такого нет (сервер отдаёт
только активные), в архиве фильтр по «завершена/отменена». И поездка просто **исчезает**
из приложения — водитель не может понять, публиковал он её вообще или нет. Ровно это
и нашлось 2026-08-06 у поездки и у заявки.

Второй вид той же беды: приложение состояние получает, но никак не подписывает — и закрытая
заявка выглядит точно как живая, с приглашением «смотреть отклики водителей», которых уже
никогда не будет.

Тест дешёвый и грубый: он проверяет, что строка состояния вообще встречается в исходниках
приложения. Это не доказывает, что подпись правильная, но ловит главное — состояние,
про которое приложение не знает совсем. Если Android-исходников рядом нет, тест пропускается.
"""
from __future__ import annotations

import pathlib

import pytest

_REPO = pathlib.Path(__file__).resolve().parents[2]
_ANDROID = _REPO / "android" / "app" / "src" / "main" / "java" / "com" / "yuldash" / "app"

# Состояния, которые сервер реально отдаёт клиенту, по видам объектов.
_STATUSES = {
    "поездка": ["active", "done", "cancelled", "expired"],
    "бронь": ["pending", "confirmed", "done", "cancelled"],
    "заявка пассажира": ["active", "matched", "cancelled", "expired"],
    "отклик водителя": ["offered", "accepted", "declined", "expired"],
    "такси-заказ": ["searching", "offered", "accepted", "arriving", "onboard",
                    "done", "cancelled", "expired", "scheduled"],
    "посылка": ["created", "accepted", "in_transit", "delivered", "canceled",
                "returning", "returned"],
}

# Состояния, которые приложению знать не нужно, с причиной.
_NOT_FOR_APP = {
    # Промежуточное состояние поиска: клиент видит его как «ищем машину» через phase,
    # отдельной подписи не требует.
    ("такси-заказ", "searching"),
}

requires_android = pytest.mark.skipif(
    not _ANDROID.exists(), reason="Android-исходников рядом нет — сверять не с чем",
)


def _android_text() -> str:
    parts = []
    for path in _ANDROID.rglob("*.kt"):
        parts.append(path.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(parts)


@pytest.fixture(scope="module")
def android_src() -> str:
    return _android_text()


@requires_android
def test_разбор_исходников_приложения_работает(android_src):
    """Страховка от «зелено, потому что ничего не прочитали»."""
    assert len(android_src) > 200_000, (
        f"Из Android-исходников прочитали всего {len(android_src)} символов — разбор сломался"
    )


@requires_android
@pytest.mark.parametrize(
    "kind, status",
    [(k, s) for k, statuses in _STATUSES.items() for s in statuses],
    ids=[f"{k}:{s}" for k, statuses in _STATUSES.items() for s in statuses],
)
def test_приложение_знает_состояние(kind, status, android_src):
    """Строка состояния обязана встречаться в коде приложения — иначе объект в этом состоянии
    для человека просто исчезает или выглядит не тем, чем является."""
    if (kind, status) in _NOT_FOR_APP:
        pytest.skip(f"{kind}:{status} — приложению не нужно (см. _NOT_FOR_APP)")
    assert f'"{status}"' in android_src, (
        f"Приложение не знает состояние «{status}» ({kind}). Объект в этом состоянии либо "
        f"исчезнет из списков, либо будет подписан не тем, чем является."
    )
