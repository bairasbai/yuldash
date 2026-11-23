"""Add inactive private same-row taxi retirement agreement seal."""
import re

from alembic import op
import sqlalchemy as sa

revision = "df_taxi_route_resume_seal"
down_revision = "de_taxi_route_edit_outcome"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMN = "route_resume_agreement_seal"
_CHECK = "ck_instantorder_route_resume_seal_len"
_EXPR = f"length({_COLUMN}) = 32"
_BAD = "Unexpected taxi retirement seal schema"


def _column():
    return sa.Column(_COLUMN, sa.LargeBinary(32), sa.CheckConstraint(_EXPR, name=_CHECK), nullable=True)


def _check_shape(conn):
    inspector = sa.inspect(conn)
    columns = {c["name"]: c for c in inspector.get_columns(_TABLE)}
    checks = [c for c in inspector.get_check_constraints(_TABLE)
              if c["name"] == _CHECK or re.search(rf"\b{_COLUMN}\b", c["sqltext"], re.I)]
    if conn.dialect.name == "sqlite":
        # SQLAlchemy reflection omits SQLite expression indexes. Inspect
        # their stored definitions too (including quoted identifiers and
        # partial predicates); ambiguous textual references refuse safely.
        mentions_seal = re.compile(rf"(?<![a-z0-9_]){_COLUMN}(?![a-z0-9_])", re.I)
        indexes = conn.exec_driver_sql(
            "SELECT sql FROM sqlite_schema WHERE type='index' AND tbl_name='instantorder' AND sql IS NOT NULL",
        ).scalars()
        if any(mentions_seal.search(sql) for sql in indexes):
            raise RuntimeError(_BAD)
    column = columns.get(_COLUMN)
    if column is None:
        if checks:
            raise RuntimeError(_BAD)
        return False
    if (not isinstance(column["type"], sa.LargeBinary)
            or not column["nullable"] or column.get("default") is not None
            or column.get("computed") is not None or column.get("identity") is not None
            or len(checks) != 1 or checks[0]["name"] != _CHECK
            or re.sub(r"\s+", "", checks[0]["sqltext"]).strip("()").lower()
            != re.sub(r"\s+", "", _EXPR)
            or checks[0].get("dialect_options", {}).get("not_valid")):
        raise RuntimeError(_BAD)
    if conn.dialect.name == "sqlite":
        # Enforce the exact inline declaration emitted by metadata and by
        # this migration. In particular don't accept a column with the
        # same affinity but foreign collation/constraints, or a table-level
        # CHECK that would survive DROP COLUMN in SQLite.
        sql = conn.exec_driver_sql(
            "SELECT sql FROM sqlite_schema WHERE type='table' AND name='instantorder'",
        ).scalar_one()
        definition = rf",\s*{_COLUMN}\s+BLOB\s+CONSTRAINT\s+{_CHECK}\s+CHECK\s*\(length\({_COLUMN}\)\s*=\s*32\)\s*[,)]"
        if not re.search(definition, sql, re.I):
            raise RuntimeError(_BAD)
    if (any(_COLUMN in (x.get("column_names") or ()) for x in inspector.get_indexes(_TABLE))
            or any(_COLUMN in x["column_names"] for x in inspector.get_unique_constraints(_TABLE))
            or any(_COLUMN in x["constrained_columns"] for x in inspector.get_foreign_keys(_TABLE))):
        raise RuntimeError(_BAD)
    return True


def _locked(action):
    conn = op.get_bind()
    if conn.dialect.name == "postgresql":
        conn.exec_driver_sql("LOCK TABLE instantorder IN ACCESS EXCLUSIVE MODE")
        action(conn)
    elif conn.dialect.name == "sqlite":
        # Alembic handles SQLite DDL as nontransactional. For both directions
        # take the real writer before inspection and keep all DDL in it.
        with op.get_context().autocommit_block():
            conn.exec_driver_sql("BEGIN IMMEDIATE")
            try:
                action(conn)
                conn.exec_driver_sql("COMMIT")
            except BaseException:
                conn.exec_driver_sql("ROLLBACK")
                raise
    else:
        raise RuntimeError("Unsupported taxi retirement seal migration dialect")


def upgrade() -> None:
    if op.get_context().as_sql:
        op.add_column(_TABLE, _column())
        return

    def add(conn):
        if not _check_shape(conn):
            op.add_column(_TABLE, _column())
            if not _check_shape(conn):
                raise RuntimeError(_BAD)

    _locked(add)


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Taxi retirement seal downgrade requires an online drained-state check")

    def drop(conn):
        if not _check_shape(conn):
            # SQLite complete DDL may precede Alembic's version stamp.
            return
        if conn.exec_driver_sql(
            "SELECT 1 FROM instantorder WHERE route_resume_agreement_seal IS NOT NULL LIMIT 1",
        ).first():
            raise RuntimeError("Drain taxi retirement agreement seals before downgrade")
        op.drop_column(_TABLE, _COLUMN)

    _locked(drop)
