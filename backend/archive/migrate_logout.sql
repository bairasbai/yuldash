-- Ревокация токенов / выход со всех устройств: момент, до которого токены недействительны.
-- Идемпотентно (IF NOT EXISTS). "user" в кавычках — зарезервированное слово в PostgreSQL.
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS tokens_valid_from TIMESTAMP;
