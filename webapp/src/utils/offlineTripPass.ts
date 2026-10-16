import { storageOperation, offlineStorageTrusted } from "./storageHealth";
import { getSessionGeneration, getToken } from "../api/client";
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
    const snapshot = { ...pass, boardingCode: stage === "onboard" ? "" : pass.boardingCode,
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
    if (getToken() !== token || getSessionGeneration() !== generation ||
      storageOperation(() => localStorage.getItem(ownerKey(bookingId))) !== ownerRaw || storageOperation(() => localStorage.getItem(passKey(bookingId))) !== raw) return null;
    const stage = tripPassStage(bookingId, generation);
    if (stage === "terminal") return null;
    return stage === "onboard" ? { ...pass, boardingCode: "" } : pass;
  } catch {
    return null;
  }
}
