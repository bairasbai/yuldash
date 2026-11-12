import { ApiError, apiCommand } from "./client";
import type { BookingRow } from "./bookings";
import { commandFingerprint } from "../utils/commandFingerprint";
import {
  readDriverDecisions, reserveDriverDecision, resolveDriverDecision,
  type BookingCommandOwner, type DriverDecision, type DriverDecisionAction,
} from "../utils/bookingCommandStore";

const RETRY_WINDOW = 24 * 60 * 60 * 1000;
const statuses = ["pending", "confirmed", "onboard", "done", "cancelled"];
const scopeOf = (action: DriverDecisionAction) => action === "confirm" ? "booking.confirm" : "booking.cancel";
const termsPattern = /^[a-f0-9]{64}$/;
type DecisionBookingRow = BookingRow & { declined?: boolean };
type BookingState = Pick<DecisionBookingRow, "id" | "status" | "declined">;

export type DriverDecisionOutcome =
  | { state: "applied"; booking: BookingState | null }
  | { state: "unseen" | "rejected" | "closed_no_effect"; original?: ApiError };

export class DriverDecisionProblem extends Error {
  constructor(readonly kind: "pending" | "expired" | "changed" | "stale" | "storage",
    readonly original?: ApiError) { super(kind); }
}

function bookingState(row: DecisionBookingRow, bookingId: number, owner: BookingCommandOwner): BookingState {
  if (row.id !== bookingId || !Number.isSafeInteger(row.passenger_id) || row.passenger_id <= 0 ||
      row.passenger_id === owner.userId || !Number.isSafeInteger(row.ride_id) || row.ride_id <= 0 ||
      !statuses.includes(row.status)) throw new Error("Unexpected driver booking result");
  return { id: row.id, status: row.status, declined: row.declined };
}

function requestBody(decision: Pick<DriverDecision, "action" | "termsKey">) {
  if (decision.action === "confirm") return undefined;
  if (!decision.termsKey || !termsPattern.test(decision.termsKey)) throw new DriverDecisionProblem("storage");
  return { reason: "", expected_terms_key: decision.termsKey };
}

export async function checkDriverDecision(
  owner: BookingCommandOwner, decision: DriverDecision, close: boolean, signal: AbortSignal,
): Promise<DriverDecisionOutcome> {
  const existing = (await readDriverDecisions(owner)).find(row => row.bookingId === decision.bookingId);
  if (existing?.key !== decision.key || (close && existing.state !== "unknown")) throw new DriverDecisionProblem("stale");
  const scope = scopeOf(decision.action);
  const path = "/command-intents/" + encodeURIComponent(decision.key);
  const view = await apiCommand<{
    state: string; scope: string | null; ref_kind: string | null; ref_id: number | null;
    result_code: string | null; current: DecisionBookingRow | null;
  }>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal,
    body: JSON.stringify({ scope, target_id: decision.bookingId, payload_hash: decision.hash }),
  } : { method: "GET", signal });

  let result: DriverDecisionOutcome;
  if (view.state === "unseen" && !close && view.scope === null && view.current === null && view.ref_id === null) {
    result = { state: "unseen" };
  } else {
    if (view.scope !== scope) throw new Error("Unexpected driver decision scope");
    if (view.state === "applied" && view.ref_kind === "booking" && view.ref_id === decision.bookingId) {
      if (view.current === null && view.result_code !== "idempotency_result_gone") throw new Error("Missing driver decision result");
      result = { state: "applied", booking: view.current === null ? null : bookingState(view.current, decision.bookingId, owner) };
    } else if ((view.state === "rejected" || view.state === "closed_no_effect") &&
        view.ref_id === null && view.ref_kind === null && view.current === null) {
      result = { state: view.state };
    } else throw new Error("Unexpected driver decision receipt");
  }
  if (!await resolveDriverDecision(owner, decision, result.state === "unseen" ? "unknown" : result.state)) {
    throw new DriverDecisionProblem("stale");
  }
  return result;
}

async function sendDecision(
  owner: BookingCommandOwner, decision: DriverDecision, signal: AbortSignal,
): Promise<DriverDecisionOutcome> {
  const snap = await commandFingerprint(scopeOf(decision.action), decision.bookingId, requestBody(decision));
  if (snap.payloadHash !== decision.hash) throw new DriverDecisionProblem("storage");
  const path = "/bookings/" + decision.bookingId + "/commands/v1/" + (decision.action === "confirm" ? "confirm" : "cancel");
  let booking: BookingState;
  try {
    booking = bookingState(await apiCommand<DecisionBookingRow>(path, owner, {
      method: "POST", signal, body: snap.wireBody, key: decision.key,
    }), decision.bookingId, owner);
  } catch (error) {
    if (signal.aborted) throw error;
    const refusal = error instanceof ApiError ? error : undefined;
    if (refusal && refusal.status >= 400 && refusal.status < 500 && ![401, 404, 405, 408, 429].includes(refusal.status)) {
      const checked = await checkDriverDecision(owner, decision, false, signal).catch(() => undefined);
      if (checked?.state === "applied") return checked;
      if (checked && checked.state !== "unseen") return { ...checked, original: refusal };
    }
    throw new DriverDecisionProblem("pending", refusal);
  }
  if (!await resolveDriverDecision(owner, decision, "applied")) throw new DriverDecisionProblem("stale");
  return { state: "applied", booking };
}

export async function repeatDriverDecision(
  owner: BookingCommandOwner, decision: DriverDecision, signal: AbortSignal,
): Promise<DriverDecisionOutcome> {
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === decision.bookingId);
  if (!saved || saved.key !== decision.key || saved.state !== "unknown") throw new DriverDecisionProblem("stale");
  const age = Date.now() - saved.createdAt;
  if (age < 0 || age >= RETRY_WINDOW) throw new DriverDecisionProblem("expired");
  return sendDecision(owner, saved, signal);
}

export async function decideDriverBooking(
  owner: BookingCommandOwner, bookingId: number, action: DriverDecisionAction, signal: AbortSignal,
): Promise<DriverDecisionOutcome> {
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0) throw new DriverDecisionProblem("storage");
  const saved = (await readDriverDecisions(owner)).find(row => row.bookingId === bookingId);
  if (saved?.state === "unknown" || saved?.state === "applied") return checkDriverDecision(owner, saved, false, signal);

  let termsKey: string | undefined;
  if (action === "decline") {
    const actor = await apiCommand<{ role: string }>(
      "/bookings/" + bookingId + "/role", owner, { method: "GET", signal },
    );
    if (actor.role !== "driver") throw new DriverDecisionProblem("changed");
    const preview = await apiCommand<{ booking_status: string; terms_key: string }>(
      "/bookings/" + bookingId + "/cancel-preview", owner, { method: "GET", signal },
    );
    if (preview.booking_status !== "pending") throw new DriverDecisionProblem("changed");
    termsKey = preview.terms_key;
  }
  const snap = await commandFingerprint(scopeOf(action), bookingId, requestBody({ action, termsKey }));
  const decision = await reserveDriverDecision(owner, bookingId, action, snap.payloadHash, saved?.key, termsKey);
  if (!decision) throw new DriverDecisionProblem("stale");
  return sendDecision(owner, decision, signal);
}
