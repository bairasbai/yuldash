"""Разовый сдвиг времени попуток из «местного, записанного как UTC» в настоящий UTC.

Что было не так (разбор №2, 2026-08-03). Приложение отправляло время выезда строкой без
часового пояса — «2026-08-05T10:00:00» — а сервер клал её в БД как есть. Вся остальная база
живёт в UTC, поэтому уфимские 10:00 хранились как 10:00 UTC, то есть на 5 часов позже, чем
на самом деле. Последствия были тихими: поездка «на 10:00» висела в ленте до 17:00 по Уфе
(фильтр `depart_at >= utcnow() - 2ч`), напоминание «доехал?» приходило на пять часов позже,
а на экране время выглядело правильным — клиент просто резал строку и не переводил пояс.

Чиним в двух местах сразу, иначе получится каша из старых и новых записей:
  * код (`app/timeutil.client_dt_to_utc` + вызовы в `routers/rides.py` и `routers/requests.py`) —
    новое время приходит с поясом и переводится точно; время БЕЗ пояса от старых версий
    приложения трактуется как местное, поэтому старые телефоны чинятся без обновления;
  * эта ревизия — уже записанные строки сдвигаются на −5 часов.

Почему именно −5 и почему всем строкам: колонки заполнялись ровно одним путём (форма в
приложении → POST /rides и POST /requests), и путь этот был одинаково неверен для всех.
Смещение берём константой, а не из настроек: миграция обязана давать один и тот же результат
при любом будущем конфиге, иначе повторный прогон на другой машине разъедет данные.
Демо-строки (`SEED_DEMO`) создавались от `utcnow()` и в проде запрещены — на них не смотрим.

Сдвигаем и прошедшие поездки тоже: история должна быть в тех же единицах, что и новые записи,
иначе статистика и разбор споров будут врать на пять часов ровно до тех пор, пока кто-нибудь
не заметит.

Прод: `alembic upgrade head`.

Revision ID: y_utc_depart
Revises: x_promo_taxi_ride
"""
from alembic import op
from sqlalchemy import inspect, text

revision = "y_utc_depart"
down_revision = "x_promo_taxi_ride"
branch_labels = None
depends_on = None

_OFFSET_HOURS = 5   # Башкортостан = UTC+5, круглогодично (перевода часов в РФ нет с 2014 г.)

# Таблица → колонка со временем, пришедшим из формы приложения.
_TARGETS = (("ride", "depart_at"), ("riderequest", "desired_at"))


def _has(bind, table: str, column: str) -> bool:
    try:
        return column in {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return False


def _shift(bind, table: str, column: str, hours: int) -> None:
    """Сдвиг на `hours` часов. SQL разный у SQLite и Postgres, поэтому ветвимся по диалекту."""
    if not _has(bind, table, column):
        return
    if bind.dialect.name == "sqlite":
        sign = "+" if hours >= 0 else "-"
        op.execute(text(
            f"UPDATE {table} SET {column} = datetime({column}, '{sign}{abs(hours)} hours') "
            f"WHERE {column} IS NOT NULL"
        ))
    else:
        op.execute(text(
            f"UPDATE {table} SET {column} = {column} + make_interval(hours => {hours}) "
            f"WHERE {column} IS NOT NULL"
        ))


def upgrade() -> None:
    bind = op.get_bind()
    for table, column in _TARGETS:
        _shift(bind, table, column, -_OFFSET_HOURS)


def downgrade() -> None:
    bind = op.get_bind()
    for table, column in _TARGETS:
        _shift(bind, table, column, _OFFSET_HOURS)
