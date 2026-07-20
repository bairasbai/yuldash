from sqlalchemy import inspect, text
from sqlalchemy.exc import ProgrammingError
from sqlmodel import SQLModel, Session, create_engine

from .config import settings

# Для SQLite нужен check_same_thread=False (FastAPI ходит из разных потоков).
_is_sqlite = settings.database_url.startswith("sqlite")
connect_args = {"check_same_thread": False} if _is_sqlite else {}

# Тюнинг пула под нагрузку (только Postgres). Критично: не плодить соединения сверх
# Postgres max_connections (дефолт 100). Формула — (pool_size+max_overflow)×воркеров ≤ лимит−резерв.
# Прод: 5 воркеров × (8+7)=15 = 75 < 100. Раньше было 25/воркер → 5×25=125 > 100 = отказ
# соединений на пике. Числа берём из config (env-настраиваемо), см. settings.db_pool_size.
# pool_pre_ping — отбрасывает соединения, оборвавшиеся после рестарта/таймаута БД
# (иначе первый запрос после простоя падает). pool_recycle — пересоздаёт раз в 30 мин.
_pool_kwargs = {} if _is_sqlite else {
    "pool_size": settings.db_pool_size,
    "max_overflow": settings.db_max_overflow,
    "pool_pre_ping": True,
    "pool_recycle": 1800,
    "pool_timeout": 30,
}
engine = create_engine(settings.database_url, echo=False, connect_args=connect_args, **_pool_kwargs)


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
    # checkfirst НЕ потокобезопасен: при нескольких воркерах gunicorn два процесса
    # одновременно видят «таблицы нет» и делают CREATE → второй падает DuplicateTable.
    # Глотаем эту гонку (таблицу создал другой воркер) — идемпотентно.
    try:
        SQLModel.metadata.create_all(engine)
    except ProgrammingError as e:  # psycopg2 DuplicateTable и т.п. при гонке воркеров
        print(f"[INIT_DB] create_all race ignored: {e}")
    _migrate_sqlite_add_columns()


def get_session():
    with Session(engine) as session:
        yield session
