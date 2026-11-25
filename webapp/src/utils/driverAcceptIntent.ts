import { API_BASE, isAccountSession } from "../api/client";
import { editDriverAcceptRecord } from "./sessionRecord";

export type DriverAcceptOwner = Parameters<typeof isAccountSession>[0];
type Action = "accept" | "decline";
type Closed = { id: number; until: number };
type Pending = Closed & { attempt: string; action: Action };
type Saved = { pending?: Pending; closed: Closed[] };
export type DriverAcceptIntent = Pending & { owner: DriverAcceptOwner };
type Claim =
  | { state: "claimed"; intent: DriverAcceptIntent }
  | { state: "existing"; intent: DriverAcceptIntent }
  | { state: "unavailable" }
  | { state: "closed" };

const validId = (id: unknown): id is number =>
  typeof id === "number" && Number.isInteger(id) && id > 0 && id <= 2_147_483_647;
const validClosed = (value: unknown): value is Closed => {
  const row = value as Partial<Closed> | null;
  return !!row && validId(row.id) && typeof row.until === "number" && Number.isSafeInteger(row.until) && row.until > 0;
};
const validPending = (value: unknown): value is Pending => {
  const row = value as Partial<Pending> | null;
  return validClosed(value) && !!row && (row.action === "accept" || row.action === "decline") &&
    typeof row.attempt === "string" && /^[0-9a-f-]{36}$/.test(row.attempt);
};
const MAX_CLOSED = 32;
function savedState(owner: DriverAcceptOwner, value: unknown): Saved {
  if (value === undefined) return { closed: [] };
  const row = value as { api?: unknown; user?: unknown; pending?: unknown; closed?: unknown; id?: unknown; attempt?: unknown } | null;
  if (row?.api === API_BASE && row.user === owner.userId) {
    if (Array.isArray(row.closed) && row.closed.length <= MAX_CLOSED && row.closed.every(validClosed) &&
        (row.pending === undefined || validPending(row.pending))) {
      return { pending: row.pending, closed: row.closed };
    }
    // Previous QA build wrote an acceptance only, without a deadline.
    if (row.closed === undefined && row.pending === undefined && validId(row.id) &&
        typeof row.attempt === "string" && /^[0-9a-f-]{36}$/.test(row.attempt)) {
      return { pending: {id: row.id, attempt: row.attempt, action: "accept", until: Date.now() + 60_000}, closed: [] };
    }
  }
  throw new Error("Unrecognized driver offer decision");
}

function assertOwner(owner: DriverAcceptOwner) {
  if (!isAccountSession(owner)) throw new Error("Driver session changed");
}
const record = (owner: DriverAcceptOwner, state: Saved) => ({api: API_BASE, user: owner.userId, ...state});

export async function readDriverAcceptIntent(owner: DriverAcceptOwner): Promise<DriverAcceptIntent | null> {
  assertOwner(owner);
  const result = await editDriverAcceptRecord(owner.generation, value => {
    assertOwner(owner);
    const { pending } = savedState(owner, value);
    return pending ? { ...pending, owner } : null;
  });
  assertOwner(owner);
  return result;
}

export async function beginDriverAcceptIntent(owner: DriverAcceptOwner, id: number, action: Action, until: number): Promise<Claim> {
  if (!validId(id) || !Number.isSafeInteger(until)) return { state: "unavailable" };
  try {
    assertOwner(owner);
    return await editDriverAcceptRecord(owner.generation, (value, replace) => {
      assertOwner(owner);
      const now = Date.now();
      if (until <= now) return { state: "closed" };
      const saved = savedState(owner, value);
      const closed = saved.closed.filter(entry => entry.until > now);
      if (closed.some(entry => entry.id === id)) return { state: "closed" };
      if (saved.pending) return { state: "existing", intent: { ...saved.pending, owner } };
      if (closed.length >= MAX_CLOSED) return { state: "unavailable" };
      const pending = { id, attempt: crypto.randomUUID(), action, until };
      replace(record(owner, { pending, closed }));
      return { state: "claimed", intent: { ...pending, owner } };
    });
  } catch {
    return { state: "unavailable" }; // Never send a decision without a committed claim.
  }
}

export async function clearDriverAcceptIntent(intent: DriverAcceptIntent, settled = false): Promise<void> {
  try {
    await editDriverAcceptRecord(intent.owner.generation, (value, replace, remove) => {
      assertOwner(intent.owner);
      const saved = savedState(intent.owner, value);
      if (saved.pending?.id !== intent.id || saved.pending.attempt !== intent.attempt) return;
      const now = Date.now();
      const closed = saved.closed.filter(entry => entry.until > now);
      if (settled && intent.until > now) closed.push({id: intent.id, until: intent.until});
      if (closed.length) replace(record(intent.owner, { closed }));
      else remove();
    });
  } catch { /* Unknown local state cannot issue a replay; the next screen reads it. */ }
}
