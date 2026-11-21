import type { ChatMessage, ChatReceipt } from "../api/chat";

const DATABASE = "yuldash-chat-receipts-v1";
const STORE = "pending";

export type ReceiptScope = { generation: string; userId: number; bookingId: number };
type Current = () => boolean;
type ReceiptRow = ReceiptScope & { messageId: number; message: ChatMessage; read: boolean };

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    let blocked = false;
    request.onupgradeneeded = () => {
      const store = request.result.createObjectStore(STORE, {
        keyPath: ["generation", "userId", "bookingId", "messageId"],
      });
      store.createIndex("booking", ["generation", "userId", "bookingId"]);
    };
    request.onblocked = () => {
      blocked = true;
      reject(new Error("Chat receipt storage blocked"));
    };
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      if (blocked) db.close();
      else {
        db.onversionchange = () => db.close();
        resolve(db);
      }
    };
  });
}

async function transaction<Result>(
  current: Current,
  mode: "readonly" | "readwrite",
  work: (store: IDBObjectStore, result: (value: Result) => void, guard: <T>(apply: (value: T) => void, req: IDBRequest<T>) => void) => void,
): Promise<Result> {
  if (!current()) throw new Error("Chat receipt scope changed");
  const db = await openDatabase();
  try {
    if (!current()) throw new Error("Chat receipt scope changed");
    return await new Promise<Result>((resolve, reject) => {
      const tx = db.transaction(STORE, mode, mode === "readwrite" ? { durability: "strict" } : undefined);
      let value: Result;
      tx.onabort = () => reject(tx.error ?? new Error("Chat receipt transaction aborted"));
      tx.oncomplete = () => current() ? resolve(value) : reject(new Error("Chat receipt scope changed"));
      const guard = <T,>(apply: (result: T) => void, request: IDBRequest<T>) => {
        request.onsuccess = () => {
          try {
            if (!current()) throw new Error("Chat receipt scope changed");
            apply(request.result);
          } catch (error) {
            tx.abort();
            reject(error);
          }
        };
      };
      try {
        work(tx.objectStore(STORE), result => { value = result; }, guard);
      } catch (error) {
        tx.abort();
        reject(error);
      }
    });
  } finally {
    db.close();
  }
}

const bookingKey = (scope: ReceiptScope) => [scope.generation, scope.userId, scope.bookingId];

export function saveChatReceipts(
  scope: ReceiptScope,
  current: Current,
  entries: readonly { message: ChatMessage; read: boolean }[],
): Promise<void> {
  return transaction(current, "readwrite", (store, _result, guard) => {
    for (const { message, read } of entries) {
      guard((row: ReceiptRow | undefined) => {
        store.put({ ...scope, messageId: message.id, message, read: read || row?.read === true });
      }, store.get([...bookingKey(scope), message.id]));
    }
  });
}

export function pendingChatReceipts(scope: ReceiptScope, current: Current, limit: number): Promise<ReceiptRow[]> {
  return transaction(current, "readonly", (store, result, guard) => {
    const key = bookingKey(scope);
    const range = IDBKeyRange.bound([...key, 1], [...key, Number.MAX_SAFE_INTEGER]);
    guard(result, store.getAll(range, limit));
  });
}

export function claimChatReceiptSend(scope: ReceiptScope, current: Current, interval: number): Promise<number> {
  return transaction(current, "readwrite", (store, result, guard) => {
    const key = [...bookingKey(scope), 0];
    guard((row: { startedAt: number } | undefined) => {
      const now = Date.now();
      const wait = row && row.startedAt <= now ? Math.max(0, row.startedAt + interval - now) : 0;
      if (!wait) store.put({ ...scope, messageId: 0, startedAt: now });
      result(wait);
    }, store.get(key));
  });
}

export function settleChatReceipts(
  scope: ReceiptScope,
  current: Current,
  requested: readonly number[],
  items: readonly ChatReceipt[],
): Promise<void> {
  const confirmed = new Map(items.map(item => [item.message_id, item]));
  return transaction(current, "readwrite", (store, _result, guard) => {
    for (const id of requested) {
      const key = [...bookingKey(scope), id];
      guard((row: ReceiptRow | undefined) => {
        if (!row) return;
        const item = confirmed.get(id);
        // Missing in the successful filtered response: no longer eligible.
        // A Read added during a Delivered request survives that request.
        if (!item || item.my_read || (item.my_delivered && !row.read)) store.delete(key);
      }, store.get(key));
    }
  });
}

export function clearChatReceipts(scope: ReceiptScope, current: Current): Promise<void> {
  return transaction(current, "readwrite", (store, _result, guard) => {
    guard(keys => keys.forEach(key => store.delete(key)), store.index("booking").getAllKeys(bookingKey(scope)));
  });
}

export async function pruneChatReceipts(generation: string, current: Current): Promise<void> {
  if (typeof indexedDB === "undefined") return;
  return transaction(current, "readwrite", (store, _result, guard) => {
    guard(keys => keys.forEach(key => {
      if (Array.isArray(key) && key[0] !== generation) store.delete(key);
    }), store.getAllKeys());
  });
}
