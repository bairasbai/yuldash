# -*- coding: utf-8 -*-
"""Статическая проверка Kotlin без компилятора (Android SDK есть не в каждой среде).

Что ловим:
1. Баланс {} () [] с учётом строк, шаблонов "$..." и комментариев.
2. Иконки Icons.Default.X без импорта.
3. Блочный комментарий, закрытый посреди слова («reporter*/respondent*») — остаток файла
   уезжает в код. Реальный баг, найденный настоящим компилятором 2026-07-27.
4. Пропущенные запятые между элементами enum (ломает сборку, глазами не видно).
5. Смешение кириллицы и латиницы внутри одного слова (частая порча башкирских строк).

Слой данных можно проверять НАСТОЯЩИМ компилятором — см. tools/compile-data-layer.sh.
"""
import re
import sys
from pathlib import Path

CYR = "аәбвгғдҗеёжҙзиклмнңоөпрҫсштуүфхһцчшщъыьэюяАӘБВГҒДЕЁЖҘЗИЙКЛМНҢОӨПРҪСТУҮФХҺЦЧШЩЪЫЬЭЮЯй"
LAT = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"


def strip_code(src: str):
    """Убираем строки/комментарии → (код, литералы, подозрительные концы блочных комментариев)."""
    out, lits, glued = [], [], []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            lits.append((src[i:j], src.count("\n", 0, i) + 1))
            out.append(" " * (j - i))
            i = j
        elif c == '"':
            j, buf = i + 1, []
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    buf.append(src[j:j + 2]); j += 2; continue
                buf.append(src[j]); j += 1
            lits.append(("".join(buf), src.count("\n", 0, i) + 1))
            # Литерал целиком гасим: скобки внутри ("[", "(") — это текст, а шаблоны ${...}
            # всегда сбалансированы сами по себе, так что баланс файла от них не зависит.
            out.append(" " * (j - i + 1))
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            out.append(" " * (j - i + 1)); i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i)); i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i)
            if j == -1:
                j = n
            else:
                # Блочный комментарий закрылся. Если пара «звёздочка-косая» приклеена к словам
                # с обеих сторон — автор её не планировал (случай «reporter*/respondent*»):
                # комментарий обрывается посреди фразы, и остаток файла уезжает в код.
                before = src[j - 1] if j > i + 2 else " "
                after = src[j + 2] if j + 2 < n else "\n"
                if not before.isspace() and before != "*" and not after.isspace():
                    glued.append((src.count("\n", 0, j) + 1, src[max(i, j - 14):j + 14]))
                j += 2
            out.append(re.sub(r"[^\n]", " ", src[i:j])); i = j
        else:
            out.append(c); i += 1
    return "".join(out), lits, glued


def check(path: Path) -> list:
    src = path.read_text(encoding="utf-8")
    code, lits, glued = strip_code(src)
    problems = []

    stack = []
    pairs = {")": "(", "}": "{", "]": "["}
    for idx, ch in enumerate(code):
        if ch in "({[":
            stack.append((ch, code.count("\n", 0, idx) + 1))
        elif ch in ")}]":
            if not stack or stack[-1][0] != pairs[ch]:
                problems.append(f"{path.name}: лишняя '{ch}' на строке {code.count(chr(10), 0, idx) + 1}")
                break
            stack.pop()
    if stack:
        problems.append(f"{path.name}: не закрыт '{stack[-1][0]}' со строки {stack[-1][1]}")

    # Путь бывает и из трёх сегментов: automirrored.filled.Send — регулярка на два сегмента
    # давала ложную тревогу на каждой зеркалящейся иконке.
    imported = set(re.findall(r"import androidx\.compose\.material\.icons\.(?:\w+\.)+(\w+)", src))
    for m in re.finditer(r"Icons\.(?:Default|Filled|Outlined|Rounded|AutoMirrored\.Filled)\.(\w+)", src):
        if m.group(1) not in imported:
            problems.append(f"{path.name}: иконка {m.group(1)} без импорта (строка {src.count(chr(10), 0, m.start()) + 1})")

    # Преждевременно закрытый блочный комментарий. Реальный случай: в KDoc написали
    # «Поля reporter*/respondent* …» — пара символов закрыла комментарий, и весь остаток
    # файла компилятор прочитал как код. Считаем только НАСТОЯЩИЕ блочные комментарии:
    # в строчном «//» такая пара безвредна (проверено компилятором).
    for line, snippet in glued:
        problems.append(f"{path.name}:{line}: блочный комментарий закрыт посреди слова "
                        f"(«{snippet}») — остаток файла уедет в код")

    # Запятые между элементами enum: пропущенная запятая — обычная опечатка при добавлении
    # экрана, и компилятор ловит её только при сборке (а её в этой среде нет).
    for m in re.finditer(r"enum class (\w+)[^{]*\{", code):
        start = m.end()
        depth, i = 1, start
        while i < len(code) and depth:
            if code[i] in "{([":
                depth += 1
            elif code[i] in "})]":
                depth -= 1
            i += 1
        body = code[start:i - 1]
        entries = body.split(";", 1)[0]          # после ';' идут методы/поля enum
        for piece in entries.split(","):
            p = " ".join(piece.split())
            if p and not re.fullmatch(r"\w+(\(.*\))?", p):
                problems.append(f"{path.name}: в enum {m.group(1)} пропущена запятая: «{p[:60]}»")

    for text, line in lits:
        clean = re.sub(r"\$\{[^}]*\}", " ", text)          # ${переменные} — это код
        clean = re.sub(r"\$\w+", " ", clean)
        clean = clean.replace("\\n", " ").replace("\\t", " ").replace('\\"', " ")
        for word in re.findall(r"[^\s.,:;!?()\[\]{}/«»—·+…\d\-]+", clean):
            if any(c in CYR for c in word) and any(c in LAT for c in word):
                problems.append(f"{path.name}:{line}: смесь кириллицы и латиницы в слове «{word}»")
    return problems


if __name__ == "__main__":
    bad = []
    for arg in sys.argv[1:]:
        bad += check(Path(arg))
    print("\n".join(bad) if bad else "OK — ничего не нашёл")
    sys.exit(1 if bad else 0)
