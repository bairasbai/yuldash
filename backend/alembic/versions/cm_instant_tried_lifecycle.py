"""Nullable taxi tried lifecycle foundation; no initialization or Redis activation."""
import sqlalchemy as sa
from alembic import op

revision = "cm_instant_tried_lifecycle"
down_revision = "cl_instant_search_revision"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Existing-column skip supports create_all bootstraps, not schema drift repair.
    if op.get_context().as_sql or "tried_lifecycle" not in {
        column["name"] for column in sa.inspect(op.get_bind()).get_columns("instantorder")
    }:
        op.add_column("instantorder", sa.Column("tried_lifecycle", sa.String(32), nullable=True))


def downgrade() -> None:
    op.drop_column("instantorder", "tried_lifecycle")
