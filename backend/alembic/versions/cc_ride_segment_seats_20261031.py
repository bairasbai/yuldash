"""Места по участкам маршрута: версия условий рейса и снимок свободных мест (RS-02 А).

Данные не трогаем: снимок leg_free строится при первой записи мест (ride_seats.py), старые
рейсы до того живут на прежнем seats_left. Downgrade сначала возвращает активным рейсам
точный seats_left из живых броней (старый код знает только его), потом снимает колонки.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect

revision = "cc_ride_segment_seats_20261031"
down_revision = "cb_sos_retry_intent_20261021"
branch_labels = None
depends_on = None

_LIVE = "('pending', 'confirmed', 'onboard')"
_SEATS_LEFT_SQL = (
    "CASE WHEN seats_total - COALESCE((SELECT SUM(seats) FROM booking "
    "WHERE booking.ride_id = ride.id AND booking.status IN " + _LIVE + "), 0) > 0 "
    "THEN seats_total - COALESCE((SELECT SUM(seats) FROM booking "
    "WHERE booking.ride_id = ride.id AND booking.status IN " + _LIVE + "), 0) ELSE 0 END"
)


def upgrade() -> None:
    bind = op.get_bind()
    if "ride" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("ride")}
    if "terms_version" not in existing:
        op.add_column("ride", sa.Column("terms_version", sa.BigInteger(), nullable=False,
                                        server_default="0"))
    if "leg_free" not in existing:
        op.add_column("ride", sa.Column("leg_free", sa.String(), nullable=False, server_default=""))
    if "seats_free_max" not in existing:
        op.add_column("ride", sa.Column("seats_free_max", sa.Integer(), nullable=False,
                                        server_default="0"))


def downgrade() -> None:
    bind = op.get_bind()
    if "ride" not in inspect(bind).get_table_names():
        return
    # Активным рейсам — точный остаток из живых броней: старый код после downgrade
    # знает только seats_left, снимок участков ему недоступен.
    op.execute(sa.text("UPDATE ride SET seats_left = " + _SEATS_LEFT_SQL + " WHERE status = 'active'"))
    existing = {column["name"] for column in inspect(bind).get_columns("ride")}
    with op.batch_alter_table("ride") as batch:
        for column in ("seats_free_max", "leg_free", "terms_version"):
            if column in existing:
                batch.drop_column(column)
