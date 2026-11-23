"""Inactive order-owned TF04 committed outcome storage."""
from alembic import op
import sqlalchemy as sa

revision = "de_taxi_route_edit_outcome"
down_revision = "dd_taxi_route_resume"
branch_labels = None
depends_on = None

_TABLE = "taxirouteeditoutcome"
_INDEXES = {f"ix_{_TABLE}_{field}": field for field in ("order_id", "expires_at")}


def _create_or_validate() -> None:
    if not op.get_context().as_sql:
        inspector = sa.inspect(op.get_bind())
        if inspector.has_table(_TABLE):
            # Initial baseline creates the current SQLModel metadata.
            columns = {c["name"]: c for c in inspector.get_columns(_TABLE)}
            foreign = inspector.get_foreign_keys(_TABLE)
            indexes = {i["name"]: i for i in inspector.get_indexes(_TABLE)}
            checks = inspector.get_check_constraints(_TABLE)
            valid = (
                set(columns) == {"owner_id", "order_id", "attempt_id", "binding", "expires_at"}
                and all(not c["nullable"] and c.get("default") is None for c in columns.values())
                and all(isinstance(columns[name]["type"], sa.Integer) for name in ("owner_id", "order_id"))
                and isinstance(columns["attempt_id"]["type"], sa.String)
                and columns["attempt_id"]["type"].length == 36
                and isinstance(columns["binding"]["type"], sa.LargeBinary)
                and isinstance(columns["expires_at"]["type"], sa.DateTime)
                and not getattr(columns["expires_at"]["type"], "timezone", False)
                and inspector.get_pk_constraint(_TABLE)["constrained_columns"] == ["owner_id", "order_id", "attempt_id"]
                and len(foreign) == 1 and foreign[0]["constrained_columns"] == ["order_id"]
                and foreign[0]["referred_table"] == "instantorder" and foreign[0]["referred_columns"] == ["id"]
                and foreign[0]["options"].get("ondelete") == "CASCADE"
                and len(checks) == 1 and checks[0]["name"] == "ck_taxi_route_edit_outcome_binding"
                and checks[0]["sqltext"].replace(" ", "").strip("()") == "length(binding)=32"
                and set(indexes) == set(_INDEXES)
                and all(
                    not indexes[name]["unique"] and indexes[name]["column_names"] == [col]
                    and indexes[name].get("dialect_options", {}).get("sqlite_where") is None
                    and indexes[name].get("dialect_options", {}).get("postgresql_where") is None
                    for name, col in _INDEXES.items()
                )
            )
            if not valid:
                raise RuntimeError("Unexpected taxi route edit outcome schema")
            return
    op.create_table(
        _TABLE,
        sa.Column("owner_id", sa.Integer(), nullable=False),
        sa.Column("order_id", sa.Integer(), sa.ForeignKey("instantorder.id", ondelete="CASCADE"), nullable=False),
        sa.Column("attempt_id", sa.String(36), nullable=False),
        sa.Column("binding", sa.LargeBinary(32), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("owner_id", "order_id", "attempt_id"),
        sa.CheckConstraint("length(binding) = 32", name="ck_taxi_route_edit_outcome_binding"),
    )
    for name, col in _INDEXES.items():
        op.create_index(name, _TABLE, [col])


def _run_sqlite_locked(conn, apply) -> None:
    # pysqlite legacy mode may autocommit individual DDL before Alembic's stamp.
    # Group the complete change, including inspection, in a physical transaction.
    with op.get_context().autocommit_block():
        conn.exec_driver_sql("BEGIN IMMEDIATE")
        try:
            apply()
            conn.exec_driver_sql("COMMIT")
        except BaseException:
            conn.exec_driver_sql("ROLLBACK")
            raise


def upgrade() -> None:
    if op.get_context().as_sql:
        _create_or_validate()
        return
    conn = op.get_bind()
    if conn.dialect.name == "sqlite":
        _run_sqlite_locked(conn, _create_or_validate)
        return
    _create_or_validate()


def _drop_empty() -> None:
    conn = op.get_bind()
    if not sa.inspect(conn).has_table(_TABLE):
        # The already-completed SQLite DDL may precede its version stamp.
        return
    if conn.dialect.name == "postgresql":
        conn.exec_driver_sql("LOCK TABLE taxirouteeditoutcome IN ACCESS EXCLUSIVE MODE")
    if conn.exec_driver_sql("SELECT 1 FROM taxirouteeditoutcome LIMIT 1").first():
        raise RuntimeError("Drain taxi route edit outcome rows before downgrade")
    op.drop_table(_TABLE)  # drops indexes with its child table


def downgrade() -> None:
    if op.get_context().as_sql:
        raise RuntimeError("Taxi route edit outcome downgrade requires an online drained-state check")
    conn = op.get_bind()
    if conn.dialect.name != "sqlite":
        _drop_empty()
        return
    _run_sqlite_locked(conn, _drop_empty)
