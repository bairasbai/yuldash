"""Долговечные события подписок на попутки и запрет повторного объявления рейса."""
from datetime import datetime, timezone

from alembic import op
import sqlalchemy as sa


revision = "da_route_watch_dispatch"
down_revision = "cz_rideshare_recipient_setup"
branch_labels = None
depends_on = None


def _create_events():
    op.create_table(
        "routewatchevent",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("ride_id", sa.Integer(), sa.ForeignKey("ride.id", ondelete="CASCADE"), nullable=False),
        sa.Column("terms_version", sa.BigInteger(), nullable=False),
        sa.Column("source_key", sa.String(64), nullable=False, unique=True),
        sa.Column("group_key", sa.String(64)),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("next_attempt_at", sa.DateTime(), nullable=False),
        sa.Column("finished_at", sa.DateTime()),
        sa.UniqueConstraint("ride_id", "terms_version", name="uq_route_watch_ride_version"),
    )


def _create_notices():
    op.create_table(
        "routewatchnotice",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("watch_id", sa.Integer(), sa.ForeignKey("routewatch.id", ondelete="CASCADE"), nullable=False),
        sa.Column("ride_id", sa.Integer(), sa.ForeignKey("ride.id", ondelete="CASCADE"), nullable=False),
        sa.Column("notification_id", sa.Integer(), sa.ForeignKey("notification.id", ondelete="SET NULL")),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.UniqueConstraint("watch_id", "ride_id", name="uq_route_watch_notice"),
    )


def _ensure_indexes(bind):
    indexes = [
        ("routewatchevent", "ix_routewatchevent_group_key", ["group_key"]),
        ("routewatchevent", "ix_route_watch_due", ["finished_at", "next_attempt_at", "id"]),
    ] + [
        ("routewatchnotice", f"ix_routewatchnotice_{column}", [column])
        for column in ("watch_id", "ride_id", "notification_id")
    ]
    inspection = sa.inspect(bind)
    for table, name, columns in indexes:
        if name not in {index["name"] for index in inspection.get_indexes(table)}:
            op.create_index(name, table, columns)


def upgrade():
    if op.get_context().as_sql:
        raise RuntimeError("Route-watch migration needs the current notification rows")
    bind = op.get_bind()
    inspection = sa.inspect(bind)
    if not inspection.has_table("routewatchevent"):
        _create_events()
    if not inspection.has_table("routewatchnotice"):
        _create_notices()
    _ensure_indexes(bind)

    # Старая запись указывает получателя и рейс. Если подписку нельзя определить
    # однозначно, защищаем от повторного объявления все действующие подписки получателя.
    # Связь заявки и поездки не угадываем по русскому или башкирскому тексту.
    bind.execute(sa.text("""
        INSERT INTO routewatchnotice (watch_id, ride_id, notification_id, created_at)
        SELECT w.id, r.id, MAX(n.id), MAX(n.created_at)
        FROM notification AS n
        JOIN ride AS r ON r.id = n.ref_id
        JOIN routewatch AS w ON w.user_id = n.user_id
        WHERE n.type = 'route_watch' AND n.ref_kind = 'ride'
          AND w.watch_kind IN ('rides', 'both') AND w.expires_at > :now
        GROUP BY w.id, r.id
        ON CONFLICT (watch_id, ride_id) DO NOTHING
    """), {"now": datetime.now(timezone.utc).replace(tzinfo=None)})


def downgrade():
    if op.get_context().as_sql:
        raise RuntimeError("Route-watch downgrade needs to check for pending events")
    pending = op.get_bind().execute(sa.text(
        "SELECT id FROM routewatchevent WHERE finished_at IS NULL LIMIT 1"
    )).first()
    if pending is not None:
        raise RuntimeError("Route-watch events are pending. Finish dispatch before downgrading.")
    op.drop_table("routewatchnotice")
    op.drop_table("routewatchevent")
