"""Возврат оплачивает курьеру дорогу: «получателя не было» больше не бесплатно (2026-08-29).

ЗАЧЕМ. Последнее место, где такси и курьер считали деньги по-разному. Пассажир не вышел —
водитель получает подачу, дорогу и ожидание. Получатель не открыл дверь — курьер вёз коробку
обратно и получал НОЛЬ: Сибай → Акъяр это 180 км туда-обратно за свой счёт и полдня времени.

Правило (решение Александра, 29.08): платим за то, что действительно случилось.
Курьер отметил хотя бы одну попытку вручения → отправитель компенсирует ему дорогу и
ожидание, но НЕ саму доставку — коробку-то не вручили. Ни одной попытки не отмечено →
не платим ничего: иначе появится «поехал, никого не было, поверьте на слово».

ЧТО ДОБАВЛЯЕТ (обе — в `parceldelivery`):
  `distance_km` — длина маршрута доставки, посчитанная при создании заказа. Раньше её
      нигде не хранили: в цене она растворялась одним числом, и разложить сумму обратно
      было нечем. Компенсация за возврат считается именно по километрам маршрута.
  `return_fee_kop` — компенсация возврата, ЗАФИКСИРОВАННАЯ в момент закрытия. Не считаем
      её заново при каждом открытии экрана: сумма должна перестать меняться ровно тогда,
      когда дело закрыто, иначе чек «растёт» сам по себе.

ИДЕМПОТЕНТНО: на свежей БД колонки приходят из `create_all` → ревизия no-op; на проде
добавляются со значением по умолчанию, существующие доставки не переписываются.

Прод: `alembic upgrade head`.

Revision ID: bj_return_pays_the_road
Revises: bi_courier_catches_up
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "bj_return_pays_the_road"
down_revision = "bi_courier_catches_up"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
# (имя, тип, значение по умолчанию для уже существующих строк)
_COLUMNS = (
    ("distance_km", sa.Float(), "0"),
    ("return_fee_kop", sa.BigInteger(), "0"),
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
