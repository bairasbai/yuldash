-- Авто-проверка водителя (OCR прав): 4 колонки в driverprofile. Идемпотентно (IF NOT EXISTS).
-- Аддитивно и обратносовместимо: старый код колонки игнорирует.
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS autocheck_result VARCHAR DEFAULT '';
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS autocheck_score  DOUBLE PRECISION DEFAULT 0;
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS autocheck_data   VARCHAR DEFAULT '';
ALTER TABLE driverprofile ADD COLUMN IF NOT EXISTS autocheck_at     TIMESTAMP;
