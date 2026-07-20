-- Флаг «напоминание оценить поездку уже отправлено» (не спамим повторно). Аддитивно, идемпотентно.
ALTER TABLE booking ADD COLUMN IF NOT EXISTS rate_reminded boolean NOT NULL DEFAULT false;
-- Уже завершённые брони помечаем как «напомнено» → НЕ рассылаем ретроспективно на старые поездки.
UPDATE booking SET rate_reminded = true WHERE status = 'done';
