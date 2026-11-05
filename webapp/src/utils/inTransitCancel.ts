import { getSessionGeneration } from "../api/client";

// Only a non-sensitive fact survives a reload. Vehicle/contact data require live authority.
const KEY = (bookingId: number) => "yuldash.inTransit." + bookingId;
export const IN_TRANSIT_MARKER_TTL_MS = 6 * 60 * 60 * 1000;
interface Marker { owner: number; generation: string; at: number }
export interface InTransitSnapshot { car: string; plate: string }
const positiveId = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v > 0;

function marker(bookingId: number): Marker | null {
  if (!positiveId(bookingId)) return null;
  try {
    const raw = sessionStorage.getItem(KEY(bookingId));
    if (!raw) return null;
    const v = JSON.parse(raw) as Marker | null;
    const generation = getSessionGeneration();
    if (!v || !positiveId(v.owner) || !generation || v.generation !== generation ||
        typeof v.at !== "number" || !Number.isFinite(v.at) || v.at <= 0 ||
        v.at > Date.now() || Date.now() - v.at > IN_TRANSIT_MARKER_TTL_MS) return null;
    // Legacy private fields never become an offline vehicle authority.
    if (Object.keys(v).some(key => !["owner", "generation", "at"].includes(key))) return null;
    return getSessionGeneration() === generation ? v : null;
  } catch { return null; }
}
export function hasOfflineInTransitCancel(bookingId: number): boolean { return marker(bookingId) !== null; }

/** Verified live user only; cached vehicle details are deliberately absent. */
export function loadInTransitCancel(bookingId: number, owner: number | undefined): InTransitSnapshot | null {
  const v = marker(bookingId);
  return v && positiveId(owner) && v.owner === owner ? { car: "", plate: "" } : null;
}
export function rememberInTransitCancel(bookingId: number, owner: number | undefined, expectedGeneration: string): void {
  try {
    const generation = expectedGeneration;
    if (!positiveId(bookingId) || !positiveId(owner) || !generation || generation !== getSessionGeneration()) return;
    sessionStorage.setItem(KEY(bookingId), JSON.stringify({ owner, generation, at: Date.now() }));
  } catch { /* This tab may have no storage; the live screen still works. */ }
}
