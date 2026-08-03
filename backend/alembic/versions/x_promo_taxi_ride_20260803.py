"""Промокод-скидка на поездку в такси (M2, kind="taxi_ride").

Новые колонки:
- instantorder.promo_discount_kop — скидка, ЗАФИКСИРОВАННАЯ в заказе (копейки, по умолчанию 0);
- promoredemption.discount_kop     — сколько скидки выдано человеку при активации кода;
- promoredemption.used_order_id    — на каком заказе она потрачена (FK → instantorder.id, NULL = цела);
- promoredemption.used_at          — когда потрачена.

Зачем колонка на заказе, а не расчёт на лету: цену со скидкой человек видит ДО заказа, и она не
должна «уехать» задним числом (изменили кампанию, подорожал тариф). Заодно это единственный
надёжный способ посчитать деньги на завершении: комиссия платформы уменьшается ровно на ту
скидку, что была обещана, а остаток доплачивается водителю в кошелёк — водитель не теряет
ни копейки (см. app/promo_ride.py).

Суммы — BigInteger в копейках, как остальные денежные поля после p3_money_bigint.

Аддитивно и ИДЕМПОТЕНТНО (паттерн v_parcel_deadline / w2_money_rules):
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонки → ревизия no-op;
- прод: add_column со server_default '0' — у старых заказов и погашений скидки просто нет
  (used_order_id/used_at nullable: «не потрачена» — валидное состояние, а не пропуск данных).

Прод: `alembic upgrade head`.

Revision ID: x_promo_taxi_ride
Revises: v_parcel_deadline
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "x_promo_taxi_ride"
down_revision = "v_parcel_deadline"
branch_labels = None
depends_on = None

_ORDER_TABLE = "instantorder"
_REDEMPTION_TABLE = "promoredemption"


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    order_cols = _columns(bind, _ORDER_TABLE)
    if order_cols and "promo_discount_kop" not in order_cols:
        op.add_column(_ORDER_TABLE, sa.Column(
            "promo_discount_kop", sa.BigInteger(), nullable=False, server_default="0"))
    red_cols = _columns(bind, _REDEMPTION_TABLE)
    if red_cols:
        if "discount_kop" not in red_cols:
            op.add_column(_REDEMPTION_TABLE, sa.Column(
                "discount_kop", sa.BigInteger(), nullable=False, server_default="0"))
        if "used_order_id" not in red_cols:
            # Без ForeignKey в ALTER: на SQLite он не навешивается отдельным ALTER, а на проде
            # (Postgres) целостность и так стережёт код — заказ со скидкой не чистится
            # ретеншеном (app/cleanup.py) и отвязывается при удалении аккаунта (app/account.py).
            op.add_column(_REDEMPTION_TABLE, sa.Column(
                "used_order_id", sa.Integer(), nullable=True))
            op.create_index("ix_promoredemption_used_order_id", _REDEMPTION_TABLE, ["used_order_id"])
        if "used_at" not in red_cols:
            op.add_column(_REDEMPTION_TABLE, sa.Column("used_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    red_cols = _columns(bind, _REDEMPTION_TABLE)
    if "used_order_id" in red_cols:
        try:
            op.drop_index("ix_promoredemption_used_order_id", table_name=_REDEMPTION_TABLE)
        except Exception:  # noqa: BLE001 — индекса может не быть (создан create_all под другим именем)
            pass
    drop_red = [c for c in ("used_at", "used_order_id", "discount_kop") if c in red_cols]
    if drop_red:
        with op.batch_alter_table(_REDEMPTION_TABLE) as batch:
            for col in drop_red:
                batch.drop_column(col)
    if "promo_discount_kop" in _columns(bind, _ORDER_TABLE):
        with op.batch_alter_table(_ORDER_TABLE) as batch:
            batch.drop_column("promo_discount_kop")
