import { ApiError, apiCommand } from "./client";
import type { BookingStatus } from "./bookings";
import type { TripStatus } from "./family";
import { BOOKING_RETRY_WINDOW } from "./bookingCommands";
import {
  isCurrentBookingOwner, passengerTripStatuses, PASSENGER_TRIP_STATUS_SCOPE as scope,
  type BookingCommandOwner, type PassengerTripStatusCommand,
} from "../utils/bookingCommandStore";
import { commandFingerprint } from "../utils/commandFingerprint";
import { observeTripPass } from "../utils/offlineTripPass";

type Receipt = {
  state: string;
  scope: string | null;
  ref_kind: string | null;
  ref_id: number | null;
  result_code: string | null;
  current: unknown;
};

export type PassengerStatusOutcome =
  | { state: "applied"; bookingStatus: BookingStatus }
  | { state: "gone" | "unseen" | "closed_no_effect" }
  | { state: "rejected"; refusal?: ApiError };

export class PassengerStatusProblem extends Error {
  constructor(readonly reason: "storage" | "stale" | "unknown" | "expired") { super(reason); }
}

function active(owner: BookingCommandOwner, signal: AbortSignal) {
  signal.throwIfAborted();
  if (!isCurrentBookingOwner(owner)) throw new PassengerStatusProblem("stale");
}

async function currentCommand(owner: BookingCommandOwner, bookingId: number, signal: AbortSignal) {
  active(owner, signal);
  const rows = await passengerTripStatuses.read(owner).catch(() => { throw new PassengerStatusProblem("storage"); });
  active(owner, signal);
  return rows.find(row => row.bookingId === bookingId);
}

async function settle(owner: BookingCommandOwner, command: PassengerTripStatusCommand,
                      state: "applied" | "rejected" | "closed_no_effect", signal: AbortSignal) {
  active(owner, signal);
  if (!await passengerTripStatuses.resolve(owner, command, state)) throw new PassengerStatusProblem("stale");
  active(owner, signal);
}

async function acceptBooking(owner: BookingCommandOwner, command: PassengerTripStatusCommand,
                             response: unknown, signal: AbortSignal): Promise<PassengerStatusOutcome> {
  active(owner, signal);
  if (!response || typeof response !== "object") throw new PassengerStatusProblem("unknown");
  const booking = response as { id?: number; passenger_id?: number; status?: BookingStatus };
  if (booking.id !== command.bookingId || booking.passenger_id !== owner.userId ||
      !booking.status || !["pending", "confirmed", "onboard", "done", "cancelled"].includes(booking.status)) {
    throw new PassengerStatusProblem("unknown");
  }
  if (booking.status === "done" || booking.status === "cancelled") {
    await observeTripPass(owner, command.bookingId, { status: booking.status }, undefined, undefined, signal);
  }
  await settle(owner, command, "applied", signal);
  return { state: "applied", bookingStatus: booking.status };
}

export async function checkPassengerTripStatus(
  owner: BookingCommandOwner, command: PassengerTripStatusCommand, close: boolean, signal: AbortSignal,
): Promise<PassengerStatusOutcome> {
  const saved = await currentCommand(owner, command.bookingId, signal);
  if (!saved || saved.key !== command.key || (close && saved.state !== "unknown")) throw new PassengerStatusProblem("stale");
  const path = "/command-intents/" + encodeURIComponent(saved.key);
  const result = await apiCommand<Receipt>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal,
    body: JSON.stringify({ scope, target_id: saved.bookingId, payload_hash: saved.hash }),
  } : { method: "GET", signal });
  active(owner, signal);
  if (!close && result.state === "unseen" && result.scope === null && result.ref_id === null &&
      result.ref_kind === null && result.current === null && result.result_code === null) return { state: "unseen" };
  if (result.scope !== scope) throw new PassengerStatusProblem("unknown");
  if (result.state === "applied" && result.ref_kind === "booking" && result.ref_id === saved.bookingId) {
    if (result.current !== null && result.result_code === null) return acceptBooking(owner, saved, result.current, signal);
    if (result.current === null && result.result_code === "idempotency_result_gone") {
      await settle(owner, saved, "applied", signal);
      return { state: "gone" };
    }
  }
  if ((result.state === "rejected" || result.state === "closed_no_effect") && result.ref_id === null &&
      result.ref_kind === null && result.current === null) {
    await settle(owner, saved, result.state, signal);
    return { state: result.state };
  }
  throw new PassengerStatusProblem("unknown");
}

async function send(owner: BookingCommandOwner, command: PassengerTripStatusCommand, signal: AbortSignal) {
  const fingerprint = await commandFingerprint(scope, command.bookingId, { status: command.stage });
  if (fingerprint.payloadHash !== command.hash) throw new PassengerStatusProblem("storage");
  active(owner, signal);
  try {
    const booking = await apiCommand<unknown>("/bookings/" + command.bookingId + "/commands/v1/trip-status", owner, {
      method: "POST", key: command.key, body: fingerprint.wireBody, signal,
    });
    return await acceptBooking(owner, command, booking, signal);
  } catch (error) {
    active(owner, signal);
    if (error instanceof ApiError && [400, 403, 409, 410, 422].includes(error.status)) {
      const result = await checkPassengerTripStatus(owner, command, false, signal).catch(() => undefined);
      active(owner, signal);
      if (result?.state === "rejected") return { ...result, refusal: error };
      if (result && result.state !== "unseen") return result;
    }
    throw new PassengerStatusProblem("unknown");
  }
}

export async function repeatPassengerTripStatus(
  owner: BookingCommandOwner, command: PassengerTripStatusCommand, signal: AbortSignal,
) {
  const saved = await currentCommand(owner, command.bookingId, signal);
  if (!saved || saved.key !== command.key || saved.state !== "unknown") throw new PassengerStatusProblem("stale");
  const age = Date.now() - saved.createdAt;
  if (age < 0 || age >= BOOKING_RETRY_WINDOW) throw new PassengerStatusProblem("expired");
  return send(owner, saved, signal);
}

export async function submitPassengerTripStatus(
  owner: BookingCommandOwner, bookingId: number, stage: TripStatus, signal: AbortSignal,
) {
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0) throw new PassengerStatusProblem("storage");
  const prior = await currentCommand(owner, bookingId, signal);
  if (prior?.state === "unknown" || (prior?.state === "applied" && prior.stage === stage)) {
    return checkPassengerTripStatus(owner, prior, false, signal);
  }
  const request = await commandFingerprint(scope, bookingId, { status: stage });
  active(owner, signal);
  const command = await passengerTripStatuses.reserve(owner, bookingId, stage, request.payloadHash, prior?.key)
    .catch(() => { throw new PassengerStatusProblem("storage"); });
  if (!command) throw new PassengerStatusProblem("unknown");
  return send(owner, command, signal);
}
