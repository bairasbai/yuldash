"""Цены до каждой остановки маршрута (RS-02 Б, целые рубли по исключению D7).

Данные не трогаем: «» — одна цена на весь маршрут, как раньше; брони хранят свою цену,
поэтому downgrade — просто снять колонку.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect

revision = "cd_ride_stop_prices_20261031"
down_revision = "cc_ride_segment_seats_20261031"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    if "ride" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("ride")}
    if "stop_prices_json" not in existing:
        op.add_column("ride", sa.Column("stop_prices_json", sa.String(), nullable=False,
                                        server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    if "ride" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("ride")}
    if "stop_prices_json" in existing:
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("stop_prices_json")
