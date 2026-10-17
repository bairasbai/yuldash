#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Разовая чистка базы: телефон близкого в комментариях заявок «За близкого» (старый сайт).

Зачем. Экран сайта «За близкого» до PR #168 дописывал номер близкого человека прямо в
комментарий заявки: «Телефон близкого: +7…» (сайт на русском) или «Яҡындың телефоны: +7…»
(на башкирском), а заметку самого человека — после «. ». Комментарий заявки — публичный:
его видели все вошедшие в ленте заявок рядом (`/requests/near`) ещё до отклика, и он же
уходил копией в Telegram админу. Сайт починен, но СТАРЫЕ заявки в базе остались как были.

Что ищет. Заявки (`riderequest`), у которых комментарий начинается РОВНО с «Телефон близкого: »
или «Яҡындың телефоны: » — так, буква в букву, писал старый код сайта. Остальные не трогает.

Как чистит. «<надпись>: <телефон>» → нейтральная метка «Заказ за близкого» (как пишет новый
сайт и Android). Телефон — всё до первого «. » или до конца строки. Заметка человека после
«. » остаётся, но проходит через общий скруб `app.observability.scrub_text`: номер, вписанный
в саму заметку, тоже станет «<телефон>». Итог: «Заказ за близкого. <заметка>» или просто
«Заказ за близкого».
  Одна осторожность сверх этого: кусок после «. », в котором только цифры и знаки номера
(пробел, +, -, скобки, точка), — это продолжение телефона, набранного с «. » внутри
(«8. 917 123-45-67»), а не заметка. Он уходит вместе с телефоном.

Безопасность запуска.
  * Без флага — ПРИМЕРКА: только читает и печатает число заявок, их номера, даты и комментарий
    ПОСЛЕ чистки. Ни телефон, ни исходный комментарий не печатаются никогда.
  * `--apply` — запись ОДНОЙ транзакцией: в ней же заново делается вся проверка примерки
    (строки заблокированы от параллельной правки), потом запись, потом сверка, что подходящих
    заявок не осталось и каждая строка в базе ровно такая, как задумано. Любой сбой — откат,
    в базе ничего не меняется, код выхода 1.
  * Повторный запуск находит 0 — нечего чистить (идемпотентно).
  * Работает и на PostgreSQL (прод), и на SQLite (тесты): только SQLModel/ORM, без SQL диалекта.

Запуск на сервере (см. описание PR):
    cd /opt/yuldash && sudo -u yuldash ./.venv/bin/python ops/scrub_relative_phones.py           # примерка
    cd /opt/yuldash && sudo -u yuldash ./.venv/bin/python ops/scrub_relative_phones.py --apply   # запись

Из репозитория (папка backend/): `python ops/scrub_relative_phones.py`.
Код выхода: 0 — всё в порядке; 1 — отказ (ничего не изменено).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
from datetime import timedelta
from typing import Optional

# Чтобы скрипт находил `app` и из backend/ (репозиторий), и из /opt/yuldash (сервер).
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from sqlalchemy import or_  # noqa: E402
from sqlmodel import Session, select  # noqa: E402

from app.config import settings  # noqa: E402
from app.db import engine  # noqa: E402
from app.models import RideRequest  # noqa: E402
from app.observability import scrub_text  # noqa: E402

# Ровно так писал старый экран сайта (webapp/src/screens/FamilyOrderScreen.tsx до PR #168):
# appText(`Телефон близкого: ${phone}`, `Яҡындың телефоны: ${phone}`), части — через ". ".
PREFIXES = ("Телефон близкого: ", "Яҡындың телефоны: ")
SEP = ". "
LABEL = "Заказ за близкого"

# Кусок из одних «телефонных» знаков (и хотя бы одна цифра) — продолжение номера, не заметка.
_PHONE_ONLY = re.compile(r"[\d +\-().]*\d[\d +\-().]*")
# Цепочка цифр с разделителями, как их пишут в номерах: для сверки «цифры номера не остались».
_DIGIT_RUN = re.compile(r"\d(?:[ \-.()+]*\d)*")


def say(text: str = "") -> None:
    """Печать, которая не падает на консоли без UTF-8 (как в ops/preflight.py)."""
    try:
        print(text)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "ascii"
        print(text.encode(enc, errors="replace").decode(enc, errors="replace"))


def split_old_comment(comment: Optional[str]) -> Optional[tuple[str, str]]:
    """(телефон, заметка) — если комментарий написан старым сайтом; иначе None."""
    if not comment:
        return None
    for prefix in PREFIXES:
        if comment.startswith(prefix):
            phone, sep, note = comment[len(prefix):].partition(SEP)
            while sep:
                head, next_sep, tail = note.partition(SEP)
                if not _PHONE_ONLY.fullmatch(head):
                    break
                phone, sep, note = phone + SEP + head, next_sep, tail
            return phone, note
    return None


def clean_comment(comment: Optional[str]) -> Optional[str]:
    """Комментарий после чистки; None — комментарий не наш, не трогаем."""
    parts = split_old_comment(comment)
    if parts is None:
        return None
    note = scrub_text(parts[1].strip())
    return f"{LABEL}{SEP}{note}" if note else LABEL


def problems(original: str, cleaned: str) -> list[str]:
    """Что не так с результатом чистки (пусто — всё хорошо). Телефон в ответ не попадает."""
    out: list[str] = []
    if not (cleaned == LABEL or cleaned.startswith(LABEL + SEP)):
        out.append("в начале нет метки «Заказ за близкого»")
    if scrub_text(cleaned) != cleaned:
        out.append("в тексте осталось похожее на телефон или адрес")
    phone_digits = re.sub(r"\D", "", (split_old_comment(original) or ("", ""))[0])
    if len(phone_digits) >= 7:
        tail = phone_digits[-7:]
        if any(tail in re.sub(r"\D", "", m.group()) for m in _DIGIT_RUN.finditer(cleaned)):
            out.append("в тексте остались цифры номера близкого")
    return out


def find_rows(session: Session, *, lock: bool = False) -> list[tuple[RideRequest, str]]:
    """Заявки старого сайта и их комментарий после чистки, по возрастанию номера."""
    stmt = (select(RideRequest)
            .where(or_(*[RideRequest.comment.startswith(p, autoescape=True) for p in PREFIXES]))
            .order_by(RideRequest.id))
    if lock:
        stmt = stmt.with_for_update()   # PostgreSQL: никто не поправит строку между проверкой и записью
    found = []
    for row in session.exec(stmt).all():
        # LIKE в базе — грубый отбор (в SQLite латиница без учёта регистра); точная сверка — здесь.
        cleaned = clean_comment(row.comment)
        if cleaned is not None:
            found.append((row, cleaned))
    return found


def _when(row: RideRequest) -> str:
    if row.created_at is None:
        return "дата неизвестна"
    local = row.created_at + timedelta(hours=settings.local_tz_offset_hours)
    return local.strftime("%d.%m.%Y %H:%M") + " по Уфе"


def _db_line() -> str:
    url = engine.url
    if url.get_backend_name() == "sqlite":
        return "База: SQLite (локальная или тестовая, не прод)"
    return (f"База: {url.database or '—'} · хост: {url.host or 'локальный сокет'}"
            f" · порт: {url.port or 5432}")


def _report(found: list[tuple[RideRequest, str]]) -> dict[int, list[str]]:
    """Печатает найденное (только после чистки) и возвращает заявки, которые чистить нельзя."""
    bad: dict[int, list[str]] = {}
    say(f"Найдено заявок: {len(found)}")
    for row, cleaned in found:
        trouble = problems(row.comment, cleaned)
        if trouble:
            bad[row.id] = trouble
            say(f"  заявка #{row.id} от {_when(row)} — НЕ ЧИЩУ АВТОМАТИЧЕСКИ: {'; '.join(trouble)}")
        else:
            say(f"  заявка #{row.id} от {_when(row)} → станет: «{cleaned}»")
    return bad


def run(apply: bool) -> int:
    say("Чистка телефонов близких в комментариях заявок («За близкого», старый сайт)")
    say(_db_line())
    say("Режим: ЗАПИСЬ (--apply)" if apply else "Режим: ПРИМЕРКА — база не меняется")
    say()

    with Session(engine) as s:
        found = find_rows(s, lock=apply)
        bad = _report(found)
        if bad:
            s.rollback()
            say()
            say(f"СТОП: {len(bad)} заявок не прошли проверку. В базе ничего не изменено.")
            say("Передай этот вывод разработчику (телефонов в нём нет).")
            return 1
        if not apply:
            s.rollback()
            say()
            if found:
                say("Это была примерка: в базе ничего не изменено.")
                say("Почистить — та же команда с --apply в конце.")
            else:
                say("Чистить нечего.")
            return 0
        if not found:
            s.rollback()
            say()
            say("Изменено заявок: 0 — чистить нечего.")
            return 0

        say()
        say("Проверка перед записью пройдена. Записываю одной транзакцией…")
        planned = {row.id: cleaned for row, cleaned in found}
        for row, cleaned in found:
            row.comment = cleaned
            s.add(row)
        s.flush()

        # Сверка ДО фиксации, в той же транзакции: подходящих не осталось, и в базе ровно задуманное.
        left = find_rows(s)
        stored = dict(s.exec(select(RideRequest.id, RideRequest.comment)
                             .where(RideRequest.id.in_(list(planned)))).all())
        if left or stored != planned:
            s.rollback()
            say(f"СТОП: сверка после записи не сошлась (осталось подходящих: {len(left)}). "
                "Всё откатено, в базе ничего не изменено. Передай вывод разработчику.")
            return 1
        s.commit()

    say(f"Изменено заявок: {len(planned)}")
    with Session(engine) as s:
        rest = len(find_rows(s))
    say(f"Проверка после записи: подходящих заявок осталось {rest}.")
    if rest:
        say("СТОП: кто-то успел создать новую такую заявку. Запусти скрипт ещё раз.")
        return 1
    say("Готово.")
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Разовая чистка: телефон близкого в комментариях заявок «За близкого» (старый сайт)")
    parser.add_argument("--apply", action="store_true",
                        help="записать в базу (без флага — только примерка, база не меняется)")
    args = parser.parse_args(argv)
    return run(apply=args.apply)


if __name__ == "__main__":
    raise SystemExit(main())
