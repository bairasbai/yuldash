"""Сторож: у текстового поля запроса должен быть потолок длины.

Зачем. Поле «остановки по пути» жило без ограничения, и туда влезали двести тысяч символов —
такая поездка раздувала каждый ответ ленты, а на сельском интернете это значит «экран
не открывается» у всех подряд (аудит 2026-08-08, волна 96).

Дыра тут не в конкретном поле, а в привычке: строку в теле запроса пишут как `str = ""`,
и потолок появляется, только если о нём вспомнили. Тест требует вспоминать всегда.

Проверка по исходникам, а не по поведению: новое поле попадает под охрану в тот же день,
когда его написали, даже если ручку ещё никто не вызывал в тестах.
"""
from __future__ import annotations

import re
from pathlib import Path

APP = Path(__file__).resolve().parents[1] / "app"

# Тело запроса — это классы, которые кончаются на In/Body/Payload: так в проекте называют
# входные модели (RideIn, CancelIn, MessageIn…). Остальные BaseModel — ответы и внутренние.
INPUT_CLASS = re.compile(r"class (\w+(?:In|Body|Payload))\(BaseModel\)")
STR_FIELD = re.compile(r"^\s{4}(\w+):\s*(?:Optional\[)?str\]?\s*=\s*(.+?)$", re.M)

# Поля, которым потолок не нужен, — с причиной у каждого.
ALLOWED_WITHOUT = {
    # Значения из закрытых списков: их проверяет Literal/enum или отдельная валидация.
    "status", "kind", "direction", "watch_kind", "category", "period", "zone", "transport",
    "delivery_type", "urgency", "size", "role", "language", "method", "purpose", "tier",
    "provider", "type", "action", "mode", "sort", "order_by", "gender", "weekdays",
}

# Короткий потолок здесь ОСОЗНАННЫЙ: это не текст человека, а код или формат.
SHORT_ON_PURPOSE = {
    "card_last4",     # четыре цифры карты — больше и не бывает
    "cargo_type",     # тип груза из списка
    "urgency",        # now / bypath
    "status",         # done / cancelled / …
    "kind",           # тип бонуса, тип места
    "method",         # sbp / card

    "language",       # ru / ba
    "gender",         # male / female
    "code",           # промокод: короткий по определению
    "transport",      # foot / bike / car
    "car_plate",      # госномер
    "zone",           # city / region
    "size",           # small / medium / large
    "delivery_type",  # poputka / courier / buy_bring
    "time",           # ЧЧ:ММ
}


def _classes_with_fields() -> list[tuple[Path, str, str, int, str]]:
    out: list[tuple[Path, str, str, int, str]] = []
    for path in APP.rglob("*.py"):
        src = path.read_text(encoding="utf-8")
        for cls_match in INPUT_CLASS.finditer(src):
            name = cls_match.group(1)
            start = cls_match.end()
            # Тело класса кончается на первой строке без отступа. Без этого в «поля модели»
            # попадали аргументы соседних ручек, и сторож ругался на то, чего в модели нет.
            body_lines: list[str] = []
            for raw in src[start:].split("\n")[1:]:
                if raw.strip() and not raw.startswith((" ", "\t")):
                    break
                body_lines.append(raw)
            body = "\n".join(body_lines)
            for f in STR_FIELD.finditer(body):
                line = src[:start].count("\n") + 2 + body[: f.start()].count("\n")
                out.append((path, name, f.group(1), line, f.group(2)))
    return out


def test_у_строковых_полей_запроса_есть_потолок():
    forgotten = []
    for path, cls, field, line, rhs in _classes_with_fields():
        if field in ALLOWED_WITHOUT or "max_length" in rhs:
            continue
        forgotten.append(f"{path.relative_to(APP.parent)}:{line} {cls}.{field}")
    assert not forgotten, (
        "У этих полей запроса нет потолка длины: "
        + "; ".join(forgotten)
        + ". Поставь Field(..., max_length=N) — иначе один человек положит ленту всему району "
        "(волна 96) — или впиши имя поля в ALLOWED_WITHOUT, если это значение из закрытого списка."
    )


def test_потолки_не_стали_издевательски_маленькими():
    """Обратная сторона: слишком тесное поле — это «не могу опубликовать» на ровном месте."""
    tight = []
    for path, cls, field, line, rhs in _classes_with_fields():
        m = re.search(r"max_length\s*=\s*(\d+)", rhs)
        if field in SHORT_ON_PURPOSE:
            continue
        if m and int(m.group(1)) < 20:
            tight.append(f"{path.relative_to(APP.parent)}:{line} {cls}.{field}={m.group(1)}")
    assert not tight, (
        "Эти поля обрезаны слишком сильно — человек не сможет написать даже короткую фразу: "
        + "; ".join(tight)
    )
