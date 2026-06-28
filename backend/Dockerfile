# Юлдаш API — образ продакшен-уровня (FastAPI + uvicorn).
# Текущий прод работает через systemd (yuldash-api). Этот образ — путь к
# контейнеризации (Фаза 2 масштабирования, см. docs/system-design.md), не ломает systemd.
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Системные зависимости для psycopg2 (PostgreSQL).
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc libpq-dev curl \
    && rm -rf /var/lib/apt/lists/*

# Зависимости отдельным слоем — кешируются, пока requirements не менялись.
COPY requirements.txt .
RUN pip install -r requirements.txt

# Код.
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini .

# Непривилегированный пользователь (не root в контейнере).
RUN useradd -m -u 10001 yuldash && chown -R yuldash:yuldash /app
USER yuldash

EXPOSE 8000

# Healthcheck — Docker/оркестратор видит, что сервис жив.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8000/health || exit 1

# 2 воркера по умолчанию (можно поднять переменной). Энтрипоинт прежний: app.main:app.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
