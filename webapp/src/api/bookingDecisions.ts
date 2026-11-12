import { ApiError, apiCommand } from "./client";
import { CANCEL_REASONS, type BookingRow, type CancelReason } from "./bookings";
import { commandFingerprint } from "../utils/commandFingerprint";
import { finishTripPass, holdTripPassCancellation, releaseTripPassCancellation } from "../utils/offlineTripPass";
import {
  isCurrentBookingOwner, readDriverDecisions, reserveDriverDecision, resolveDriverDecision,
  type BookingCommandOwner, type BookingDecision, type BookingDecisionAction,
} from "../utils/bookingCommandStore";

const RETRY_WINDOW = 24 * 60 * 60 * 1000;
const statuses = ["pending", "confirmed", "onboard", "done", "cancelled"];
const scopeOf = (action: BookingDecisionAction) => action === "confirm" ? "booking.confirm" : "booking.cancel";
const termsPattern = /^[a-f0-9]{64}$/;
type DecisionBookingRow = BookingRow & { declined?: boolean };
type BookingState = Pick<DecisionBookingRow, "id" | "status" | "declined" | "contact_then_cancel">;
export type PassengerCancellationStatus = "pending" | "confirmed";
export interface PassengerCancellationTerms {
  owner: BookingCommandOwner;
  bookingId: number;
  status: PassengerCancellationStatus;
  consequence: "early" | "late" | "driver_departed";
  termsKey: string;
}

export type BookingDecisionOutcome =
  | { state: "applied"; booking: BookingState | null }
  | { state: "unseen" | "rejected" | "closed_no_effect"; original?: ApiError; rejectionCode?: string };

export class BookingDecisionProblem extends Error {
  constructor(readonly kind: "pending" | "expired" | "changed" | "stale" | "storage",
    readonly original?: ApiError) { super(kind); }
}

function bookingState(row: DecisionBookingRow, decision: BookingDecision, owner: BookingCommandOwner): BookingState {
  const passenger = decision.action === "passenger_cancel";
  if (row.id !== decision.bookingId || !Number.isSafeInteger(row.passenger_id) || row.passenger_id <= 0 ||
      (row.passenger_id === owner.userId) !== passenger || !Number.isSafeInteger(row.ride_id) || row.ride_id <= 0 ||
      !statuses.includes(row.status)) throw new Error("Unexpected booking decision result");
  return { id: row.id, status: row.status, declined: row.declined,
    ...(passenger ? { contact_then_cancel: row.contact_then_cancel === true } : {}) };
}

function requestBody(decision: Pick<BookingDecision, "action" | "termsKey" | "reason">) {
  if (decision.action === "confirm") return undefined;
  if (!decision.termsKey || !termsPattern.test(decision.termsKey)) throw new BookingDecisionProblem("storage");
  const reason = decision.action === "passenger_cancel" ? decision.reason : "";
  if (reason !== "" && !CANCEL_REASONS.some(option => option.code === reason)) throw new BookingDecisionProblem("storage");
  return { reason, expected_terms_key: decision.termsKey };
}

async function settleDecision(owner: BookingCommandOwner, decision: BookingDecision, result: BookingDecisionOutcome) {
  if (!isCurrentBookingOwner(owner)) throw new BookingDecisionProblem("stale");
  try {
    if (result.state === "applied" && (result.booking?.status === "done" || result.booking?.status === "cancelled" ||
        (decision.action === "passenger_cancel" && result.booking === null))) {
      if (!await finishTripPass(owner, decision.bookingId)) throw new BookingDecisionProblem("storage");
    } else if (decision.action === "passenger_cancel" && (result.state === "rejected" || result.state === "closed_no_effect")) {
      if (!await releaseTripPassCancellation(owner, decision.bookingId, decision.key)) throw new BookingDecisionProblem("storage");
    }
  } catch (error) {
    if (!isCurrentBookingOwner(owner)) throw new BookingDecisionProblem("stale");
    if (error instanceof BookingDecisionProblem) throw error;
    throw new BookingDecisionProblem("storage");
  }
  if (!await resolveDriverDecision(owner, decision, result.state === "unseen" ? "unknown" : result.state)) {
    throw new BookingDecisionProblem("stale");
  }
  return result;
}

export async function checkDriverDecision(
  owner: BookingCommandOwner, decision: BookingDecision, close: boolean, signal: AbortSignal,
): Promise<BookingDecisionOutcome> {
  const existing = (await readDriverDecisions(owner)).find(row => row.bookingId === decision.bookingId);
  if (existing?.key !== decision.key || (close && existing.state !== "unknown")) throw new BookingDecisionProblem("stale");
  const scope = scopeOf(existing.action);
  const path = "/command-intents/" + encodeURIComponent(existing.key);
  const view = await apiCommand<{
    state: string; scope: string | null; ref_kind: string | null; ref_id: number | null;
    result_code: string | null; current: DecisionBookingRow | null;
  }>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal,
    body: JSON.stringify({ scope, target_id: existing.bookingId, payload_hash: existing.hash }),
  } : { method: "GET", signal });

  let result: BookingDecisionOutcome;
  if (view.state === "unseen" && !close && view.scope === null && view.current === null && view.ref_id === null) {
    result = { state: "unseen" };
  } else {
    if (view.scope !== scope) throw new Error("Unexpected driver decision scope");
    if (view.state === "applied" && view.ref_kind === "booking" && view.ref_id === decision.bookingId) {
      if (view.current === null && view.result_code !== "idempotency_result_gone") throw new Error("Missing driver decision result");
      result = { state: "applied", booking: view.current === null ? null : bookingState(view.current, existing, owner) };
    } else if ((view.state === "rejected" || view.state === "closed_no_effect") &&
        view.ref_id === null && view.ref_kind === null && view.current === null) {
      result = view.state === "rejected" && view.result_code
        ? { state: view.state, rejectionCode: view.result_code } : { state: view.state };
    } else throw new Error("Unexpected driver decision receipt");
  }
  return settleDecision(owner, existing, result);
}

async function sendDecision(
  owner: BookingCommandOwner, decision: BookingDecision, signal: AbortSignal,
): Promise<BookingDecisionOutcome> {
  const snap = await commandFingerprint(scopeOf(decision.action), decision.bookingId, requestBody(decision));
  if (snap.payloadHash !== decision.hash) throw new BookingDecisionProblem("storage");
  if (decision.action === "passenger_cancel") {
    const held = await holdTripPassCancellation(owner, decision.bookingId, decision.key).catch(() => false);
    if (!held) throw new BookingDecisionProblem(isCurrentBookingOwner(owner) ? "storage" : "stale");
    signal.throwIfAborted();
  }
  const path = "/bookings/" + decision.bookingId + "/commands/v1/" + (decision.action === "confirm" ? "confirm" : "cancel");
  let booking: BookingState;
  try {
    booking = bookingState(await apiCommand<DecisionBookingRow>(path, owner, {
      method: "POST", signal, body: snap.wireBody, key: decision.key,
    }), decision, owner);
  } catch (error) {
    if (signal.aborted) throw error;
    const refusal = error instanceof ApiError ? error : undefined;
    if (refusal && refusal.status >= 400 && refusal.status < 500 && ![401, 404, 405, 408, 429].includes(refusal.status)) {
      const checked = await checkDriverDecision(owner, decision, false, signal).catch(() => undefined);
      if (checked?.state === "applied") return checked;
      if (checked && checked.state !== "unseen") return { ...checked, original: refusal };
    }
    throw new BookingDecisionProblem("pending", refusal);
  }
  return settleDecision(owner, decision, { state: "applied", booking });
}

export async function repeatDriverDecision(
  owner: BookingCommandOwner, decision: BookingDecision, signal: AbortSignal,
): Promise<BookingDecisionOutcome> {
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === decision.bookingId);
  if (!saved || saved.key !== decision.key || saved.state !== "unknown") throw new BookingDecisionProblem("stale");
  const age = Date.now() - saved.createdAt;
  if (age < 0 || age >= RETRY_WINDOW) throw new BookingDecisionProblem("expired");
  return sendDecision(owner, saved, signal);
}

export async function decideDriverBooking(
  owner: BookingCommandOwner, bookingId: number, action: Exclude<BookingDecisionAction, "passenger_cancel">, signal: AbortSignal,
): Promise<BookingDecisionOutcome> {
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0) throw new BookingDecisionProblem("storage");
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === bookingId);
  if (saved?.action === "passenger_cancel") throw new BookingDecisionProblem("changed");
  if (saved?.state === "unknown" || saved?.state === "applied") return checkDriverDecision(owner, saved, false, signal);

  let termsKey: string | undefined;
  if (action === "decline") {
    const actor = await apiCommand<{ role: string }>(
      "/bookings/" + bookingId + "/role", owner, { method: "GET", signal },
    );
    if (actor.role !== "driver") throw new BookingDecisionProblem("changed");
    const preview = await apiCommand<{ booking_status: string; terms_key: string }>(
      "/bookings/" + bookingId + "/cancel-preview", owner, { method: "GET", signal },
    );
    if (preview.booking_status !== "pending") throw new BookingDecisionProblem("changed");
    termsKey = preview.terms_key;
  }
  const snap = await commandFingerprint(scopeOf(action), bookingId, requestBody({ action, termsKey }));
  const decision = await reserveDriverDecision(owner, bookingId, action, snap.payloadHash, saved?.key, termsKey);
  if (!decision) throw new BookingDecisionProblem("stale");
  return sendDecision(owner, decision, signal);
}

export async function preparePassengerCancellation(
  owner: BookingCommandOwner, bookingId: number, status: PassengerCancellationStatus, signal: AbortSignal,
): Promise<PassengerCancellationTerms> {
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0) throw new BookingDecisionProblem("storage");
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === bookingId);
  if (saved?.state === "unknown") throw new BookingDecisionProblem("pending");
  const path = "/bookings/" + bookingId;
  const actor = await apiCommand<{ role: string }>(path + "/role", owner, { method: "GET", signal });
  if (actor.role !== "passenger") throw new BookingDecisionProblem("changed");
  const preview = await apiCommand<{ booking_status: string; consequence: string; terms_key: string }>(
    path + "/cancel-preview", owner, { method: "GET", signal },
  );
  const { consequence } = preview;
  if (preview.booking_status !== status || (consequence !== "early" && consequence !== "late" && consequence !== "driver_departed")) {
    throw new BookingDecisionProblem("changed");
  }
  if (!termsPattern.test(preview.terms_key)) throw new BookingDecisionProblem("storage");
  return { owner, bookingId, status, consequence, termsKey: preview.terms_key };
}

export async function cancelPassengerBooking(
  owner: BookingCommandOwner, terms: PassengerCancellationTerms, reason: CancelReason, signal: AbortSignal,
): Promise<BookingDecisionOutcome> {
  if (terms.owner.namespace !== owner.namespace || terms.owner.generation !== owner.generation) {
    throw new BookingDecisionProblem("stale");
  }
  const { bookingId, termsKey } = terms;
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === bookingId);
  if (saved?.action === "passenger_cancel" && (saved.state === "unknown" || saved.state === "applied")) {
    return checkDriverDecision(owner, saved, false, signal);
  }
  if (saved?.state === "unknown") throw new BookingDecisionProblem("changed");
  const action = "passenger_cancel";
  const snapshot = await commandFingerprint(scopeOf(action), bookingId, requestBody({ action, termsKey, reason }));
  const decision = await reserveDriverDecision(owner, bookingId, action, snapshot.payloadHash, saved?.key, termsKey, reason);
  if (!decision) throw new BookingDecisionProblem("stale");
  return sendDecision(owner, decision, signal);
}
