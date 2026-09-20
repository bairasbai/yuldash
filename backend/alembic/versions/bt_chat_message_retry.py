"""Add REST booking-message retry receipts without rebuilding Message."""
from alembic import op
import sqlalchemy as sa

revision = 'bt_chat_message_retry'
down_revision = 'bs_merge_heads_20260903'
branch_labels = None
depends_on = None


def upgrade():
    # The historical baseline uses current metadata on fresh databases.
    if 'chatmessagerequest' in sa.inspect(op.get_bind()).get_table_names():
        return
    op.create_table('chatmessagerequest',
        sa.Column('id', sa.Integer(), primary_key=True, nullable=False),
        sa.Column('sender_id', sa.Integer(), nullable=False),
        sa.Column('booking_id', sa.Integer(), nullable=False),
        sa.Column('request_key', sa.String(128), nullable=False),
        sa.Column('payload_hash', sa.String(64), nullable=False),
        sa.Column('message_id', sa.Integer(), sa.ForeignKey('message.id', ondelete='CASCADE'), nullable=False),
        sa.UniqueConstraint('sender_id', 'booking_id', 'request_key', name='uq_chat_message_request'))


def downgrade():
    op.drop_table('chatmessagerequest')
