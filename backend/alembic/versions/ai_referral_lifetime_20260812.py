"""Пожизненный счётчик реферальных бонусов: user.referral_bonus_lifetime.

Зачем. Потолок `MAX_REFERRAL_CREDITS=20` ограничивал ОСТАТОК бонусов, а не их общее число:
потратил 20 бесплатных поднятий — и можешь набрать ещё 20, и так бесконечно. Проба волны 25:
50 приглашённых аккаунтов = 40 бонусов, отказов не было ни одного. Комментарий в коде при этом
обещал, что потолок закрывает «бесконечные бесплатные поднятия».

Считать приглашённых по `user.referred_by` было бы дешевле, но такой счёт обнуляется удалением
приглашённых аккаунтов (а вместе с ними освобождаются и телефоны). Поэтому счётчик живёт
у ПРИГЛАСИВШЕГО и переживает и трату бонусов, и удаление приглашённых.

Старым пользователям ставим 0: у честных за глаза, а у того, кто уже успел накрутить, лимит
начнёт считаться с этого дня — задним числом никого не наказываем.

ИДЕМПОТЕНТНО: свежая БД получает колонку из create_all (baseline) → тут no-op.

Revision ID: ai_referral_lifetime
Revises: merge_20260812
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ai_referral_lifetime"
down_revision = "merge_20260812"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    if "referral_bonus_lifetime" in _columns(bind, "user"):
        return
    op.add_column("user", sa.Column("referral_bonus_lifetime", sa.Integer(),
                                    nullable=False, server_default="0"))


def downgrade() -> None:
    bind = op.get_bind()
    if "referral_bonus_lifetime" not in _columns(bind, "user"):
        return
    op.drop_column("user", "referral_bonus_lifetime")
