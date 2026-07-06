"""Юлдаш API — конфиг gunicorn с uvicorn-воркерами (Фаза 5: многопроцессность).

Зачем: один uvicorn-процесс = одно ядро. Под рост до 100k держим N воркеров за nginx,
приложение stateless, WS-события между процессами уже разносит Redis pub/sub
(см. app/services.py: _CHAT_CHANNEL, publish/subscribe).

Запуск (пример, пути — плейсхолдеры, подставь свои на сервере):
    /opt/yuldash/.venv/bin/gunicorn app.main:app -c backend/deploy/gunicorn_conf.py

Или через systemd — см. yuldash-api.service.example рядом.

Число воркеров берём из окружения WEB_CONCURRENCY (стандарт), иначе считаем от ядер.
Приложение (app/) при этом НЕ меняется — воркеры это уровень запуска, не кода.
"""
import multiprocessing
import os

# --- Сеть: слушаем только localhost, наружу выставляет nginx (TLS + ip_hash) ---
bind = os.getenv("YULDASH_BIND", "127.0.0.1:8000")

# --- Класс воркера: uvicorn (ASGI) — тот же движок, что и сейчас, но под мастером gunicorn ---
worker_class = "uvicorn.workers.UvicornWorker"

# --- Сколько воркеров ---
# Правило: (2 × ядра) + 1 для sync-CPU; для async-IO (наш случай) хватает ≈ ядра..2×ядра.
# Приоритет: явный WEB_CONCURRENCY → иначе автоформула. Начни с 2–4, тюнингуй по нагрузочному.
_default = min((multiprocessing.cpu_count() * 2) + 1, 8)
workers = int(os.getenv("WEB_CONCURRENCY", _default))

# --- Таймауты ---
# ВАЖНО: у нас есть WebSocket (чат/карта/трек) с долгими соединениями. Обычный gunicorn
# timeout убивает воркер, если он «молчит» — для WS это нормально. UvicornWorker сам
# держит соединения, поэтому ставим щедрый timeout и graceful.
timeout = int(os.getenv("YULDASH_WORKER_TIMEOUT", "120"))
graceful_timeout = 30
keepalive = 15

# --- Перезапуск воркеров, чтобы не копить утечки памяти под долгой нагрузкой ---
max_requests = 2000
max_requests_jitter = 200        # разброс, чтобы воркеры не рестартовали разом

# --- Логи в journald (systemd подхватит), формат по вкусу ---
accesslog = "-"                  # stdout → journalctl -u yuldash-api
errorlog = "-"
loglevel = os.getenv("GUNICORN_LOGLEVEL", "info")

# --- Имя процесса (видно в ps/top) ---
proc_name = "yuldash-api"

# --- preload: НЕ включаем ---
# init_db()/create_all и Redis-pub/sub поднимаются в lifespan каждого воркера; preload_app
# может дать общее состояние до fork и сюрпризы с соединениями БД/Redis. Оставляем False.
preload_app = False
