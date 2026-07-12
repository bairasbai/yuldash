"""Единый логгер Юлдаша. Раньше по коду был размазан print() — не фильтруется по уровню,
не уходит в stderr, не дружит с uvicorn/сборщиками логов. Здесь один именованный логгер
`yuldash` с уровнями (info/warning/error). Уровень — из LOG_LEVEL (env), по умолчанию INFO.

Использование:
    from .logs import log
    log.info("[SMS] отправлено")
    log.warning("[GEO] PostGIS недоступен: %s", e)
    log.exception("[ERR] необработанное")   # внутри except — со стектрейсом

CLI-скрипт cleanup.py осознанно печатает отчёт через print() — это консольный вывод, не логи."""
import logging
import os

log = logging.getLogger("yuldash")

# Настраиваем один раз. Под uvicorn корневой логгер уже сконфигурирован — тогда просто
# наследуем его хендлеры (propagate=True). Если хендлеров нигде нет (тесты, cron, голый
# python) — вешаем свой в stderr, чтобы сообщения не пропали.
if not log.handlers and not logging.getLogger().handlers:
    _h = logging.StreamHandler()  # stderr по умолчанию
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    log.addHandler(_h)

log.setLevel(getattr(logging, os.environ.get("LOG_LEVEL", "INFO").upper(), logging.INFO))
