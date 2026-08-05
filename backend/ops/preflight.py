#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Предполётная проверка прода: что мешает выкатке — ДО того, как выкатывать.

Зачем это отдельно от сервера. Сервер и так отказывается стартовать с небезопасной
настройкой — но узнаёшь об этом уже во время выкатки, в виде «сервис не поднялся»
и простыни в логах. Этот скрипт задаёт те же вопросы заранее, на спокойную голову,
и отвечает человеческим языком: что не так, чем это грозит и что вписать.

Как запускать (на сервере, из папки backend):

    python ops/preflight.py                  # проверить настройки из окружения/.env
    python ops/preflight.py --env-file .env  # явно указать файл настроек

Что означает код выхода:
    0 — можно выкатывать (замечания могут быть, они не блокируют работу);
    1 — выкатывать нельзя: сервер откажется стартовать или будет работать неправильно.

Секретов скрипт не печатает: только «задано / не задано» и длину, где это важно.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import sys

# Чтобы скрипт работал и из backend/, и из backend/ops/.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
if os.name == "nt" and not os.environ.get("WT_SESSION"):
    GREEN = RED = YELLOW = DIM = RESET = ""   # старая консоль Windows цвета не поймёт

OK, BAD, WARN = "[ok]", "[!!]", "[ ! ]"
LINE, THICK = "-" * 72, "=" * 72


def say(text: str = "") -> None:
    """Печать, которая переживает консоль Windows.

    Скрипт задуман для сервера (там UTF-8), но запускать его будут и с ноутбука.
    Консоль Windows в cp1251 не умеет ни рамок, ни галочек — и весь вывод превращался
    в ошибку кодировки. Инструмент проверки, который сам падает, хуже отсутствия
    инструмента: его показаниям верят.
    """
    try:
        print(text)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "ascii"
        print(text.encode(enc, errors="replace").decode(enc, errors="replace"))


def _load_env_file(path: pathlib.Path) -> int:
    """Подхватывает KEY=VALUE из файла настроек, не перетирая уже заданное окружение."""
    if not path.is_file():
        return 0
    added = 0
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value
            added += 1
    return added


def main() -> int:
    parser = argparse.ArgumentParser(description="Предполётная проверка прода Юлдаша")
    parser.add_argument("--env-file", default=".env", help="файл с настройками (по умолчанию .env)")
    args = parser.parse_args()

    env_path = pathlib.Path(args.env_file)
    loaded = _load_env_file(env_path)
    say(THICK)
    say("ПРЕДПОЛЁТНАЯ ПРОВЕРКА ЮЛДАША")
    say(THICK)
    if loaded:
        say(f"{DIM}Настройки прочитаны из {env_path} ({loaded} строк){RESET}")
    elif env_path.name:
        say(f"{DIM}Файла {env_path} нет — смотрю только переменные окружения{RESET}")
    say()

    from app.config import Settings   # импорт после подстановки окружения

    settings = Settings()
    env_name = settings.env.strip().lower()
    say(f"Режим: {env_name}")
    if not settings.is_prod:
        say()
        say(f"{YELLOW}Это НЕ прод-режим.{RESET} Прод-проверки не применяются: сервер поднимется")
        say("с ослабленными правилами. Для настоящей выкатки нужен ENV=prod.")
        say()
        return 0

    # --- 1. Блокирующее: сервер не стартует -------------------------------------------
    blockers: list[str] = []
    try:
        settings.validate_production()
    except RuntimeError as e:
        text = str(e).replace("Небезопасная прод-конфигурация: ", "")
        blockers = [p.strip() for p in text.split(";") if p.strip()]

    say(LINE)
    say("1. БЕЗ ЭТОГО СЕРВЕР НЕ ЗАПУСТИТСЯ")
    say(LINE)
    if blockers:
        for b in blockers:
            say(f"  {RED}{BAD}{RESET} {b}")
    else:
        say(f"  {GREEN}{OK}{RESET} всё на месте")
    say()

    # --- 2. Не блокирует работу, но мешает выпуску --------------------------------------
    warnings = settings.launch_warnings()
    say(LINE)
    say("2. СЕРВЕР ЗАПУСТИТСЯ, НО ЭТО АУКНЕТСЯ")
    say(LINE)
    if warnings:
        for w in warnings:
            say(f"  {YELLOW}{WARN}{RESET} {w}")
    else:
        say(f"  {GREEN}{OK}{RESET} замечаний нет")
    say()

    # --- 3. Что включено: видно ли людям такси, курьера, деньги -------------------------
    say(LINE)
    say("3. ЧТО СЕЙЧАС ВКЛЮЧЕНО")
    say(LINE)
    flags = [
        ("Такси", settings.taxi_enabled, "без него у людей нет «Быстрого заказа»"),
        ("Курьер", settings.courier_enabled, "без него нет доставки посылок"),
        ("Push-уведомления", bool(settings.firebase_credentials.strip()),
         "без них водитель не увидит заказ с погашенным экраном"),
        ("Видимость падений (Sentry)", bool(settings.sentry_dsn.strip()),
         "без неё краш у человека в Баймаке не увидит никто"),
        ("Redis", bool(settings.redis_url.strip()),
         "без него такси показывает «рядом никого» при живых водителях"),
        ("Принудительное обновление", settings.min_app_version_code > 0,
         "без него ломающий релиз некому раскатить"),
        ("Вход для ревьюера магазина", bool(settings.review_phone.strip() and settings.review_code.strip()),
         "без него Google Play и RuStore отклонят сборку"),
    ]
    for name, on, why in flags:
        mark = f"{GREEN}вкл{RESET}" if on else f"{DIM}выкл{RESET}"
        tail = "" if on else f"  {DIM}— {why}{RESET}"
        say(f"  {name}: {mark}{tail}")
    say()

    # --- 4. Деньги против публичной оферты ---------------------------------------------
    money = [n for n, v in (("комиссия за посылки", settings.parcel_fee_enabled),
                            ("выплаты", settings.payouts_enabled),
                            ("чаевые деньгами", settings.tips_money_enabled)) if v]
    say(LINE)
    say("4. ДЕНЬГИ И ОБЕЩАНИЯ")
    say(LINE)
    if money:
        say(f"  {RED}{BAD}{RESET} включено: {', '.join(money)}")
        say("     Публичная оферта на yulbash.ru/terms обещает «комиссия не берётся».")
        say("     Пока текст оферты не переписан, это публичное обещание одного и делание другого.")
    else:
        say(f"  {GREEN}{OK}{RESET} денежные функции выключены — оферта «комиссия не берётся» правдива")
    say()

    # --- Итог ---------------------------------------------------------------------------
    say(THICK)
    if blockers:
        say(f"{RED}ВЫКАТЫВАТЬ НЕЛЬЗЯ{RESET}: {len(blockers)} шт. сервер не запустится.")
        say("Исправь пункты из раздела 1 и запусти проверку снова.")
        say(THICK)
        return 1
    if warnings:
        say(f"{YELLOW}ВЫКАТЫВАТЬ МОЖНО{RESET}, но {len(warnings)} замечаний из раздела 2 стоит закрыть до магазина.")
    else:
        say(f"{GREEN}ВСЁ ЧИСТО{RESET} — можно выкатывать.")
    say(THICK)
    return 0


if __name__ == "__main__":
    sys.exit(main())
