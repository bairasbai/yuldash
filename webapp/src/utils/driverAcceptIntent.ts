import { API_BASE, isAccountSession } from "../api/client";

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

export function beginDriverAcceptIntent(owner: DriverAcceptOwner, id: number): DriverAcceptIntent | null {
  if (!validId(id) || !isAccountSession(owner) || readDriverAcceptIntent(owner)) return null;
  try {
    const attempt = crypto.randomUUID();
    const data = JSON.stringify({ api: API_BASE, user: owner.userId, login: owner.generation, id, attempt });
    localStorage.setItem(KEY, data);
    if (localStorage.getItem(KEY) !== data || !isAccountSession(owner)) return null;
    return { id, attempt, owner };
  } catch {
    // If the intent could not be saved, do not send an untrackable Accept.
    return null;
  }
}

export function clearDriverAcceptIntent(intent: DriverAcceptIntent): void {
  try {
    const current = readDriverAcceptIntent(intent.owner);
    if (current?.id === intent.id && current.attempt === intent.attempt) localStorage.removeItem(KEY);
  } catch { /* A confirmed result may be reconciled again, but is never replayed. */ }
}
