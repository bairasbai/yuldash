"""Такси: круговой рейс, смена адреса в пути, остановки (волны 158–159, 2026-08-21).

Колонки таблицы instantorder, добавленные тремя решениями Александра:

  • круговой рейс — «отвези и привези обратно» с скидкой на обратную дорогу;
  • смена адреса назначения прямо в поездке, с расчётом «проеденное + остаток»;
  • остановки по пути с координатами (у попутки они строкой названий, там цену ставит
    сам водитель; в такси считает сервер, и без координат остановку не оплатить).

Плюс `pricing_k` — полный множитель цены. До него на заказе жил только `surge_k` (спрос),
и любой пересчёт давал цену НИЖЕ настоящей: ночью, в метель, в дальней подаче. Тихо и всегда
в одну сторону, против водителя.

ИДЕМПОТЕНТНО: каждая колонка проверяется отдельно. На свежей БД их уже создал create_all
из моделей — тогда ревизия просто ничего не делает.

Прод: `alembic upgrade head` (идёт после слияния голов, az_merge_heads_20260821).
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ba_taxi_route_edit"
down_revision = "az_merge_heads_20260821"
branch_labels = None
depends_on = None

_TABLE = "instantorder"

# (имя, тип, значение по умолчанию). Значение обязательно: таблица не пустая, а колонка
# без умолчания на живых строках даёт NULL там, где код ждёт число, — и падает уже в проде.
_COLUMNS = (
    # --- круговой рейс ---
    ("round_trip", sa.Boolean(), "false"),
    ("return_wait_min", sa.Integer(), "0"),
    # --- цена ---
    # Полный множитель: спрос × ночь × погода × дальняя подача. Единица = «без наценок».
    ("pricing_k", sa.Float(), "1.0"),
    # Сколько машина реально прошла с пассажиром — основа расчёта при смене адреса.
    ("driven_km", sa.Float(), "0.0"),
    # --- смена адреса ---
    ("destination_changed_at", sa.DateTime(), None),
    ("destination_changes", sa.Integer(), "0"),
    ("destination_ack_at", sa.DateTime(), None),
    # Предложенная, но ещё не применённая смена (ждёт согласия водителя).
    ("pending_to_lat", sa.Float(), None),
    ("pending_to_lng", sa.Float(), None),
    ("pending_to_text", sa.String(), "''"),
    ("pending_price", sa.Integer(), None),
    ("pending_asked_at", sa.DateTime(), None),
    ("pending_reason", sa.String(), "''"),
    # Водитель завершил поездку досрочно и почему.
    ("early_finish_reason", sa.String(), "''"),
    # --- остановки ---
    ("waypoints_json", sa.String(), "''"),
    ("stop_started_at", sa.DateTime(), None),
)


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def upgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    if not есть:
        return          # таблицы нет — её создаст create_all из моделей
    for name, coltype, default in _COLUMNS:
        if name in есть:
            continue
        # nullable=True даже там, где в модели значение обязательно: на живых строках
        # колонку иначе не добавить, а умолчание и так заполнит их правильным значением.
        op.add_column(_TABLE, sa.Column(name, coltype, nullable=True,
                                        server_default=default))


def downgrade() -> None:
    bind = op.get_bind()
    есть = _columns(bind)
    for name, _coltype, _default in reversed(_COLUMNS):
        if name in есть:
            op.drop_column(_TABLE, name)
