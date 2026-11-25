import { API_BASE, isAccountSession } from "../api/client";
import { editDriverAcceptRecord } from "./sessionRecord";

export type DriverAcceptOwner = Parameters<typeof isAccountSession>[0];
export type DriverAcceptIntent = { id: number; attempt: string; owner: DriverAcceptOwner };

type Claim =
  | { state: "claimed" | "existing"; intent: DriverAcceptIntent }
  | { state: "unavailable" };

const validId = (id: unknown): id is number =>
  typeof id === "number" && Number.isInteger(id) && id > 0 && id <= 2_147_483_647;

function savedIntent(owner: DriverAcceptOwner, value: unknown): DriverAcceptIntent | null {
  if (value === undefined) return null;
  const row = value as { api?: unknown; user?: unknown; id?: unknown; attempt?: unknown } | null;
  if (row?.api !== API_BASE || row.user !== owner.userId || !validId(row.id) ||
      typeof row.attempt !== "string" || !/^[0-9a-f-]{36}$/.test(row.attempt)) {
    throw new Error("Unrecognized driver acceptance record");
  }
  return { id: row.id, attempt: row.attempt, owner };
}

function assertOwner(owner: DriverAcceptOwner) {
  if (!isAccountSession(owner)) throw new Error("Driver session changed");
}

export async function readDriverAcceptIntent(owner: DriverAcceptOwner): Promise<DriverAcceptIntent | null> {
  assertOwner(owner);
  const result = await editDriverAcceptRecord(owner.generation, value => {
    assertOwner(owner);
    return savedIntent(owner, value);
  });
  assertOwner(owner);
  return result;
}

export async function beginDriverAcceptIntent(owner: DriverAcceptOwner, id: number): Promise<Claim> {
  if (!validId(id)) return { state: "unavailable" };
  try {
    assertOwner(owner);
    return await editDriverAcceptRecord(owner.generation, (value, replace) => {
      assertOwner(owner);
      const existing = savedIntent(owner, value);
      if (existing) return { state: "existing", intent: existing };
      const attempt = crypto.randomUUID();
      replace({ api: API_BASE, user: owner.userId, id, attempt });
      return { state: "claimed", intent: { id, attempt, owner } };
    });
  } catch {
    return { state: "unavailable" }; // No committed intent means no Accept.
  }
}

export async function clearDriverAcceptIntent(intent: DriverAcceptIntent): Promise<void> {
  try {
    await editDriverAcceptRecord(intent.owner.generation, (value, _replace, remove) => {
      assertOwner(intent.owner);
      const current = savedIntent(intent.owner, value);
      if (current?.id === intent.id && current.attempt === intent.attempt) remove();
    });
  } catch { /* A confirmed result may be reconciled again, but is never replayed. */ }
}
