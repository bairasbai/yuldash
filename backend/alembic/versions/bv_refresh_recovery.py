"""Hash-bound recovery of a lost refresh response."""
from alembic import op
import sqlalchemy as sa

revision = 'bv_refresh_recovery'
down_revision = 'bu_chat_receipt_index'
branch_labels = None
depends_on = None


def upgrade():
    names = {column['name'] for column in sa.inspect(op.get_bind()).get_columns('refreshtoken')}
    if 'rotation_id_hash' not in names:
        op.add_column('refreshtoken', sa.Column('rotation_id_hash', sa.String(), nullable=True))
    if 'rotated_at' not in names:
        op.add_column('refreshtoken', sa.Column('rotated_at', sa.DateTime(), nullable=True))


def downgrade():
    op.drop_column('refreshtoken', 'rotated_at')
    op.drop_column('refreshtoken', 'rotation_id_hash')
