"""Журнал помеченных текстов для админа: таблица textflag.

Пометки ставились и раньше, но ложились только в счётчик Redis — админ видел ЧИСЛО
помеченных за сегодня и не мог посмотреть, кто и за что. Теперь есть журнал.

Приватность: сам текст в таблице НЕ хранится (§8) — только ссылка (place + ref_id).

ИДЕМПОТЕНТНО: свежая БД получает таблицу из create_all (baseline) → тут no-op;
прод — создаём. Пустая таблица прежнее поведение не меняет.

Revision ID: ah_text_flags
Revises: ag_minors
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ah_text_flags"
down_revision = "ag_minors"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "textflag" in _tables(bind):
        return
    op.create_table(
        "textflag",
        sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("place", sa.String(length=32), nullable=False),
        sa.Column("ref_id", sa.Integer(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    # Индексы под два реальных запроса экрана: «новые сверху» и «сколько у этого человека».
    op.create_index("ix_textflag_user_id", "textflag", ["user_id"])
    op.create_index("ix_textflag_kind", "textflag", ["kind"])
    op.create_index("ix_textflag_created_at", "textflag", ["created_at"])


def downgrade() -> None:
    bind = op.get_bind()
    if "textflag" not in _tables(bind):
        return
    for ix in ("ix_textflag_created_at", "ix_textflag_kind", "ix_textflag_user_id"):
        try:
            op.drop_index(ix, table_name="textflag")
        except Exception:  # noqa: BLE001 — индекса может не быть (создан через create_all)
            pass
    op.drop_table("textflag")
