"""Курьер догоняет такси: дорога к посылке, зимняя дорога, ночь и ожидание (2026-08-28).

ЗАЧЕМ. Всё, что в такси чинили целой волной, у курьера осталось как было:

  • курьер ехал за посылкой в соседнее село ДАРОМ — 15 км за свой счёт;
  • стоял у двери сорок минут — бесплатно;
  • в гололёд вёз по той же дороге, что таксист, но без компенсации;
  • ночью доставка стоила столько же, сколько днём, и ночью её никто не брал.

ЧТО ДОБАВЛЯЕТ (все — в `parceldelivery`):
  `pickup_fee_kop` / `pickup_km` / `pickup_pending` / `pickup_enroute` — дорога курьера
      к посылке. Считается не по GPS (координат курьера у нас нет), а от города, где он
      работает; фиксируется в момент, когда он берёт заказ. До этого отправителю показан
      потолок, а не выдуманное число.
  `weather_fee_kop` / `weather_kind` — зимняя дорога, тем же расчётом, что у такси.
  `night_k` — ночная надбавка, зафиксированная на доставке (1.0 = день). Нужна чеку:
      «ночь +15%» объясняет, а молча выросшая сумма — нет.
  `waiting_started_at` / `waiting_sender_kop` / `waiting_receiver_kop` — платное ожидание.
      Раздельно по концам: курьер ждёт и отправителя, и получателя, и задерживают его
      разные люди. В чеке видно, где сколько набежало, — делят это между собой они сами.

ИДЕМПОТЕНТНО: на свежей БД колонки приходит из `create_all` → ревизия no-op; на проде
добавляются со значением по умолчанию, существующие доставки не переписываются.

Прод: `alembic upgrade head`.

Revision ID: bi_courier_catches_up
Revises: bh_winter_road
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bi_courier_catches_up"
down_revision = "bh_winter_road"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
# «Что-то не так с ценой» теперь есть и у доставки — жалобы двух режимов надо различать.
_COMPLAINT_TABLE = "pricecomplaint"
# (имя, тип, значение по умолчанию для уже существующих строк)
_COLUMNS = (
    ("pickup_fee_kop", sa.BigInteger(), "0"),
    ("pickup_km", sa.Float(), "0"),
    ("pickup_pending", sa.Boolean(), sa.text("false")),
    ("pickup_enroute", sa.Boolean(), sa.text("false")),
    ("weather_fee_kop", sa.BigInteger(), "0"),
    ("weather_kind", sa.String(length=16), "''"),
    ("night_k", sa.Float(), "1.0"),
    ("waiting_started_at", sa.DateTime(), None),
    ("waiting_sender_kop", sa.BigInteger(), "0"),
    ("waiting_receiver_kop", sa.BigInteger(), "0"),
)


def _columns(bind) -> set:
    insp = inspect(bind)
    if _TABLE not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(_TABLE)}


def _table_columns(bind, table: str) -> set:
    insp = inspect(bind)
    if table not in set(insp.get_table_names()):
        return set()
    return {c["name"] for c in insp.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    have = _columns(bind)
    if have:
        for name, type_, default in _COLUMNS:
            if name in have:
                continue
            op.add_column(_TABLE, sa.Column(name, type_, nullable=True, server_default=default))
    жалобы = _table_columns(bind, _COMPLAINT_TABLE)
    if жалобы and "kind" not in жалобы:
        op.add_column(_COMPLAINT_TABLE,
                      sa.Column("kind", sa.String(length=16), nullable=True, server_default="'taxi'"))


def downgrade() -> None:
    for name, _type, _default in reversed(_COLUMNS):
        try:
            op.drop_column(_TABLE, name)
        except Exception:  # noqa: BLE001 — колонки может не быть, откат не должен падать
            pass
