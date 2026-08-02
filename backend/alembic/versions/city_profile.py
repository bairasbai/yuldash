"""Город в профиле — user.city (родной город человека).

Закрывает реальный пробел: у User не было своего города (город был только у
партнёра/курьера/таксиста). Без него витрина купонов/посылок не могла по умолчанию
показать «в моём городе» — приходилось выбирать вручную каждый раз.

ИДЕМПОТЕНТНО (baseline-через-create_all, как соседние ревизии courier_c*/mon_m*/w2_*):
- свежая/dev БД: create_all уже создал колонку/индекс → шаги no-op;
- прод (создан раньше, без city): добавляем колонку user.city + индекс.

Прод: `alembic upgrade head`.

Revision ID: city_profile
Revises: courier_c3
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "city_profile"
down_revision = "courier_c3"
branch_labels = None
depends_on = None


def _tables(bind) -> set:
    return set(inspect(bind).get_table_names())


def _columns(bind, table) -> set:
    if table not in _tables(bind):
        return set()
    return {c["name"] for c in inspect(bind).get_columns(table)}


def _indexes(bind, table) -> set:
    if table not in _tables(bind):
        return set()
    return {i["name"] for i in inspect(bind).get_indexes(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if "user" in _tables(bind):
        if "city" not in _columns(bind, "user"):
            op.add_column("user",
                          sa.Column("city", sa.String(), nullable=False,
                                    server_default=""))
        if "ix_user_city" not in _indexes(bind, "user"):
            op.create_index("ix_user_city", "user", ["city"])


def downgrade() -> None:
    bind = op.get_bind()
    if "user" in _tables(bind):
        if "ix_user_city" in _indexes(bind, "user"):
            op.drop_index("ix_user_city", table_name="user")
        if "city" in _columns(bind, "user"):
            with op.batch_alter_table("user") as batch:
                batch.drop_column("city")
