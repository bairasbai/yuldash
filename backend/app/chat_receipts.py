"""Queries and atomic writes for per-message acknowledgements in rideshare chat."""
from datetime import datetime
from hashlib import sha256
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import and_, case, func, literal, or_, text
from sqlalchemy.dialects.postgresql import insert as postgres_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlmodel import Session, select

from .chat_receipt_models import ChatMessageReceipt
from .models import Message
from .timeutil import utcnow


MessageId = Annotated[int, Field(strict=True, gt=0, le=2_147_483_647)]


class ReceiptBatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    delivered_ids: list[MessageId] = Field(default_factory=list, max_length=100)
    read_ids: list[MessageId] = Field(default_factory=list, max_length=100)

    def joined(self, other: "ReceiptBatch") -> "ReceiptBatch | None":
        read = set(self.read_ids) | set(other.read_ids)
        delivered = (set(self.delivered_ids) | set(other.delivered_ids)) - read
        if len(read) > 100 or len(delivered) > 100:
            return None
        return ReceiptBatch(delivered_ids=sorted(delivered), read_ids=sorted(read))

    @field_validator("delivered_ids", "read_ids")
    @classmethod
    def _unique_ids(cls, ids: list[int]) -> list[int]:
        if len(set(ids)) != len(ids):
            raise ValueError("message IDs must be unique")
        return ids


class ReceiptItem(BaseModel):
    message_id: int
    peer_delivered: bool = False
    peer_read: bool = False
    my_delivered: bool = False
    my_read: bool = False


def lock_receipt_pair(session: Session, first_user: int, second_user: int) -> None:
    """Order receipt writes and changes to bilateral chat access."""
    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        low, high = sorted((first_user, second_user))
        digest = sha256(f"yuldash:chat-receipts:{low}:{high}".encode("ascii")).digest()
        lock_id = int.from_bytes(digest[:8], byteorder="big", signed=True)
        session.execute(text("SELECT pg_advisory_xact_lock(CAST(:key AS bigint))"), {"key": lock_id})
    elif dialect == "sqlite":
        # Authentication has already read from the session; the caller has not written.
        session.rollback()
        session.connection().exec_driver_sql("BEGIN IMMEDIATE")
    else:
        raise RuntimeError("Unsupported chat receipt database")


def visible_to_user(user_id: int):
    # The existing hide action stores canonical comma-separated numeric IDs.
    names = literal(",") + func.coalesce(Message.hidden_user_ids, "") + literal(",")
    return names.not_like(f"%,{user_id},%")


def receipt_conditions(booking_id: int, user_id: int, since: datetime):
    return (
        Message.booking_id == booking_id,
        Message.created_at >= since,
        Message.deleted.is_(False),
        visible_to_user(user_id),
    )


def unread_counts(session: Session, user_id: int, peers: dict[int, int], since: datetime) -> dict[int, int]:
    if not peers:
        return {}
    return dict(session.exec(
        select(Message.booking_id, func.count(Message.id))
        .outerjoin(ChatMessageReceipt, and_(
            ChatMessageReceipt.message_id == Message.id,
            ChatMessageReceipt.user_id == user_id,
        ))
        .where(
            Message.booking_id.in_(peers),
            Message.sender_id == case(peers, value=Message.booking_id),
            Message.created_at >= since,
            Message.deleted.is_(False),
            visible_to_user(user_id),
            ChatMessageReceipt.read.is_not(True),
        )
        .group_by(Message.booking_id)
    ).all())


def receipt_items(session: Session, booking_id: int, user_id: int, peer_id: int, since: datetime,
                  *, after: int | None = None, limit: int = 500, ids: set[int] | None = None):
    if ids is not None and not ids:
        return [], None
    recipient = case((Message.sender_id == user_id, peer_id), else_=user_id)
    query = (
        select(Message.id, Message.sender_id, ChatMessageReceipt.delivered, ChatMessageReceipt.read)
        .outerjoin(ChatMessageReceipt, and_(
            ChatMessageReceipt.message_id == Message.id,
            ChatMessageReceipt.user_id == recipient,
        ))
        .where(*receipt_conditions(booking_id, user_id, since), Message.sender_id.in_((user_id, peer_id)))
        .order_by(Message.id)
    )
    if ids is not None:
        query = query.where(Message.id.in_(ids))
    if after is not None:
        query = query.where(Message.id > after)
    rows = session.exec(query.limit(limit + 1)).all()
    items = [
        ReceiptItem(message_id=message_id,
                    peer_delivered=sender == user_id and bool(delivered),
                    peer_read=sender == user_id and bool(read),
                    my_delivered=sender != user_id and bool(delivered),
                    my_read=sender != user_id and bool(read))
        for message_id, sender, delivered, read in rows[:limit]
    ]
    return items, items[-1].message_id if len(rows) > limit else None


def apply_receipt_batch(session: Session, booking_id: int, user_id: int, peer_id: int,
                        since: datetime, batch: ReceiptBatch) -> tuple[set[int], list[dict]]:
    read = set(batch.read_ids)
    requested = read | set(batch.delivered_ids)
    if not requested:
        return set(), []
    ids = set(session.exec(
        select(Message.id)
        .where(*receipt_conditions(booking_id, user_id, since),
               Message.sender_id == peer_id, Message.id.in_(requested))
        .order_by(Message.id)
        .with_for_update()
    ).all())
    if not ids:
        return set(), []

    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        insert = postgres_insert
    elif dialect == "sqlite":
        insert = sqlite_insert
    else:
        raise RuntimeError("Unsupported chat receipt database")

    table = ChatMessageReceipt.__table__
    now = utcnow()
    statement = insert(table).values([
        {"message_id": message_id, "user_id": user_id, "delivered": True,
         "read": message_id in read, "updated_at": now}
        for message_id in sorted(ids)
    ])
    incoming = statement.excluded
    updated = statement.on_conflict_do_update(
        index_elements=[table.c.message_id, table.c.user_id],
        set_={
            "delivered": or_(table.c.delivered, incoming.delivered),
            "read": or_(table.c.read, incoming.read),
            "updated_at": incoming.updated_at,
        },
        where=or_(
            and_(table.c.delivered.is_(False), incoming.delivered),
            and_(table.c.read.is_(False), incoming.read),
        ),
    ).returning(table.c.message_id, table.c.user_id, table.c.delivered, table.c.read)
    return ids, [dict(row) for row in session.execute(updated).mappings()]
