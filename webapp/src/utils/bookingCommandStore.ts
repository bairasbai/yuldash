import { API_BASE, isAccountSession } from "../api/client";
import { COMMAND_KEY_PREFIX } from "./commandFingerprint";
import type { TripStatus } from "../api/family";
import type { RideSeriesIntent, RideSeriesResult } from "./rideSeriesIntent";

type State = "unknown" | "applied" | "rejected" | "closed_no_effect";
interface CommandSlot {
  key: string;
  hash: string;
  createdAt: number;
  state: State;
}
export interface PublicBookingSelection {
  version: 1;
  pickup: string;
  dropoff: string;
  seats: number;
  price?: number;
  payMethod?: "cash" | "sbp" | "negotiate";
  payAmount?: number;
  frontSeat?: boolean;
}
export interface BookingCommand extends CommandSlot {
  rideId: number;
  /** Missing on old rows. Null means the original request omitted the version. */
  expectedTermsVersion?: number | null;
  forAnother?: true;
  publicSelection?: PublicBookingSelection;
}
export interface RidePublication extends CommandSlot {
  draftHash: string;
  stopLanguage: "ru" | "ba";
  utcOffsetMinutes: number;
}
type PublicationRow = RidePublication | RideSeriesIntent | { key: string; state: "released" };
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
  forAnother = false, publicSelection?: PublicBookingSelection,
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
      ...(forAnother ? { forAnother: true as const } : {}),
      ...(publicSelection ? { publicSelection } : {}),
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
    if (current.state !== "unknown" && current.state !== state) return { result: false };
    if (current.state === state && !current.publicSelection) return { result: true };
    const { publicSelection: _selection, ...metadata } = current;
    return { result: true, updated: rows.map(row => row === current ? { ...metadata, state } : row) };
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
  return publicationTransaction<{
    revision: string | null; command?: RidePublication; series?: RideSeriesIntent;
  }>(owner, ([row]) => {
    if (row && "mode" in row) return { result: { revision: row.key, series: row } };
    return { result: { revision: row?.key ?? null, command: row?.state === "released" ? undefined : row } };
  });
}

type PublicationReservation =
  | { status: "reserved" | "existing"; command: RidePublication }
  | { status: "stale" };

export function reserveRidePublication(
  owner: BookingCommandOwner, revision: string | null, hash: string,
  normalization: Pick<RidePublication, "draftHash" | "stopLanguage" | "utcOffsetMinutes">,
) {
  return publicationTransaction<PublicationReservation>(owner, ([prior]) => {
    if (prior && "mode" in prior) return { result: { status: "stale" } };
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
    if (current?.key !== command.key || "mode" in current) return { result: false };
    if (current.state !== "unknown") return { result: current.state === state };
    return { result: true, updated: [{ ...current, state }] };
  });
}

export function releaseRidePublication(owner: BookingCommandOwner, command: RidePublication): Promise<boolean> {
  return publicationTransaction(owner, ([current]) => {
    if (current?.key !== command.key || "mode" in current ||
        current.state === "unknown" || current.state === "released") return { result: false };
    return { result: true, updated: [{ key: current.key, state: "released" }] };
  });
}


export function reserveRideSeries(owner: BookingCommandOwner, revision: string | null, series: RideSeriesIntent) {
  return publicationTransaction(owner, ([prior]) => {
    if ((prior?.key ?? null) !== revision || (prior && (!("state" in prior) || prior.state !== "released"))) {
      return { result: false };
    }
    return { result: true, updated: [series] };
  });
}

export function updateRideSeries(owner: BookingCommandOwner, series: RideSeriesIntent, updates: RideSeriesResult[]) {
  return publicationTransaction<RideSeriesIntent | undefined>(owner, ([current]) => {
    if (current?.key !== series.key || !("mode" in current)) return { result: undefined };
    const byDate = new Map(updates.map(item => [item.depart_at, item]));
    const items = current.items.map(item => {
      const update = byDate.get(item.depart_at);
      return item.outcome === "unknown" && update ? { ...item, ...update } : item;
    });
    const updated = { ...current, items };
    return { result: updated, updated: [updated] };
  });
}

export function replaceRejectedSeriesDates(
  owner: BookingCommandOwner, series: RideSeriesIntent, retry: RideSeriesIntent, replacedDates: string[],
) {
  return publicationTransaction<RideSeriesIntent | undefined>(owner, ([current]) => {
    if (current?.key !== series.key || !("mode" in current) || current.items.some(item => item.outcome === "unknown")) {
      return { result: undefined };
    }
    const rejected = current.items.filter(item => item.outcome === "rejected");
    const replaced = new Set(replacedDates);
    if (!replaced.size || replaced.size !== replacedDates.length || replaced.size !== retry.items.length ||
        replacedDates.some(date => !rejected.some(item => item.depart_at === date))) {
      return { result: undefined };
    }
    const retained = current.items.filter(item => !replaced.has(item.depart_at));
    if (retry.items.some(item => retained.some(ride => ride.depart_at === item.depart_at))) {
      return { result: undefined };
    }
    const updated = { ...retry, items: [...retained, ...retry.items].sort((a, b) => a.depart_at.localeCompare(b.depart_at)) };
    return { result: updated, updated: [updated] };
  });
}

export function releaseRideSeries(owner: BookingCommandOwner, series: RideSeriesIntent) {
  return publicationTransaction(owner, ([current]) => {
    if (current?.key !== series.key || !("mode" in current) || current.items.some(item => item.outcome === "unknown")) {
      return { result: false };
    }
    return { result: true, updated: [{ key: current.key, state: "released" }] };
  });
}


export type BookingDecisionAction = "confirm" | "decline" | "passenger_cancel";
export interface BookingDecision extends CommandSlot {
  bookingId: number;
  action: BookingDecisionAction;
  termsKey?: string;
  reason?: string;
}

function driverDecisionTransaction<T>(
  owner: BookingCommandOwner, edit: (rows: BookingDecision[]) => Edit<T, BookingDecision>,
  requireCurrent = true,
): Promise<T> {
  return transaction(owner, edit, requireCurrent, JSON.stringify(["booking.driver_decision", owner.namespace]));
}

export function readDriverDecisions(owner: BookingCommandOwner) {
  return driverDecisionTransaction(owner, rows => ({ result: rows }));
}

export function reserveDriverDecision(
  owner: BookingCommandOwner, bookingId: number, action: BookingDecisionAction, hash: string,
  previousKey: string | undefined, termsKey?: string, reason?: string,
): Promise<BookingDecision | undefined> {
  return driverDecisionTransaction(owner, rows => {
    const prior = rows.find(row => row.bookingId === bookingId);
    if (prior?.state === "unknown" || prior?.key !== previousKey) return { result: undefined };
    const updated = rows.filter(row => row !== prior);
    if (updated.length >= MAX_SLOTS) {
      const evict = updated.findIndex(row => row.state !== "unknown");
      if (evict < 0) return { result: undefined };
      updated.splice(evict, 1);
    }
    const decision: BookingDecision = {
      key: COMMAND_KEY_PREFIX + crypto.randomUUID(), hash, createdAt: Date.now(), state: "unknown",
      bookingId, action, ...(termsKey === undefined ? {} : { termsKey }), ...(reason === undefined ? {} : { reason }),
    };
    return { result: decision, updated: [...updated, decision] };
  });
}

export function resolveDriverDecision(owner: BookingCommandOwner, decision: BookingDecision, state: State) {
  return driverDecisionTransaction(owner, rows => {
    const current = rows.find(row => row.bookingId === decision.bookingId);
    if (current?.key !== decision.key) return { result: false };
    if (current.state !== "unknown" || state === "unknown") return { result: current.state === state };
    return { result: true, updated: rows.map(row => row === current ? { ...row, state } : row) };
  });
}

export type DriverStage = "boarded" | "done" | "no_show";

interface BookingStageCommand<Stage extends string> extends CommandSlot {
  bookingId: number;
  stage: Stage;
}

export type DriverStageCommand = BookingStageCommand<DriverStage>;
export type PassengerTripStatusCommand = BookingStageCommand<TripStatus>;
export const PASSENGER_TRIP_STATUS_SCOPE = "booking.trip_status";

function bookingStageStore<Stage extends string>(scope: string) {
  type Row = BookingStageCommand<Stage>;
  function stageTransaction<T>(
    owner: BookingCommandOwner, edit: (rows: Row[]) => Edit<T, Row>, requireCurrent = true,
  ): Promise<T> {
    return transaction(owner, edit, requireCurrent, JSON.stringify([scope, owner.namespace]));
  }
  return {
    read: (owner: BookingCommandOwner) => stageTransaction(owner, rows => ({ result: rows })),
    reserve(owner: BookingCommandOwner, bookingId: number, stage: Stage, hash: string, previousKey: string | undefined) {
      return stageTransaction<Row | undefined>(owner, rows => {
        const prior = rows.find(row => row.bookingId === bookingId);
        if (prior?.state === "unknown" || prior?.key !== previousKey) return { result: undefined };
        const updated = rows.filter(row => row !== prior);
        if (updated.length >= MAX_SLOTS) {
          const evict = updated.findIndex(row => row.state !== "unknown");
          if (evict < 0) return { result: undefined };
          updated.splice(evict, 1);
        }
        const command: Row = {
          bookingId, stage, hash, key: COMMAND_KEY_PREFIX + crypto.randomUUID(), createdAt: Date.now(), state: "unknown",
        };
        return { result: command, updated: [...updated, command] };
      });
    },
    resolve(owner: BookingCommandOwner, command: Row, state: State) {
      return stageTransaction(owner, rows => {
        const current = rows.find(row => row.bookingId === command.bookingId);
        if (current?.key !== command.key) return { result: false };
        if (current.state !== "unknown" || state === "unknown") return { result: current.state === state };
        return { result: true, updated: rows.map(row => row === current ? { ...row, state } : row) };
      });
    },
    purge: (owner: BookingCommandOwner) => stageTransaction(owner, () => ({ result: undefined, updated: null }), false),
  };
}

const driverStages = bookingStageStore<DriverStage>("booking.driver_status");
export const readDriverStageCommands = driverStages.read;
export const reserveDriverStageCommand = driverStages.reserve;
export const resolveDriverStageCommand = driverStages.resolve;
export const passengerTripStatuses = bookingStageStore<TripStatus>(PASSENGER_TRIP_STATUS_SCOPE);

export interface LegacyPassengerTripStatus {
  id: string;
  bookingId: number;
  stage: TripStatus;
  // Keep the import marker if another tab still holds the old row in memory.
  settled?: true;
}

function legacyPassengerTransaction<T>(
  owner: BookingCommandOwner, edit: (rows: LegacyPassengerTripStatus[]) => Edit<T, LegacyPassengerTripStatus>,
  requireCurrent = true,
) {
  return transaction(owner, edit, requireCurrent, JSON.stringify(["legacy-trip-status", owner.namespace]));
}

export const legacyPassengerTripStatuses = {
  read: (owner: BookingCommandOwner) => legacyPassengerTransaction(owner, rows => ({ result: rows.filter(row => !row.settled) })),
  import: (owner: BookingCommandOwner, entries: LegacyPassengerTripStatus[]) => legacyPassengerTransaction(owner, rows => {
    const known = new Set(rows.map(row => row.id));
    return { result: undefined, updated: [...rows, ...entries.filter(entry => !known.has(entry.id))] };
  }),
  remove: (owner: BookingCommandOwner, ids: ReadonlySet<string>) =>
    legacyPassengerTransaction(owner, rows => ({ result: undefined,
      updated: rows.map(row => ids.has(row.id) ? { ...row, settled: true } : row) })),
};

export interface SavedCardConfirmation {
  bookingId: number;
  key: string;
  hash: string;
  wireBody: string;
  createdAt: number;
}

function cardConfirmationTransaction<T>(
  owner: BookingCommandOwner, edit: (rows: SavedCardConfirmation[]) => Edit<T, SavedCardConfirmation>,
  requireCurrent = true,
) {
  return transaction(owner, edit, requireCurrent, JSON.stringify(["booking.card_confirm", owner.namespace]));
}

export function readCardConfirmation(owner: BookingCommandOwner, bookingId: number) {
  return cardConfirmationTransaction(owner, rows => ({ result: rows.find(row => row.bookingId === bookingId) }));
}

export function reserveCardConfirmation(owner: BookingCommandOwner, bookingId: number, hash: string, wireBody: string) {
  return cardConfirmationTransaction<SavedCardConfirmation | undefined>(owner, rows => {
    if (rows.length >= MAX_SLOTS || rows.some(row => row.bookingId === bookingId)) return { result: undefined };
    const command = { bookingId, key: COMMAND_KEY_PREFIX + crypto.randomUUID(), hash, wireBody, createdAt: Date.now() };
    return { result: command, updated: [...rows, command] };
  });
}

export function releaseCardConfirmation(owner: BookingCommandOwner, command: SavedCardConfirmation) {
  return cardConfirmationTransaction(owner, rows => {
    const current = rows.find(row => row.bookingId === command.bookingId);
    if (current?.key !== command.key) return { result: false };
    return { result: true, updated: rows.filter(row => row !== current) };
  });
}

/** Call only after deleting the account and publishing the logged-out generation. */
export async function purgeBookingCommands(owner: BookingCommandOwner): Promise<void> {
  await Promise.all([
    transaction(owner, () => ({ result: undefined, updated: null }), false),
    publicationTransaction(owner, () => ({ result: undefined, updated: null }), false),
    driverDecisionTransaction(owner, () => ({ result: undefined, updated: null }), false),
    driverStages.purge(owner),
    passengerTripStatuses.purge(owner),
    legacyPassengerTransaction(owner, () => ({ result: undefined, updated: null }), false),
    cardConfirmationTransaction(owner, () => ({ result: undefined, updated: null }), false),
  ]);
}
