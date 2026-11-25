import { API_BASE, isAccountSession } from "../api/client";
import { reconcileSession } from "./sessionCredentials";
import { assertSessionPermit, sessionMutation, storageFenceEnabled } from "./storageHealth";

export type DriverAcceptOwner = Parameters<typeof isAccountSession>[0];
export type DriverAcceptIntent = { id: number; attempt: string; owner: DriverAcceptOwner };
const KEY = "yuldash.taxi.pendingAccept";
const validId = (id: unknown): id is number =>
  typeof id === "number" && Number.isInteger(id) && id > 0 && id <= 2_147_483_647;

export function readDriverAcceptIntent(owner: DriverAcceptOwner): DriverAcceptIntent | null {
  if (!isAccountSession(owner)) return null;
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const row = JSON.parse(raw);
    if (row?.api !== API_BASE || row.user !== owner.userId || row.login !== owner.generation ||
        !validId(row.id) || typeof row.attempt !== "string" || !/^[0-9a-f-]{36}$/.test(row.attempt)) return null;
    return { id: row.id, attempt: row.attempt, owner };
  } catch { return null; }
}

function withIntentMutation<T>(work: () => T): Promise<T> {
  // Basic localStorage reads/writes are not a cross-tab transaction. Never
  // permit an Accept that cannot be claimed under the same lock as logout.
  if (!storageFenceEnabled() || typeof navigator === "undefined" || !navigator.locks) {
    return Promise.reject(new Error("Safe taxi acceptance requires Web Locks"));
  }
  return Promise.resolve(sessionMutation(async permit => {
    await reconcileSession();
    assertSessionPermit(permit);
    return work();
  }));
}

export async function beginDriverAcceptIntent(owner: DriverAcceptOwner, id: number): Promise<DriverAcceptIntent | null> {
  if (!validId(id)) return null;
  try {
    return await withIntentMutation(() => {
      if (!isAccountSession(owner) || readDriverAcceptIntent(owner)) return null;
      const attempt = crypto.randomUUID();
      const data = JSON.stringify({ api: API_BASE, user: owner.userId, login: owner.generation, id, attempt });
      localStorage.setItem(KEY, data);
      if (localStorage.getItem(KEY) !== data || !isAccountSession(owner)) return null;
      return { id, attempt, owner };
    });
  } catch {
    // If the intent could not be saved, do not send an untrackable Accept.
    return null;
  }
}

export async function clearDriverAcceptIntent(intent: DriverAcceptIntent): Promise<void> {
  try {
    await withIntentMutation(() => {
      const current = readDriverAcceptIntent(intent.owner);
      if (current?.id === intent.id && current.attempt === intent.attempt) localStorage.removeItem(KEY);
    });
  } catch { /* A confirmed result may be reconciled again, but is never replayed. */ }
}
