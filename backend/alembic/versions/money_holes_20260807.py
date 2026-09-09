"""Барьер БД против двойной компенсации промокода: частичный UNIQUE на ledgerentry.ext_id.

Зачем (аудит 2026-08-07). Остаток промо-скидки, не влезший в комиссию, платформа доплачивает
водителю в кошелёк записью kind=adj с ключом "promo:{order_id}". Идемпотентность держалась
ТОЛЬКО прикладной проверкой «такая запись уже есть?», а между проверкой и вставкой успевает
вклиниться параллельный «Завершил» (двойной тап / ретрай сети): обе сессии видят «нет» и обе
пишут — водителю падает двойная компенсация. Долг от той же гонки давно защищён
UNIQUE(order_id); компенсация такой защиты не имела.

Почему индекс ЧАСТИЧНЫЙ. Тем же ext_id помечаются выплата (kind=payout) и её возврат при
отказе банка (kind=adj, ключ "payout:…"), а у earn/fee ext_id пустой — сплошная уникальность
запретила бы законные записи. Условие сужено ровно до неймспейса компенсаций ("promo:…").

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт индекс из метаданных (app/ledger.py) → no-op;
- прод: сначала расшиваем возможные дубли, потом создаём индекс.

Дубли НЕ удаляем — ledger append-only, историю денег не переписываем. Лишней записи меняем
только ключ на "promo:{id}#dup{row_id}": деньги и след остаются, индекс их больше не связывает,
а разбор (вернуть ли переплату) остаётся человеческим решением админа.

Postgres: индекс строится CONCURRENTLY (без блокировки записи в живую таблицу), поэтому вне
транзакции миграции. Сорвалось — сносим недостроенный индекс и падаем громко: тихо оставить
барьер выключенным хуже, чем не выкатиться.

Revision ID: money_holes_20260807
Revises: ad_rating_tags
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "money_holes_20260807"
down_revision = "ad_rating_tags"
branch_labels = None
depends_on = None

INDEX = "uq_ledgerentry_promo_comp"
# substr вместо LIKE: одинаково работает на SQLite и Postgres и не тащит «%» в DDL.
_PREFIX = "substr(ext_id, 1, 6) = 'promo:'"


def _has_index(bind) -> bool:
    try:
        return INDEX in {ix["name"] for ix in inspect(bind).get_indexes("ledgerentry")}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline)
        return False


def upgrade() -> None:
    bind = op.get_bind()
    if "ledgerentry" not in set(inspect(bind).get_table_names()):
        return
    if _has_index(bind):
        return                                  # уже есть (create_all на свежей БД)
    pg = bind.dialect.name.startswith("postgres")
    # Untyped literal resolves to the column type (enum or varchar). Casting an enum
    # to text is STABLE, not IMMUTABLE, and PostgreSQL rejects it in index predicates.
    kind = "kind"

    # --- 1. Расшиваем дубли: у всех, кроме самой первой записи по ключу, меняем ext_id.
    dupes = bind.execute(sa.text(
        f"SELECT e.id, e.ext_id FROM ledgerentry e "
        f"WHERE e.{kind} = 'adj' AND substr(e.ext_id, 1, 6) = 'promo:' "
        f"  AND EXISTS (SELECT 1 FROM ledgerentry p WHERE p.ext_id = e.ext_id "
        f"              AND p.{kind} = 'adj' AND p.id < e.id)"
    )).all()
    for row_id, ext in dupes:
        bind.execute(sa.text("UPDATE ledgerentry SET ext_id = :new WHERE id = :id"),
                     {"new": f"{ext}#dup{row_id}", "id": row_id})
    if dupes:
        print(f"[money_holes] расшито дублей компенсации промокода: {len(dupes)} "
              f"(записи сохранены, ключи помечены #dup — разобрать переплату вручную)")

    # --- 2. Сам барьер.
    where = f"{kind} = 'adj' AND {_PREFIX}"
    if pg:
        try:
            with op.get_context().autocommit_block():
                op.execute(f"CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS {INDEX} "
                           f"ON ledgerentry (ext_id) WHERE {where}")
        except Exception:
            with op.get_context().autocommit_block():
                op.execute(f"DROP INDEX IF EXISTS {INDEX}")   # недостроенный INVALID — убираем
            raise
    else:
        op.execute(f"CREATE UNIQUE INDEX IF NOT EXISTS {INDEX} "
                   f"ON ledgerentry (ext_id) WHERE {where}")


def downgrade() -> None:
    bind = op.get_bind()
    if "ledgerentry" in set(inspect(bind).get_table_names()):
        op.execute(f"DROP INDEX IF EXISTS {INDEX}")
