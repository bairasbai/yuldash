"""Record the commission assigned to a taxi ride, including an explicit zero."""
import sqlalchemy as sa
from alembic import op

revision = "dd_taxi_assessed_commission"
down_revision = "dc_taxi_checkout_scan"
branch_labels = None
depends_on = None


def upgrade():
    if not op.get_context().as_sql:
        columns = sa.inspect(op.get_bind()).get_columns("instantorder")
        if any(column["name"] == "commission_assessed_kop" for column in columns):
            return
    op.add_column("instantorder", sa.Column("commission_assessed_kop", sa.BigInteger(), nullable=True))


def downgrade():
    op.drop_column("instantorder", "commission_assessed_kop")
