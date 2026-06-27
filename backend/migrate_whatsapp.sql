-- Миграция: WhatsApp поле в таблице user.
-- "user" в кавычках — зарезервированное слово PostgreSQL.
-- Идемпотентно (IF NOT EXISTS). Применять после migrate_oauth.sql (или независимо).

ALTER TABLE "user" ADD COLUMN IF NOT EXISTS whatsapp_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_user_whatsapp_verified ON "user"(whatsapp_verified);
