"""Stable server identity for a single login and its web-push binding."""

import sqlalchemy as sa
from alembic import op


revision = "ch_auth_session_20261112"
down_revision = "cg_command_receipt_20261111"
branch_labels = None
depends_on = None

_TABLES = ("refreshtoken", "webpushsubscription")
_COLUMN = "auth_session_id"
_NAMING = {"fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s"}


def upgrade():
    conn = op.get_bind()
    if not sa.inspect(conn).has_table("authsession"):
        op.create_table(
            "authsession",
            sa.Column("id", sa.String(64), primary_key=True),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id", ondelete="CASCADE"), nullable=False),
            sa.Column("revoked_at", sa.DateTime(), nullable=True),
            sa.Column("access_expires_at", sa.DateTime(), nullable=False),
            sa.Column("logout_hashes", sa.JSON(), nullable=False),
        )
        op.create_index("ix_authsession_user_id", "authsession", ["user_id"])
    if not any(column["name"] == "binding_id" for column in sa.inspect(conn).get_columns("webpushsubscription")):
        op.add_column("webpushsubscription", sa.Column("binding_id", sa.String(32), nullable=True))
    for table in _TABLES:
        inspector = sa.inspect(conn)
        has_column = any(column["name"] == _COLUMN for column in inspector.get_columns(table))
        has_fk = any(key["constrained_columns"] == [_COLUMN] for key in inspector.get_foreign_keys(table))
        if not has_column or not has_fk:
            with op.batch_alter_table(table) as batch:
                if not has_column:
                    batch.add_column(sa.Column(_COLUMN, sa.String(64), nullable=True))
                if not has_fk:
                    batch.create_foreign_key(f"fk_{table}_auth_session", "authsession", [_COLUMN], ["id"])
        name = f"ix_{table}_{_COLUMN}"
        if not any(index["name"] == name for index in sa.inspect(conn).get_indexes(table)):
            op.create_index(name, table, [_COLUMN])


def downgrade():
    for table in reversed(_TABLES):
        fk = next(key for key in sa.inspect(op.get_bind()).get_foreign_keys(table)
                  if key["constrained_columns"] == [_COLUMN])
        name = fk["name"] or f"fk_{table}_{_COLUMN}_authsession"
        op.drop_index(f"ix_{table}_{_COLUMN}", table_name=table)
        with op.batch_alter_table(table, naming_convention=_NAMING) as batch:
            batch.drop_constraint(name, type_="foreignkey")
            batch.drop_column(_COLUMN)
            if table == "webpushsubscription":
                batch.drop_column("binding_id")
    op.drop_table("authsession")
