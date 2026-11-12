import { API_BASE, isAccountSession } from "../api/client";
import { COMMAND_KEY_PREFIX } from "./commandFingerprint";

type State = "unknown" | "applied" | "rejected" | "closed_no_effect";
interface CommandSlot {
  key: string;
  hash: string;
  createdAt: number;
  state: State;
}
export interface BookingCommand extends CommandSlot {
  rideId: number;
  /** Missing on old rows. Null means the original request omitted the version. */
  expectedTermsVersion?: number | null;
}
export interface RidePublication extends CommandSlot {
  draftHash: string;
  stopLanguage: "ru" | "ba";
  utcOffsetMinutes: number;
}
type PublicationRow = RidePublication | { key: string; state: "released" };
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

type Edit<T, Row> = { result: T; updated?: Row[] | null };
async function transaction<T, Row = BookingCommand>(
  owner: BookingCommandOwner, edit: (rows: Row[]) => Edit<T, Row>,
  requireCurrent = true, storageKey = owner.namespace,
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
      const request = store.get(storageKey);
      request.onsuccess = () => {
        try {
          if (!active()) throw new Error("Booking session changed");
          const { updated, result: value } = edit(request.result ?? []);
          result = value;
          if (updated === null) store.delete(storageKey);
          else if (updated !== undefined) store.put(updated, storageKey);
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
  owner: BookingCommandOwner, rideId: number, hash: string, replaceKey?: string, expectedTermsVersion?: number | null,
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
      ...(expectedTermsVersion !== undefined ? { expectedTermsVersion } : {}),
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

function publicationKey(owner: BookingCommandOwner): string {
  return JSON.stringify(["ride.create", owner.namespace]);
}

function publicationTransaction<T>(
  owner: BookingCommandOwner, edit: (rows: PublicationRow[]) => Edit<T, PublicationRow>,
  requireCurrent = true,
): Promise<T> {
  return transaction(owner, edit, requireCurrent, publicationKey(owner));
}

export function readPublicationSlot(owner: BookingCommandOwner) {
  return publicationTransaction(owner, ([row]) => ({
    result: { revision: row?.key ?? null, command: row?.state === "released" ? undefined : row },
  }));
}

type PublicationReservation =
  | { status: "reserved" | "existing"; command: RidePublication }
  | { status: "stale" };

export function reserveRidePublication(
  owner: BookingCommandOwner, revision: string | null, hash: string,
  normalization: Pick<RidePublication, "draftHash" | "stopLanguage" | "utcOffsetMinutes">,
) {
  return publicationTransaction<PublicationReservation>(owner, ([prior]) => {
    if (prior && prior.state !== "released") return { result: { status: "existing", command: prior } };
    if ((prior?.key ?? null) !== revision) return { result: { status: "stale" } };
    const command: RidePublication = {
      key: COMMAND_KEY_PREFIX + crypto.randomUUID(), hash, ...normalization,
      createdAt: Date.now(), state: "unknown",
    };
    return { result: { status: "reserved", command }, updated: [command] };
  });
}

export function resolveRidePublication(
  owner: BookingCommandOwner, command: RidePublication, state: Exclude<State, "unknown">,
): Promise<boolean> {
  return publicationTransaction(owner, ([current]) => {
    if (current?.key !== command.key) return { result: false };
    if (current.state !== "unknown") return { result: current.state === state };
    return { result: true, updated: [{ ...current, state }] };
  });
}

export function releaseRidePublication(owner: BookingCommandOwner, command: RidePublication): Promise<boolean> {
  return publicationTransaction(owner, ([current]) => {
    if (current?.key !== command.key || current.state === "unknown" || current.state === "released") return { result: false };
    return { result: true, updated: [{ key: current.key, state: "released" }] };
  });
}


export type DriverDecisionAction = "confirm" | "decline";
export interface DriverDecision extends CommandSlot {
  bookingId: number;
  action: DriverDecisionAction;
  termsKey?: string;
}

function driverDecisionTransaction<T>(
  owner: BookingCommandOwner, edit: (rows: DriverDecision[]) => Edit<T, DriverDecision>,
  requireCurrent = true,
): Promise<T> {
  return transaction(owner, edit, requireCurrent, JSON.stringify(["booking.driver_decision", owner.namespace]));
}

export function readDriverDecisions(owner: BookingCommandOwner) {
  return driverDecisionTransaction(owner, rows => ({ result: rows }));
}

export function reserveDriverDecision(
  owner: BookingCommandOwner, bookingId: number, action: DriverDecisionAction, hash: string,
  previousKey: string | undefined, termsKey?: string,
): Promise<DriverDecision | undefined> {
  return driverDecisionTransaction(owner, rows => {
    const prior = rows.find(row => row.bookingId === bookingId);
    if (prior?.state === "unknown" || prior?.key !== previousKey) return { result: undefined };
    const updated = rows.filter(row => row !== prior);
    if (updated.length >= MAX_SLOTS) {
      const evict = updated.findIndex(row => row.state !== "unknown");
      if (evict < 0) return { result: undefined };
      updated.splice(evict, 1);
    }
    const decision: DriverDecision = {
      key: COMMAND_KEY_PREFIX + crypto.randomUUID(), hash, createdAt: Date.now(), state: "unknown",
      bookingId, action, ...(termsKey === undefined ? {} : { termsKey }),
    };
    return { result: decision, updated: [...updated, decision] };
  });
}

export function resolveDriverDecision(owner: BookingCommandOwner, decision: DriverDecision, state: State) {
  return driverDecisionTransaction(owner, rows => {
    const current = rows.find(row => row.bookingId === decision.bookingId);
    if (current?.key !== decision.key) return { result: false };
    if (current.state !== "unknown" || state === "unknown") return { result: current.state === state };
    return { result: true, updated: rows.map(row => row === current ? { ...row, state } : row) };
  });
}

/** Call only after deleting the account and publishing the logged-out generation. */
export async function purgeBookingCommands(owner: BookingCommandOwner): Promise<void> {
  await Promise.all([
    transaction(owner, () => ({ result: undefined, updated: null }), false),
    publicationTransaction(owner, () => ({ result: undefined, updated: null }), false),
    driverDecisionTransaction(owner, () => ({ result: undefined, updated: null }), false),
  ]);
}
