"""Одно сохранённое намерение SOS переживает потерю HTTP-ответа."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy import inspect

revision = "cb_sos_retry_intent_20261021"
down_revision = "ca_booking_segments_20261018"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    if "sosevent" not in inspect(bind).get_table_names():
        return
    existing = {column["name"] for column in inspect(bind).get_columns("sosevent")}
    for name in ("idempotency_key", "payload_hash"):
        if name not in existing:
            op.add_column("sosevent", sa.Column(name, sa.String(length=64), nullable=True))
    constraints = {constraint["name"] for constraint in inspect(bind).get_unique_constraints("sosevent")}
    if "uq_sos_owner_intent" not in constraints:
        with op.batch_alter_table("sosevent") as batch:
            batch.create_unique_constraint("uq_sos_owner_intent", ["user_id", "idempotency_key"])


def downgrade() -> None:
    bind = op.get_bind()
    if "sosevent" not in inspect(bind).get_table_names():
        return
    constraints = {constraint["name"] for constraint in inspect(bind).get_unique_constraints("sosevent")}
    columns = {column["name"] for column in inspect(bind).get_columns("sosevent")}
    with op.batch_alter_table("sosevent") as batch:
        if "uq_sos_owner_intent" in constraints:
            batch.drop_constraint("uq_sos_owner_intent", type_="unique")
        for column in ("payload_hash", "idempotency_key"):
            if column in columns:
                batch.drop_column(column)
