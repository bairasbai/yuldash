"""Подписки браузера на Web Push: таблица webpushsubscription.

Что это даёт человеку. До сих пор уведомления получал только тот, кто поставил
приложение из магазина: «водитель подъехал», «новое сообщение», «заказ принят» шли
через Firebase, то есть только на Android. Человек, открывший сайт, не узнавал НИЧЕГО —
он должен был сидеть с открытым экраном и ждать.

Клиентская часть веб-пуша в `webapp/` была написана целиком (подписка, service worker,
переходы по клику) и упиралась в отсутствующий эндпоинт: браузер подписывался, отправлял
подписку на сервер и получал 404. Формально всё работало, фактически — никому ничего
не приходило (сверка «PWA = приложение», слой уведомлений, 2026-08-31).

Почему отдельная таблица, а не `devicetoken`. У FCM устройство — это одна строка-токен.
У браузера сообщение ШИФРУЕТСЯ ключами самой подписки (`p256dh` + `auth`), и без них
отправить нельзя ничего. Три разные сущности в одном поле — это разбор строки на каждой
отправке и первая же путаница при чистке.

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline `create_all` уже создаёт таблицу из модели → тут no-op;
- прод: создаём таблицу, если её ещё нет.

Revision ID: bq_web_push
Revises: bp_dirty_car_demand
"""
from alembic import op
import sqlalchemy as sa

revision = "bq_web_push"
down_revision = "bp_dirty_car_demand"
branch_labels = None
depends_on = None

TABLE = "webpushsubscription"


def upgrade() -> None:
    bind = op.get_bind()
    if TABLE in sa.inspect(bind).get_table_names():
        return  # уже создана baseline'ом — ничего не делаем
    op.create_table(
        TABLE,
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("user.id"), nullable=False),
        # Адрес пуш-сервиса браузера. Уникален: одна подписка — один человек.
        sa.Column("endpoint", sa.String(), nullable=False),
        sa.Column("p256dh", sa.String(), nullable=False, server_default=""),
        sa.Column("auth", sa.String(), nullable=False, server_default=""),
        sa.Column("content_encoding", sa.String(), nullable=False, server_default="aes128gcm"),
        sa.Column("device_id", sa.String(), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
    )
    op.create_index(f"ix_{TABLE}_user_id", TABLE, ["user_id"])
    op.create_index(f"ix_{TABLE}_endpoint", TABLE, ["endpoint"], unique=True)
    op.create_index(f"ix_{TABLE}_device_id", TABLE, ["device_id"])


def downgrade() -> None:
    bind = op.get_bind()
    if TABLE not in sa.inspect(bind).get_table_names():
        return
    op.drop_table(TABLE)
