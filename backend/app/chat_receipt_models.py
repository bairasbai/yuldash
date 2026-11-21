"""Per-message delivery and read confirmations for rideshare chat."""
from datetime import datetime

from sqlalchemy import Boolean, CheckConstraint, Column, false
from sqlmodel import Field, SQLModel

from .timeutil import utcnow


class ChatMessageReceipt(SQLModel, table=True):
    __table_args__ = (
        CheckConstraint("NOT read OR delivered", name="ck_chat_message_receipt_read_requires_delivery"),
    )

    message_id: int = Field(primary_key=True, foreign_key="message.id", ondelete="CASCADE")
    user_id: int = Field(primary_key=True, foreign_key="user.id", ondelete="CASCADE", index=True)
    delivered: bool = Field(default=False, sa_column=Column(Boolean, nullable=False, server_default=false()))
    read: bool = Field(default=False, sa_column=Column(Boolean, nullable=False, server_default=false()))
    updated_at: datetime = Field(default_factory=utcnow)
