"""Свести головы миграций после второго слияния с main (2026-08-12, вечер).

Пока шёл аудит, в `main` приехали классы машин (`ai_car_classes`), а в ветке аудита —
пожизненный счётчик реферальных бонусов (`ai_referral_lifetime`). Обе отошли от одной точки,
и голов снова стало две: `alembic upgrade head` на сервере падает с «Multiple head revisions»,
то есть бэкенд не выкатить.

Пустая merge-ревизия линеаризует историю; схему не меняет. Третий такой случай за месяц —
поэтому в `lessons.md` записано правило: после слияния долгоживущих веток первым делом смотреть
на «одну голову», а не на тесты.

Revision ID: merge_20260812b
Revises: ai_car_classes, ai_referral_lifetime
"""

revision = "merge_20260812b"
down_revision = ("ai_car_classes", "ai_referral_lifetime")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass
