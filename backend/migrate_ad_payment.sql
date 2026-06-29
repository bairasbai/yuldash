-- Оплата рекламы через очередь подтверждения: ссылка платежа на объявление. Идемпотентно.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS ad_id INTEGER;
