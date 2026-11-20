"""Revoke all pre-cutover administrator sessions."""

from datetime import datetime, timezone

import sqlalchemy as sa
from alembic import op


revision = "cn_admin_telegram_cutover"
down_revision = "cm_auth_epoch"
branch_labels = None
depends_on = None


def upgrade() -> None:
    users = sa.table(
        "user", sa.column("id", sa.Integer()), sa.column("role", sa.String()),
        sa.column("auth_epoch", sa.BigInteger()), sa.column("tokens_valid_from", sa.DateTime()),
    )
    refresh = sa.table(
        "refreshtoken", sa.column("user_id", sa.Integer()), sa.column("revoked", sa.Boolean()),
    )
    sessions = sa.table(
        "authsession", sa.column("user_id", sa.Integer()), sa.column("revoked_at", sa.DateTime()),
    )
    cutoff = op.inline_literal(datetime.now(timezone.utc).replace(tzinfo=None), type_=sa.DateTime())
    is_admin = users.c.role == op.inline_literal("admin")
    admin_ids = sa.select(users.c.id).where(is_admin)
    op.execute(users.update().where(is_admin).values(
        auth_epoch=users.c.auth_epoch + op.inline_literal(1),
        tokens_valid_from=sa.case(
            (users.c.tokens_valid_from > cutoff, users.c.tokens_valid_from), else_=cutoff,
        ),
    ))
    op.execute(refresh.update().where(
        refresh.c.user_id.in_(admin_ids), refresh.c.revoked.is_(False),
    ).values(revoked=sa.true()))
    op.execute(sessions.update().where(
        sessions.c.user_id.in_(admin_ids), sessions.c.revoked_at.is_(None),
    ).values(revoked_at=cutoff))


def downgrade() -> None:
    """Keep the revocations in effect across a rollback."""
