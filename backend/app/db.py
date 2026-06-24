from sqlalchemy import inspect, text
from sqlmodel import SQLModel, Session, create_engine

from .config import settings

# Для SQLite нужен check_same_thread=False (FastAPI ходит из разных потоков).
connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, echo=False, connect_args=connect_args)


# Тип колонки SQLModel/SQLAlchemy → DDL-тип SQLite (для ALTER TABLE ADD COLUMN).
def _sqlite_coltype(col) -> str:
    t = col.type.__class__.__name__.lower()
    if "int" in t or "bool" in t:
        return "INTEGER"
    if "float" in t or "numeric" in t or "real" in t:
        return "REAL"
    if "datetime" in t or "date" in t or "time" in t:
        return "DATETIME"
    return "TEXT"


def _sqlite_default(col) -> str:
    """SQL-литерал значения по умолчанию для добавляемой колонки (NULL, если нет)."""
    default = getattr(col, "default", None)
    if default is None or getattr(default, "arg", None) is None or callable(getattr(default, "arg", None)):
        return "NULL"
    val = default.arg
    if isinstance(val, bool):
        return "1" if val else "0"
    if isinstance(val, (int, float)):
        return str(val)
    # Enum со значением-строкой
    enum_value = getattr(val, "value", None)
    if enum_value is not None:
        val = enum_value
    return "'" + str(val).replace("'", "''") + "'"


def _migrate_sqlite_add_columns() -> None:
    """Добавляет недостающие колонки в существующие SQLite-таблицы.
    SQLModel.create_all не меняет уже созданные таблицы — это лёгкая dev-миграция,
    чтобы старая локальная БД догоняла модели. В проде используйте полноценные миграции.
    """
    if not settings.database_url.startswith("sqlite"):
        return
    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())
    with engine.begin() as conn:
        for table in SQLModel.metadata.sorted_tables:
            if table.name not in existing_tables:
                continue
            have = {c["name"] for c in inspector.get_columns(table.name)}
            for col in table.columns:
                if col.name in have:
                    continue
                ddl = f'ALTER TABLE "{table.name}" ADD COLUMN "{col.name}" {_sqlite_coltype(col)} DEFAULT {_sqlite_default(col)}'
                conn.execute(text(ddl))


def init_db() -> None:
    # импорт моделей регистрирует таблицы в metadata
    from . import models  # noqa: F401
    SQLModel.metadata.create_all(engine)
    _migrate_sqlite_add_columns()


def get_session():
    with Session(engine) as session:
        yield session
