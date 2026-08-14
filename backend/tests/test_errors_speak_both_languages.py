"""Сторож: сообщение, которое читает человек, должно быть на двух языках.

Главное правило Юлдаша — любая видимая надпись существует по-русски и по-башкирски. Для
экранов приложения это давно стережёт отдельный тест, а серверные сообщения об ошибках
никто не проверял. А это ровно те слова, которые человек видит в худшую минуту: «время
выезда уже прошло», «код больше не действует», «сначала закрой комиссию».

Для двуязычных ошибок в проекте есть `herr(status, ru, ba)`. Голый `HTTPException` с русским
текстом означает, что башкироязычному человеку показали строку, которую он может не понять,
— и произошло это молча, никакой тест не падал.

Тест разбирает исходники и требует: русский текст в `HTTPException` допустим только там, где
его читает НЕ обычный человек, — админские ручки, служебные сбои инфраструктуры, заглушки
неподключённых входов. Всё остальное должно идти через `herr`.
"""
from __future__ import annotations

import re
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "app"

CYRILLIC = re.compile(r"[А-Яа-яЁё]")
RAISE_HTTP = re.compile(r"HTTPException\(\s*(\d{3})\s*,\s*(.+?)\)", re.S)

# Кого эти строки НЕ касаются: их не видит обычный человек.
ADMIN_ONLY = ("Только для админа", "Только для администратора")

# Ручки админки: их сообщения читает Александр, а не человек в дороге.
ADMIN_TEXTS = (
    "Слоты основателей заняты",              # /admin/ads: столько founder-мест в продаже
    "У пользователя нет зафиксированного устройства",   # /admin/bans/device
    "Укажи device_id или user_id",           # /admin/bans/device
    "Вина на заявителе",                     # решение по спору принимает модератор
    "У оценки нет текста для модерации",     # очередь модерации отзывов
)
INFRA_HINTS = (
    "SMS не настроены",       # ключа провайдера нет в окружении — сообщение дежурному, не человеку
    "SMS не отправлено",      # сбой шлюза: клиент показывает своё «не получилось, повтори»
    "SMS-шлюз временно недоступен",
    "Сервис не настроен",
    "Номер {phone} занят",    # пример внутри комментария про чистку логов, а не живой код
)
STUBS = (
    "VK-вход ещё не подключён",       # кнопки нет ни в одном живом клиенте, заглушка для старых
    "WhatsApp-вход ещё не подключён",
)
SERVICE_TEXTS = ("Пустой токен", "Нужен device_id или user_id", "Недопустимый тип бонуса")


def _sources() -> list[tuple[Path, str]]:
    return [(p, p.read_text(encoding="utf-8")) for p in APP.rglob("*.py")]


def _one_sided_messages() -> list[str]:
    out: list[str] = []
    for path, src in _sources():
        for m in RAISE_HTTP.finditer(src):
            arg = m.group(2)
            if not CYRILLIC.search(arg):
                continue                      # английское/служебное — не текст для человека
            if '"ru"' in arg or "'ru'" in arg:
                continue                      # уже двуязычный словарь
            flat = " ".join(arg.split())
            if any(a in flat for a in ADMIN_ONLY):
                continue
            if any(h in flat for h in INFRA_HINTS + STUBS + SERVICE_TEXTS + ADMIN_TEXTS):
                continue
            if "MSG" in flat or "msg" in flat or "_message" in flat:
                continue                      # общая константа/функция — двуязычие внутри неё
            line = src[: m.start()].count("\n") + 1
            out.append(f"{path.relative_to(APP.parent)}:{line} → {flat[:70]}")
    return out


def test_ошибки_человеку_на_двух_языках():
    forgotten = _one_sided_messages()
    assert not forgotten, (
        "Эти сообщения человек увидит только по-русски: "
        + "; ".join(forgotten)
        + ". Используй herr(код, ru, ba) — или добавь причину в списки исключений этого теста, "
        "если строку читает админ, а не пользователь."
    )


def test_двуязычная_ошибка_действительно_двуязычна():
    """Проверка самого инструмента: herr кладёт оба языка, а не дублирует один."""
    from app.errors import herr

    exc = herr(409, "Код уже погашён", "Код инде ҡулланылған")
    assert exc.status_code == 409
    assert exc.detail["ru"] and exc.detail["ba"]
    assert exc.detail["ru"] != exc.detail["ba"], "башкирский текст совпал с русским — перевода нет"


def test_живая_ошибка_приходит_с_обоими_языками(client, user_factory):
    """Сквозная проверка: то, что реально уходит клиенту, содержит оба языка."""
    driver = user_factory("ДвуязычнаяОшибкаВодитель")
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2020-01-01T10:00:00", "seats_total": 3, "price": 300,   # время в прошлом
    })
    assert r.status_code >= 400, r.text
    detail = r.json().get("detail")
    assert isinstance(detail, dict), f"ошибка пришла одной строкой: {detail}"
    assert detail.get("ru") and detail.get("ba"), detail
