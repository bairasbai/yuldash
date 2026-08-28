"""Слить восемь параллельных голов миграций в одну (аудит 2026-08-21).

ЧТО СЛОМАЛОСЬ. Деплой прода выполняет `alembic upgrade head` (deploy-backend.bat, шаг 3).
Эта команда требует РОВНО ОДНУ голову. А их накопилось восемь: ветки заводились
параллельно и ни разу не сходились обратно.

    ag_user_gender · ah_text_flags · ai_car_classes · ai_referral_lifetime
    ao_lost_item_calls · booking_cancel_reason · money_holes_20260807 · villages_district

С таким деревом alembic отвечает «Multiple head revisions are present» и НЕ НАКАТЫВАЕТ
НИЧЕГО. То есть колонки, добавленные в модели за последние недели, до прода не доезжали
вообще — ни мои, ни чужие. Локально этого не видно: там SQLite, и `_migrate_sqlite_add_columns`
(db.py) молча дописывает недостающие колонки сам. В проде PostgreSQL, и он так не умеет.

ЧТО ДЕЛАЕТ ЭТА РЕВИЗИЯ. Ничего со схемой — она пустая. Её единственная работа: назвать все
восемь голов своими предками, чтобы у дерева снова стала одна вершина и `upgrade head`
заработал. Дальше цепочка продолжается обычным порядком.

Почему пустая, а не «заодно поправим схему»: слияние должно быть проверяемым одним взглядом.
Любое действие внутри превратило бы его в место, где ошибка ищется дольше всего.
"""
revision = "az_merge_heads_20260821"
down_revision = (
    "ag_user_gender",
    "ah_text_flags",
    "ai_car_classes",
    "ai_referral_lifetime",
    "ao_lost_item_calls",
    "booking_cancel_reason",
    "money_holes_20260807",
    "villages_district",
)
branch_labels = None
depends_on = None


def upgrade() -> None:
    """Слияние веток. Схему не трогаем."""


def downgrade() -> None:
    """Разъединять обратно нечего: ревизия ничего не меняла."""
