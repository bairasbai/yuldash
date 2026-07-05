-- Boost-поездки (платное поднятие). Идемпотентно. Таблица payment создаётся create_all.
-- SQLModel.create_all НЕ добавляет колонки/индексы в уже существующую таблицу ride → ALTER вручную.
ALTER TABLE ride ADD COLUMN IF NOT EXISTS boosted_until TIMESTAMP;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS boost_tier VARCHAR DEFAULT '';
CREATE INDEX IF NOT EXISTS idx_ride_boosted_until ON ride (boosted_until);
