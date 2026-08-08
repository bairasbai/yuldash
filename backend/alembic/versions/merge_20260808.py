"""Свести две головы миграций (аудит 2026-08-08).

`ae_arrival_verified` (GPS-подтверждение «подъезжаю») и `money_holes_20260807`
(UNIQUE на ledgerentry.ext_id) ответвились от ОДНОЙ ревизии `ad_rating_tags` —
получилось две головы, и `alembic upgrade head` на сервере падает с
«Multiple head revisions are present». То есть выкатить бэкенд было нельзя.

Ровно тот же случай, что `merge_20260720`: пустая merge-ревизия линеаризует историю.
Схему НЕ меняет — обе миграции идемпотентны сами по себе.

Проверка «одна голова» в CI работает верно (шаг «Alembic — одна голова»), значит
после `money_holes_20260807` она была красной и это не заметили. Урок — в lessons.md.

Revision ID: merge_20260808
Revises: ae_arrival_verified, money_holes_20260807
"""

revision = "merge_20260808"
down_revision = ("ae_arrival_verified", "money_holes_20260807")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
