"""leaf-1.1 · alembic-ревизия refund_unique_20261002: барьер F3 должен реально доехать до прода.

Независимое ревью Opus (круг 2, 2026-10-02): `uq_ledgerentry_refund` был объявлен ТОЛЬКО на
уровне модели (`app/ledger.py`). А прод получает новые индексы ТОЛЬКО через `alembic upgrade
head` (`test_migrations_reach_production.py`) — без отдельной ревизии барьер F3 до прода не
доехал бы никогда.

Почему тесты явно СНИМАЮТ индекс после подъёма схемы до родителя этой ревизии, а не просто
останавливаются на нём. На СВЕЖЕЙ тестовой базе `0001_baseline_schema.py` делает
`SQLModel.metadata.create_all()` ИЗ ТЕКУЩИХ моделей — значит барьер F3 (он уже в `app/ledger.py`)
появляется сразу на этом самом первом шаге, одним пакетом со всем остальным, и «дойти до
родителя» на свежей базе НЕ воспроизводит интересующее нас состояние. На проде всё наоборот:
`0001_baseline` был применён один раз через `alembic stamp head` БЕЗ create_all (таблицы уже
существовали из старого тулинга — см. докстринг самого baseline) — то есть прод РЕАЛЬНО дошёл до
родителя этой ревизии, ни разу не увидев барьер F3. Снятие индекса сразу после подъёма до
родителя — честная имитация именно этой стартовой точки, а не костыль мимо архитектуры.

Почему тесты — на ОТДЕЛЬНОЙ свежей базе (createdb/dropdb на изолированном кластере
127.0.0.1:55450), а не на общем `app.db.engine` из conftest. Две причины:
1. `CREATE UNIQUE INDEX CONCURRENTLY` (прод-путь) требует настоящей таблицы вне транзакции —
   на временной/разделяемой таблице это ненадёжно.
2. Тест «миграция находит дубли» сеет заведомо испорченные данные (два возврата с одним ext_id) —
   на общем `ledgerentry`, который параллельно пишут другие тесты сессии, это было бы гонкой
   само по себе и мусорило бы чужие проверки.

Каждый тест поднимает и удаляет свою базу — дороже по времени, зато ничего не разделяет с
остальным прогоном. Требует изолированный Postgres (AUDIT_PG_BASE, по умолчанию тот же кластер,
что и у tools/audit_mutation.py:FreshPostgres) — без него тесты пропускаются, а не падают.
"""
import importlib.util
import os
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urlparse

import pytest
import sqlalchemy as sa
from alembic import command
from alembic.script import ScriptDirectory
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations

BACKEND = Path(__file__).resolve().parents[3]
REVISION_FILE = BACKEND / "alembic" / "versions" / "refund_unique_20261002.py"
PARENT_REVISION = "bv_refresh_recovery"   # ровно down_revision этой ревизии
INDEX = "uq_ledgerentry_refund"

PG_BASE = os.environ.get("AUDIT_PG_BASE", "postgresql://audit_user@127.0.0.1:55450")
PG_BIN = Path(os.environ.get("AUDIT_PG_BIN", r"C:\Program Files\PostgreSQL\16\bin"))


def _tool(name: str) -> str:
    exe = PG_BIN / (name + (".exe" if os.name == "nt" else ""))
    return str(exe) if exe.exists() else name


def _conn_args():
    p = urlparse(PG_BASE)
    return ["--host", p.hostname or "127.0.0.1", "--port", str(p.port or 5432),
            "--username", p.username or "postgres", "--no-password"]


@pytest.fixture
def fresh_db():
    """Своя пустая база на изолированном кластере; DATABASE_URL на время теста указывает туда
    (его читает alembic/env.py), после — возвращается как было. База всегда удаляется, даже
    если тест упал."""
    name = re.sub(r"[^a-z0-9_]", "_",
                  f"m_refund_{os.getpid()}_{int(time.time() * 1000)}".lower())[:60]
    args = _conn_args()
    try:
        subprocess.run([_tool("createdb"), *args, name], check=True,
                       capture_output=True, text=True, timeout=30)
    except (FileNotFoundError, OSError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as e:
        pytest.skip(f"нет изолированного PostgreSQL на {PG_BASE}: {e}")
        return
    url = f"{PG_BASE.rstrip('/')}/{name}"
    previous = os.environ.get("DATABASE_URL")
    os.environ["DATABASE_URL"] = url
    try:
        yield url
    finally:
        if previous is None:
            os.environ.pop("DATABASE_URL", None)
        else:
            os.environ["DATABASE_URL"] = previous
        subprocess.run([_tool("dropdb"), *args, "--if-exists", name], capture_output=True)


def _cfg() -> Config:
    return Config(str(BACKEND / "alembic.ini"))


def _load_migration():
    """Импорт файла ревизии напрямую (не через alembic) — только для прямого вызова upgrade()
    ВТОРОЙ раз подряд (идемпотентность, R4): `command.upgrade` через alembic_version во второй
    раз эту ревизию уже не тронул бы вообще, а нам нужно доказать, что сама функция, вызванная
    дважды, безопасна."""
    spec = importlib.util.spec_from_file_location("refund_unique_20261002_direct", REVISION_FILE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _index_names(engine) -> set[str]:
    return {ix["name"] for ix in sa.inspect(engine).get_indexes("ledgerentry")}


def _invoke_upgrade_directly(engine, migration) -> None:
    """Вызвать migration.upgrade() напрямую, в обход версии alembic_version — нужно, когда
    хотим войти в ТЕЛО функции повторно (идемпотентность, перестройка INVALID-индекса), а не
    положиться на то, что alembic сам пропустит уже применённую ревизию.

    `op.get_context().autocommit_block()` внутри upgrade() (CONCURRENTLY) требует, чтобы alembic
    САМ открыл транзакцию через begin_transaction() — ровно так, как это делает
    alembic/env.py::run_migrations_online. Без этого шага внутренний assert в autocommit_block()
    падает (self._transaction is None), даже если соединение само по себе в порядке.
    """
    with engine.connect() as conn:
        ctx = MigrationContext.configure(conn)
        with Operations.context(ctx):
            with ctx.begin_transaction():
                migration.upgrade()


def _reach_prod_like_state(fresh_db: str) -> None:
    """Поднять схему до родителя этой ревизии и снять барьер F3, который на СВЕЖЕЙ базе только
    что создал `0001_baseline`'s create_all заодно со всем остальным (см. докстринг модуля) —
    чтобы честно воспроизвести состояние, в котором реальный прод встречает эту ревизию."""
    command.upgrade(_cfg(), PARENT_REVISION)
    engine = sa.create_engine(fresh_db)
    try:
        with engine.begin() as conn:
            conn.execute(sa.text(f"DROP INDEX IF EXISTS {INDEX}"))
    finally:
        engine.dispose()


def _seed_duplicate_refund(engine, ext_id: str = "refund:order:999") -> list[int]:
    """Два возврата одной и той же комиссии — ровно то, что гонка до правки F3 могла оставить.
    Возвращает ID обеих записей (нужны тесту — сообщение об остановке называет их поимённо).

    Через ORM (SQLModel), не raw SQL: модели знают свои Python-дефолты (`name=""` и т.п.),
    которых нет на уровне колонки (NOT NULL без server_default) — raw INSERT пришлось бы
    перечислять все колонки вручную и держать в курсе каждого будущего поля `user`.
    """
    from sqlmodel import Session
    from app.models import User, LedgerEntry, LedgerKind
    with Session(engine) as s:
        user = User(phone="+70000000999")
        s.add(user)
        s.flush()
        entries = []
        for _ in range(2):
            e = LedgerEntry(driver_id=user.id, kind=LedgerKind.adj, amount_kop=15000,
                            note="тест: дубль возврата", ext_id=ext_id)
            s.add(e)
            entries.append(e)
        s.commit()
        return [e.id for e in entries]


# ==================== R1: выкатка на прод-подобной базе создаёт индекс ====================
def test_r1_upgrade_to_head_creates_the_index_on_a_prod_like_schema(fresh_db):
    _reach_prod_like_state(fresh_db)
    command.upgrade(_cfg(), "head")   # теперь реально проходит ЧЕРЕЗ код этой ревизии
    engine = sa.create_engine(fresh_db)
    try:
        assert INDEX in _index_names(engine), (
            "после `alembic upgrade head` барьера F3 нет — на проде двойной возврат "
            "комиссии снова ничем не остановлен"
        )
    finally:
        engine.dispose()


# ==================== R2: downgrade снимает индекс ====================
def test_r2_downgrade_removes_the_index(fresh_db):
    _reach_prod_like_state(fresh_db)
    command.upgrade(_cfg(), "head")
    # Откат к родителю именно этой ревизии, а не «на шаг назад»: после слияния голов
    # (bd_merge_heads_20261002) у вершины два предка, и «-1» неоднозначен.
    parent = ScriptDirectory.from_config(_cfg()).get_revision("refund_unique_20261002").down_revision
    command.downgrade(_cfg(), parent)
    engine = sa.create_engine(fresh_db)
    try:
        assert INDEX not in _index_names(engine)
    finally:
        engine.dispose()


# ==================== R3: дубль останавливает выкатку, ничего не меняя ====================
def test_r3_existing_duplicate_stops_the_upgrade_without_touching_money(fresh_db):
    _reach_prod_like_state(fresh_db)
    engine = sa.create_engine(fresh_db)
    try:
        ids = _seed_duplicate_refund(engine, "refund:order:999")

        # RuntimeError, не конкретный подкласс: alembic грузит файл ревизии своим загрузчиком,
        # отдельным от нашего _load_migration() в R4 — классы из двух загрузок не считаются
        # одним и тем же isinstance, а встроенный RuntimeError идентичен в любом случае.
        with pytest.raises(RuntimeError) as excinfo:
            command.upgrade(_cfg(), "head")
        message = str(excinfo.value)
        assert "refund:order:999" in message and all(str(i) in message for i in ids), (
            "сообщение об остановке должно называть КОНКРЕТНЫЙ ext_id и ID КАЖДОЙ строки, "
            f"иначе разбирать дубль на проде не по чему (ожидали id {ids}): {message!r}"
        )

        assert INDEX not in _index_names(engine), "индекс не должен создаваться, пока есть дубли"
        with engine.begin() as conn:
            version = conn.execute(sa.text("SELECT version_num FROM alembic_version")).scalar_one()
            rows = conn.execute(sa.text(
                "SELECT ext_id FROM ledgerentry WHERE ext_id = 'refund:order:999'"
            )).scalars().all()
        assert version == PARENT_REVISION, (
            "сорвавшаяся ревизия не должна считаться применённой — иначе повторный "
            f"`upgrade head` молча её пропустит; сейчас alembic_version={version!r}"
        )
        assert rows == ["refund:order:999", "refund:order:999"], (
            "денежные записи не должны ни переименовываться, ни удаляться — "
            f"разбор остаётся за человеком, а в базе сейчас {rows!r}"
        )
    finally:
        engine.dispose()


# ==================== R4: без дублей — идемпотентно при повторном вызове ====================
def test_r4_upgrade_without_duplicates_is_idempotent_when_called_twice(fresh_db):
    _reach_prod_like_state(fresh_db)
    engine = sa.create_engine(fresh_db)
    try:
        migration = _load_migration()
        _invoke_upgrade_directly(engine, migration)
        _invoke_upgrade_directly(engine, migration)   # второй вызов — не падает и не дублирует
        assert INDEX in _index_names(engine)
    finally:
        engine.dispose()


# ==================== R5: недостроенный (INVALID) индекс после прерванного деплоя ==========
def test_r5_invalid_index_from_an_interrupted_deploy_is_rebuilt(fresh_db):
    """Независимое ревью Opus (Н2, повторный круг 2026-10-02): `CREATE UNIQUE INDEX
    CONCURRENTLY` не атомарна — если процесс убьют снаружи посреди постройки (оборвался ssh,
    Ctrl+C на «зависшем» деплое), в каталоге остаётся индекс с ИМЕНЕМ, но помеченный
    `pg_index.indisvalid = false`. Старая проверка «есть имя → выход» и `IF NOT EXISTS` его не
    отличали от готового барьера — повторный `upgrade head` молча считал бы ревизию применённой,
    а F3 на проде так и не заработала бы. Симулируем снаружи ровно то же самое: создаём индекс
    по-настоящему, затем напрямую (как суперпользователь — audit_user на изолированном кластере)
    помечаем его невалидным через `pg_index`, не трогая остальной процесс."""
    _reach_prod_like_state(fresh_db)
    engine = sa.create_engine(fresh_db)
    try:
        migration = _load_migration()
        _invoke_upgrade_directly(engine, migration)          # индекс построен и валиден
        assert INDEX in _index_names(engine)

        with engine.begin() as conn:
            conn.execute(sa.text(
                "UPDATE pg_index SET indisvalid = false WHERE indexrelid = CAST(:n AS regclass)"
            ), {"n": INDEX})
            still_valid = conn.execute(sa.text(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = CAST(:n AS regclass)"
            ), {"n": INDEX}).scalar_one()
        assert still_valid is False, "не удалось смоделировать недостроенный индекс для теста"

        _invoke_upgrade_directly(engine, migration)          # должна заметить и перестроить

        with engine.begin() as conn:
            valid = conn.execute(sa.text(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = CAST(:n AS regclass)"
            ), {"n": INDEX}).scalar_one()
        assert valid is True, (
            "после повторного upgrade индекс должен быть ВАЛИДНЫМ — иначе прерванный деплой "
            "навсегда оставляет барьер F3 выключенным без единого сигнала об этом"
        )
    finally:
        engine.dispose()
