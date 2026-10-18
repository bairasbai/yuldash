"""Высадка и предложение места в своём рейсе. Старые данные не угадываем."""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ca_booking_segments_20261018"
down_revision = "bz_booking_pickup_stop_20261018"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    tables = set(inspect(bind).get_table_names())
    additions = {
        "booking": [sa.Column("dropoff_stop", sa.String(), nullable=True)],
        "requestresponse": [
            sa.Column("ride_id", sa.Integer(), nullable=True),
            sa.Column("pickup_stop", sa.String(), nullable=True),
            sa.Column("dropoff_stop", sa.String(), nullable=True),
            sa.Column("route_snapshot", sa.String(), nullable=False, server_default=""),
            sa.Column("booking_id", sa.Integer(), nullable=True),
        ],
    }
    for table, columns in additions.items():
        if table not in tables:
            continue
        existing = {c["name"] for c in inspect(bind).get_columns(table)}
        for column in columns:
            if column.name not in existing:
                op.add_column(table, column)
    if "requestresponse" in tables:
        existing = {i["name"] for i in inspect(bind).get_indexes("requestresponse")}
        for column in ("ride_id", "booking_id"):
            name = f"ix_requestresponse_{column}"
            if name not in existing:
                op.create_index(name, "requestresponse", [column])
        foreign_columns = {tuple(f["constrained_columns"])
                           for f in inspect(bind).get_foreign_keys("requestresponse")}
        missing = [(column, target) for column, target in (("ride_id", "ride"), ("booking_id", "booking"))
                   if (column,) not in foreign_columns]
        if missing:
            with op.batch_alter_table("requestresponse") as batch:
                for column, target in missing:
                    batch.create_foreign_key(f"fk_requestresponse_{column}", target, [column], ["id"])


def downgrade() -> None:
    bind = op.get_bind()
    for table, columns in (
        ("requestresponse", ("booking_id", "route_snapshot", "dropoff_stop", "pickup_stop", "ride_id")),
        ("booking", ("dropoff_stop",)),
    ):
        if table not in inspect(bind).get_table_names():
            continue
        existing = {c["name"] for c in inspect(bind).get_columns(table)}
        if table == "requestresponse":
            for index in inspect(bind).get_indexes(table):
                if set(index["column_names"]) & set(columns):
                    op.drop_index(index["name"], table_name=table)
        with op.batch_alter_table(table) as batch:
            for fk in inspect(bind).get_foreign_keys(table):
                if fk.get("name") and set(fk["constrained_columns"]) & set(columns):
                    batch.drop_constraint(fk["name"], type_="foreignkey")
            for column in columns:
                if column in existing:
                    batch.drop_column(column)
