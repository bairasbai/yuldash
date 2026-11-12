import type { MeetingPointInput } from "../api/bookings";
import { getSessionGeneration } from "../api/client";

// A tab-local, one-use handoff. No guardian identity, token or automatic booking.
const PREFIX = "yuldash.guestBooking.";
const PENDING = PREFIX + "pending";
const RESTORE = PREFIX + "restore";
export const GUEST_BOOKING_DRAFT_TTL_MS = 15 * 60 * 1000;

export interface GuestBookingOptions {
  frontSeat?: boolean;
  minor?: boolean;
}

export interface GuestBookingSelection extends GuestBookingOptions {
  seats: number;
  pickupStop: string | null;
  dropoffStop: string | null;
  meeting: MeetingPointInput | null;
  alighting: MeetingPointInput | null;
}
export interface GuestBookingDraft extends GuestBookingSelection {
  rideId: number;
  nonce: string;
  sourceGeneration: string;
  at: number;
}
type RestoredDraft = GuestBookingDraft & { owner: string };
const positiveId = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v > 0;

function point(value: unknown): MeetingPointInput | null {
  if (value == null) return null;
  if (typeof value !== "object" || Array.isArray(value)) throw Error("invalid point");
  const v = value as MeetingPointInput;
  if (v.clear === true) return null;
  const result: MeetingPointInput = {};
  if (v.lat != null || v.lng != null) {
    if (typeof v.lat !== "number" || typeof v.lng !== "number" || !Number.isFinite(v.lat) ||
        !Number.isFinite(v.lng) || Math.abs(v.lat) > 90 || Math.abs(v.lng) > 180) throw Error("invalid coordinates");
    result.lat = v.lat; result.lng = v.lng;
  }
  if (v.landmark_id != null) {
    if (!positiveId(v.landmark_id)) throw Error("invalid landmark");
    result.landmark_id = v.landmark_id;
  }
  if (v.note != null) {
    if (typeof v.note !== "string" || v.note.length > 120) throw Error("invalid note");
    if (v.note.trim()) result.note = v.note.trim();
  }
  return Object.keys(result).length ? result : null;
}
function stop(value: unknown): string | null {
  if (value == null) return null;
  if (typeof value !== "string" || value.length > 200 || !value.trim()) throw Error("invalid stop");
  return value.trim();
}
function validate(raw: unknown): GuestBookingDraft | null {
  try {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
    const v = raw as GuestBookingDraft;
    if (!positiveId(v.rideId) || !Number.isSafeInteger(v.seats) || v.seats < 1 || v.seats > 8 ||
        typeof v.nonce !== "string" || !/^[a-zA-Z0-9-]{16,80}$/.test(v.nonce) ||
        typeof v.sourceGeneration !== "string" || typeof v.at !== "number" ||
        !Number.isFinite(v.at) || v.at <= 0 || v.at > Date.now() || Date.now() - v.at > GUEST_BOOKING_DRAFT_TTL_MS ||
        (v.frontSeat !== undefined && typeof v.frontSeat !== "boolean") ||
        (v.minor !== undefined && typeof v.minor !== "boolean")) return null;
    return { rideId: v.rideId, nonce: v.nonce, sourceGeneration: v.sourceGeneration, at: v.at,
      seats: v.seats, pickupStop: stop(v.pickupStop), dropoffStop: stop(v.dropoffStop),
      meeting: point(v.meeting), alighting: point(v.alighting),
      ...(v.frontSeat && { frontSeat: true }), ...(v.minor && { minor: true }) };
  } catch { return null; }
}
function read(key: string): unknown {
  const raw = sessionStorage.getItem(key);
  return raw ? JSON.parse(raw) : null;
}
function removeMatching(key: string, nonce: string): void {
  try { if ((read(key) as { nonce?: unknown } | null)?.nonce === nonce) sessionStorage.removeItem(key); } catch { /* unavailable */ }
}
export function rememberGuestBookingDraft(rideId: number, selection: GuestBookingSelection, sourceGeneration: string): string | undefined {
  try {
    if (sourceGeneration !== getSessionGeneration()) return undefined;
    const draft = validate({ rideId, ...selection, nonce: crypto.randomUUID(), sourceGeneration, at: Date.now() });
    if (!draft) return undefined;
    sessionStorage.removeItem(RESTORE);
    sessionStorage.setItem(PENDING, JSON.stringify(draft));
    if (sourceGeneration !== getSessionGeneration()) { removeMatching(PENDING, draft.nonce); return undefined; }
    return draft.nonce;
  } catch { return undefined; }
}
/** Called only by the accepted login operation, before publishing its new session. */
export function claimGuestBookingDraft(nonce: unknown, sourceGeneration: string): GuestBookingDraft | null {
  if (typeof nonce !== "string") return null;
  try {
    const raw = read(PENDING) as { nonce?: unknown } | null;
    if (raw?.nonce !== nonce) return null;
    sessionStorage.removeItem(PENDING);
    const draft = validate(raw);
    return draft && draft.sourceGeneration === sourceGeneration && getSessionGeneration() === sourceGeneration ? draft : null;
  } catch { return null; }
}
/** Publish only after that login operation committed its exact new owner. */
export function publishGuestBookingDraft(draft: GuestBookingDraft, owner: string): string | undefined {
  try {
    const valid = validate(draft);
    if (!valid || !owner || owner === valid.sourceGeneration || getSessionGeneration() !== owner) return undefined;
    sessionStorage.setItem(RESTORE, JSON.stringify({ ...valid, owner }));
    if (getSessionGeneration() !== owner) { removeMatching(RESTORE, valid.nonce); return undefined; }
    return valid.nonce;
  } catch { return undefined; }
}
/** A fresh ride read consumes this handoff once; later visits/reloads cannot reuse it. */
export function consumeGuestBookingDraft(nonce: unknown, rideId: number, owner: string): GuestBookingSelection | null {
  if (typeof nonce !== "string") return null;
  try {
    const raw = read(RESTORE) as RestoredDraft | null;
    if (raw?.nonce !== nonce) return null;
    sessionStorage.removeItem(RESTORE);
    const draft = validate(raw);
    if (!draft || draft.rideId !== rideId || !owner || raw.owner !== owner || getSessionGeneration() !== owner) return null;
    return { seats: draft.seats, pickupStop: draft.pickupStop, dropoffStop: draft.dropoffStop,
      meeting: draft.meeting, alighting: draft.alighting,
      ...(draft.frontSeat && { frontSeat: true }), ...(draft.minor && { minor: true }) };
  } catch { return null; }
}
export function clearPendingGuestBookingDraft(nonce: unknown): void {
  if (typeof nonce === "string") removeMatching(PENDING, nonce);
}

export function clearRestoredGuestBookingDraft(nonce: unknown): void {
  if (typeof nonce === "string") removeMatching(RESTORE, nonce);
}
