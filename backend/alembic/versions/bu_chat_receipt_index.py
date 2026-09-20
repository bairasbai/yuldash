"""Index retry receipts for parent-message cascade deletion."""
from alembic import op
import sqlalchemy as sa

revision = 'bu_chat_receipt_index'
down_revision = 'bt_chat_message_retry'
branch_labels = None
depends_on = None


def upgrade():
    names = {index['name'] for index in sa.inspect(op.get_bind()).get_indexes('chatmessagerequest')}
    if 'ix_chatmessagerequest_message_id' not in names:
        op.create_index('ix_chatmessagerequest_message_id', 'chatmessagerequest', ['message_id'])


def downgrade():
    op.drop_index('ix_chatmessagerequest_message_id', table_name='chatmessagerequest')
