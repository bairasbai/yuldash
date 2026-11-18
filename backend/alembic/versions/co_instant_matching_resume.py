"""Inactive nullable matching-resume foundation; no backfill or activation."""
import sqlalchemy as sa
from alembic import op

revision = "co_instant_matching_resume"
down_revision = "cn_instant_tried_history"
branch_labels = None
depends_on = None


def upgrade() -> None:
    offline = op.get_context().as_sql
    columns = set() if offline else {
        column["name"] for column in sa.inspect(op.get_bind()).get_columns("instantorder")
    }
    # Existing-column skip supports create_all bootstrap, not schema drift repair.
    if "matching_resume_revision" not in columns:
        op.add_column("instantorder", sa.Column("matching_resume_revision", sa.BigInteger(), nullable=True))
    if "matching_resume_next_at" not in columns:
        op.add_column("instantorder", sa.Column("matching_resume_next_at", sa.DateTime(timezone=False), nullable=True))
    if "matching_resume_attempts" not in columns:
        op.add_column("instantorder", sa.Column("matching_resume_attempts", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("instantorder", "matching_resume_attempts")
    op.drop_column("instantorder", "matching_resume_next_at")
    op.drop_column("instantorder", "matching_resume_revision")
