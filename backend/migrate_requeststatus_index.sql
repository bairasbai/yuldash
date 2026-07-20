-- Фаза 1 · B5 (архитектурное ревью 2026-07-20): индекс по riderequest.status.
-- /requests/feed и /requests/near фильтруют status='active' (зеркально ride.status, у которого
-- индекс уже есть) — без индекса лента заявок водителю = seq-scan таблицы riderequest на каждый запрос.
-- Идемпотентно (IF NOT EXISTS). Прод-применение (Postgres): psql < этот файл или через apply-скрипт.
CREATE INDEX IF NOT EXISTS ix_riderequest_status ON riderequest (status);

-- Обновить статистику планировщика, чтобы индекс сразу пошёл в дело.
ANALYZE riderequest;
