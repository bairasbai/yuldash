"""Потолок длины текста у сервера и у приложения — одно число.

Что было. Сервер режет свободный текст (`Field(max_length=…)`) и на превышении отвечает
общим «Проверь введённые данные». Приложение этих потолков не знало и принимало сколько
угодно. Человек упирался в отказ уже ПОСЛЕ того, как всё написал.

Самое обидное место — жалоба. «Опиши, что случилось»: человек подробно описывает
происшествие, жмёт «отправить» и получает непонятную ошибку. Момент для этого худший из
возможных: он и так расстроен, а приложение отвечает «проверь введённые данные», не сказав
ни что проверить, ни где.

Так же было с письмом в поддержку, комментарием к поездке и описанием посылки.

Теперь поле просто перестаёт принимать лишнее — как во всех привычных приложениях.
А этот сторож держит числа согласованными: поменяли на сервере — поменяйте и в приложении,
иначе отказ вернётся, только теперь тише.
"""
from __future__ import annotations

import pathlib
import re

import pytest

_ROOT = pathlib.Path(__file__).resolve().parents[2]
_UIKIT = _ROOT / "android/app/src/main/java/com/yuldash/app/UiKit.kt"

# Константа в приложении → (файл сервера, поле, ожидаемый max_length).
_PAIRS = [
    ("REPORT_DETAILS_MAX", "app/routers/safety.py", "reason"),
    ("SUPPORT_TEXT_MAX", "app/routers/support.py", "body"),
    ("PARCEL_DESC_MAX", "app/routers/parcels.py", "description"),
    ("RIDE_COMMENT_MAX", "app/schemas.py", "comment"),
]


def _app_limit(name: str) -> int:
    src = _UIKIT.read_text(encoding="utf-8")
    m = re.search(rf"internal const val {name} = (\d+)", src)
    assert m, f"в приложении нет потолка {name} (UiKit.kt)"
    return int(m.group(1))


def _server_limit(rel: str, field: str) -> int:
    src = (_ROOT / "backend" / rel).read_text(encoding="utf-8")
    m = re.search(rf"\b{field}\s*:\s*str\s*=\s*Field\([^)]*max_length=(\d+)", src)
    assert m, f"на сервере нет max_length у поля {field} ({rel})"
    return int(m.group(1))


@pytest.mark.parametrize("const,rel,field", _PAIRS)
def test_потолок_совпадает_с_сервером(const, rel, field):
    app = _app_limit(const)
    server = _server_limit(rel, field)
    assert app == server, (
        f"{const}={app}, а сервер принимает {server} ({rel}:{field}). "
        "Если приложение позволяет больше — человек упрётся в отказ после того, как всё написал; "
        "если меньше — молча обрежет то, что он собирался сказать."
    )


def test_поля_с_потолком_реально_его_применяют():
    """Константа заведена, но её могли не подключить к полю ввода. Проверяем применение."""
    android = _ROOT / "android/app/src/main/java/com/yuldash/app"
    used = {
        name
        for name in (p[0] for p in _PAIRS)
        for f in android.glob("*.kt")
        if f.name != "UiKit.kt" and re.search(rf"\.take\({name}\)", f.read_text(encoding="utf-8"))
    }
    missing = sorted({p[0] for p in _PAIRS} - used)
    assert not missing, (
        "потолок заведён, но ни одно поле его не применяет: %s. "
        "Число само по себе ничего не ограничивает." % ", ".join(missing)
    )
