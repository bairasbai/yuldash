"""Барьер БД против двойного возврата комиссии: частичный UNIQUE на ledgerentry.ext_id (F3).

Зачем (независимое ревью Opus, leaf-1.1, круг 2, 2026-10-02). `debt.refund_commission_to_wallet`
возвращает водителю УЖЕ ПЕРЕВЕДЁННУЮ комиссию при подтверждении жалобы «не заплатили» — ключ
"refund:order:{id}" / "refund:parcel:{id}" (`debt.refund_ext_id`). Идемпотентность держалась
только прикладной проверкой «уже возвращали?» и (с этого же раунда) вставкой под SAVEPOINT — но
сам барьер в БД (частичный UNIQUE, зеркало `uq_ledgerentry_promo_comp`) объявлен только на уровне
модели (`app/ledger.py`). На свежей базе `create_all` берёт его из метаданных сразу, а прод живёт
ИСКЛЮЧИТЕЛЬНО через `alembic upgrade head` (`test_migrations_reach_production.py`) — индекс,
объявленный только в модели, до прода не доедет никогда, и защита от двойного возврата там
не сработает.

Почему НЕ чиним дубли автоматически (отличие от money_holes_20260807.py). Там лишняя запись —
переплаченная КОМПЕНСАЦИЯ, админ разбирает её на досуге, сам факт перевода не теряется (ключ
просто теряет уникальность, деньги остаются как есть). Здесь дубль — это ПЕРЕВОД ЖИВЫХ ДЕНЕГ
ВОДИТЕЛЮ ВТОРОЙ РАЗ: платформа уже отдала и потеряла их без следа. Переименовать ext_id и тихо
создать индекс значило бы одновременно (а) стереть различимость «это был дубль» неинформативным
суффиксом и (б) с этого момента выдавать код за «проблема решена», хотя деньги никто не вернул.
Решение ведущего: если дубли есть, миграция ОСТАНАВЛИВАЕТСЯ с точным списком (ext_id, сколько раз)
и НЕ меняет ни одной денежной записи, и не создаёт индекс — разбор (сверить переводы, взыскивать
ли переплату) решает человек. Выкатка просто ждёт следующего релиза после разбора; `alembic
upgrade head` можно повторять сколько угодно раз — после того как дублей не останется, индекс
создастся сам.

ИДЕМПОТЕНТНО, оба пути:
- свежая БД: baseline create_all уже создаёт индекс из метаданных → no-op;
- прод без дублей: создаёт индекс (и тоже no-op при повторном запуске — IF NOT EXISTS/проверка).

Postgres: индекс строится CONCURRENTLY (без блокировки записи в живую таблицу), поэтому вне
транзакции миграции — тем же приёмом, что и в money_holes_20260807.py. Сорвалось — сносим
недостроенный индекс и падаем громко: тихо оставить барьер выключенным хуже, чем не выкатиться.

Revision ID: refund_unique_20261002
Revises: bv_refresh_recovery
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "refund_unique_20261002"
down_revision = "bv_refresh_recovery"
branch_labels = None
depends_on = None

INDEX = "uq_ledgerentry_refund"
# substr вместо LIKE — одинаково работает на SQLite и Postgres и не тащит «%» в DDL.
# 7 — длина префикса "refund:" (debt.refund_ext_id).
_PREFIX = "substr(ext_id, 1, 7) = 'refund:'"


class RefundDuplicatesFound(RuntimeError):
    """Остановка выкатки: на проде уже есть дубли возврата комиссии — разбор за человеком,
    миграция сама по себе ничего не удаляет и не переименовывает."""


def _has_index(bind) -> bool:
    try:
        return INDEX in {ix["name"] for ix in inspect(bind).get_indexes("ledgerentry")}
    except Exception:  # noqa: BLE001 — таблицы ещё нет (до baseline)
        return False


def _find_duplicates(bind):
    """[(ext_id, count), ...] среди kind=adj с префиксом 'refund:' — больше одной записи на
    ключ значит, что гонку когда-то (до правки F3 в этом же раунде) выиграли ДВЕ стороны."""
    rows = bind.execute(sa.text(
        "SELECT ext_id, COUNT(*) AS n FROM ledgerentry "
        "WHERE kind = 'adj' AND substr(ext_id, 1, 7) = 'refund:' "
        "GROUP BY ext_id HAVING COUNT(*) > 1 ORDER BY ext_id"
    )).all()
    return [(r[0], r[1]) for r in rows]


def upgrade() -> None:
    bind = op.get_bind()
    if "ledgerentry" not in set(inspect(bind).get_table_names()):
        return
    if _has_index(bind):
        return                                  # уже есть (create_all на свежей БД)

    dupes = _find_duplicates(bind)
    if dupes:
        details = "; ".join(f"{ext_id} × {n}" for ext_id, n in dupes)
        raise RefundDuplicatesFound(
            "[refund_unique_20261002] НАЙДЕНЫ дубли возврата комиссии — выкатка ОСТАНОВЛЕНА, "
            f"ничего не изменено: {details}. Водителю(ям) уже вернули одну и ту же комиссию "
            "больше одного раза (гонка до правки F3). Разберите вручную (сверить переводы, "
            "решить — взыскивать ли переплату с платформы в убыток), затем повторите "
            "`alembic upgrade head` — индекс создастся сам, как только дублей не останется."
        )

    pg = bind.dialect.name.startswith("postgres")
    where = f"kind = 'adj' AND {_PREFIX}"
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
