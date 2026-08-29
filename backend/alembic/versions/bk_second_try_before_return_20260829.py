"""Повторный заезд по просьбе отправителя: ступенька между «не застал» и возвратом (2026-08-29).

ЗАЧЕМ. После `bj_return_pays_the_road` возврат перестал быть бесплатным для отправителя —
и обнажилась дыра рядом. Между «курьер не застал получателя» и «плати за возврат почти
полную стоимость доставки» не было ни одной ступеньки. Человек вышел в магазин на два часа
— и это стоило отправителю 1 100 ₽ на межгороде. У Royal Mail в этом месте восемнадцать
дней и заказ передоставки, у UPS — платный повторный выезд по просьбе.

Мы берём правило UPS: платит тот, кто ПОПРОСИЛ изменение. Отправитель нажимает «получатель
уже дома, заедь ещё раз» — заезд оплачивается как половина маршрута (`courier_redeliver_km_k`),
не больше `courier_redeliver_max` раз. Заезд по инициативе самого курьера не считается: иначе
попытки крутятся в одиночку, а платит отправитель.

ЧТО ДОБАВЛЯЕТ (в `parceldelivery`):
  `redeliver_requests` — сколько повторных заездов попросил отправитель. Счётчик просьб,
      а не поездок: платим за пересечение просьб и реальных попыток курьера.

ИДЕМПОТЕНТНО: на свежей БД колонка приходит из `create_all` → ревизия no-op; на проде
добавляется со значением 0, существующие доставки не переписываются.

Прод: `alembic upgrade head`.

Revision ID: bk_second_try_before_return
Revises: bj_return_pays_the_road
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bk_second_try_before_return"
down_revision = "bj_return_pays_the_road"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
_COLUMNS = (
    ("redeliver_requests", sa.Integer(), "0"),
)


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    have = _columns(op.get_bind())
    if not have:
        return
    for name, type_, default in _COLUMNS:
        if name in have:
            continue
        op.add_column(_TABLE, sa.Column(name, type_, nullable=True, server_default=default))


def downgrade() -> None:
    for name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(_TABLE, name)
        except Exception:  # noqa: BLE001 — колонки может не быть, откат не должен падать
            pass
