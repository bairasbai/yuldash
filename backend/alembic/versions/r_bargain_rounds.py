"""Второй круг торга по заявке: цена на столе, чей ход, история торга.

Раньше отклик водителя был «бери или уходи»: он называл цену, а пассажир мог только принять
или молча уйти. Половина сделок в селе гибнет на разнице в 50 ₽, которую обе стороны прошли бы
навстречу — торговаться здесь привычка, а не неудобство (механика inDrive, `docs/gaps-*` → «ПОТОМ»).

Аддитивно, ИДЕМПОТЕНТНО: свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: r_bargain_rounds
Revises: q_courier_identity
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "r_bargain_rounds"
down_revision = "q_courier_identity"
branch_labels = None
depends_on = None

_TABLE = "requestresponse"
_COLUMNS = [
    ("current_price", sa.Integer(), {"nullable": False, "server_default": "0"}),
    ("last_offer_by", sa.String(length=16), {"nullable": False, "server_default": "driver"}),
    ("bargain_rounds", sa.Integer(), {"nullable": False, "server_default": "0"}),
    ("bargain_history", sa.String(length=200), {"nullable": False, "server_default": ""}),
]


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    if not existing:      # таблицы нет (свежая БД до create_all) — нечего дополнять
        return
    for name, type_, kwargs in _COLUMNS:
        if name not in existing:
            op.add_column(_TABLE, sa.Column(name, type_, **kwargs))
    # Старые отклики: цена на столе = та, что водитель назвал. Без этого они выглядели бы
    # как «0 ₽» до первого встречного хода.
    if "current_price" not in existing:
        op.execute(sa.text(f"UPDATE {_TABLE} SET current_price = price WHERE current_price = 0"))


def downgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    names = [name for name, _ty, _k in _COLUMNS if name in existing]
    if names:
        with op.batch_alter_table(_TABLE) as b:
            for name in names:
                b.drop_column(name)
