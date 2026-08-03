"""Аудит бэкенда 2026-08-03: спор переживает удаление аккаунта + честный стаж прав.

Что и зачем:
- incident.reporter_id / respondent_id → NULLABLE. Раньше «удалить аккаунт» стирало спор
  целиком, и обвинённый одним тапом уничтожал заявление жертвы, её фото-улики и решение
  админа. Теперь сторона, удалившая аккаунт, ОБЕЗЛИЧИВАЕТСЯ (ссылка → NULL), а разбор живёт
  (см. app/account.py, шаг 3.7-bis). Тот же приём уже применён к report.target_user_id.
- taxiapplication.license_since_date — точная дата выдачи прав. По одному году стаж считался
  вычитанием годов: права от 31.12.2023 проходили 01.01.2026 как «3 года», хотя реального
  стажа 2 года и 1 день. Колонка опциональная: старые клиенты шлют только год, для них стаж
  считается от 31 декабря этого года (консервативно).

Аддитивно и ИДЕМПОТЕНТНО (паттерн o_gaps_taxi_courier): свежая БД (create_all) → no-op;
прод → add_column / alter_column.

Прод: `alembic upgrade head`.

Revision ID: s_audit_20260803
Revises: r_bargain_rounds
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "s_audit_20260803"
down_revision = "r_bargain_rounds"
branch_labels = None
depends_on = None

# (таблица, колонка, тип, kwargs)
_COLUMNS = [
    ("taxiapplication", "license_since_date", sa.Date(), {"nullable": True}),
]

# (таблица, колонка) — стороны спора становятся NULLABLE (обезличивание вместо удаления).
_NULLABLE = [
    ("incident", "reporter_id"),
    ("incident", "respondent_id"),
]


def _columns(bind, table: str) -> dict:
    try:
        return {c["name"]: c for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return {}


def upgrade() -> None:
    bind = op.get_bind()
    for table, name, type_, kwargs in _COLUMNS:
        if name not in _columns(bind, table):
            op.add_column(table, sa.Column(name, type_, **kwargs))
    for table, name in _NULLABLE:
        col = _columns(bind, table).get(name)
        if col is not None and not col.get("nullable", True):
            with op.batch_alter_table(table) as b:
                b.alter_column(name, existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    bind = op.get_bind()
    # Обратно в NOT NULL не идём: к этому моменту в таблице уже могут быть обезличенные споры
    # (NULL у стороны), и жёсткий откат уронил бы миграцию на живых данных.
    for table, name, _t, _k in _COLUMNS:
        if name in _columns(bind, table):
            with op.batch_alter_table(table) as b:
                b.drop_column(name)
