"""Счётчик нажатий «забыл вещь»: booking.lost_item_calls (аудит 2026-08-08, волна 151).

Зачем. Кнопка «забыл вещь» открывает закрытый чат заново на 48 часов — это правильный выход:
чат после поездки закрывается, а телефон с заднего сиденья иначе не вернуть. Но нажимать её
можно было бесконечно.

Проверено пробой: поездка была 400 дней назад, пять нажатий подряд — пять раз «готово» и пять
уведомлений человеку. Нажимая раз в двое суток, водитель держал бы переписку с пассажиркой
открытой сколько угодно, и каждое нажатие дёргало бы её уведомлением. Это та же «травля
кнопкой», от которой закрывали брони в волне 51.

Теперь границ две: не позже месяца после поездки (дальше это уже не поиск вещи) и не больше
трёх раз. Кому по-настоящему не вернули вещь — поможет поддержка, там разбирает человек.

ИДЕМПОТЕНТНО: на свежей БД колонку уже создаёт create_all из моделей → no-op.

Прод: `alembic upgrade head`.

Revision ID: ao_lost_item_calls
Revises: an_promo_claim_log
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ao_lost_item_calls"
down_revision = "an_promo_claim_log"
branch_labels = None
depends_on = None

_TABLE = "booking"


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if not есть:
        return
    if "lost_item_calls" not in есть:
        op.add_column(_TABLE, sa.Column("lost_item_calls", sa.Integer(), nullable=False,
                                        server_default="0"))


def downgrade() -> None:
    bind = op.get_bind()
    if "lost_item_calls" in _columns(bind):
        op.drop_column(_TABLE, "lost_item_calls")
