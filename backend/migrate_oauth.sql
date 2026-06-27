-- Миграция: Telegram/VK OAuth поля в таблице user.
-- ВАЖНО: "user" в кавычках — это зарезервированное слово PostgreSQL.
-- Идемпотентно (IF NOT EXISTS) — безопасно запускать повторно.
-- create_all НЕ добавляет колонки в существующие таблицы Postgres, поэтому миграция вручную.

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS telegram_id VARCHAR;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS vk_id VARCHAR;

-- Уникальный индекс = и уникальность, и быстрый поиск. NULL'ы не конфликтуют (у большинства юзеров пусто).
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_telegram_id ON "user"(telegram_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_vk_id ON "user"(vk_id);
