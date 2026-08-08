"""Очередь модерации витрины скидок: состояние проверки у купона + жалобы.

Зачем. Раньше у купона было одно поле `status` (draft/active/paused/archived) — «чего хочет
партнёр». Решения проверки хранить было негде, поэтому чистый купон не видел НИКТО и НИКОГДА:
автопроверка ищет телефоны/ссылки/ругань по шаблонам и пропускает «скидка 90% при предоплате
на карту». Теперь рядом живёт `review` — «что решила проверка», и есть очередь у админа.

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт колонки и таблицу из моделей → тут no-op;
- прод: coupon уже есть без колонок → добавляем со значениями, сохраняющими прежнее поведение.

Прежнее поведение сохраняется буквально: всем существующим купонам ставим review='approved'
(они уже висят в витрине и Александр их видел), а НЕ 'pending' — иначе в первый же день после
выката очередь заполнилась бы всеми старыми купонами разом.

Revision ID: af_coupon_review
Revises: merge_20260808
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "af_coupon_review"
down_revision = "merge_20260808"
branch_labels = None
depends_on = None

# (имя колонки, тип, значение по умолчанию на сервере)
_NEW_COLUMNS = (
    ("review", sa.String(length=16), sa.text("'approved'")),
    ("review_flag", sa.String(length=16), sa.text("''")),
    ("review_note", sa.String(), sa.text("''")),
    ("reviewed_at", sa.DateTime(), None),
    ("reports_count", sa.Integer(), sa.text("0")),
)


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def _tables(bind) -> set:
    try:
        return set(inspect(bind).get_table_names())
    except Exception:  # noqa: BLE001
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    cols = _columns(bind, "coupon")
    if cols:
        for name, type_, default in _NEW_COLUMNS:
            if name in cols:
                continue
            nullable = default is None
            op.add_column("coupon", sa.Column(name, type_, nullable=nullable,
                                              server_default=default))
        if "review" not in cols:
            # Индекс на review: очередь админа выбирает held/pending, витрина отсекает blocked.
            op.create_index("ix_coupon_review", "coupon", ["review"])

    if "couponreport" not in _tables(bind):
        op.create_table(
            "couponreport",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("coupon_id", sa.Integer(), sa.ForeignKey("coupon.id"), nullable=False),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
            sa.Column("reason", sa.String(length=500), nullable=False, server_default=sa.text("''")),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            # Один человек — одна жалоба на купон: повторными нажатиями очередь не засыпать.
            sa.UniqueConstraint("coupon_id", "user_id", name="uq_couponreport_coupon_user"),
        )
        op.create_index("ix_couponreport_coupon_id", "couponreport", ["coupon_id"])
        op.create_index("ix_couponreport_user_id", "couponreport", ["user_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if "couponreport" in _tables(bind):
        op.drop_table("couponreport")
    cols = _columns(bind, "coupon")
    if "review" in cols:
        try:
            op.drop_index("ix_coupon_review", table_name="coupon")
        except Exception:  # noqa: BLE001 — индекса могло не быть (создан baseline'ом иначе)
            pass
    if cols:
        with op.batch_alter_table("coupon") as batch:
            for name, _type, _default in _NEW_COLUMNS:
                if name in cols:
                    batch.drop_column(name)
