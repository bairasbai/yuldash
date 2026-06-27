-- Миграция: Telegram/VK OAuth поля в User таблице
-- Применить перед развёртыванием на yulbash.ru

ALTER TABLE user
ADD COLUMN telegram_id VARCHAR(255) UNIQUE NULL,
ADD COLUMN vk_id VARCHAR(255) UNIQUE NULL;

CREATE INDEX idx_user_telegram_id ON user(telegram_id);
CREATE INDEX idx_user_vk_id ON user(vk_id);
