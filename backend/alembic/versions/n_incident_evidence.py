"""Фото-доказательства споров (порт из pr88): incident.evidence_urls / respondent_evidence_urls.

Спор «слово против слова» без фото нерешаем: заявитель прикладывает доказательства при подаче,
обвинённый — при объяснении (право на защиту). Файлы приватны (/secure/evidence, ретеншен
не трогает), в БД — CSV своих URL (≤10 шт., внешние хосты отбрасываются).

Аддитивно, ИДЕМПОТЕНТНО (паттерн g_tips): свежая БД (create_all) → no-op; прод → add_column.

Прод: `alembic upgrade head`.

Revision ID: n_incident_evidence
Revises: m_audit_hardening
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "n_incident_evidence"
down_revision = "m_audit_hardening"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "incident")
    if "evidence_urls" not in cols:
        op.add_column("incident", sa.Column("evidence_urls", sa.Text(), nullable=False, server_default=""))
    if "respondent_evidence_urls" not in cols:
        op.add_column("incident", sa.Column("respondent_evidence_urls", sa.Text(), nullable=False, server_default=""))


def downgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "incident")
    with op.batch_alter_table("incident") as b:
        if "respondent_evidence_urls" in cols:
            b.drop_column("respondent_evidence_urls")
        if "evidence_urls" in cols:
            b.drop_column("evidence_urls")
