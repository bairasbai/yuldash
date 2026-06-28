-- Отзывы о приложении (для лендинга). На проде init_db() (create_all) создаёт таблицу сам,
-- этот файл — для ручного/контрольного применения на PostgreSQL.
CREATE TABLE IF NOT EXISTS appreview (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER,
    name        TEXT NOT NULL DEFAULT '',
    city        TEXT NOT NULL DEFAULT '',
    stars       INTEGER NOT NULL DEFAULT 5,
    text        TEXT NOT NULL DEFAULT '',
    published   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_appreview_user_id ON appreview (user_id);
CREATE INDEX IF NOT EXISTS ix_appreview_published ON appreview (published);
