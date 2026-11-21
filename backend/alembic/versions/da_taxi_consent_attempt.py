"""Ephemeral owner-bound admission fences for newly issued taxi quotes only."""
import sqlalchemy as sa
from alembic import op

revision = "da_taxi_consent_attempt"
down_revision = "cz_taxi_initial_price_consent"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # The project baseline creates CURRENT SQLModel metadata, including this table.
    # A populated older parent has no table; accept only the exact expected shape.
    if not op.get_context().as_sql:
        inspector = sa.inspect(op.get_bind())
        if inspector.has_table("taxiconsentattempt"):
            columns = {c["name"]: c for c in inspector.get_columns("taxiconsentattempt")}
            expected = {"owner_id", "request_key", "quote_id", "expires_at", "state"}
            foreign = inspector.get_foreign_keys("taxiconsentattempt")
            valid = (
                set(columns) == expected and all(not c["nullable"] for c in columns.values())
                and isinstance(columns["owner_id"]["type"], sa.Integer)
                and isinstance(columns["expires_at"]["type"], sa.DateTime)
                and all(isinstance(columns[k]["type"], sa.String) and columns[k]["type"].length == size
                        for k, size in [("request_key", 36), ("quote_id", 36), ("state", 12)])
                and inspector.get_pk_constraint("taxiconsentattempt")["constrained_columns"] == ["owner_id", "request_key"]
                and any(c["column_names"] == ["quote_id"] for c in inspector.get_unique_constraints("taxiconsentattempt"))
                and any(c["constrained_columns"] == ["owner_id"] and c["referred_table"] == "user"
                        and c["referred_columns"] == ["id"] and c["options"].get("ondelete") == "CASCADE" for c in foreign)
                and any(c["name"] == "ck_taxi_attempt_state" for c in inspector.get_check_constraints("taxiconsentattempt"))
                and any(c["name"] == "ix_taxiconsentattempt_expires_at" and c["column_names"] == ["expires_at"]
                        for c in inspector.get_indexes("taxiconsentattempt"))
            )
            if not valid:
                raise RuntimeError("Unexpected taxi attempt schema; migration requires review")
            return
    op.create_table(
        "taxiconsentattempt",
        sa.Column("owner_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), nullable=False),
        sa.Column("request_key", sa.String(36), nullable=False),
        sa.Column("quote_id", sa.String(36), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("state", sa.String(12), nullable=False),
        sa.PrimaryKeyConstraint("owner_id", "request_key"),
        sa.UniqueConstraint("quote_id", name="uq_taxi_attempt_quote"),
        sa.CheckConstraint("state IN ('issued', 'closed', 'committed')", name="ck_taxi_attempt_state"),
    )
    op.create_index("ix_taxiconsentattempt_expires_at", "taxiconsentattempt", ["expires_at"])


def downgrade() -> None:
    op.drop_index("ix_taxiconsentattempt_expires_at", table_name="taxiconsentattempt")
    op.drop_table("taxiconsentattempt")
