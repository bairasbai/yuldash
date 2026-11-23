"""Inactive taxi route retirement handoff storage."""
import sqlalchemy as sa
from alembic import op

revision = "dd_taxi_route_resume"
down_revision = "dc_merge_taxi_consent_receipts"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMNS = {
    "route_resume_id": sa.String(32),
    "route_resume_previous_driver_id": sa.Integer(),
    "route_resume_retired_at": sa.DateTime(),
    "route_resume_deadline_at": sa.DateTime(),
    "route_resume_ready_at": sa.DateTime(),
    "route_resume_claim_id": sa.String(32),
}
_INDEXES = {
    "ix_instantorder_route_resume_ready":
        ("route_resume_ready_at", "route_resume_id IS NOT NULL AND status = 'searching'"),
    "ix_instantorder_route_resume_deadline":
        ("route_resume_deadline_at", "route_resume_id IS NOT NULL AND status IN ('searching', 'offered')"),
}


def _run_schema_change(fn):
    connection = op.get_bind()
    if connection.dialect.name != "sqlite" or op.get_context().as_sql:
        fn()
        return
    # Alembic's SQLite environment assumes non-transactional DDL. Explicitly
    # own a physical writer transaction for every checked schema operation.
    with op.get_context().autocommit_block():
        connection.exec_driver_sql("BEGIN IMMEDIATE")
        try:
            fn()
            connection.exec_driver_sql("COMMIT")
        except BaseException:
            connection.exec_driver_sql("ROLLBACK")
            raise


def _add():
    connection = op.get_bind()
    inspector = None if op.get_context().as_sql else sa.inspect(connection)
    columns = {item["name"]: item for item in inspector.get_columns(_TABLE)} if inspector else {}
    indexes = {item["name"]: item for item in inspector.get_indexes(_TABLE)} if inspector else {}
    for name, kind in _COLUMNS.items():
        current = columns.get(name)
        if current is None:
            op.add_column(_TABLE, sa.Column(name, kind, nullable=True))
        elif (not current["nullable"] or current.get("default") is not None
                or not isinstance(current["type"], type(kind))
                or getattr(current["type"], "length", None) != getattr(kind, "length", None)
                or getattr(current["type"], "timezone", False)):
            raise RuntimeError(f"Unexpected {_TABLE}.{name} definition")
    for name, (column, predicate) in _INDEXES.items():
        provided = indexes.get(name)
        if provided is None:
            op.create_index(name, _TABLE, [column, "id"], unique=False,
                            sqlite_where=sa.text(predicate), postgresql_where=sa.text(predicate))
        else:
            where = provided.get("dialect_options", {}).get(connection.dialect.name + "_where")
            # Existing indexes can come from bootstrap metadata; require a
            # partial predicate and the exact expected key order. PostgreSQL
            # reflection may add parentheses, enum casts or ANY formatting.
            if provided["unique"] or provided["column_names"] != [column, "id"] or where is None:
                raise RuntimeError(f"Unexpected {name} definition")


def upgrade() -> None:
    _run_schema_change(_add)


def _remove():
    connection = op.get_bind()
    if connection.dialect.name == "postgresql":
        connection.exec_driver_sql("LOCK TABLE instantorder IN ACCESS EXCLUSIVE MODE")
    inspector = sa.inspect(connection)
    names = {column["name"] for column in inspector.get_columns(_TABLE)}
    indexes = {index["name"] for index in inspector.get_indexes(_TABLE)}
    present_columns = _COLUMNS.keys() & names
    present_indexes = _INDEXES.keys() & indexes
    if not present_columns and not present_indexes:
        # SQLite physically committed the complete downgrade but the Alembic
        # version stamp didn't: finish the version update, not a second DROP.
        return
    if present_columns != _COLUMNS.keys() or present_indexes != _INDEXES.keys():
        raise RuntimeError("Unexpected partial taxi route resume schema; recover before downgrade")
    if connection.exec_driver_sql(
        "SELECT 1 FROM instantorder WHERE "
        + " OR ".join(name + " IS NOT NULL" for name in _COLUMNS)
        + " LIMIT 1"
    ).first():
        raise RuntimeError("Drain taxi route resume markers before downgrade")
    for name in reversed(_INDEXES):
        op.drop_index(name, table_name=_TABLE)
    for name in reversed(_COLUMNS):
        op.drop_column(_TABLE, name)


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Taxi route resume downgrade requires an online drained-state check")
    _run_schema_change(_remove)
