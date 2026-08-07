"""P2 (аудит #73): UNIQUE(promoredemption.user_id) — гонка «один код на юзера».

Прикладная проверка «уже активировал» не сериализует гонку ДВУХ РАЗНЫХ кодов (лочатся разные
строки promocode) → юзер мог получить двойной бонус, блогеру засчитывался результат дважды.
Дедуп дублей ДО UNIQUE (иначе упадёт), затем констрейнт. Идемпотентно.

Revision ID: p2_promo_unique
Revises: b1_reset_verified
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "p2_promo_unique"
down_revision = "b1_reset_verified"
branch_labels = None
depends_on = None

_UQ = "uq_promoredemption_user"


def upgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    if "promoredemption" not in insp.get_table_names():
        return
    uniques = {c["name"] for c in insp.get_unique_constraints("promoredemption")}
    if _UQ in uniques:
        return
    # Схлопнуть дубли, оставив раннюю (min id) активацию на юзера — иначе UNIQUE упадёт.
    if bind.dialect.name == "postgresql":
        op.execute(sa.text(
            "DELETE FROM promoredemption a USING promoredemption b "
            "WHERE a.id > b.id AND a.user_id = b.user_id"
        ))
    else:  # sqlite: на свежей БД дублей нет; констрейнт уже создан create_all → сюда не дойдём
        op.execute(sa.text(
            "DELETE FROM promoredemption WHERE id NOT IN "
            "(SELECT MIN(id) FROM promoredemption GROUP BY user_id)"
        ))
    op.create_unique_constraint(_UQ, "promoredemption", ["user_id"])


def downgrade() -> None:
    bind = op.get_bind()
    insp = inspect(bind)
    if "promoredemption" in insp.get_table_names():
        uniques = {c["name"] for c in insp.get_unique_constraints("promoredemption")}
        if _UQ in uniques:
            # batch_alter_table, а не голый drop_constraint: SQLite не умеет ALTER констрейнтов
            # (NotImplementedError), и откат падал. batch пересоздаёт таблицу без констрейнта;
            # на PostgreSQL это тот же самый ALTER TABLE ... DROP CONSTRAINT, что и раньше.
            with op.batch_alter_table("promoredemption") as b:
                b.drop_constraint(_UQ, type_="unique")
