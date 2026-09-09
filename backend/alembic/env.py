"""Alembic env: миграции по моделям SQLModel. URL берётся из DATABASE_URL (env) или settings."""
import os
import sys
from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool, inspect

# чтобы импортировать app.*
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlmodel import SQLModel  # noqa: E402
from app import models  # noqa: E402,F401  — импорт ради регистрации всех таблиц в metadata
from app.config import settings  # noqa: E402

config = context.config
if config.config_file_name:
    fileConfig(config.config_file_name)

# URL: приоритет env DATABASE_URL → settings (тот же, что у приложения).
config.set_main_option("sqlalchemy.url", os.environ.get("DATABASE_URL") or settings.database_url)

target_metadata = SQLModel.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            # lock_timeout: миграции идут по ЖИВОМУ приложению (alembic upgrade ДО рестарта). Без
            # него CREATE INDEX / ADD CONSTRAINT на горячей ride/user встаёт в очередь за живой
            # транзакцией и сам блокирует ВЕСЬ трафик к таблице (стойл выдачи/логина). С lock_timeout
            # DDL, не взявший лок за 3с, падает быстро → деплой чисто фейлится (ретрай на низком
            # трафике), а не морозит прод. SET LOCAL — на время этой миграционной транзакции.
            if connection.dialect.name == "postgresql":
                connection.exec_driver_sql("SET LOCAL lock_timeout = '3s'")
                # Existing revision IDs include a 33-character ID. Alembic's default
                # VARCHAR(32) rejects it on PostgreSQL (SQLite does not enforce length).
                # Preserve existing IDs and widen old tables as well as fresh installs.
                connection.exec_driver_sql(
                    "CREATE TABLE IF NOT EXISTS alembic_version "
                    "(version_num VARCHAR(128) NOT NULL PRIMARY KEY)"
                )
                version_column = next(
                    column for column in inspect(connection).get_columns("alembic_version")
                    if column["name"] == "version_num"
                )
                length = getattr(version_column["type"], "length", None)
                if length is not None and length < 128:
                    connection.exec_driver_sql(
                        "ALTER TABLE alembic_version ALTER COLUMN version_num TYPE VARCHAR(128)"
                    )
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
