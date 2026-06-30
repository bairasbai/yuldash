-- Подфаза активной поездки от водителя (departed/arriving) для live-баннера пассажиру. Аддитивно, идемпотентно.
ALTER TABLE booking ADD COLUMN IF NOT EXISTS driver_phase varchar NOT NULL DEFAULT '';
