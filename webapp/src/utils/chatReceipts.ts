import type { ChatMessage, ChatReceipt, ChatReceiptBatch } from "../api/chat";

export const RECEIPT_BATCH_SIZE = 100;
export const RECEIPT_BATCH_INTERVAL = 1500;

export function chatTimestamp(value: string | undefined): number {
  if (!value) return Number.NaN;
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value);
  return Date.parse(zoned ? value : `${value}Z`);
}

export function receiptCandidate(message: ChatMessage, ownId: number, since: number): boolean {
  return Number.isSafeInteger(message.id) && message.id > 0 &&
    Number.isSafeInteger(message.sender_id) && message.sender_id > 0 && message.sender_id !== ownId &&
    !message.from_admin && !message.deleted && !message.hidden_for_me && typeof message.text === "string" &&
    (message.text !== "" || Boolean(message.voice_url)) &&
    chatTimestamp(message.created_at ?? message.timestamp) >= since;
}

export function mergeChatReceipt(old: ChatReceipt | undefined, item: ChatReceipt): ChatReceipt {
  const peerRead = Boolean(old?.peer_read || item.peer_read);
  const myRead = Boolean(old?.my_read || item.my_read);
  return {
    message_id: item.message_id,
    peer_delivered: Boolean(peerRead || old?.peer_delivered || item.peer_delivered),
    peer_read: peerRead,
    my_delivered: Boolean(myRead || old?.my_delivered || item.my_delivered),
    my_read: myRead,
  };
}

export function receiptBatch(rows: Iterable<{ message: ChatMessage; read: boolean }>): ChatReceiptBatch {
  const delivered_ids: number[] = [];
  const read_ids: number[] = [];
  for (const { message, read } of rows) {
    delivered_ids.push(message.id);
    if (read) read_ids.push(message.id);
    if (delivered_ids.length === RECEIPT_BATCH_SIZE) break;
  }
  return { delivered_ids, read_ids };
}
