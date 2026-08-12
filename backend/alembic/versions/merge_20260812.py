"""Свести две головы миграций после слияния веток (2026-08-12).

Месяц ветка аудита и `main` жили порознь и каждая завела свою миграцию:
`ag_user_gender` (пол переехал на человека) и `ah_text_flags` (журнал помеченных текстов).
После слияния голов стало две, а `alembic upgrade head` на сервере падает с
«Multiple head revisions are present» — то есть выкатить бэкенд было бы нельзя.

Пустая merge-ревизия линеаризует историю. Схему НЕ меняет: обе миграции самостоятельны
и идемпотентны, порядок между ними не важен.

Тот же случай, что `merge_20260720` и `merge_20260808`. Проверка «одна голова» стоит в CI —
после каждого слияния долгоживущих веток смотреть на неё ПЕРВЫМ делом.

Revision ID: merge_20260812
Revises: ag_user_gender, ah_text_flags
"""

revision = "merge_20260812"
down_revision = ("ag_user_gender", "ah_text_flags")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
