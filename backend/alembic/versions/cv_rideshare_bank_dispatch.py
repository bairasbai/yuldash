"""Persist switch consent, encrypted requests, and a worker lease."""

import sqlalchemy as sa
from alembic import op


revision = "cv_rideshare_bank_dispatch"
down_revision = "cu_rideshare_bank_recipient"
branch_labels = None
depends_on = None


_CHECKOUT_COLUMNS = (
    ("paid_revision", sa.BigInteger),
    ("card_revision", sa.BigInteger),
    ("recipient_version", sa.String(32)),
    ("driver_consent_at", sa.DateTime),
    ("receipt_conflict", sa.Boolean),
)
_OPERATION_COLUMNS = (
    ("request_ciphertext", sa.LargeBinary),
    ("confirmation_ciphertext", sa.LargeBinary),
    ("lease_token", sa.String(32)),
    ("lease_until", sa.DateTime),
)
_TABLES = (
    ("ridesharebankcheckout", _CHECKOUT_COLUMNS),
    ("ridesharebankoperation", _OPERATION_COLUMNS),
)


def upgrade() -> None:
    offline = op.get_context().as_sql
    for table, columns in _TABLES:
        present = set() if offline else {col["name"] for col in sa.inspect(op.get_bind()).get_columns(table)}
        for name, column_type in columns:
            if name not in present:
                if name == "receipt_conflict":
                    op.add_column(table, sa.Column(name, column_type, nullable=False, server_default=sa.false()))
                else:
                    op.add_column(table, sa.Column(name, column_type, nullable=True))


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Bank dispatch downgrade requires an empty database check")
    connection = op.get_bind()
    for table, _ in _TABLES:
        if connection.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first() is not None:
            raise RuntimeError("Bank attempts and dispatch evidence must be preserved")
    for table, columns in reversed(_TABLES):
        for name, _ in reversed(columns):
            op.drop_column(table, name)
