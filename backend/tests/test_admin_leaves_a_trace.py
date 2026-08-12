# -*- coding: utf-8 -*-
"""У каждого админского действия есть автор — или записано, почему он не нужен.

Аудит 2026-08-12, волна 36. Помощник `logs.admin_action` завели ещё в августе, но позвали
из 8 мест, а пишущих админских ручек 44. Общий лог запросов показывает «POST /admin/... -> 200»,
то есть ЧТО, но не КТО.

Пока админ один, это незаметно. Но модерация водителей ручная, помощник рано или поздно
появится — и вся прошлая история окажется без авторства задним числом. Хуже того: если телефон
Александра однажды попадёт в чужие руки, вопрос «что успели сделать» останется без ответа.

Сторож ниже требует решения для КАЖДОЙ пишущей админской ручки: либо она оставляет след,
либо стоит в списке ниже с человеческой причиной. Молчания быть не должно — оно неотличимо
от «забыли».
"""
import re
from pathlib import Path

import pytest

ROUTERS = Path("app/routers")

# Ручки, которым след не нужен, и почему. Общее правило: это правка ВИТРИНЫ (наш собственный
# контент и настройки продукта), а не действие в отношении человека, его денег или доступа.
NO_TRACE_NEEDED = {
    "ads.py": "реклама — наша витрина: карточка, статус, срок показа. Про человека там ничего",
    "coupons.py": "купоны и карточки бизнеса — витрина партнёров, не персональные данные людей",
    "promo.py": "промокампании — маркетинговая настройка продукта, к конкретному человеку не привязана",
    "reviews.py": "публикация отзыва — работа с нашим же публичным контентом, решение видно в самой витрине",
    "taxi.py:cities": "список городов, где включено такси — настройка продукта",
}


def _admin_write_endpoints():
    """(файл, путь, имя функции, тело) для всех пишущих админских ручек."""
    pattern = re.compile(
        r'@router\.(?:post|put|patch|delete)\(\s*"(?P<path>/admin[^"]*)"[^)]*\)\s*\n'
        r'(?:async\s+)?def\s+(?P<name>\w+)\(',
    )
    out = []
    for file in sorted(ROUTERS.glob("*.py")):
        src = file.read_text(encoding="utf-8")
        for m in pattern.finditer(src):
            body = src[m.end():]
            nxt = re.search(r"\n@router\.|\nclass \w+|\n@app\.", body)
            out.append((file.name, m.group("path"), m.group("name"), body[:nxt.start()] if nxt else body))
    return out


def test_сторож_вообще_видит_админские_ручки():
    """Если разбор сломается, тест ниже станет зелёным по недоразумению — проверяем счёт."""
    found = _admin_write_endpoints()
    assert len(found) >= 40, f"нашлось всего {len(found)} админских ручек — разбор сломался"


def test_каждое_админское_действие_оставляет_след_или_объяснено():
    forgotten = []
    for fname, path, func, body in _admin_write_endpoints():
        if "admin_action(" in body:
            continue
        reason_key = f"{fname}:cities" if "taxi-cities" in path else fname
        if reason_key in NO_TRACE_NEEDED:
            continue
        forgotten.append(f"{fname} {path} ({func})")

    assert forgotten == [], (
        "эти админские действия не оставляют следа «кто это сделал»: " + ", ".join(forgotten) +
        ". Позови logs.admin_action(user.id, \"что.сделал\", ...) после успешного действия ИЛИ "
        "впиши файл в NO_TRACE_NEEDED с причиной."
    )


def test_причины_отсутствия_следа_написаны_для_человека():
    for key, reason in NO_TRACE_NEEDED.items():
        assert len(reason) >= 25, f"причина для {key} слишком короткая: {reason}"


@pytest.mark.parametrize("must_log", [
    "safety.py", "drivers.py", "taxi.py", "courier.py", "incidents.py",
    "parcels.py", "support.py", "waitlist.py", "debt.py", "payments.py", "antifraud.py",
])
def test_чувствительные_разделы_действительно_пишут_след(must_log):
    """Список того, где след обязателен всегда: деньги, доступ к работе, ограничение человека,
    чужие персональные данные, действия ЗА человека."""
    src = (ROUTERS / must_log).read_text(encoding="utf-8")
    assert "admin_action(" in src, f"{must_log}: ни одно действие не оставляет автора"
