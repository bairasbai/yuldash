"""Optional initial taxi price consent, retained only with its order."""
import sqlalchemy as sa
from alembic import op

revision = "cz_taxi_initial_price_consent"
down_revision = "cz_rideshare_recipient_setup"
branch_labels = None
depends_on = None

_COLUMNS = (
    sa.Column("consent_request_key", sa.String(36), nullable=True),
    sa.Column("consent_request_digest", sa.String(64), nullable=True),
    sa.Column("consent_quote_id", sa.String(36), nullable=True),
    sa.Column("consent_snapshot_json", sa.String(), nullable=True),
)
_UNIQUE = {
    "uq_taxi_consent_owner_key": ["passenger_id", "consent_request_key"],
    "uq_taxi_consent_quote": ["consent_quote_id"],
}


def upgrade() -> None:
    offline = op.get_context().as_sql
    inspector = None if offline else sa.inspect(op.get_bind())
    columns = set() if offline else {c["name"] for c in inspector.get_columns("instantorder")}
    constraints = set() if offline else {c["name"] for c in inspector.get_unique_constraints("instantorder")}
    with op.batch_alter_table("instantorder") as batch:
        for column in _COLUMNS:
            if column.name not in columns:
                batch.add_column(column)
        for name, fields in _UNIQUE.items():
            if name not in constraints:
                batch.create_unique_constraint(name, fields)


def downgrade() -> None:
    with op.batch_alter_table("instantorder") as batch:
        for name in _UNIQUE:
            batch.drop_constraint(name, type_="unique")
        for column in reversed(_COLUMNS):
            batch.drop_column(column.name)
