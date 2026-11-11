import { API_BASE, isAccountSession } from "../api/client";
import { COMMAND_KEY_PREFIX } from "./commandFingerprint";

type State = "unknown" | "applied" | "rejected" | "closed_no_effect";
export interface BookingCommand {
  rideId: number;
  key: string;
  hash: string;
  createdAt: number;
  state: State;
}
export interface BookingCommandOwner {
  userId: number;
  generation: string;
  namespace: string;
}
const MAX_SLOTS = 64;
const DATABASE = "yuldash-booking-commands-v1";
const STORE = "accounts";

export function bookingCommandOwner(
  user: { id: number; created_at?: string } | null | undefined, generation: string,
): BookingCommandOwner | null {
  if (!user || !Number.isSafeInteger(user.id) || user.id <= 0 || !user.created_at || !generation) return null;
  return { userId: user.id, generation, namespace: JSON.stringify([API_BASE, user.id, user.created_at]) };
}

export function isCurrentBookingOwner(owner: BookingCommandOwner): boolean {
  return isAccountSession(owner);
}

type Edit<T> = { result: T; updated?: BookingCommand[] | null };
async function transaction<T>(
  owner: BookingCommandOwner, edit: (rows: BookingCommand[]) => Edit<T>, requireCurrent = true,
): Promise<T> {
  const active = () => !requireCurrent || isCurrentBookingOwner(owner);
  if (!active()) throw new Error("Booking session changed");
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onerror = () => reject(request.error);
    request.onblocked = () => { blocked = true; reject(new Error("Booking storage blocked")); };
    request.onsuccess = () => {
      if (blocked) request.result.close();
      else resolve(request.result);
    };
  });
  try {
    if (!active()) throw new Error("Booking session changed");
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite", { durability: "strict" });
      const store = tx.objectStore(STORE);
      let result: T;
      tx.onabort = () => reject(tx.error ?? new Error("Booking storage aborted"));
      tx.oncomplete = () => active() ? resolve(result) : reject(new Error("Booking session changed"));
      const request = store.get(owner.namespace);
      request.onsuccess = () => {
        try {
          if (!active()) throw new Error("Booking session changed");
          const { updated, result: value } = edit(request.result ?? []);
          result = value;
          if (updated === null) store.delete(owner.namespace);
          else if (updated !== undefined) store.put(updated, owner.namespace);
        } catch (error) { tx.abort(); reject(error); }
      };
    });
  } finally {
    db.close();
  }
}

export function readBookingCommand(owner: BookingCommandOwner, rideId: number) {
  return transaction(owner, rows => ({ result: rows.find(row => row.rideId === rideId) }));
}

export function readPendingBookingCommands(owner: BookingCommandOwner): Promise<BookingCommand[]> {
  return transaction(owner, rows => ({ result: rows.filter(row => row.state === "unknown") }));
}

type Reservation = { status: "reserved" | "existing"; command: BookingCommand } | { status: "full" };
export function reserveBookingCommand(
  owner: BookingCommandOwner, rideId: number, hash: string, replaceKey?: string,
): Promise<Reservation> {
  return transaction<Reservation>(owner, rows => {
    const prior = rows.find(row => row.rideId === rideId);
    if (prior && (prior.state === "unknown" || prior.key !== replaceKey)) {
      return { result: { status: "existing", command: prior } };
    }
    const updated = rows.filter(row => row !== prior);
    if (updated.length >= MAX_SLOTS) {
      const evict = updated.findIndex(row => row.state !== "unknown");
      if (evict === -1) return { result: { status: "full" } };
      updated.splice(evict, 1);
    }
    const command: BookingCommand = {
      rideId, key: COMMAND_KEY_PREFIX + crypto.randomUUID(), hash, createdAt: Date.now(), state: "unknown",
    };
    updated.push(command);
    return { result: { status: "reserved", command }, updated };
  });
}

export function resolveBookingCommand(
  owner: BookingCommandOwner, command: BookingCommand, state: Exclude<State, "unknown">,
): Promise<boolean> {
  return transaction(owner, rows => {
    const current = rows.find(row => row.rideId === command.rideId);
    if (current?.key !== command.key) return { result: false };
    if (current.state !== "unknown") return { result: current.state === state };
    return { result: true, updated: rows.map(row => row === current ? { ...row, state } : row) };
  });
}

/** Call only after deleting the account and publishing the logged-out generation. */
export function purgeBookingCommands(owner: BookingCommandOwner): Promise<void> {
  return transaction(owner, () => ({ result: undefined, updated: null }), false);
}
