-- Миграция: WhatsApp поле в User таблице
-- Применить после migrate_oauth.sql

ALTER TABLE user
ADD COLUMN whatsapp_verified BOOLEAN DEFAULT FALSE;

CREATE INDEX idx_user_whatsapp_verified ON user(whatsapp_verified);
