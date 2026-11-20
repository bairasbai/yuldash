"""Keep SMS.ru receipt IDs and status codes without message contents."""

import sqlalchemy as sa
from alembic import op


revision = "cp_smsru_receipts"
down_revision = "co_sms_budget"
branch_labels = None
depends_on = None
_TABLE = "otpsendattempt"
_INDEX = "ix_otpsendattempt_sms_ru_message_id"


def upgrade() -> None:
    inspector = None if op.get_context().as_sql else sa.inspect(op.get_bind())
    columns = {col["name"] for col in inspector.get_columns(_TABLE)} if inspector else set()
    if "sms_ru_message_id" not in columns:
        op.add_column(_TABLE, sa.Column("sms_ru_message_id", sa.String(64)))
    if "sms_ru_status_code" not in columns:
        op.add_column(_TABLE, sa.Column("sms_ru_status_code", sa.Integer()))
    if inspector is None or not any(index["name"] == _INDEX for index in inspector.get_indexes(_TABLE)):
        op.create_index(_INDEX, _TABLE, ["sms_ru_message_id"], unique=True)


def downgrade() -> None:
    op.drop_index(_INDEX, table_name=_TABLE)
    op.drop_column(_TABLE, "sms_ru_status_code")
    op.drop_column(_TABLE, "sms_ru_message_id")
