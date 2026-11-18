"""Optional token for a taxi offer; legacy rows remain NULL."""
import sqlalchemy as sa
from alembic import op

revision = "ck_instant_offer_token"
down_revision = "cj_request_traveler_origin"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if op.get_context().as_sql or "offer_token" not in {
        column["name"] for column in sa.inspect(op.get_bind()).get_columns("instantorder")
    }:
        op.add_column("instantorder", sa.Column("offer_token", sa.String(32), nullable=True))


def downgrade() -> None:
    op.drop_column("instantorder", "offer_token")
