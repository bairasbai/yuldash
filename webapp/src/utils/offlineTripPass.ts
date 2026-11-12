import { storageOperation, offlineStorageTrusted } from "./storageHealth";
import { getSessionGeneration, getToken, isAccountSession } from "../api/client";
import { hasOfflineInTransitCancel } from "./inTransitCancel";
import { tripPassStage, type TripPass } from "./tripPass";

const passKey = (id: number) => `yuldash.tripPass.${id}`;
const ownerKey = (id: number) => `yuldash.tripPass.${id}.owner`;
const positiveId = (id: unknown): id is number => typeof id === "number" && Number.isSafeInteger(id) && id > 0;

/** Caller gets ownerId from the verified AuthProvider user, never from a cached pass. */
export function saveVerifiedTripPass(pass: Omit<TripPass, "savedAt">, ownerId: number | undefined, generation: string): void {
  try {
    if (!offlineStorageTrusted()) return;
    const token = getToken();
    const current = () => !!token && getToken() === token && !!generation && getSessionGeneration() === generation;
    if (!positiveId(ownerId) || !positiveId(pass.bookingId) || !current()) return;
    const ownershipId = crypto.randomUUID();
    const stage = tripPassStage(pass.bookingId, generation);
    if (stage === "terminal") return;
    const payMethod = pass.payMethod && ["cash", "sbp", "negotiate"].includes(pass.payMethod) ? pass.payMethod : undefined;
    const snapshot = { ...pass, payMethod, boardingCode: stage === "onboard" ? "" : pass.boardingCode,
      ownerId, sessionGeneration: generation, ownershipId, savedAt: Date.now() };
    if (!current() || tripPassStage(pass.bookingId, generation) === "terminal") return;
    storageOperation(() => localStorage.setItem(passKey(pass.bookingId), JSON.stringify(snapshot)));
    if (!current() || tripPassStage(pass.bookingId, generation) === "terminal") return;
    storageOperation(() => localStorage.setItem(ownerKey(pass.bookingId), JSON.stringify({ ownerId, generation, ownershipId })));
    // No rollback: another tab may already own the key. Partial writes fail the read guard.
    if (!current()) return;
  } catch {
    /* A previous complete snapshot may survive; an incomplete new pair is never readable. */
  }
}

/** Read-only offline exception; this does not authenticate a user or enable actions. */
export function loadOfflineTripPass(bookingId: number): TripPass | null {
  if (!offlineStorageTrusted()) return null;
  try {
    const token = getToken(), generation = getSessionGeneration();
    if (!positiveId(bookingId) || !token || !generation) return null;
    const raw = storageOperation(() => localStorage.getItem(passKey(bookingId)));
    const ownerRaw = storageOperation(() => localStorage.getItem(ownerKey(bookingId)));
    if (!raw || !ownerRaw) return null;
    const pass = JSON.parse(raw) as TripPass, owner = JSON.parse(ownerRaw);
    if (!pass || typeof pass !== "object" || !owner || typeof owner !== "object" ||
      pass.bookingId !== bookingId || !positiveId(pass.ownerId) || pass.ownerId !== owner.ownerId ||
      pass.sessionGeneration !== generation || owner.generation !== generation ||
      typeof pass.ownershipId !== "string" || !pass.ownershipId || pass.ownershipId !== owner.ownershipId ||
      !Number.isFinite(pass.savedAt) || pass.savedAt <= 0 || pass.savedAt > Date.now() ||
      !Number.isFinite(pass.price) || pass.price < 0 || !positiveId(pass.seats) ||
      ![pass.fromCity, pass.toCity, pass.departAt, pass.driverName, pass.driverCar, pass.driverPlate,
        pass.driverPhone, pass.boardingCode, pass.pickup].every(value => typeof value === "string")) return null;
    if (pass.payMethod !== undefined && !["cash", "sbp", "negotiate"].includes(pass.payMethod)) return null;
    if ([pass.pickupStop, pass.dropoffStop].some(value => value != null && typeof value !== "string") ||
      (pass.rideHasStops !== undefined && typeof pass.rideHasStops !== "boolean")) return null;
    if (!isAccountSession({ userId: pass.ownerId, generation }) || getToken() !== token || getSessionGeneration() !== generation ||
      storageOperation(() => localStorage.getItem(ownerKey(bookingId))) !== ownerRaw || storageOperation(() => localStorage.getItem(passKey(bookingId))) !== raw) return null;
    const stage = tripPassStage(bookingId, generation);
    if (stage === "terminal" || getToken() !== token || getSessionGeneration() !== generation) return null;
    return stage === "onboard" ? { ...pass, boardingCode: "" } : pass;
  } catch {
    return null;
  }
}

export type OfflineTripOption = Pick<TripPass, "bookingId" | "fromCity" | "toCity" | "departAt">;

/** The start screen gets only selection metadata from snapshots it can actually open. */
export function listOfflineTripOptions(): { generation: string; trips: OfflineTripOption[] } | null {
  if (!offlineStorageTrusted()) return null;
  try {
    const token = getToken(), generation = getSessionGeneration();
    if (!token || !generation) return null;
    const keys = storageOperation(() => Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i)));
    const trips: Array<OfflineTripOption & { savedAt: number }> = [];
    for (const key of keys) {
      const match = key?.match(/^yuldash\.tripPass\.([1-9]\d*)$/);
      if (!match) continue;
      const id = Number(match[1]);
      if (!positiveId(id) || hasOfflineInTransitCancel(id)) continue;
      const pass = loadOfflineTripPass(id);
      if (!pass || pass.sessionGeneration !== generation) continue;
      const { bookingId, fromCity, toCity, departAt, savedAt } = pass;
      trips.push({ bookingId, fromCity, toCity, departAt, savedAt });
    }
    if (getToken() !== token || getSessionGeneration() !== generation || !offlineStorageTrusted()) return null;
    trips.sort((a, b) => b.savedAt - a.savedAt || b.bookingId - a.bookingId);
    return { generation, trips: trips.map(({ bookingId, fromCity, toCity, departAt }) => ({ bookingId, fromCity, toCity, departAt })) };
  } catch {
    return null;
  }
}
