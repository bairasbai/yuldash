from sqlmodel import SQLModel, Session, create_engine

from .config import settings

# Для SQLite нужен check_same_thread=False (FastAPI ходит из разных потоков).
connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, echo=False, connect_args=connect_args)


def init_db() -> None:
    # импорт моделей регистрирует таблицы в metadata
    from . import models  # noqa: F401
    SQLModel.metadata.create_all(engine)


def get_session():
    with Session(engine) as session:
        yield session
