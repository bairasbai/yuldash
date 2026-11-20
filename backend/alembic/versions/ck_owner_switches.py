"""Store owner login settings and their change history."""

import sqlalchemy as sa
from alembic import op


revision = "ck_owner_switches"
down_revision = "cj_request_traveler_origin"
branch_labels = None
depends_on = None


def upgrade() -> None:
    offline = op.get_context().as_sql
    inspector = None if offline else sa.inspect(op.get_bind())
    if offline or not inspector.has_table("appsetting"):
        op.create_table(
            "appsetting",
            sa.Column("key", sa.String(64), primary_key=True),
            sa.Column("value", sa.Boolean(), nullable=True),
            sa.Column("updated_at", sa.DateTime(), nullable=True),
            sa.Column("updated_by", sa.Integer(), sa.ForeignKey("user.id", ondelete="SET NULL"), nullable=True),
        )
    create_history = offline or not inspector.has_table("appsettingchange")
    if create_history:
        op.create_table(
            "appsettingchange",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("key", sa.String(64), sa.ForeignKey("appsetting.key"), nullable=False),
            sa.Column("old_value", sa.Boolean(), nullable=True),
            sa.Column("new_value", sa.Boolean(), nullable=False),
            sa.Column("admin_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="SET NULL"), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("note", sa.String(300), nullable=True),
        )
    index = "ix_appsettingchange_history"
    if create_history or not any(item["name"] == index for item in inspector.get_indexes("appsettingchange")):
        op.create_index(index, "appsettingchange", ["key", "created_at", "id"])

    # Baseline migrations may already have created the tables from current metadata.
    op.execute(
        "INSERT INTO appsetting (key) VALUES ('login.sms'), ('login.telegram'), ('login.vk'), ('login.whatsapp') "
        "ON CONFLICT (key) DO NOTHING"
    )


def downgrade() -> None:
    op.drop_table("appsettingchange")
    op.drop_table("appsetting")
