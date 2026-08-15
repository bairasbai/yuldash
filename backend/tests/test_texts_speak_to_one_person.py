"""Сторож тона: с человеком говорим на «ты» и объясняем, что делать.

Правило проекта: обращение на «ты», по-соседски, без канцелярита. Не из вежливости к стилю —
Юлдаш держится на ощущении «между своими», и «Вам необходимо подтвердить» рушит его быстрее,
чем любая техническая неполадка.

Проверять это глазами бесполезно: текстов сотни, и «вы» просачивается по одному. Тест смотрит
исходники и требует «ты» там, где сервер обращается к ОДНОМУ человеку.

Обращение на «вы» к ДВОИМ остаётся законным и нужным: «фото видят только вы двое», «решение
напишем вам обоим», «деньги идут напрямую между вами». Это не вежливая форма, а множественное
число — такие фразы тест пропускает.
"""
from __future__ import annotations

import re
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "app"

# Вежливое «вы» к одному человеку.
POLITE = re.compile(r"\b(Вы|Вам|Вас|Ваш\w*|вы|вам|вас|ваш\w*)\b")

# Фразы, где «вы/вам/вами» — это МНОЖЕСТВЕННОЕ число, про обе стороны сделки.
ABOUT_TWO = ("вы двое", "вам обоим", "между вами", "вы связались", "вы договорились",
             "вы вместе", "вас двоих")

# Повелительное наклонение на «вы»: «нажмите», «укажите», «подтвердите».
POLITE_IMPERATIVE = re.compile(
    r"\b(нажмите|укажите|отправьте|подтвердите|введите|выберите|проверьте|повторите|"
    r"заполните|дождитесь|обновите|попробуйте)\b", re.IGNORECASE,
)


# Файлы, где строки адресованы НЕ человеку в дороге.
NOT_FOR_USERS = {
    "config.py",      # предупреждения при запуске — их читает Александр в логе сервера
    "antifraud.py",   # регулярки фильтра мата: там «вы» — кусок слова, а не обращение
}


def _texts() -> list[tuple[Path, int, str]]:
    """Строковые литералы из кода — то, что реально видит человек."""
    out: list[tuple[Path, int, str]] = []
    for path in APP.rglob("*.py"):
        if path.name in NOT_FOR_USERS:
            continue
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("#", '"""', "*")):
                continue                      # комментарии и docstring — не текст для человека
            for m in re.finditer(r'"([^"]{8,})"', line):
                text = m.group(1)
                if not re.search(r"[А-Яа-яЁё]", text):
                    continue
                out.append((path, i, text))
    return out


def test_к_одному_человеку_обращаемся_на_ты():
    guilty = []
    for path, line, text in _texts():
        low = text.lower()
        if any(phrase in low for phrase in ABOUT_TWO):
            continue                          # «вы двое» — это про обе стороны, так и надо
        if POLITE.search(text):
            guilty.append(f"{path.relative_to(APP.parent)}:{line} → {text[:60]}")
    assert not guilty, (
        "Здесь с человеком говорят на «вы», хотя весь проект — на «ты»: "
        + "; ".join(guilty)
        + ". Юлдаш держится на ощущении «между своими», и вежливое «Вам необходимо» рушит его "
        "быстрее любой поломки."
    )


def test_не_командуем_вежливым_повелительным():
    """«Нажмите», «укажите» — та же чужая интонация, только спрятанная в глаголе."""
    guilty = []
    for path, line, text in _texts():
        if POLITE_IMPERATIVE.search(text):
            guilty.append(f"{path.relative_to(APP.parent)}:{line} → {text[:60]}")
    assert not guilty, (
        "Вежливое повелительное вместо «ты»: " + "; ".join(guilty) +
        ". Пиши «нажми», «укажи», «проверь»."
    )


def test_сумма_договорённости_объясняет_границу(client, user_factory):
    """Сухое «некорректная сумма» человеку ничего не говорит: он видел свою цифру."""
    from test_api import _ride

    driver = user_factory("СуммаВодитель")
    passenger = user_factory("СуммаПассажир")
    ride_id = _ride(client, driver, comment="сумма")

    r = client.post("/bookings", headers=passenger["auth"], json={
        "ride_id": ride_id, "seats": 1, "pay_amount": 10_000_000,
    })

    assert r.status_code == 400, r.text
    detail = r.json()["detail"]
    assert "0" in detail["ru"] and "₽" in detail["ru"], f"граница не названа: {detail['ru']}"
    assert detail["ba"] and detail["ba"] != detail["ru"], "нет башкирского перевода"
