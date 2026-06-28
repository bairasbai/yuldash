-- Аватар профиля: фото пользователя (публичный media-URL). create_all не добавляет
-- колонку в существующую таблицу PG → добавляем вручную при деплое.
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS avatar_url VARCHAR DEFAULT '' NOT NULL;
