"""Фаза 4 (D5): формализованное доверие «между своими».

Добавляет:
- таблицу `trust`      — дарованный статус «свой» (L3) + кто пригласил (цепочка);
- таблицу `invitecode` — инвайт-коды в круг доверия (владелец, остаток активаций);
- таблицу `consent`    — реестр согласий (152-ФЗ): вид согласия + таймстамп;
- колонки `ride.only_trusted` и `riderequest.only_trusted` — флаг «только для своих».

ИДЕМПОТЕНТНО (совместимо с create_all на свежей БД и с прод-PG, где таблицы уже есть):
- существующие таблицы/колонки не пересоздаём (проверка через inspector);
- на проде без этих объектов — создаём их.

Прод: `alembic upgrade head`.

Revision ID: p4_trust
Revises: 0004_booking_boarding_code
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p4_trust"
down_revision = "f22_medical_partner"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _cols(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in insp.get_table_names():
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if "trust" not in tables:
        op.create_table(
            "trust",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("level", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("invited_by", sa.Integer(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_trust_user_id", "trust", ["user_id"], unique=True)

    if "invitecode" not in tables:
        op.create_table(
            "invitecode",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("code", sa.String(), nullable=False),
            sa.Column("owner_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("uses_left", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_invitecode_code", "invitecode", ["code"], unique=True)
        op.create_index("ix_invitecode_owner_id", "invitecode", ["owner_id"], unique=False)

    if "consent" not in tables:
        op.create_table(
            "consent",
            sa.Column("id", sa.Integer(), primary_key=True, nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("kind", sa.String(), nullable=False),
            sa.Column("granted_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_consent_user_id", "consent", ["user_id"], unique=False)
        op.create_index("ix_consent_kind", "consent", ["kind"], unique=False)

    if "only_trusted" not in _cols(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.add_column(sa.Column("only_trusted", sa.Boolean(), nullable=False, server_default=sa.false()))

    if "only_trusted" not in _cols(bind, "riderequest"):
        with op.batch_alter_table("riderequest") as batch:
            batch.add_column(sa.Column("only_trusted", sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    bind = op.get_bind()
    tables = _tables(bind)

    if "only_trusted" in _cols(bind, "riderequest"):
        with op.batch_alter_table("riderequest") as batch:
            batch.drop_column("only_trusted")
    if "only_trusted" in _cols(bind, "ride"):
        with op.batch_alter_table("ride") as batch:
            batch.drop_column("only_trusted")
    if "consent" in tables:
        op.drop_table("consent")
    if "invitecode" in tables:
        op.drop_table("invitecode")
    if "trust" in tables:
        op.drop_table("trust")
