import { AUTH_STORE, TRIP_STORE, openTripDatabase } from "./tripPassStore";

export interface SessionCredentials {
  generation: string;
  access: string | null;
  refresh: string | null;
  logoutToken?: string;
}

export interface SessionRecord extends SessionCredentials {
  revision: number;
}

const CURRENT = "current";
const REVISION = "revision";
const WEB_PUSH = "web-push";
export const SESSION_LOGOUT_TOKEN = /^[0-9a-f]{64}\.[0-9a-f]{64}$/;

function savedRevision(value: unknown): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : 0;
}

function parse(value: unknown): SessionRecord | undefined {
  if (value === undefined) return;
  const row = value as Partial<SessionRecord> | null;
  if (!row || typeof row.generation !== "string" || !row.generation ||
      !savedRevision(row.revision) ||
      (row.logoutToken !== undefined && (row.access === null ||
        typeof row.logoutToken !== "string" || !SESSION_LOGOUT_TOKEN.test(row.logoutToken))) ||
      !(row.access === null ? row.refresh === null :
        typeof row.access === "string" && !!row.access.trim() &&
        typeof row.refresh === "string" && !!row.refresh.trim())) {
    throw new Error("Invalid saved session");
  }
  return row as SessionRecord;
}

async function transaction<T>(
  mode: "readonly" | "readwrite",
  work: (current: SessionRecord | undefined, revoked: boolean, put: (row: SessionRecord) => void,
    revoke: (generation: string, ticket?: string) => void, candidate: SessionCredentials | undefined, revision: number) => T,
  readLegacy?: () => SessionCredentials | undefined,
  repairInvalid = false,
): Promise<T> {
  const db = await openTripDatabase();
  try {
    return await new Promise<T>((resolve, reject) => {
      const stores = mode === "readwrite" ? [AUTH_STORE, TRIP_STORE] : [AUTH_STORE];
      const tx = mode === "readwrite"
        ? db.transaction(stores, mode, { durability: "strict" })
        : db.transaction(stores, mode);
      const auth = tx.objectStore(AUTH_STORE);
      const put = (row: SessionRecord) => {
        parse(row);
        auth.put(row, CURRENT);
        auth.put(row.revision, REVISION);
      };
      const revoke = (generation: string, ticket?: string) => {
        tx.objectStore(TRIP_STORE).put({ revoked: true }, generation);
        if (ticket) auth.put({ generation, ticket }, ["logout", generation]);
      };
      let result: T;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(tx.error ?? new Error("Session transaction aborted"));
      function check(run: () => void) {
        try { run(); } catch (error) { tx.abort(); reject(error); }
      }
      const request = auth.get(CURRENT);
      request.onsuccess = () => check(() => {
        let current: SessionRecord | undefined;
        try { current = parse(request.result); }
        catch (error) {
          if (!repairInvalid) throw error;
          const generation = request.result?.generation;
          if (typeof generation === "string" && generation) revoke(generation);
          const clock = auth.get(REVISION);
          clock.onsuccess = () => check(() => {
            const revision = Math.max(savedRevision(clock.result), savedRevision(request.result?.revision));
            result = work(undefined, false, put, revoke, undefined, revision);
          });
          return;
        }
        const revision = current?.revision ?? 0;
        const candidate = current ?? readLegacy?.();
        if (mode === "readonly" || !candidate?.access) {
          result = work(current, false, put, revoke, candidate, revision);
          return;
        }
        const pass = tx.objectStore(TRIP_STORE).get(candidate.generation);
        pass.onsuccess = () => check(() => {
          result = work(current, pass.result?.revoked === true, put, revoke, candidate, revision);
        });
      });
    });
  } finally {
    db.close();
  }
}

export function initializeSessionRecord(
  guestGeneration: string,
  readLegacy: () => SessionCredentials | undefined,
  repairInvalid = false,
): Promise<SessionRecord> {
  return transaction("readwrite", (current, revoked, put, _revoke, candidate, revision) => {
    if (current && !revoked) return current;
    const credentials = candidate?.access && !revoked
      ? candidate
      : { generation: guestGeneration, access: null, refresh: null };
    const saved = { ...credentials, revision: revision + 1 };
    put(saved);
    return saved;
  }, readLegacy, repairInvalid);
}

export function readSessionRecord(): Promise<SessionRecord> {
  return transaction("readonly", (current, revoked) => {
    if (!current || revoked) throw new Error("Saved session is unavailable");
    return current;
  });
}

export function replaceSessionRecord(
  expectedGeneration: string,
  next: SessionCredentials,
  canWrite: () => void,
  expectedPair?: Pick<SessionCredentials, "access" | "refresh">,
): Promise<{ current: SessionRecord; applied: boolean }> {
  return transaction("readwrite", (current, revoked, put, revoke) => {
    canWrite();
    if (!current) throw new Error("Saved session is unavailable");
    if (revoked || current.generation !== expectedGeneration ||
        (expectedPair && (current.access !== expectedPair.access || current.refresh !== expectedPair.refresh))) {
      if (revoked) throw new Error("Saved session is revoked");
      return { current, applied: false };
    }
    if (current.generation !== next.generation) revoke(current.generation, current.logoutToken);
    const saved = { ...next, revision: current.revision + 1 };
    put(saved);
    return { current: saved, applied: true };
  });
}

interface PendingSessionLogout {
  generation: string;
  ticket: string;
}

async function credentialRequest<T>(mode: "readonly" | "readwrite", operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openTripDatabase();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(AUTH_STORE, mode, mode === "readwrite" ? { durability: "strict" } : undefined);
      const request = operation(tx.objectStore(AUTH_STORE));
      tx.oncomplete = () => resolve(request.result);
      tx.onabort = () => reject(tx.error ?? new Error("Credential storage unavailable"));
    });
  } finally {
    db.close();
  }
}

export async function listPendingSessionLogouts(): Promise<PendingSessionLogout[]> {
  const rows = await credentialRequest("readonly", store => store.getAll());
  return rows.filter((row): row is PendingSessionLogout => typeof row?.generation === "string" &&
    typeof row?.ticket === "string" && SESSION_LOGOUT_TOKEN.test(row.ticket));
}

export async function acknowledgeSessionLogout(generation: string): Promise<void> {
  await credentialRequest("readwrite", store => store.delete(["logout", generation]));
}

export interface WebPushBinding {
  generation: string;
  endpoint: string;
  sessionId: string;
  bindingId: string;
}

export async function readWebPushBinding(): Promise<WebPushBinding | undefined> {
  const row = await credentialRequest("readonly", store => store.get(WEB_PUSH));
  if (typeof row?.generation === "string" && typeof row.endpoint === "string" &&
      typeof row.sessionId === "string" && /^[0-9a-f]{64}$/.test(row.sessionId) &&
      typeof row.bindingId === "string" && /^[0-9a-f]{32}$/.test(row.bindingId)) return row;
}

export async function writeWebPushBinding(
  generation: string, next: WebPushBinding | undefined, expectedBindingId?: string,
): Promise<boolean> {
  const db = await openTripDatabase();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(AUTH_STORE, "readwrite", { durability: "strict" });
      const store = tx.objectStore(AUTH_STORE);
      let applied = false;
      tx.oncomplete = () => resolve(applied);
      tx.onabort = () => reject(tx.error ?? new Error("Push binding storage unavailable"));
      const current = store.get(CURRENT);
      current.onsuccess = () => {
        if (current.result?.generation !== generation || !current.result.access) return;
        if (next) {
          store.put(next, WEB_PUSH);
          applied = true;
          return;
        }
        const binding = store.get(WEB_PUSH);
        binding.onsuccess = () => {
          if (binding.result?.generation !== generation || binding.result.bindingId !== expectedBindingId) return;
          store.delete(WEB_PUSH);
          applied = true;
        };
      };
    });
  } finally {
    db.close();
  }
}

export function notifyPushScopeChanged(): void {
  try {
    if (typeof navigator === "undefined" || !navigator.serviceWorker) return;
    const message = { type: "auth-scope-changed" };
    const worker = navigator.serviceWorker.controller;
    if (worker) worker.postMessage(message);
    else void navigator.serviceWorker.getRegistration?.().then(registration => {
      registration?.active?.postMessage(message);
    }).catch(() => {});
  } catch { /* The worker independently checks storage before displaying any details. */ }
}
