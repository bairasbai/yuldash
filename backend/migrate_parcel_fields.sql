-- Поля посылки на поездке (category=parcel): получатель + габарит/вес. Аддитивно, идемпотентно.
ALTER TABLE ride ADD COLUMN IF NOT EXISTS receiver_name varchar;
ALTER TABLE ride ADD COLUMN IF NOT EXISTS parcel_size varchar;
