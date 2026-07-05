-- Перф-индексы под запуск с наплывом. Идемпотентно (IF NOT EXISTS).
-- Главное: самый горячий /rides и /rides/near фильтруют status='active' и сортируют по depart_at.
-- Без индекса — full scan таблицы ride на каждый запрос поиска поездок.

-- Поиск активных поездок + сортировка по времени выезда (составной — закрывает WHERE+ORDER BY).
CREATE INDEX IF NOT EXISTS ix_ride_status_depart ON ride (status, depart_at);
-- Отдельный индекс по статусу (для /rides/near и прочих фильтров по active).
CREATE INDEX IF NOT EXISTS ix_ride_status ON ride (status);
-- Лента/счётчики по времени создания (фоллбэк, когда кеш /feed протух).
CREATE INDEX IF NOT EXISTS ix_ride_created ON ride (created_at);
CREATE INDEX IF NOT EXISTS ix_booking_created ON booking (created_at);

-- Обновить статистику планировщика, чтобы индексы сразу пошли в дело.
ANALYZE ride;
ANALYZE booking;
