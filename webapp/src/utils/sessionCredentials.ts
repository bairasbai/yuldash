import {
  initializeSessionRecord, notifyPushScopeChanged, readSessionRecord, replaceSessionRecord,
  type SessionCredentials, type SessionRecord,
} from "./sessionRecord";
import { publishTripPassChange } from "./tripPassStore";
import { canImportLegacyDuringRecovery, rememberSession, reportStorageFailure, sessionEpoch, storageFenced } from "./storageHealth";

const LEGACY = ["yuldash.session", "yuldash.token", "yuldash.refresh"] as const;
const CHANGE_KEY = "yuldash.auth.changed";
const CHANNEL = "yuldash-auth-changes";
let current: SessionRecord | undefined;
let startup: Promise<SessionRecord> | undefined;
let channel: BroadcastChannel | undefined;
const listeners = new Set<() => void>();

export function newSessionGeneration(): string {
  return crypto.randomUUID();
}

function accept(record: SessionRecord, external: boolean): SessionRecord {
  if (current && current.revision >= record.revision) return current;
  const changed = !!current && current.generation !== record.generation;
  current = record;
  rememberSession(record.generation);
  if (external && changed) listeners.forEach(listener => listener());
  return record;
}

function readLegacy(): SessionCredentials | undefined {
  const [generation, access, refresh] = LEGACY.map(key => localStorage.getItem(key));
  if (generation?.trim() && access?.trim() && refresh?.trim()) return { generation, access, refresh };
}

function discardLegacy(): void {
  try { LEGACY.forEach(key => localStorage.removeItem(key)); }
  catch { /* The committed record takes precedence even if cleanup is interrupted. */ }
}

export function initializeSessionStorage(): Promise<SessionRecord> {
  if (current) return Promise.resolve(current);
  if (!startup) {
    startup = initializeSessionRecord(newSessionGeneration(), readLegacy).then(record => {
      discardLegacy();
      return accept(record, false);
    }).catch(error => {
      reportStorageFailure(false);
      throw error;
    }).finally(() => { startup = undefined; });
  }
  return startup;
}

export function cachedSession(): SessionRecord | undefined {
  return storageFenced() ? undefined : current;
}

export async function reconcileSession(): Promise<void> {
  if (!current || storageFenced()) return;
  const epoch = sessionEpoch();
  try {
    let record = await readSessionRecord();
    if (epoch !== sessionEpoch() || storageFenced()) return;
    const cached = current;
    if (record.revision <= cached.revision && record.generation !== cached.generation) {
      // Confirm a replaced database before fencing: an older concurrent read may finish last.
      record = await readSessionRecord();
      if (epoch !== sessionEpoch() || storageFenced() || current !== cached) return;
      if (record.revision <= cached.revision && record.generation !== cached.generation) {
        throw new Error("Session storage was replaced");
      }
    }
    accept(record, true);
  } catch (error) {
    if (epoch === sessionEpoch()) reportStorageFailure(false);
    throw error;
  }
}

function receiveChange(): void {
  void reconcileSession().catch(() => { /* The storage boundary handles failed reads. */ });
}

export function isSessionStorageSignal(key: string | null): boolean {
  return key === CHANGE_KEY || key === null;
}

function storageChanged(event: StorageEvent): void {
  if (isSessionStorageSignal(event.key)) receiveChange();
}

export function subscribeSessionChanges(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1 && typeof window !== "undefined") {
    window.addEventListener("storage", storageChanged);
    if (typeof window.BroadcastChannel === "function") {
      channel = new window.BroadcastChannel(CHANNEL);
      channel.onmessage = receiveChange;
    }
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && typeof window !== "undefined") {
      window.removeEventListener("storage", storageChanged);
      channel?.close();
      channel = undefined;
    }
  };
}

function notifyOtherTabs(revision: number): void {
  try { channel?.postMessage(null); } catch { /* A later read uses the committed record. */ }
  try { localStorage.setItem(CHANGE_KEY, String(revision)); } catch { /* The notification carries no authority. */ }
}

export async function persistSession(
  generation: string,
  next: SessionCredentials,
  permit: () => void,
  expectedPair?: Pick<SessionCredentials, "access" | "refresh">,
): Promise<{ applied: boolean; current: SessionRecord }> {
  try {
    const result = await replaceSessionRecord(generation, next, permit, expectedPair);
    const confirmed = accept(result.current, !result.applied);
    if (result.applied) {
      notifyOtherTabs(result.current.revision);
      if (generation !== result.current.generation) {
        publishTripPassChange();
        notifyPushScopeChanged();
      }
    }
    return { applied: result.applied && confirmed.revision === result.current.revision, current: confirmed };
  } catch (error) {
    reportStorageFailure(false);
    throw error;
  }
}

export async function sessionForRecovery(): Promise<SessionRecord> {
  const legacy = canImportLegacyDuringRecovery() ? readLegacy : () => undefined;
  const previous = current?.generation;
  current = await initializeSessionRecord(newSessionGeneration(), legacy, true);
  if (previous !== current.generation) notifyPushScopeChanged();
  discardLegacy();
  return current;
}
