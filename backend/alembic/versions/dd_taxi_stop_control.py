"""Add nullable opt-in waypoint and active wait identity; do not backfill legacy waits."""

import sqlalchemy as sa
from alembic import op

revision = "dd_taxi_stop_control"
down_revision = "dc_merge_taxi_consent_receipts"
branch_labels = None
depends_on = None

_COLUMNS = {
    "stop_route_revision": 36,
    "stop_wait_revision": 36,
    "stop_wait_kind": 12,
    "stop_active_waypoint_id": 36,
}


def upgrade() -> None:
    # The first historical migration creates current metadata on an empty DB.
    # Older populated installations still receive only ADD COLUMN operations.
    inspector = None if op.get_context().as_sql else sa.inspect(op.get_bind())
    columns = {c["name"]: c for c in inspector.get_columns("instantorder")} if inspector else {}
    for name, length in _COLUMNS.items():
        if name in columns:
            column = columns[name]
            if (not column["nullable"] or not isinstance(column["type"], sa.String)
                    or column["type"].length != length or column["default"] is not None):
                raise RuntimeError(f"Unexpected instantorder.{name} definition")
        else:
            op.add_column("instantorder", sa.Column(name, sa.String(length), nullable=True))


def downgrade() -> None:
    # Do not run while explicit waits are active. Older writers cannot protect their waypoint.
    for name in reversed(_COLUMNS):
        op.drop_column("instantorder", name)
