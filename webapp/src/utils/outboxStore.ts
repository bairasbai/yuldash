import { getSessionGeneration } from "../api/client";
import type { OutboxAction } from "./outbox";

const DATABASE = "yuldash-outbox-v1";
let connection: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (!connection) {
    connection = new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(DATABASE, 1);
      let blocked = false;
      request.onupgradeneeded = () => {
        const actions = request.result.createObjectStore("actions", { keyPath: "sequence", autoIncrement: true });
        actions.createIndex("session", "session");
        actions.createIndex("identity", "identity", { unique: true });
        // Квитанция импорта остаётся после отправки: старый снимок не воскресит сообщение.
        request.result.createObjectStore("imported");
      };
      request.onblocked = () => { blocked = true; reject(new Error("Queue database blocked")); };
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        if (blocked) { request.result.close(); return; }
        request.result.onversionchange = () => { request.result.close(); connection = null; };
        resolve(request.result);
      };
    }).catch((error) => { connection = null; throw error; });
  }
  return connection;
}

const identity = (a: OutboxAction) => JSON.stringify([a.session, a.id]);
async function legacy(generation: string): Promise<Map<string, { action: OutboxAction; fingerprint: string; position: number }[]>> {
  const groups = new Map<string, { action: OutboxAction; fingerprint: string; position: number }[]>();
  let rows: OutboxAction[];
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem("yuldash.outbox") ?? "[]");
    if (!Array.isArray(parsed)) return groups;
    rows = parsed.filter((a): a is OutboxAction => a != null && a.session === generation &&
      (typeof a.id === "string" || typeof a.id === "number") && Number.isFinite(a.bookingId) &&
      ["message", "trip_status", "driver_status"].includes(a.kind) && typeof a.payload === "string" && Number.isFinite(a.createdAt));
  } catch { return groups; }
  for (const [position, action] of rows.entries()) {
    const bytes = new TextEncoder().encode(JSON.stringify([
      action.session, action.id, action.bookingId, action.kind, action.payload, action.createdAt,
    ]));
    const fingerprint = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
      byte => byte.toString(16).padStart(2, "0")).join("");
    const key = identity(action), group = groups.get(key) ?? [];
    if (!group.some(row => row.fingerprint === fingerprint)) group.push({ action, fingerprint, position });
    groups.set(key, group);
  }
  return groups;
}

async function transaction<T>(generation: string, active: () => boolean,
  work: (actions: IDBObjectStore, done: (value: T) => void) => void): Promise<T> {
  const db = await open();
  if (!active()) throw new Error("Queue session changed");
  const old = await legacy(generation);
  if (!active()) throw new Error("Queue session changed");
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction(["actions", "imported"], "readwrite");
    const actions = tx.objectStore("actions"), imported = tx.objectStore("imported");
    let value: T;
    tx.oncomplete = () => active() ? resolve(value) : reject(new Error("Queue session changed"));
    tx.onabort = () => reject(tx.error ?? new Error("Queue transaction aborted"));
    tx.onerror = () => {}; // onabort даёт единственный окончательный результат.
    const fail = (error: unknown) => { tx.abort(); reject(error); };
    const start = () => {
      if (!active()) { tx.abort(); return; }
      try { work(actions, result => { value = result; }); } catch (error) { fail(error); }
    };
    let remaining = old.size;
    const pending: { action: OutboxAction; position: number }[] = [];
    if (!remaining) { start(); return; }
    for (const [key, group] of old) {
      const request = imported.get(key);
      request.onsuccess = () => {
        if (!active()) { tx.abort(); return; }
        try {
          // Старое true не содержит отпечатка: нельзя угадывать, что уже отправлено.
          // Сохраняем его прежнее поведение, не воскрешая подтверждённые сообщения.
          if (request.result !== true) {
            const fingerprints: string[] = request.result ?? [];
            for (const { action, fingerprint, position } of group) {
              if (fingerprints.includes(fingerprint)) continue;
              // Последний элемент сохраняет старый ключ, как в прежнем импорте.
              // Остальные получают отдельные стабильные ключи повторной отправки.
              const id = !request.result && fingerprint === group[group.length - 1].fingerprint
                ? action.id : `legacy-${fingerprint}`;
              const migrated = { ...action, id };
              pending.push({ action: migrated, position });
              fingerprints.push(fingerprint);
            }
            imported.put(fingerprints, key);
          }
          if (--remaining === 0) {
            for (const { action } of pending.sort((a, b) => a.position - b.position)) {
              actions.add({ ...action, identity: identity(action) });
            }
            start();
          }
        } catch (error) { fail(error); return; }
      };
    }
  });
}

export function listActions(generation: string, active: () => boolean): Promise<OutboxAction[]> {
  return transaction(generation, active, (actions, done) => {
    const request = actions.index("session").getAll(generation);
    request.onsuccess = () => done(request.result);
  });
}
export function insertAction(action: OutboxAction, active: () => boolean): Promise<void> {
  return transaction(action.session, active, (actions, done) => {
    actions.add({ ...action, identity: identity(action) });
    done(undefined);
  });
}
export function removeAction(action: OutboxAction, active: () => boolean): Promise<void> {
  return transaction(action.session, active, (actions, done) => {
    const request = actions.index("identity").getKey(identity(action));
    request.onsuccess = () => {
      if (request.result !== undefined) actions.delete(request.result);
      done(undefined);
    };
  });
}
export async function clearActions(generation: string): Promise<void> {
  const db = await open();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction("actions", "readwrite"), actions = tx.objectStore("actions");
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error ?? new Error("Queue cleanup aborted"));
    const request = actions.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      // Поздняя очистка A не затрагивает уже вошедшего B.
      if (cursor.value.session === generation || cursor.value.session !== getSessionGeneration()) cursor.delete();
      cursor.continue();
    };
  });
}
