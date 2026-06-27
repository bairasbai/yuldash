"""add otp attempts and created_at (anti brute-force + throttle)

Revision ID: d1e2f3a4b5c6
Revises: c5bb69cf495d
Create Date: 2026-06-27 17:45:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'd1e2f3a4b5c6'
down_revision: Union[str, Sequence[str], None] = 'c5bb69cf495d'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Защита OTP-кода: attempts (счётчик попыток против перебора) + created_at (throttle запросов)."""
    op.add_column('otpcode', sa.Column('attempts', sa.Integer(), nullable=False, server_default='0'))
    op.add_column('otpcode', sa.Column('created_at', sa.DateTime(), nullable=False, server_default=sa.func.now()))


def downgrade() -> None:
    op.drop_column('otpcode', 'created_at')
    op.drop_column('otpcode', 'attempts')
