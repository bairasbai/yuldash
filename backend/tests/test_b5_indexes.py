"""Фаза 1 · B5 (архитектурное ревью 2026-07-20): индексы под горячие фильтры.

riderequest.status фильтруется в /requests/feed и /requests/near, но индекса не было
(в отличие от зеркального ride.status). Проверяем, что create_all строит индекс на свежей БД.
На проде (существующая таблица) — применяется migrate_requeststatus_index.sql.
"""
from sqlalchemy import inspect

from app.db import engine


def test_riderequest_status_is_indexed(client):   # client → lifespan создаёт таблицы (create_all)
    insp = inspect(engine)
    index_cols = [tuple(ix["column_names"]) for ix in insp.get_indexes("riderequest")]
    assert any("status" in cols for cols in index_cols), (
        f"нет индекса, покрывающего riderequest.status: {index_cols}"
    )


def test_ride_status_still_indexed(client):
    # регресс-страховка: у ride.status индекс как был (не сломали зеркальный кейс)
    insp = inspect(engine)
    index_cols = [tuple(ix["column_names"]) for ix in insp.get_indexes("ride")]
    assert any("status" in cols for cols in index_cols), (
        f"пропал индекс по ride.status: {index_cols}"
    )
