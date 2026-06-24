-- Миграция под премиум-предпочтения поездки + проверку водителя (2026-06-24).
-- Идемпотентно (IF NOT EXISTS) — безопасно запускать повторно.
-- create_all НЕ добавляет колонки в существующие таблицы Postgres, поэтому миграция вручную.

ALTER TABLE ride ADD COLUMN IF NOT EXISTS pets_allowed boolean NOT NULL DEFAULT false;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS child_seat boolean NOT NULL DEFAULT false;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS women_only boolean NOT NULL DEFAULT false;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS smoking boolean NOT NULL DEFAULT false;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS baggage boolean NOT NULL DEFAULT false;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS air_conditioner boolean NOT NULL DEFAULT false;

ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS license_url varchar NOT NULL DEFAULT '';
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS car_photo_url varchar NOT NULL DEFAULT '';
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS verify_submitted_at timestamp;
