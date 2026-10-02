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
Решение ведущего: если дубли есть, миграция ОСТАНАВЛИВАЕТСЯ с точным списком (ext_id и ID КАЖДОЙ
строки) и НЕ меняет ни одной денежной записи, и не создаёт индекс — разбор решает человек.

Как снять остановку (если на проде реально нашлись дубли refund:*):
1. По ID строк из сообщения об ошибке сверить переводы — сколько раз и когда деньги реально
   ушли водителю (журнал ledger append-only, записи никуда не делись, id точно называет их).
2. Решить, взыскивать ли переплату — списать будущей комиссией или простить в убыток платформы;
   это решение человека, миграция его не принимает и не должна.
3. Записи НЕ удалять (история денег не переписывается). У всех строк одного ext_id, КРОМЕ
   самой ранней (минимальный id), сменить ключ по образцу money_holes_20260807.py:
   `UPDATE ledgerentry SET ext_id = ext_id || '#dup' || id WHERE id IN (<все ID этого ext_id
   кроме самого раннего>);` — деньги и след остаются, индекс их больше не связывает.
4. Повторить `alembic upgrade head` — как только дублей по ext_id не останется, индекс
   создастся сам, тем же прогоном.
Выкатка просто ждёт следующего релиза после разбора — ничего не удаляет и не портит сама.

ИДЕМПОТЕНТНО, все пути:
- свежая БД: baseline create_all уже создаёт индекс из метаданных → no-op;
- прод без дублей: создаёт индекс (и тоже no-op при повторном запуске — IF NOT EXISTS/проверка);
- прод с НЕДОСТРОЕННЫМ (INVALID) индексом после прерванного прошлого деплоя — см. ниже.

Postgres: индекс строится CONCURRENTLY (без блокировки записи в живую таблицу), поэтому вне
транзакции миграции — тем же приёмом, что и в money_holes_20260807.py. Сорвалось ВНУТРИ ЭТОГО
процесса (ошибка на построении) — сносим недостроенный индекс и падаем громко сами.

Н2 (независимое ревью Opus, повторный круг 2026-10-02). А если процесс убьют СНАРУЖИ прямо
посреди `CREATE INDEX CONCURRENTLY` — оборвался ssh в деплой-скрипте, человек нажал Ctrl+C на
«зависшем» деплое (CONCURRENTLY ждёт окончания ВСЕХ старых транзакций, в том числе ночного
`pg_dump`)? Тогда собственный `except` не успевает сработать, а в каталоге остаётся индекс с
ИМЕНЕМ, но `pg_index.indisvalid = false` — старая проверка «есть имя → выход» и `IF NOT EXISTS`
такой индекс не отличали от готового: следующий `upgrade head` молча отмечал бы ревизию
применённой, а барьера F3 на проде так и не было бы, без единого сигнала об этом. Теперь
проверяем `pg_index.indisvalid` явно (`_index_state`): недостроенный индекс сносится
(`DROP INDEX CONCURRENTLY`, той же некритичной ценой, что и постройка) и строится заново тем же
прогоном — повторный `alembic upgrade head` после прерванного деплоя теперь чинит барьер сам,
без ручного вмешательства.

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


def _index_state(bind) -> str:
    """'missing' | 'valid' | 'invalid'.

    Н2 (независимое ревью Opus, повторный круг 2026-10-02): проверка только по ИМЕНИ индекса
    (как было раньше) не отличает готовый барьер от НЕДОСТРОЕННОГО. `CREATE UNIQUE INDEX
    CONCURRENTLY` на PostgreSQL не атомарна — если процесс убьют посреди постройки (оборвался
    ssh в деплой-скрипте, человек нажал Ctrl+C на «зависшем» деплое; CONCURRENTLY ждёт окончания
    ВСЕХ старых транзакций, в том числе ночного pg_dump), индекс остаётся в каталоге с именем,
    но помеченный `pg_index.indisvalid = false` — SQLAlchemy 2.0.49 отражает такие индексы наравне
    с готовыми (проверено по исходнику), и `IF NOT EXISTS` при повторном `CREATE` тоже молча их
    пропускает. Старая проверка «есть имя → выход» в этом случае НАВСЕГДА оставила бы барьер F3
    выключенным, а `alembic_version` при этом продвинулась бы — выкатка выглядела бы успешной.
    На SQLite понятия невалидного индекса нет."""
    if INDEX not in {ix["name"] for ix in inspect(bind).get_indexes("ledgerentry")}:
        return "missing"
    if not bind.dialect.name.startswith("postgres"):
        return "valid"
    valid = bind.execute(sa.text(
        "SELECT indisvalid FROM pg_index WHERE indexrelid = CAST(:name AS regclass)"
    ), {"name": INDEX}).scalar_one()
    return "valid" if valid else "invalid"


def _find_duplicates(bind):
    """[(ext_id, [row_id, ...]), ...] среди kind=adj с префиксом 'refund:' — больше одной записи
    на ключ значит, что гонку когда-то (до правки F3 в этом же раунде) выиграли ДВЕ стороны.
    ID строк — чтобы сообщение об остановке называло ТОЧНЫЕ записи, а не только ext_id и счётчик
    (независимое ревью Opus: без ID строк разбор дубля на проде не по чему вести)."""
    rows = bind.execute(sa.text(
        "SELECT ext_id, id FROM ledgerentry "
        "WHERE kind = 'adj' AND substr(ext_id, 1, 7) = 'refund:' "
        "ORDER BY ext_id, id"
    )).all()
    by_ext: dict[str, list[int]] = {}
    for ext_id, row_id in rows:
        by_ext.setdefault(ext_id, []).append(int(row_id))
    return [(ext_id, ids) for ext_id, ids in by_ext.items() if len(ids) > 1]


def upgrade() -> None:
    bind = op.get_bind()
    if "ledgerentry" not in set(inspect(bind).get_table_names()):
        return
    state = _index_state(bind)
    if state == "valid":
        return                                  # уже готов (create_all на свежей БД)

    if state == "invalid":
        # Снести НЕДОСТРОЕННЫЙ индекс и построить заново — см. docstring _index_state выше.
        # DROP ... CONCURRENTLY — та же причина, что и у CREATE: не блокировать живую таблицу.
        with op.get_context().autocommit_block():
            op.execute(f"DROP INDEX CONCURRENTLY IF EXISTS {INDEX}")

    dupes = _find_duplicates(bind)
    if dupes:
        details = "; ".join(f"{ext_id} (id={','.join(str(i) for i in ids)})" for ext_id, ids in dupes)
        raise RefundDuplicatesFound(
            "[refund_unique_20261002] НАЙДЕНЫ дубли возврата комиссии — выкатка ОСТАНОВЛЕНА, "
            f"ничего не изменено: {details}. Водителю(ям) уже вернули одну и ту же комиссию "
            "больше одного раза (гонка до правки F3). Как разобрать: 1) сверить переводы по "
            "каждому id — сколько раз и когда деньги реально ушли водителю; 2) решить, "
            "взыскивать ли переплату (платформа может простить в убыток, может списать будущей "
            "комиссией — это решение человека, не миграции); 3) после разбора НЕ удалять записи "
            "(ledger append-only, историю денег не переписываем) — оставить только САМУЮ РАННЮЮ "
            "(минимальный id) с исходным ext_id, у остальных сменить ключ по образцу "
            "money_holes_20260807: UPDATE ledgerentry SET ext_id = ext_id || '#dup' || id WHERE "
            "id IN (...все ID этого ext_id, КРОМЕ самого раннего...); 4) повторить "
            "`alembic upgrade head` — индекс создастся сам, как только дублей по ext_id не "
            "останется."
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
