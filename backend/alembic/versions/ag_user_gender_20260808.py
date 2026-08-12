"""Пол переезжает с профиля водителя на пользователя: «только женщины» становится правилом.

Зачем. Отметка «Только женщины» на попутке ничем не проверялась — проверять было нечем:
пол лежал на `DriverProfile.gender`, а у пассажира профиля водителя нет вовсе. Женщина
выбирала такую поездку как гарантию, а сервер пускал в неё кого угодно (аудит 2026-08-08).

Что делает миграция:
- добавляет `user.gender` ("" | female | male, по умолчанию не указан);
- ПЕРЕНОСИТ уже указанные значения из `driverprofile.gender` — женщины-водители, которые
  однажды отметили себя, не должны делать это заново;
- старую колонку НЕ удаляет: данные целы, а код её больше не читает (см. models.py).

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создал `user.gender` из модели → добавление пропускаем,
  перенос всё равно выполняем (на пустой таблице это no-op);
- прод: колонки нет → добавляем и переносим.

Revision ID: ag_user_gender
Revises: af_coupon_review
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "ag_user_gender"
down_revision = "af_coupon_review"
branch_labels = None
depends_on = None


def _columns(bind, table: str) -> set:
    try:
        return {c["name"] for c in inspect(bind).get_columns(table)}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline) → считаем пустой
        return set()


def upgrade() -> None:
    bind = op.get_bind()
    user_cols = _columns(bind, "user")
    if not user_cols:
        return                      # таблицы нет — baseline создаст всё сам из моделей
    if "gender" not in user_cols:
        op.add_column("user", sa.Column("gender", sa.String(length=8),
                                        nullable=False, server_default=sa.text("''")))
    # Перенос: только реально указанные значения и только тем, у кого на User ещё пусто.
    # Кавычки вокруг user обязательны — в Postgres это зарезервированное слово.
    if "gender" in _columns(bind, "driverprofile"):
        op.execute(sa.text(
            'UPDATE "user" SET gender = ('
            "  SELECT dp.gender FROM driverprofile dp"
            '  WHERE dp.user_id = "user".id AND dp.gender IN (\'female\', \'male\')'
            "  LIMIT 1"
            ") "
            'WHERE COALESCE("user".gender, \'\') = \'\' '
            "  AND EXISTS ("
            "    SELECT 1 FROM driverprofile dp2"
            '    WHERE dp2.user_id = "user".id AND dp2.gender IN (\'female\', \'male\')'
            "  )"
        ))


def downgrade() -> None:
    bind = op.get_bind()
    if "gender" in _columns(bind, "user"):
        with op.batch_alter_table("user") as batch:
            batch.drop_column("gender")
