"""Durable route-watch work and the record of rides already announced."""
from datetime import datetime

from sqlalchemy import BigInteger, Column, Index, UniqueConstraint
from sqlmodel import Field, SQLModel

from .timeutil import utcnow


class RouteWatchEvent(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    ride_id: int = Field(foreign_key="ride.id", ondelete="CASCADE")
    terms_version: int = Field(sa_column=Column(BigInteger, nullable=False))
    source_key: str = Field(max_length=64, unique=True)
    group_key: str | None = Field(default=None, max_length=64, index=True)
    created_at: datetime = Field(default_factory=utcnow)
    next_attempt_at: datetime = Field(default_factory=utcnow)
    finished_at: datetime | None = None

    __table_args__ = (
        UniqueConstraint("ride_id", "terms_version", name="uq_route_watch_ride_version"),
        Index("ix_route_watch_due", "finished_at", "next_attempt_at", "id"),
    )


class RouteWatchNotice(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    watch_id: int = Field(foreign_key="routewatch.id", ondelete="CASCADE", index=True)
    ride_id: int = Field(foreign_key="ride.id", ondelete="CASCADE", index=True)
    notification_id: int | None = Field(default=None, foreign_key="notification.id", ondelete="SET NULL", index=True)
    created_at: datetime = Field(default_factory=utcnow)

    __table_args__ = (UniqueConstraint("watch_id", "ride_id", name="uq_route_watch_notice"),)
