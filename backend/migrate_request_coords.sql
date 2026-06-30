-- Координаты концов маршрута у заявок пассажира (для карты водителя + радиус-поиска /requests/near).
-- Аддитивно, идемпотентно (create_all НЕ добавляет колонки в существующие таблицы PG — нужен ALTER).
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS from_lat double precision;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS from_lng double precision;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS to_lat double precision;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS to_lng double precision;
