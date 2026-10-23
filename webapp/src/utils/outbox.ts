import { ApiError, getSessionGeneration } from "../api/client";
import { sendMessageRest, type ChatMessage } from "../api/chat";
import { setDriverStatus, type DriverPhase } from "../api/driver";
import { setTripStatus, type TripStatus } from "../api/family";
import { clearActions, insertAction, listActions, removeAction } from "./outboxStore";

export type OutboxKind = "message" | "trip_status" | "driver_status";
export interface OutboxAction {
  id: string | number;
  session: string;
  bookingId: number;
  kind: OutboxKind;
  payload: string;
  createdAt: number;
}

const listeners = new Set<() => void>();

/**
 * Отметка из очереди («Выехал», «Доехала»), которую сервер потом отклонил насовсем: «Рано: можно
 * нажать за час до выезда», «Поездка отменена». Раньше такое действие молча снималось с очереди, а
 * человек думал, что пассажир уже знает. Теперь отказ запоминается — его показывает плашка в оболочке.
 * Только в памяти вкладки: отказ принадлежит входу и не переживает выход (`session`).
 */
export interface OutboxRefusal {
  key: string;
  session: string;
  bookingId: number;
  kind: OutboxKind;
  payload: string;
  /** Слова сервера на языке интерфейса в момент отказа (иначе общая фраза клиента по коду). */
  message: string;
}
let refusals: OutboxRefusal[] = [];
const refusalListeners = new Set<() => void>();
function notifyRefusals(): void { refusalListeners.forEach(fn => { try { fn(); } catch { /* Независимые подписчики. */ } }); }
export function subscribeOutboxRefusals(fn: () => void): () => void {
  refusalListeners.add(fn);
  return () => refusalListeners.delete(fn);
}
/** Отказы текущего входа, старые первыми. */
export function getOutboxRefusals(): OutboxRefusal[] {
  const session = getSessionGeneration();
  // Поколение входа не возвращается: отказы прошлых входов уже никому не покажем — выбрасываем.
  if (refusals.some(r => r.session !== session)) refusals = refusals.filter(r => r.session === session);
  return refusals.slice();
}
export function dismissOutboxRefusal(key: string): void {
  const before = refusals.length;
  refusals = refusals.filter(r => r.key !== key);
  if (refusals.length !== before) notifyRefusals();
}
function recordRefusal(action: OutboxAction, error: unknown): void {
  // Сообщения чата имеют свой путь (в чате видно, что не ушло); здесь — отметки о ходе поездки.
  if (action.kind === "message" || !(error instanceof ApiError)) return;
  const key = `${action.session}:${String(action.id)}`;
  if (refusals.some(r => r.key === key)) return;
  refusals = [...refusals, { key, session: action.session, bookingId: action.bookingId, kind: action.kind, payload: action.payload, message: error.message }];
  notifyRefusals();
}
const receiptListeners = new Set<(action: OutboxAction, receipt: ChatMessage) => void>();
let snapshot: OutboxAction[] = [], snapshotSession = "", readVersion = 0, clearVersion = 0;
let channel: BroadcastChannel | null = null;
let listening = false;
const SIGNAL = "yuldash.outbox.changed";
function notify(): void { listeners.forEach(fn => { try { fn(); } catch { /* Независимые подписчики. */ } }); }
function connect(): void {
  if (typeof window === "undefined") return;
  if (!listening) {
    window.addEventListener?.("storage", event => {
      if (event.key === SIGNAL) void refreshOutbox().catch(() => {});
    });
    listening = true;
  }
  try {
    if (!channel && "BroadcastChannel" in window) {
      channel = new BroadcastChannel("yuldash-outbox");
      channel.onmessage = () => { void refreshOutbox().catch(() => {}); };
    }
  } catch { /* Необязательный сигнал не меняет результат commit. */ }
}
function changed(): void {
  connect();
  try { channel?.postMessage(null); } catch { /* Есть запасной storage-сигнал. */ }
  try { localStorage.setItem(SIGNAL, createOutboxId()); } catch { /* При focus база перечитывается. */ }
}

/** Снимок служит только для UI. Отправитель всегда читает транзакционную базу. */
export async function refreshOutbox(): Promise<void> {
  const generation = getSessionGeneration(), version = ++readVersion, cleared = clearVersion;
  const active = () => generation === getSessionGeneration() && cleared === clearVersion;
  const rows = await listActions(generation, active);
  if (!active() || version !== readVersion) return;
  snapshot = rows; snapshotSession = generation; notify();
}
export function subscribeOutbox(fn: () => void): () => void {
  connect(); listeners.add(fn);
  void refreshOutbox().catch(() => {});
  return () => listeners.delete(fn);
}

let seq = Date.now();
export function createOutboxId(): string {
  seq += 1;
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID() : `${Date.now()}-${Math.random()}-${seq}`;
}

/** Promise завершается только после commit, ошибки записи не маскируются под успех. */
export async function enqueue(bookingId: number, kind: OutboxKind, payload: string, id: string = createOutboxId()): Promise<void> {
  const generation = getSessionGeneration(), cleared = clearVersion;
  const active = () => generation === getSessionGeneration() && cleared === clearVersion;
  await insertAction({ id, session: generation, bookingId, kind, payload, createdAt: Date.now() }, active);
  changed();
  // Сохранение уже подтверждено. Сбой обновления счётчика не должен предлагать повтор записи.
  await refreshOutbox().catch(() => {});
}
export function outboxCount(bookingId: number): number {
  return snapshotSession === getSessionGeneration() ? snapshot.filter(a => a.bookingId === bookingId).length : 0;
}
export function hasPending(): boolean {
  return snapshotSession === getSessionGeneration() && snapshot.length > 0;
}
export async function clearOutbox(): Promise<void> {
  const generation = getSessionGeneration();
  clearVersion++; readVersion++; snapshot = []; snapshotSession = generation; notify();
  refusals = []; notifyRefusals();
  try { localStorage.removeItem("yuldash.outbox"); } catch { /* Вход не зависит от localStorage cleanup. */ }
  await clearActions(generation);
  changed();
}
export function isPermanentRejection(error: unknown): boolean {
  // Таймаут, ограничение частоты, 5xx и нечитаемый ответ не подтверждают отказ.
  // Сохраняем действие и его ключ до следующей попытки отправки.
  return error instanceof ApiError && [400, 403, 404, 405, 409, 410, 413, 415, 422].includes(error.status);
}

/** Remove only this persisted intent and its original login, never a newer account's action. */
export async function acknowledgeMessage(bookingId: number, id: string, expectedGeneration: string): Promise<void> {
  const cleared = clearVersion;
  const active = () => expectedGeneration === getSessionGeneration() && cleared === clearVersion;
  if (!active()) return;
  const rows = await listActions(expectedGeneration, active);
  if (!active()) return;
  const action = rows.find(a => a.bookingId === bookingId && a.kind === "message" && String(a.id) === id);
  if (action) await removeAction(action, active);
  if (!active()) return;
  changed();
  await refreshOutbox().catch(() => {});
}

async function run(a: OutboxAction): Promise<void> {
  switch (a.kind) {
    case "message": {
      const receipt = await sendMessageRest(a.bookingId, a.payload, undefined, String(a.id), a.session);
      if (receipt?.hidden_for_me === true && a.session === getSessionGeneration()) receiptListeners.forEach(listener => {
        try { listener(a, receipt); } catch { /* A view cannot change confirmed delivery. */ }
      });
      return;
    }
    case "trip_status":
      await setTripStatus(a.bookingId, a.payload as TripStatus);
      return;
    case "driver_status":
      await setDriverStatus(a.bookingId, a.payload as DriverPhase);
      return;
    default:
      return; // неизвестный вид (старая версия сайта) — просто снимаем
  }
}

let flushing: string | null = null;
export async function flushOutbox(): Promise<boolean> {
  const generation = getSessionGeneration(), cleared = clearVersion;
  if (flushing === generation) return false;
  flushing = generation;
  const active = () => generation === getSessionGeneration() && cleared === clearVersion;
  const drain = async () => {
    let sent = false;
    while (active()) {
      const rows = await listActions(generation, active);
      if (!active() || rows.length === 0) break;
      const action = rows[0];
      try { await run(action); }
      catch (error) {
        if (!active()) return false;
        if (!isPermanentRejection(error)) break;
        recordRefusal(action, error); // отказ насовсем: действие снимаем, но человек должен о нём узнать
      }
      if (!active()) return false;
      await removeAction(action, active);
      sent = true; changed();
    }
    if (!active()) return false;
    await refreshOutbox();
    return sent;
  };
  try {
    if (typeof navigator !== "undefined" && navigator.locks) {
      return await navigator.locks.request(`yuldash-outbox:${generation}`, drain);
    }
    return await drain();
  } catch (error) {
    if (!active()) return false;
    throw error;
  } finally { if (flushing === generation) flushing = null; }
}

export function watchOutbox(onFlushed: () => void, onReceipt?: (bookingId: number, receipt: ChatMessage) => void): () => void {
  connect();
  const generation = getSessionGeneration();
  let stopped = false;
  let busy = false;
  let retry: ReturnType<typeof setTimeout> | null = null;
  const active = () => !stopped && generation === getSessionGeneration();
  const receiptListener = (action: OutboxAction, receipt: ChatMessage) => {
    if (active() && action.session === generation) onReceipt?.(action.bookingId, receipt);
  };
  receiptListeners.add(receiptListener);
  const clearRetry = () => {
    if (retry !== null) clearTimeout(retry);
    retry = null;
  };
  // Сервер может вернуться после 503, пока интернет всё время включён: события online не будет.
  // Повторяем только непустую очередь и только при открытой видимой вкладке, без параллельных отправок.
  const schedule = () => {
    if (!active() || !hasPending()) { clearRetry(); return; }
    if (busy || retry !== null) return;
    retry = setTimeout(() => { retry = null; tryFlush(); }, 7000);
  };
  const tryFlush = () => {
    if (!active() || busy) return;
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    clearRetry();
    busy = true;
    // Начальный UI-снимок может быть пуст, хотя база содержит сохранённые действия.
    void flushOutbox().then(changed => {
      if (changed && active()) onFlushed();
    }).catch(() => { /* Запись остаётся в базе до следующей попытки. */ })
      .finally(() => { busy = false; schedule(); });
  };
  const visible = () => { if (typeof document === "undefined" || document.visibilityState === "visible") tryFlush(); };
  const unsubscribe = subscribeOutbox(schedule);
  tryFlush();
  window.addEventListener("online", tryFlush);
  window.addEventListener("focus", visible);
  if (typeof document !== "undefined") document.addEventListener?.("visibilitychange", visible);
  return () => {
    stopped = true;
    receiptListeners.delete(receiptListener);
    clearRetry();
    unsubscribe();
    window.removeEventListener("online", tryFlush);
    window.removeEventListener("focus", visible);
    if (typeof document !== "undefined") document.removeEventListener?.("visibilitychange", visible);
  };
}

/** Revoke in-memory actions without touching an unavailable disk. */
export function resetOutboxMemory(): void { clearVersion++; readVersion++; snapshot = []; snapshotSession = ""; notify(); refusals = []; notifyRefusals(); }
