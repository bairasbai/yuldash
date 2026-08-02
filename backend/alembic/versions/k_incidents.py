"""Система «Справедливость»: таблицы incident + safetyprofile (дополняют Report).

Двусторонний разбор споров (due process) + лестница наказаний со страйками/паузами/затуханием.
Аддитивно: существующие жалобы (Report) не трогаем.

ИДЕМПОТЕНТНО (паттерн g_tips), оба пути:
- свежая БД: create_all уже создал таблицы из моделей → ревизия no-op (таблицы уже есть);
- прод (Postgres): создаём недостающие таблицы + индексы.

Прод: `alembic upgrade head`.

Revision ID: k_incidents
Revises: j_rating_excluded
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "k_incidents"
down_revision = "j_rating_excluded"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    tables = set(inspect(bind).get_table_names())
    if "incident" not in tables:
        op.create_table(
            "incident",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("booking_id", sa.Integer(), sa.ForeignKey("booking.id"), nullable=True),
            sa.Column("reporter_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("respondent_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("type", sa.String(), nullable=False),
            sa.Column("reporter_role", sa.String(), nullable=False, server_default=""),
            sa.Column("description", sa.String(), nullable=False, server_default=""),
            sa.Column("status", sa.String(), nullable=False, server_default="open"),
            sa.Column("suspected_bump", sa.Boolean(), nullable=False, server_default=sa.text("false")),
            sa.Column("respondent_statement", sa.String(), nullable=False, server_default=""),
            sa.Column("responded_at", sa.DateTime(), nullable=True),
            sa.Column("resolution", sa.String(), nullable=False, server_default=""),
            sa.Column("fault", sa.String(), nullable=False, server_default=""),
            sa.Column("resolution_note", sa.String(), nullable=False, server_default=""),
            sa.Column("compensation_kop", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("appeal_text", sa.String(), nullable=False, server_default=""),
            sa.Column("appeal_status", sa.String(), nullable=False, server_default=""),
            sa.Column("resolved_by", sa.Integer(), sa.ForeignKey("user.id"), nullable=True),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
            sa.Column("resolved_at", sa.DateTime(), nullable=True),
        )
        op.create_index("ix_incident_booking_id", "incident", ["booking_id"])
        op.create_index("ix_incident_reporter_id", "incident", ["reporter_id"])
        op.create_index("ix_incident_respondent_id", "incident", ["respondent_id"])
        op.create_index("ix_incident_type", "incident", ["type"])
        op.create_index("ix_incident_status", "incident", ["status"])
    if "safetyprofile" not in tables:
        op.create_table(
            "safetyprofile",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("strikes", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("warnings", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("standing", sa.String(), nullable=False, server_default="good"),
            sa.Column("suspended_until", sa.DateTime(), nullable=True),
            sa.Column("suspend_reason", sa.String(), nullable=False, server_default=""),
            sa.Column("last_strike_at", sa.DateTime(), nullable=True),
            sa.Column("rating_shield", sa.Boolean(), nullable=False, server_default=sa.text("false")),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_safetyprofile_user_id", "safetyprofile", ["user_id"], unique=True)


def downgrade() -> None:
    bind = op.get_bind()
    tables = set(inspect(bind).get_table_names())
    if "safetyprofile" in tables:
        op.drop_table("safetyprofile")
    if "incident" in tables:
        op.drop_table("incident")
