-- Telegram «Поделиться номером»: реальный номер, которым юзер поделился в боте
-- (кнопка request_contact). Хранится на сессии входа до верификации.
-- Идемпотентно (IF NOT EXISTS).
ALTER TABLE tgauth ADD COLUMN IF NOT EXISTS shared_phone VARCHAR;
