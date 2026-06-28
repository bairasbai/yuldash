-- Гео-координаты концов маршрута (геокодятся при публикации) — для радиус-поиска.
-- Идемпотентно. Колонки критичны (код их читает). PostGIS-расширение и GiST-индекс —
-- отдельно, best-effort при деплое (если PostGIS не установлен — работает Python-фолбэк).
ALTER TABLE ride ADD COLUMN IF NOT EXISTS from_lat DOUBLE PRECISION;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS from_lng DOUBLE PRECISION;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS to_lat   DOUBLE PRECISION;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS to_lng   DOUBLE PRECISION;
