#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Разовая чистка базы: телефон близкого в комментариях заявок «За близкого».

Зачем. Комментарий заявки — публичный: его видят все вошедшие в ленте заявок рядом
(`/requests/near`) ещё до отклика, и он же уходил копией в Telegram админу. Два старых
клиента дописывали туда номер близкого человека:
  * сайт до PR #168: «Телефон близкого: <номер>» (по-башкирски «Яҡындың телефоны: <номер>»),
    заметка человека — после «. »;
  * приложение Android с 29.06 по 05.07.2026 (коммит 5562cf75, AccessibilityScreens.kt:785;
    убрано в 78a8fd39): «Заказ за близкого · <номер>» (по-башкирски «Яҡын кеше өсөн заказ · <номер>»),
    без заметки.
Клиенты починены, но старые заявки в базе остались как были.

Что ищет.
  * Заявки, у которых комментарий НАЧИНАЕТСЯ ровно с одной из четырёх надписей выше — буква
    в букву, как писал старый код. Их чистит (см. ниже).
  * Заявки, где такая надпись есть, но не в начале (человек правил комментарий), —
    «проверить руками». Их текст никто не читает: при записи каждая цепочка из 5 и больше
    цифр в них механически заменяется на «<телефон>», больше ничего не меняется.

Как чистит. Надпись и номер → метка того же языка: «Заказ за близкого» или
«Яҡын кеше өсөн заказ». Номер — всё до первого «. » (сайт) или до конца строки (приложение).
Заметка человека с сайта остаётся и проходит через общий скруб `app.observability.scrub_text`.
Цифр номера в итоге не остаётся никогда — заметка убирается целиком (остаётся только метка),
если в ней:
  * 5 и больше цифр подряд — до или после скруба. Между цифрами может стоять до трёх любых
    не-букв: пробел любой ширины (в т.ч. неразрывный), любое тире и минус, точка, запятая,
    косая черта, подчёркивание, скобки, перевод строки, табуляция. Буква цепочку рвёт.
    Поэтому уходят и заметки с датой с годом («17.10.2026»), промежутком времени
    («10:00-12:00») и суммой от 10 000;
  * есть хоть одна цифра, а сам номер короче полного (меньше 10 цифр): номер, похоже, разорван;
  * снова одна из четырёх надписей;
  * что-то не прошло итоговую проверку.
Кусок после «. » из одних цифр и не-букв («. 347 264-18-90», «. 2», «. 15.30») считается
продолжением номера и уходит вместе с ним. Про каждую заявку с убранной заметкой вывод говорит
прямо.

Безопасность запуска.
  * Без флага — ПРИМЕРКА: только читает. Печатает число заявок, их номера, даты по Уфе,
    «активна»/«закрыта» и каким станет комментарий — только «метка + заметка (N символов)»
    или «только метка»; у «проверить руками» — сколько цепочек цифр будет заменено.
    Ни телефона, ни текста комментария, ни имени близкого вывод не содержит.
  * Примерка работает и на старом коде сервера, где нет `scrub_text`: тогда без «станет».
    `--apply` без `scrub_text` отказывается: чистка — после выкладки.
  * `--apply` — запись ОДНОЙ транзакцией: в ней заново вся проверка примерки (строки
    заблокированы, ждать блокировку — не дольше 5 секунд), запись, сверка до фиксации
    (подходящих не осталось, в базе ровно задуманное). Любой сбой до фиксации — откат.
  * Повторный запуск находит 0 и ничего не меняет (идемпотентно).
  * SQLite — только с переменной окружения YULDASH_SCRUB_ALLOW_SQLITE=1 (её ставят тесты):
    без неё скрипт не откроет случайный файл ./yuldash.db, если файл настроек .env не прочитан.
  * Только SQLModel/ORM (PostgreSQL и SQLite); единственная строка под PostgreSQL —
    `SET LOCAL lock_timeout`.

Запуск на сервере (подробно — в описании PR #173):
    cd /opt/yuldash && sudo -u yuldash ./.venv/bin/python -u ops/scrub_relative_phones.py           # примерка
    cd /opt/yuldash && sudo -u yuldash ./.venv/bin/python -u ops/scrub_relative_phones.py --apply   # запись

Код выхода: 0 — всё в порядке; 1 — СТОП (см. строку «СТОП»); 2 — ЕЩЁ РАЗ (почищено,
но за время чистки появились новые такие заявки — запустить ещё раз).
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
from dataclasses import dataclass
from datetime import timedelta
from typing import Optional

# Чтобы скрипт находил `app` и из backend/ (репозиторий), и из /opt/yuldash (сервер).
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from sqlalchemy import or_, text  # noqa: E402
from sqlmodel import Session, select  # noqa: E402

from app.config import settings  # noqa: E402
from app.db import engine  # noqa: E402
from app.models import RideRequest  # noqa: E402

try:   # на сервере может стоять код старше общего скруба — тогда только примерка без «станет»
    from app.observability import scrub_text  # noqa: E402
except ImportError:
    scrub_text = None

LABEL_RU = "Заказ за близкого"
LABEL_BA = "Яҡын кеше өсөн заказ"
LABELS = (LABEL_RU, LABEL_BA)
SEP = ". "
# Надпись → метка того же языка. Ровно так писал старый код (см. шапку файла).
SITE_PREFIXES = {"Телефон близкого: ": LABEL_RU, "Яҡындың телефоны: ": LABEL_BA}
APP_PREFIXES = {"Заказ за близкого · ": LABEL_RU, "Яҡын кеше өсөн заказ · ": LABEL_BA}
PREFIXES = (*SITE_PREFIXES, *APP_PREFIXES)
# Надписи, которые ищем и не в начале: такие заявки — «проверить руками».
MARKERS = ("Телефон близкого:", "Яҡындың телефоны:", "Заказ за близкого · ", "Яҡын кеше өсөн заказ · ")
PHONE_MASK = "<телефон>"
ALLOW_SQLITE_ENV = "YULDASH_SCRUB_ALLOW_SQLITE"
FULL_PHONE_DIGITS = 10          # полный номер: 10 цифр без 8/+7 или 11 с ними
LOCK_TIMEOUT_SEC = 5            # PostgreSQL: дольше не ждём строку, занятую другой операцией

# Между цифрами номера человек ставит что угодно, кроме букв: пробел любой ширины (в т.ч.
# неразрывный U+00A0, U+202F, U+2009), тире (– — ‑ − －), точку, запятую, «/», «_», скобки,
# перевод строки, табуляцию. `[\W_]` — любая не-буква и не-цифра; до трёх подряд. Буква рвёт.
# Кусок из одних цифр и не-букв (и хотя бы одна цифра) — продолжение номера, не заметка.
_PHONE_ONLY = re.compile(r"[\W_]*\d[\W\d_]*")
# 5 и больше цифр подряд — заметку убираем; в «проверить руками» такую цепочку заменяем.
_FIVE_DIGITS = re.compile(r"\d(?:[\W_]{0,3}\d){4,}")
# Цепочка цифр: для сверки «последних цифр номера в итоге нет».
_DIGIT_RUN = re.compile(r"\d(?:[\W_]{0,3}\d)*")


def say(text_: str = "") -> None:
    """Печать, которая не падает на консоли без UTF-8 (как в ops/preflight.py)."""
    try:
        print(text_)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "ascii"
        print(text_.encode(enc, errors="replace").decode(enc, errors="replace"))


def _digits(s: str) -> str:
    return re.sub(r"\D", "", s)


@dataclass(frozen=True)
class Old:
    """Комментарий старого клиента, разобранный на части."""
    label: str      # метка того же языка
    source: str     # «сайт» или «приложение»
    phone: str
    note: str


@dataclass(frozen=True)
class Plan:
    """Каким станет комментарий."""
    result: str
    note_len: int = 0       # 0 — только метка
    dropped: str = ""       # почему заметку убрали; пусто — не убирали


def match_old(comment: Optional[str]) -> Optional[Old]:
    """Разбор комментария старого сайта или приложения; None — комментарий не их."""
    if not comment:
        return None
    for prefix, label in SITE_PREFIXES.items():
        if comment.startswith(prefix):
            phone, sep, note = comment[len(prefix):].partition(SEP)
            while sep:
                head, next_sep, tail = note.partition(SEP)
                if not _PHONE_ONLY.fullmatch(head):
                    break
                phone, sep, note = phone + SEP + head, next_sep, tail
            return Old(label, "сайт", phone, note.strip())
    for prefix, label in APP_PREFIXES.items():
        if comment.startswith(prefix):
            return Old(label, "приложение", comment[len(prefix):], "")   # заметки там не было
    return None


def is_manual(comment: Optional[str]) -> bool:
    """Надпись про телефон близкого есть, но не в начале — «проверить руками»."""
    return bool(comment) and match_old(comment) is None and any(m in comment for m in MARKERS)


def mask_long_digits(comment: str) -> tuple[str, int]:
    """«Проверить руками»: каждая цепочка из 5+ цифр → «<телефон>». (новый текст, сколько заменено)."""
    return _FIVE_DIGITS.subn(PHONE_MASK, comment)


def plan_comment(comment: Optional[str]) -> Optional[Plan]:
    """Каким станет комментарий старого клиента; None — не наш, не трогаем. Нужен scrub_text."""
    old = match_old(comment)
    if old is None:
        return None
    if not old.note:
        return Plan(old.label)
    if _FIVE_DIGITS.search(old.note):
        return Plan(old.label, dropped="в заметке 5 и больше цифр подряд")
    if len(_digits(old.phone)) < FULL_PHONE_DIGITS and re.search(r"\d", old.note):
        return Plan(old.label, dropped="номер короче полного, а в заметке есть цифры")
    if any(m in old.note for m in MARKERS):
        return Plan(old.label, dropped="в заметке снова надпись про телефон")
    note = scrub_text(old.note).strip()
    result = f"{old.label}{SEP}{note}" if note else old.label
    if _FIVE_DIGITS.search(note) or problems(comment, result):
        return Plan(old.label, dropped="заметка не прошла проверку")
    return Plan(result, note_len=len(note))


def problems(original: str, cleaned: str) -> list[str]:
    """Что не так с итогом чистки (пусто — всё хорошо). Ни номер, ни текст в ответ не попадают."""
    out: list[str] = []
    if not any(cleaned == label or cleaned.startswith(label + SEP) for label in LABELS):
        out.append("в начале нет метки")
    if any(cleaned.startswith(p) for p in PREFIXES):
        out.append("в начале осталась старая надпись")
    if scrub_text is not None and scrub_text(cleaned) != cleaned:
        out.append("в тексте осталось похожее на телефон или адрес")
    if _FIVE_DIGITS.search(cleaned):
        out.append("в тексте 5 и больше цифр подряд")
    old = match_old(original)
    phone_digits = _digits(old.phone) if old else ""
    if len(phone_digits) >= 7:
        tail = phone_digits[-7:]
        if any(tail in _digits(m.group()) for m in _DIGIT_RUN.finditer(cleaned)):
            out.append("в тексте остались цифры номера близкого")
    return out


def _any_like(patterns, *, at_start: bool):
    col = RideRequest.comment
    return or_(*[col.startswith(p, autoescape=True) if at_start else col.contains(p, autoescape=True)
                 for p in patterns])


def find_rows(session: Session, *, lock: bool = False) -> list[RideRequest]:
    """Заявки старых клиентов (надпись в начале), по возрастанию номера."""
    stmt = select(RideRequest).where(_any_like(PREFIXES, at_start=True)).order_by(RideRequest.id)
    if lock:
        stmt = stmt.with_for_update()   # PostgreSQL: никто не поправит строку между проверкой и записью
    # LIKE в базе — грубый отбор (в SQLite латиница без учёта регистра); точная сверка — здесь.
    return [r for r in session.exec(stmt).all() if match_old(r.comment) is not None]


def find_manual(session: Session, *, lock: bool = False) -> list[RideRequest]:
    """Заявки, где надпись есть, но не в начале."""
    stmt = select(RideRequest).where(_any_like(MARKERS, at_start=False)).order_by(RideRequest.id)
    if lock:
        stmt = stmt.with_for_update()
    return [r for r in session.exec(stmt).all() if is_manual(r.comment)]


def _plural(n: int, one: str, few: str, many: str) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return one
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return few
    return many


def _when(row: RideRequest) -> str:
    if row.created_at is None:
        return "дата неизвестна"
    local = row.created_at + timedelta(hours=getattr(settings, "local_tz_offset_hours", 5))
    return local.strftime("%d.%m.%Y %H:%M") + " по Уфе"


def _head(row: RideRequest, source: str = "") -> str:
    parts = [f"заявка #{row.id}", _when(row),
             "активна" if getattr(row, "status", None) == "active" else "закрыта"]
    if source:
        parts.append(source)
    if hasattr(RideRequest, "for_relative_name") and not row.for_relative_name:
        parts.append("имени близкого нет")
    return "  " + " · ".join(parts)


def _db_line() -> str:
    url = engine.url
    if url.get_backend_name() == "sqlite":
        return "База: SQLite (тестовая)"
    return (f"База: {url.database or '—'} · хост: {url.host or 'локальный сокет'}"
            f" · порт: {url.port or 5432}")


def _manual_info(manual: list[RideRequest]) -> list[tuple[str, int]]:
    """Строки «проверить руками» считаем заранее: после записи объекты строк уже не читаются."""
    return [(_head(row), mask_long_digits(row.comment)[1]) for row in manual]


def _report_manual(info: list[tuple[str, int]], *, done: bool) -> None:
    say()
    say(f"Проверить руками: {len(info)}"
        + (" — надпись про телефон близкого есть, но не в начале. Текст не читаю: цепочки из 5+ цифр"
           " при записи механически заменяются на <телефон>, больше ничего не меняется" if info else ""))
    for head, n in info:
        if not n:
            what = "длинных цепочек цифр нет, не меняю"
        elif done:
            what = f"цифры заменены: {n}"
        else:
            what = f"заменю цепочек цифр: {n}"
        say(f"{head} → {what}")


def _report_plans(plans: list[tuple[RideRequest, Plan]]) -> None:
    say(f"Найдено заявок: {len(plans)}")
    for row, plan in plans:
        if plan.note_len:
            what = f"метка + заметка ({plan.note_len} {_plural(plan.note_len, 'символ', 'символа', 'символов')})"
        else:
            what = "только метка"
        if plan.dropped:
            what += f" — заметку убираю: {plan.dropped}"
        say(f"{_head(row, match_old(row.comment).source)} → станет: {what}")


def _pending(session: Session) -> int:
    """Сколько заявок ещё есть что чистить: со старой надписью и «руками» с длинными цифрами."""
    return len(find_rows(session)) + sum(1 for r in find_manual(session) if mask_long_digits(r.comment)[1])


def run(apply: bool, state: dict) -> int:
    say("Чистка телефонов близких в комментариях заявок «За близкого» (старый сайт и старое приложение)")
    say(_db_line())
    say("Режим: ЗАПИСЬ (--apply)" if apply else "Режим: ПРИМЕРКА — база не меняется")
    say()

    with Session(engine) as s:
        if apply and engine.dialect.name == "postgresql":
            s.connection().execute(text(f"SET LOCAL lock_timeout = '{LOCK_TIMEOUT_SEC}s'"))
        rows = find_rows(s, lock=apply)
        manual = find_manual(s, lock=apply)
        manual_info = _manual_info(manual)

        if scrub_text is None:      # старый код сервера: только примерка (--apply отсечён в main)
            say("Код программы на сервере старый (нет общей функции скруба): показываю номера заявок")
            say("и даты без «станет». Каким станет текст — покажет примерка после выкладки. Чистка — тоже после неё.")
            say()
            say(f"Найдено заявок: {len(rows)}")
            for row in rows:
                say(_head(row, match_old(row.comment).source))
            _report_manual(manual_info, done=False)
            say()
            say("Это была примерка: в базе ничего не изменено.")
            return 0

        plans = [(row, plan_comment(row.comment)) for row in rows]
        bad = [row.id for row, plan in plans if plan is None or problems(row.comment, plan.result)]
        if bad:
            s.rollback()
            say(f"СТОП: {len(bad)} заявок не прошли итоговую проверку (номера: {', '.join(map(str, bad))}). "
                "В базе ничего не изменено. Это ошибка скрипта — передай этот вывод разработчику.")
            return 1
        masked = {}
        for row in manual:
            new, n = mask_long_digits(row.comment)
            if n:
                masked[row.id] = new

        _report_plans(plans)
        if not apply:
            _report_manual(manual_info, done=False)
            say()
            say("Это была примерка: в базе ничего не изменено.")
            if plans or masked:
                say("Почистить — та же команда с --apply в конце, но только после полной выкладки (см. инструкцию).")
            return 0
        if not plans and not masked:
            _report_manual(manual_info, done=False)
            say()
            say("Изменено заявок: 0 — чистить нечего.")
            return 0

        planned = {row.id: plan.result for row, plan in plans}
        planned.update(masked)
        say()
        say("Проверка перед записью пройдена. Записываю одной транзакцией…")
        for row in [*rows, *manual]:
            if row.id in planned:
                row.comment = planned[row.id]
                s.add(row)
        s.flush()

        # Сверка ДО фиксации, в той же транзакции: подходящих не осталось, и в базе ровно задуманное.
        left = _pending(s)
        stored = dict(s.exec(select(RideRequest.id, RideRequest.comment)
                             .where(RideRequest.id.in_(list(planned)))).all())
        if left or stored != planned:
            s.rollback()
            say(f"СТОП: сверка после записи не сошлась (осталось подходящих: {left}). "
                "Всё откатилось, в базе ничего не изменено. Передай этот вывод разработчику.")
            return 1
        try:
            s.commit()
        except Exception as e:  # noqa: BLE001 — текст ошибки может нести данные; печатаем только тип
            say(f"СТОП: ошибка {type(e).__name__} в момент записи — неизвестно, записалось ли. "
                "Запусти примерку: она покажет, что осталось.")
            return 1
        state["committed"] = True

    _report_manual(manual_info, done=True)
    say()
    say(f"Изменено заявок: {len(planned)} — по старой надписи: {len(plans)}, "
        f"цифры заменены в «Проверить руками»: {len(masked)}")
    with Session(engine) as s:
        rest = _pending(s)
    say(f"Проверка после записи: подходящих заявок осталось {rest}.")
    if rest:
        say(f"ЕЩЁ РАЗ: пока шла чистка, появились новые такие заявки ({rest}). Найденные раньше уже "
            "почищены и записаны. Запусти ту же команду с --apply ещё раз.")
        return 2
    say("Готово.")
    return 0


def _is_lock_timeout(exc: BaseException) -> bool:
    return getattr(getattr(exc, "orig", None), "pgcode", None) == "55P03"


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Разовая чистка: телефон близкого в комментариях заявок «За близкого»")
    parser.add_argument("--apply", action="store_true",
                        help="записать в базу (без флага — только примерка, база не меняется)")
    args = parser.parse_args(argv)

    if engine.url.get_backend_name() == "sqlite" and os.environ.get(ALLOW_SQLITE_ENV) != "1":
        say("СТОП: скрипт смотрит в файл SQLite, а не в базу сервера: похоже, не прочитан файл настроек")
        say(".env. Запускай из папки программы: cd /opt/yuldash && … В базе ничего не изменено.")
        return 1
    if args.apply and scrub_text is None:
        say("СТОП: чистка — после выкладки. В коде программы на сервере нет общей функции скруба")
        say("(app.observability.scrub_text), без неё заметку не проверить. Сейчас можно только посчитать —")
        say("та же команда без --apply. В базе ничего не изменено.")
        return 1

    state = {"committed": False}
    try:
        return run(args.apply, state)
    except Exception as e:  # noqa: BLE001 — без трассировки: в ней могут быть данные заявок
        if _is_lock_timeout(e):
            say(f"СТОП: заявки заняты другой операцией дольше {LOCK_TIMEOUT_SEC} секунд. В базе ничего не изменено. "
                "Запусти ещё раз через пару минут.")
        elif state["committed"]:
            say(f"СТОП: ошибка {type(e).__name__} уже после записи. Чистка записана; "
                "запусти примерку, чтобы проверить.")
        else:
            say(f"СТОП: ошибка {type(e).__name__}. Всё откатилось, в базе ничего не изменено.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
