"""Optional initial taxi price consent, retained only with its order."""
import sqlalchemy as sa
from alembic import op

revision = "cz_taxi_initial_price_consent"
down_revision = "cz_rideshare_recipient_setup"
branch_labels = None
depends_on = None

_TABLE = "instantorder"
_COLUMNS = (
    ("consent_request_key", 36),
    ("consent_request_digest", 64),
    ("consent_quote_id", 36),
    ("consent_snapshot_json", None),
)
_UNIQUE_INDEXES = {
    "uq_taxi_consent_owner_key": ["passenger_id", "consent_request_key"],
    "uq_taxi_consent_quote": ["consent_quote_id"],
}


def upgrade() -> None:
    # The initial baseline creates current metadata. Populated older installs
    # need additive ALTERs: rebuilding instantorder breaks its inbound FKs.
    inspector = None if op.get_context().as_sql else sa.inspect(op.get_bind())
    columns = {c["name"]: c for c in inspector.get_columns(_TABLE)} if inspector else {}
    indexes = {i["name"]: i for i in inspector.get_indexes(_TABLE)} if inspector else {}
    constraints = {c["name"]: c for c in inspector.get_unique_constraints(_TABLE)} if inspector else {}
    for name, length in _COLUMNS:
        if name not in columns:
            op.add_column(_TABLE, sa.Column(name, sa.String(length), nullable=True))
        else:
            column = columns[name]
            if (not column["nullable"] or not isinstance(column["type"], sa.String)
                    or column["type"].length != length):
                raise RuntimeError(f"Unexpected {_TABLE}.{name} definition")
    for name, fields in _UNIQUE_INDEXES.items():
        if name in constraints:
            if constraints[name]["column_names"] != fields:
                raise RuntimeError(f"Unexpected {name} definition")
        elif name in indexes:
            if not indexes[name]["unique"] or indexes[name]["column_names"] != fields:
                raise RuntimeError(f"Unexpected {name} definition")
        else:
            op.create_index(name, _TABLE, fields, unique=True)


def _downgrade_legacy_sqlite(constraints: set[str]) -> None:
    # Published QA databases have inline UNIQUE constraints, which SQLite can
    # only remove by rebuilding the table. Disabling FKs within an active
    # transaction is ignored. Do it on this connection before acquiring the
    # write lock, then validate all references before committing the rebuild.
    connection = op.get_bind()
    fk_enabled = bool(connection.exec_driver_sql("PRAGMA foreign_keys").scalar_one())
    with op.get_context().autocommit_block():
        connection.exec_driver_sql("PRAGMA foreign_keys=OFF")
        try:
            if connection.exec_driver_sql("PRAGMA foreign_keys").scalar_one():
                raise RuntimeError("Could not suspend foreign keys for the SQLite table rebuild")
            connection.exec_driver_sql("BEGIN IMMEDIATE")
            # Alembic reflects named indexes and constraints, but not triggers.
            triggers = connection.exec_driver_sql(
                "SELECT sql FROM sqlite_master WHERE type='trigger' AND tbl_name='instantorder'"
            ).scalars().all()
            with op.batch_alter_table(_TABLE) as batch:
                for name in _UNIQUE_INDEXES:
                    if name in constraints:
                        batch.drop_constraint(name, type_="unique")
                    else:
                        batch.drop_index(name)
                for name, _ in reversed(_COLUMNS):
                    batch.drop_column(name)
            for ddl in triggers:
                connection.exec_driver_sql(ddl)
            if connection.exec_driver_sql("PRAGMA foreign_key_check").first():
                raise RuntimeError("Invalid reference after the SQLite taxi consent rollback")
            connection.exec_driver_sql("COMMIT")
        finally:
            if connection.connection.driver_connection.in_transaction:
                connection.exec_driver_sql("ROLLBACK")
            if fk_enabled:
                connection.exec_driver_sql("PRAGMA foreign_keys=ON")


def downgrade() -> None:
    inspector = None if op.get_context().as_sql else sa.inspect(op.get_bind())
    constraints = {c["name"] for c in inspector.get_unique_constraints(_TABLE)} if inspector else set()
    legacy = constraints.intersection(_UNIQUE_INDEXES)
    if legacy and op.get_bind().dialect.name == "sqlite":
        _downgrade_legacy_sqlite(legacy)
        return
    for name in _UNIQUE_INDEXES:
        if name in legacy:
            op.drop_constraint(name, _TABLE, type_="unique")
        else:
            op.drop_index(name, table_name=_TABLE)
    for name, _ in reversed(_COLUMNS):
        op.drop_column(_TABLE, name)
