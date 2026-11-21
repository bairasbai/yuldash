import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import {
  fetchChatReceipts, fetchConversations, postChatReceipts, type ChatMessage, type ChatReceipt,
} from "../api/chat";
import { ApiError, isAccountSession } from "../api/client";
import {
  claimChatReceiptSend, clearChatReceipts, pendingChatReceipts, saveChatReceipts, settleChatReceipts, type ReceiptScope,
} from "./chatReceiptStore";
import {
  chatTimestamp, mergeChatReceipt, receiptBatch, receiptCandidate, RECEIPT_BATCH_INTERVAL, RECEIPT_BATCH_SIZE,
} from "./chatReceipts";

type ReceiptView = {
  since: number | null;
  items: ReadonlyMap<number, ChatReceipt>;
  unread: number | null;
};

const empty: ReceiptView = { since: null, items: new Map(), unread: null };
const RETRY_INTERVAL = 3000;

function startReceiptSync(scope: ReceiptScope, publish: (view: ReceiptView) => void) {
  const lifetime = new AbortController();
  const active = () => !lifetime.signal.aborted && isAccountSession(scope);
  const { bookingId } = scope;
  let view = empty;
  let version = 0;
  let running = false;
  let missedWake = false;
  let needsSync = true;
  let timer: number | undefined;
  let scheduledAt = 0;
  const staged = new Map<number, "delivered" | "read">();

  function update(next: ReceiptView) {
    if (!active()) return;
    view = next;
    publish(next);
  }

  function schedule(delay = 0) {
    if (!active() || (timer !== undefined && scheduledAt <= Date.now() + delay)) return;
    window.clearTimeout(timer);
    scheduledAt = Date.now() + delay;
    timer = window.setTimeout(() => { timer = undefined; void pump(); }, delay);
  }

  function refresh(invalidate = false, delay = 0) {
    if (!active()) return;
    if (invalidate) {
      version++;
      staged.clear();
      update(empty);
    }
    needsSync = true;
    schedule(delay);
  }

  async function restore(current: () => boolean) {
    const first = await fetchChatReceipts(bookingId, null, lifetime.signal);
    if (!current()) return;
    if (!first.open) {
      update(empty);
      staged.clear();
      await clearChatReceipts(scope, current);
      const conversations = await fetchConversations(lifetime.signal);
      if (!current()) return;
      const unread = conversations.find(chat => chat.booking_id === bookingId)?.unread;
      update({ ...empty, unread: unread ?? null });
      return;
    }
    const since = chatTimestamp(first.receipts_since ?? undefined);
    if (!Number.isFinite(since)) throw new Error("Invalid chat receipt cutoff");
    const items = new Map<number, ChatReceipt>();
    const earlier = view.since === since ? view.items : empty.items;
    let page = first;
    let after = 0;
    while (current()) {
      for (const item of page.items) items.set(item.message_id, mergeChatReceipt(earlier.get(item.message_id), item));
      const next = page.next_after_message_id;
      if (next === null) {
        update({ since, items, unread: page.unread });
        return;
      }
      if (!Number.isSafeInteger(next) || next <= after) throw new Error("Invalid chat receipt cursor");
      after = next;
      try {
        page = await fetchChatReceipts(bookingId, after, lifetime.signal);
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error;
        // Retention can delete the cursor while the current page is in flight.
        refresh(true, RETRY_INTERVAL);
        return;
      }
      if (!current()) return;
      if (!page.open || chatTimestamp(page.receipts_since ?? undefined) !== since) {
        refresh(true, RETRY_INTERVAL);
        return;
      }
    }
  }

  async function pump() {
    if (!active() || navigator.onLine === false) return;
    if (running) { missedWake = true; return; }
    running = true;
    const snapshot = version;
    const current = () => active() && version === snapshot;
    const restoring = needsSync;
    let waiting: number | null = null;
    needsSync = false;
    try {
      if (restoring) await restore(current);
      const since = view.since;
      if (!current() || needsSync || since === null) return;
      const pending = await pendingChatReceipts(scope, current, RECEIPT_BATCH_SIZE);
      if (!current() || needsSync || !pending.length) return;
      const eligible = pending.filter(row => receiptCandidate(row.message, scope.userId, since));
      const excluded = pending.filter(row => !eligible.includes(row)).map(row => row.messageId);
      if (excluded.length) await settleChatReceipts(scope, current, excluded, []);
      const batch = receiptBatch(eligible);
      if (!batch.delivered_ids.length) { waiting = 0; return; }
      const delay = await claimChatReceiptSend(scope, current, RECEIPT_BATCH_INTERVAL);
      if (!current() || needsSync) return;
      if (delay) { waiting = delay; return; }
      const reply = await postChatReceipts(bookingId, batch, lifetime.signal, scope.generation);
      if (!current()) return;
      if (!reply.open || chatTimestamp(reply.receipts_since ?? undefined) !== view.since) {
        refresh(true);
        return;
      }
      const items = new Map(view.items);
      for (const item of reply.items) items.set(item.message_id, mergeChatReceipt(items.get(item.message_id), item));
      await settleChatReceipts(scope, current, batch.delivered_ids, reply.items);
      if (!current()) return;
      update({ ...view, items, unread: reply.unread });
      waiting = RECEIPT_BATCH_INTERVAL;
    } catch (error) {
      if (!current()) return;
      if (error instanceof ApiError && [403, 404, 410].includes(error.status)) {
        update(empty);
        staged.clear();
        try { await clearChatReceipts(scope, current); } catch { /* The next visit reconciles the queue. */ }
      } else {
        if (restoring) needsSync = true;
        waiting = RETRY_INTERVAL;
      }
    } finally {
      running = false;
      const wake = missedWake;
      missedWake = false;
      if (current() && waiting !== null) schedule(waiting);
      else if (needsSync || wake) schedule();
    }
  }

  function stage(messages: readonly ChatMessage[], read: boolean) {
    if (!active() || view.since === null) return;
    const since = view.since;
    const flag = read ? "read" : "delivered";
    const snapshot = version;
    const entries: { message: ChatMessage; read: boolean }[] = [];
    for (const message of messages) {
      if (!receiptCandidate(message, scope.userId, since)) continue;
      const known = view.items.get(message.id);
      const pending = staged.get(message.id);
      if (read ? known?.my_read || pending === "read" : known?.my_delivered || pending) continue;
      staged.set(message.id, flag);
      entries.push({ message, read });
    }
    if (!entries.length) return;
    const current = () => active() && version === snapshot && view.since === since;
    void saveChatReceipts(scope, current, entries).then(() => schedule()).catch(() => {
      if (!current()) return;
      for (const { message } of entries) if (staged.get(message.id) === flag) staged.delete(message.id);
      // Keep delivery honest if the write fails. A refreshed feed can try again.
      refresh(false, RETRY_INTERVAL);
    });
  }

  schedule();
  return {
    refresh,
    stage,
    close() {
      lifetime.abort();
      window.clearTimeout(timer);
    },
  };
}

function rowsActuallyVisible(feed: HTMLElement, entries: IntersectionObserverEntry[]): number[] {
  const bounds = feed.getBoundingClientRect();
  return entries.flatMap(entry => {
    if (!entry.isIntersecting || !feed.contains(entry.target)) return [];
    const rect = entry.target.getBoundingClientRect();
    if (rect.bottom <= bounds.top || rect.top >= bounds.bottom || rect.right <= bounds.left || rect.left >= bounds.right ||
        rect.width <= 0 || rect.height <= 0 || getComputedStyle(entry.target).visibility !== "visible") return [];
    return [Number((entry.target as HTMLElement).dataset.messageId)];
  });
}

export function useChatReceipts(
  scope: ReceiptScope,
  messages: readonly ChatMessage[],
  feedRef: RefObject<HTMLDivElement>,
  visible: boolean,
  historyReady: boolean,
) {
  const { generation, userId, bookingId } = scope;
  const [view, setView] = useState(empty);
  const sync = useRef<ReturnType<typeof startReceiptSync> | null>(null);
  useEffect(() => {
    const scope = { generation, userId, bookingId };
    if (!bookingId || !isAccountSession(scope)) return;
    setView(empty);
    const session = startReceiptSync(scope, setView);
    sync.current = session;
    return () => { sync.current = null; session.close(); };
  }, [generation, userId, bookingId]);

  const refresh = useCallback((invalidate = false) => sync.current?.refresh(invalidate), []);
  useEffect(() => {
    if (!historyReady || view.since === null) return;
    sync.current?.stage(messages, false);
  }, [historyReady, messages, view]);

  useEffect(() => {
    const feed = feedRef.current;
    const session = sync.current;
    if (!session || !feed || !historyReady || !visible || view.since === null || typeof IntersectionObserver === "undefined") return;
    const candidates = new Map(messages.map(message => [message.id, message]));
    let observing = true;
    const observer = new IntersectionObserver(entries => {
      if (!observing || sync.current !== session || document.visibilityState !== "visible") return;
      const read = rowsActuallyVisible(feed, entries).flatMap(id => {
        const message = candidates.get(id);
        return message ? [message] : [];
      });
      session.stage(read, true);
    }, { root: feed });
    for (const node of feed.querySelectorAll("[data-message-id]")) observer.observe(node);
    return () => { observing = false; observer.disconnect(); };
  }, [feedRef, historyReady, messages, view.since, visible]);

  return { ...view, refresh };
}
