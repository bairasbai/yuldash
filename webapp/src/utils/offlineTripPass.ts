import { getAccountSession, isAccountSession } from "../api/client";
import { offlineStorageTrusted, reportStorageFailure } from "./storageHealth";
import { serverDate } from "./serverTime";
import { sameBoardingTimestamp } from "./boardingUndo";
import { inTransitHelpAvailable } from "./inTransitCancel";
import {
  acceptTripPassState, closeTripPass, newTripPassRecord, positiveTripId, validTripPass,
  type TripPass, type TripPassOwner, type TripPassRecord, type TripPassSnapshot, type TripPassState,
} from "./tripPass";
import { editTripRecords, readTripRecords } from "./tripPassStore";

function currentOwner(owner: TripPassOwner): boolean {
  try { return offlineStorageTrusted() && isAccountSession(owner); } catch { return false; }
}

async function change(
  owner: TripPassOwner, bookingId: number,
  update: (record: TripPassRecord | undefined, closedRides: ReadonlySet<number>) => { result: boolean; record?: TripPassRecord },
  signal?: AbortSignal,
): Promise<boolean> {
  if (!positiveTripId(bookingId) || !currentOwner(owner) || signal?.aborted) return false;
  return editTripRecords(owner, () => !signal?.aborted && currentOwner(owner), (rows, closedRides) => {
    const previous = rows.get(bookingId);
    const { result, record } = update(previous, closedRides);
    if (record === undefined || record === previous) return { result };
    const trips = new Map(rows);
    trips.set(bookingId, record);
    return { result, trips };
  }, false);
}

export async function readTripPassRevision(owner: TripPassOwner | null, bookingId: number): Promise<string | null | undefined> {
  if (!owner || !positiveTripId(bookingId) || !currentOwner(owner)) return;
  try {
    const rows = await readTripRecords(owner, () => currentOwner(owner));
    if (!rows || !currentOwner(owner)) return;
    const record = rows.get(bookingId);
    if (record?.status === "terminal" || record?.cancellationKey) return;
    return record?.revision ?? null;
  } catch {
    return undefined;
  }
}

/** Data accepted from the authenticated details, role or action response only. */
export async function observeTripPass(
  owner: TripPassOwner | null, bookingId: number, state: TripPassState,
  revision: string | null | undefined, snapshot?: TripPassSnapshot, signal?: AbortSignal,
): Promise<void> {
  if (!owner || state.status === "pending") return;
  try {
    await change(owner, bookingId, (previous, closedRides) => {
      if (snapshot && closedRides.has(snapshot.rideId)) return { result: true, record: closeTripPass(previous) };
      let record = acceptTripPassState(previous, state, revision);
      if (record && record.status !== "terminal" && !record.cancellationKey && snapshot &&
          snapshot.bookingId === bookingId && revision !== undefined && revision ===
          (previous && previous.status !== "terminal" ? previous.revision : null) && record.status === state.status) {
        const matchingStage = record.status === "onboard"
          ? !record.onboardAt || sameBoardingTimestamp(record.onboardAt, state.onboard_at)
          : !record.undoId || (record.undoId === state.boarding_undo_confirmed_id &&
            sameBoardingTimestamp(record.undoAt, state.boarding_undo_onboard_at));
        const pass = { ...snapshot, ownerId: owner.userId, sessionGeneration: owner.generation,
          boardingCode: record.status === "confirmed" ? record.pass?.boardingCode ?? "" : "", savedAt: Date.now() };
        if (matchingStage && validTripPass(pass, owner, bookingId)) record = { ...record, rideId: snapshot.rideId, pass };
      }
      return { result: true, record };
    }, signal);
  } catch {
    if (!signal?.aborted && currentOwner(owner) &&
        (state.status === "onboard" || state.status === "cancelled" || state.status === "done")) reportStorageFailure();
  }
}

export async function readTripPassRevisions(owner: TripPassOwner | null): Promise<ReadonlyMap<number, string | null> | null> {
  if (!owner || !currentOwner(owner)) return null;
  try {
    const rows = await readTripRecords(owner, () => currentOwner(owner));
    if (!rows) return null;
    const revisions = new Map<number, string | null>();
    for (const [id, record] of rows) {
      if (record.status !== "terminal") revisions.set(id, record.revision);
    }
    return revisions;
  } catch {
    return null;
  }
}

export async function observeTripPassList(
  owner: TripPassOwner | null,
  bookings: ReadonlyArray<{ id: number } & TripPassState>,
  signal?: AbortSignal,
  revisions?: ReadonlyMap<number, string | null> | null,
): Promise<void> {
  if (!owner || !currentOwner(owner) || signal?.aborted) return;
  const changes = bookings.filter(row => positiveTripId(row.id) &&
    (row.status === "done" || row.status === "cancelled" || row.status === "onboard"));
  if (!changes.length) return;
  try {
    await editTripRecords(owner, () => !signal?.aborted && currentOwner(owner), rows => {
      const trips = new Map(rows);
      let changed = false;
      for (const row of changes) {
        const previous = trips.get(row.id);
        if (row.status === "onboard" && !sameBoardingTimestamp(row.onboard_at, row.onboard_at) &&
            (!revisions || (revisions.get(row.id) ?? null) !==
              (previous && previous.status !== "terminal" ? previous.revision : null))) continue;
        const record = acceptTripPassState(previous, row, revisions ? revisions.get(row.id) ?? null : undefined);
        if (record && record !== previous) {
          trips.set(row.id, record);
          changed = true;
        }
      }
      return { result: undefined, ...(changed && { trips }) };
    }, undefined);
  } catch {
    if (!signal?.aborted && currentOwner(owner)) reportStorageFailure();
  }
}

export async function saveTripPassCode(
  owner: TripPassOwner | null, bookingId: number, revision: string | null | undefined, code: string, signal?: AbortSignal,
): Promise<void> {
  if (!owner || revision === undefined || typeof code !== "string" || !code) return;
  try {
    await change(owner, bookingId, record => {
      if (record?.status !== "confirmed" || record.cancellationKey || record.revision !== revision || !record.pass) {
        return { result: false };
      }
      if (record.pass.boardingCode === code) return { result: true };
      return { result: true, record: { ...record, pass: { ...record.pass, boardingCode: code } } };
    }, signal);
  } catch { /* The online code is independent of saving a copy. */ }
}

export async function saveTripPassPayment(
  owner: TripPassOwner | null, bookingId: number, revision: string | null | undefined,
  payMethod: NonNullable<TripPass["payMethod"]>, price?: number | null,
): Promise<void> {
  if (!owner || revision === undefined) return;
  try {
    await change(owner, bookingId, record => {
      if (!record || record.status === "terminal" || record.cancellationKey || record.revision !== revision || !record.pass) {
        return { result: false };
      }
      const pass = { ...record.pass, payMethod, price: price ?? record.pass.price };
      return validTripPass(pass, owner, bookingId)
        ? { result: true, record: { ...record, pass } }
        : { result: false };
    });
  } catch { /* Payment agreement was already returned by the server. */ }
}

export function holdTripPassCancellation(owner: TripPassOwner, bookingId: number, key: string): Promise<boolean> {
  return change(owner, bookingId, previous => {
    if (previous?.status === "terminal" || previous?.cancellationKey === key) return { result: true };
    if (previous?.cancellationKey) return { result: false };
    const record = previous ?? newTripPassRecord();
    return { result: true, record: { ...record, pass: undefined, cancellationKey: key, revision: key } };
  });
}

export function releaseTripPassCancellation(owner: TripPassOwner, bookingId: number, key: string): Promise<boolean> {
  return change(owner, bookingId, record => {
    if (!record || record.status === "terminal" || record.cancellationKey !== key) return { result: true };
    return { result: true, record: { ...record, cancellationKey: undefined, revision: "closed:" + key } };
  });
}

export function finishTripPass(owner: TripPassOwner, bookingId: number): Promise<boolean> {
  return change(owner, bookingId, previous => ({ result: true, record: closeTripPass(previous) }));
}

export async function finishTripPassRide(owner: TripPassOwner | null, rideId: number): Promise<void> {
  if (!owner || !positiveTripId(rideId) || !currentOwner(owner)) return;
  try {
    await editTripRecords(owner, () => currentOwner(owner), (rows, closedRides) => {
      if (closedRides.has(rideId)) return { result: undefined };
      const trips = new Map(rows);
      for (const [bookingId, record] of rows) {
        if (record.status !== "terminal" && record.rideId === rideId) trips.set(bookingId, closeTripPass(record));
      }
      return { result: undefined, trips, closedRides: new Set([...closedRides, rideId]) };
    }, undefined);
  } catch {
    if (currentOwner(owner)) reportStorageFailure();
  }
}

export type OfflineTripView = {
  generation: string;
  status: "confirmed" | "onboard" | "terminal" | "held";
  cancelledInTransit: boolean;
  pass: TripPass | null;
};

function visiblePass(record: TripPassRecord | undefined, owner: TripPassOwner, bookingId: number): TripPass | null {
  if (!record || record.status === "terminal" || record.cancellationKey || !record.pass ||
      !validTripPass(record.pass, owner, bookingId)) return null;
  return record.status === "onboard" ? { ...record.pass, boardingCode: "" } : record.pass;
}

export async function readOfflineTrip(bookingId: number): Promise<OfflineTripView | null> {
  const owner = getAccountSession();
  if (!positiveTripId(bookingId) || !owner || !currentOwner(owner)) return null;
  try {
    const rows = await readTripRecords(owner, () => currentOwner(owner));
    if (!rows || !currentOwner(owner)) return null;
    const record = rows.get(bookingId);
    return { generation: owner.generation,
      status: record && record.status !== "terminal" && record.cancellationKey ? "held" : record?.status ?? "confirmed",
      cancelledInTransit: record?.status === "terminal" && inTransitHelpAvailable(record.cancelledInTransitAt),
      pass: visiblePass(record, owner, bookingId) };
  } catch {
    return null;
  }
}

export type OfflineTripOption = Pick<TripPass, "bookingId" | "fromCity" | "toCity" | "departAt">;
export type OfflineTripOptions = { generation: string; trips: OfflineTripOption[] };

/** The start screen gets only selection metadata from snapshots it can actually open. */
export async function listOfflineTripOptions(): Promise<OfflineTripOptions | null> {
  const owner = getAccountSession();
  if (!owner || !currentOwner(owner)) return null;
  try {
    const rows = await readTripRecords(owner, () => currentOwner(owner));
    if (!rows || !currentOwner(owner)) return null;
    const trips: Array<OfflineTripOption & { savedAt: number }> = [];
    for (const [id, record] of rows) {
      if (!positiveTripId(id)) continue;
      const pass = visiblePass(record, owner, id);
      if (!pass || !pass.fromCity.trim() || !pass.toCity.trim() || (pass.departAt && !serverDate(pass.departAt))) continue;
      const { bookingId, fromCity, toCity, departAt, savedAt } = pass;
      trips.push({ bookingId, fromCity, toCity, departAt, savedAt });
    }
    trips.sort((a, b) => b.savedAt - a.savedAt || b.bookingId - a.bookingId);
    return { generation: owner.generation,
      trips: trips.map(({ bookingId, fromCity, toCity, departAt }) => ({ bookingId, fromCity, toCity, departAt })) };
  } catch {
    return null;
  }
}
