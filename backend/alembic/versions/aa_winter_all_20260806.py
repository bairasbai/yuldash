"""Зимний протокол для такси и доставки (2026-08-06).

Что это. Зимой трасса Сибай–Уфа — четыре часа. Если человек не отметил, что доехал,
приложение спрашивает «всё в порядке?», а через полчаса молчания зовёт близких.

Проблема, которую чиним. Механика существовала ТОЛЬКО у попутки. В такси и доставке её
не было, хотя дорога та же и зима та же: пассажир такси едет те же четыре часа, курьер —
тоже, и вдобавок один. Классическая забытая сторона: правило завели для первого сценария
и не вернулись к остальным (та же болезнь, что у чата, у кнопки «застрял» и у суммы отмены).

Что делает миграция: добавляет обеим таблицам ту же пару отметок, что уже есть у брони —
когда спросили и когда человек ответил «доехал».

Аддитивно и идемпотентно: на свежей БД колонки приходят из `create_all` → ревизия no-op;
на проде добавляются как nullable, у существующих строк остаются пустыми (значит «не
спрашивали»), никакие данные не переписываются.

Прод: `alembic upgrade head`.

Revision ID: aa_winter_all
Revises: z2_cargo_kind
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "aa_winter_all"
down_revision = "z2_cargo_kind"
branch_labels = None
depends_on = None

_TABLES = ("instantorder", "parceldelivery")
_COLUMNS = ("winter_check_sent_at", "winter_check_ack_at")


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    for table in _TABLES:
        have = _columns(bind, table)
        if not have:
            continue
        for name in _COLUMNS:
            if name not in have:
                op.add_column(table, sa.Column(name, sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    for table in _TABLES:
        have = _columns(bind, table)
        drop = [name for name in _COLUMNS if name in have]
        if drop:
            with op.batch_alter_table(table) as batch:
                for name in drop:
                    batch.drop_column(name)
