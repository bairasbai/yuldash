# -*- coding: utf-8 -*-
"""Проверка навигации Юлдаша без компилятора.

`when (screen)` в YuldashApp.kt должен покрывать ВСЕ элементы `enum class Screen`
(MainActivity.kt). Пропущенная ветка в when без else = ошибка компиляции
«when expression must be exhaustive», а найти её глазами в списке из 90 экранов нельзя.
"""
import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "android/app/src/main/java/com/yuldash/app"


def enum_entries(src: str, name: str) -> list:
    m = re.search(rf"enum class {name}[^{{]*\{{", src)
    if not m:
        return []
    i, depth = m.end(), 1
    while i < len(src) and depth:
        if src[i] in "{([":
            depth += 1
        elif src[i] in "})]":
            depth -= 1
        i += 1
    body = src[m.end():i - 1]
    body = re.sub(r"//[^\n]*", "", body)          # комментарии
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = body.split(";", 1)[0]                   # после ';' — методы enum
    out = []
    for piece in body.split(","):
        p = " ".join(piece.split())
        if p:
            out.append(p.split("(")[0])
    return out


def main() -> int:
    main_kt = (BASE / "MainActivity.kt").read_text(encoding="utf-8")
    app_kt = (BASE / "YuldashApp.kt").read_text(encoding="utf-8")

    entries = enum_entries(main_kt, "Screen")
    if not entries:
        print("не нашёл enum class Screen — проверка не выполнена")
        return 1

    handled = set(re.findall(r"Screen\.(\w+)\s*->", app_kt))
    has_else = re.search(r"^\s*else\s*->", app_kt, re.M) is not None

    missing = [e for e in entries if e not in handled]
    unknown = sorted(handled - set(entries))

    problems = []
    if missing and not has_else:
        problems.append("НЕТ ветки в when(screen), а else отсутствует → сборка упадёт:\n  - "
                        + "\n  - ".join(missing))
    elif missing:
        problems.append("нет своей ветки (перехватит else, экран не откроется):\n  - "
                        + "\n  - ".join(missing))
    if unknown:
        problems.append("ссылка на Screen.X, которого нет в enum:\n  - " + "\n  - ".join(unknown))

    print(f"элементов enum Screen: {len(entries)} · веток when: {len(handled)} · else: {'есть' if has_else else 'нет'}")
    if problems:
        print("\n".join(problems))
        return 1
    print("OK — навигация полная")
    return 0


if __name__ == "__main__":
    sys.exit(main())
