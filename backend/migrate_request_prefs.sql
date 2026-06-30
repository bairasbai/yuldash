-- Предпочтения/условия пассажира в заявке. Аддитивно, идемпотентно.
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS women_only boolean NOT NULL DEFAULT false;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS child_seat boolean NOT NULL DEFAULT false;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS pets boolean NOT NULL DEFAULT false;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS wheelchair boolean NOT NULL DEFAULT false;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS non_smoking boolean NOT NULL DEFAULT false;
ALTER TABLE riderequest ADD COLUMN IF NOT EXISTS air_conditioner boolean NOT NULL DEFAULT false;
