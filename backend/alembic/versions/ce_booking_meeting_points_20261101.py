"""Точное место встречи/высадки у брони (RS-03 А).

Данные не трогаем: у старых броней координаты NULL, пояснения «», версия 0 — правила прежние
(место встречи — остановка/пин, «Подъезжаю» — прежний путь). Личные данные: точки живут до
зачистки (cleanup.scrub_meeting_points, 30 дней от выезда) или удаления аккаунта (с бронью).
downgrade — просто снять колонки (брони хранят сами себя, вычисляемого нет).
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect

revision = "ce_booking_meeting_points_20261101"
down_revision = "cd_ride_stop_prices_20261031"
branch_labels = None
depends_on = None

_FLOAT_NULLABLE = [("meet_lat", sa.Float()), ("meet_lng", sa.Float()),
                   ("alight_lat", sa.Float()), ("alight_lng", sa.Float())]
_TEXT_DEFAULT = [("meet_note", ""), ("alight_note", ""), ("meet_proposal", "")]
_INT_DEFAULT = [("meet_version", 0), ("meet_changes", 0)]
_INT_NULLABLE = [("meet_landmark_id", sa.Integer()), ("alight_landmark_id", sa.Integer())]
_ALL = ([name for name, _ in _FLOAT_NULLABLE] + [name for name, _ in _TEXT_DEFAULT]
        + [name for name, _ in _INT_DEFAULT] + [name for name, _ in _INT_NULLABLE])


def upgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    for name, typ in _FLOAT_NULLABLE:
        if name not in existing:
            op.add_column("booking", sa.Column(name, typ, nullable=True))
    for name, default in _TEXT_DEFAULT:
        if name not in existing:
            op.add_column("booking", sa.Column(name, sa.String(), nullable=False, server_default=default))
    for name, default in _INT_DEFAULT:
        if name not in existing:
            op.add_column("booking", sa.Column(name, sa.Integer(), nullable=False, server_default=str(default)))
    for name, typ in _INT_NULLABLE:
        if name not in existing:
            op.add_column("booking", sa.Column(name, typ, nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    if "booking" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("booking")}
    present = [name for name in _ALL if name in existing]
    if present:
        with op.batch_alter_table("booking") as batch:
            for name in present:
                batch.drop_column(name)
