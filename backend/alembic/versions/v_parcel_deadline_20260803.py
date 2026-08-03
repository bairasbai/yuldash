"""У доставки появляется срок: «к какому дню нужно».

parceldelivery.deliver_by — DATE, NULL. NULL = «не срочно, когда получится» (так и было
у всех старых заявок, поведение не меняется).

Зачем: у заявки была только `urgency` (bypath|now), а это про СПОСОБ, не про срок. У «по пути»
доставка зависит от того, поедет ли кто-то в ту сторону, — могла тянуться неделю. Отправитель
не понимал, доедет ли посылка сегодня или через неделю, а курьер, глядя на заявку, не знал,
ждут ли её к завтрашнему утру. Дата, а не datetime: человек мыслит днями («нужно к пятнице»).

Приватность: срок — НЕ персональные данные, а условие заказа, поэтому (в отличие от телефона
получателя и адресов) он отдаётся и в открытом списке свободных заказов — курьер решает,
берётся ли он успеть. Флаг `overdue` («срок вышел, а доставка не завершена») считается на
сервере по МЕСТНОМУ дню (Уфа UTC+5) и в БД не хранится — см. routers/parcels._is_overdue.

Аддитивно и ИДЕМПОТЕНТНО (паттерн t_parcel_address / u_parcel_chat):
- свежая БД: 0001_baseline (create_all из моделей) уже создал колонку → ревизия no-op;
- прод: add_column nullable=True без server_default (у старых заявок срока просто нет).

Прод: `alembic upgrade head`.

Revision ID: v_parcel_deadline
Revises: u_parcel_chat
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "v_parcel_deadline"
down_revision = "u_parcel_chat"
branch_labels = None
depends_on = None

_TABLE = "parceldelivery"
# NULL-able НАМЕРЕННО: «срок не указан» — это не пропуск данных, а честный вариант заказа.
_COLUMNS = [
    ("deliver_by", sa.Date()),   # нужно доставить не позже этого (местного) дня
]


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (свежая БД до create_all)
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    if not existing:   # таблицы нет → её создаст baseline/create_all уже с этой колонкой
        return
    for name, type_ in _COLUMNS:
        if name not in existing:
            op.add_column(_TABLE, sa.Column(name, type_, nullable=True))


def downgrade() -> None:
    bind = op.get_bind()
    existing = _columns(bind, _TABLE)
    for name, _type in _COLUMNS:
        if name in existing:
            with op.batch_alter_table(_TABLE) as b:
                b.drop_column(name)
