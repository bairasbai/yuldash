-- Фаза 1 · B5 (архитектурное ревью 2026-07-20): индекс по riderequest.status.
-- /requests/feed и /requests/near фильтруют status='active' (зеркально ride.status, у которого
-- индекс уже есть) — без индекса лента заявок водителю = seq-scan таблицы riderequest на каждый запрос.
--
-- CONCURRENTLY: обычный CREATE INDEX берёт блокировку записи на всё время построения → на живой
-- таблице под нагрузкой это стойл записи при деплое. CONCURRENTLY строит без блокировки записи.
-- ВАЖНО: CREATE INDEX CONCURRENTLY НЕЛЬЗЯ выполнять внутри транзакции — запускай этот файл в
-- autocommit-режиме, по одной команде (например: psql -d yuldash -f migrate_requeststatus_index.sql,
-- psql по умолчанию autocommit; НЕ оборачивай в BEGIN/COMMIT).
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_riderequest_status ON riderequest (status);

-- Обновить статистику планировщика, чтобы индекс сразу пошёл в дело.
ANALYZE riderequest;
