-- Реферал «позови своего»: поля на таблице user (PostgreSQL, "user" — зарезервированное слово → в кавычках).
-- create_all НЕ добавляет колонки в существующую таблицу → ручной ALTER. Идемпотентно (IF NOT EXISTS).
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS referral_code VARCHAR DEFAULT '' NOT NULL;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS referred_by INTEGER;
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS referral_credits INTEGER DEFAULT 0 NOT NULL;
CREATE INDEX IF NOT EXISTS ix_user_referral_code ON "user" (referral_code);
