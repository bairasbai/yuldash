import { ApiError, apiCommand } from "./client";
import { fetchTripState, type TripState } from "./bookings";
import { driverStep, emitDriverAction } from "../utils/driverActions";
import { commandFingerprint } from "../utils/commandFingerprint";
import { BOOKING_RETRY_WINDOW } from "./bookingCommands";
import {
  isCurrentBookingOwner, readDriverStageCommands, reserveDriverStageCommand, resolveDriverStageCommand,
  type BookingCommandOwner, type DriverStage, type DriverStageCommand,
} from "../utils/bookingCommandStore";

const scope = "booking.driver_status";

export class DriverStageProblem extends Error {
  constructor(readonly kind: "storage" | "expired" | "unknown" | "stale" | "refresh",
              readonly refusal?: ApiError) {
    super(kind);
  }
}

type Result =
  | { state: "applied"; trip: TripState | null }
  | { state: "rejected" | "closed_no_effect" | "unseen"; refusal?: ApiError };

type Receipt = {
  state: string;
  scope: string | null;
  ref_kind: string | null;
  ref_id: number | null;
  result_code: string | null;
  current: unknown;
};

function active(owner: BookingCommandOwner, signal: AbortSignal) {
  signal.throwIfAborted();
  if (!isCurrentBookingOwner(owner)) throw new DriverStageProblem("stale");
}

async function savedCommand(owner: BookingCommandOwner, bookingId: number) {
  try {
    return (await readDriverStageCommands(owner)).find(row => row.bookingId === bookingId);
  } catch {
    throw new DriverStageProblem(isCurrentBookingOwner(owner) ? "storage" : "stale");
  }
}

async function settle(owner: BookingCommandOwner, command: DriverStageCommand, state: "applied" | "rejected" | "closed_no_effect") {
  const saved = await resolveDriverStageCommand(owner, command, state).catch(() => false);
  if (!saved) throw new DriverStageProblem(isCurrentBookingOwner(owner) ? "storage" : "stale");
}

async function currentTrip(owner: BookingCommandOwner, bookingId: number, signal: AbortSignal): Promise<TripState | null> {
  active(owner, signal);
  try {
    const trip = await fetchTripState(bookingId, signal);
    active(owner, signal);
    if (trip.role !== "driver") throw new DriverStageProblem("stale");
    return trip;
  } catch (error) {
    active(owner, signal);
    if (error instanceof ApiError && error.status === 404) return null;
    if (error instanceof DriverStageProblem) throw error;
    throw new DriverStageProblem("refresh");
  }
}

export async function checkDriverStage(
  owner: BookingCommandOwner, command: DriverStageCommand, close: boolean, signal: AbortSignal,
): Promise<Result> {
  active(owner, signal);
  const saved = await savedCommand(owner, command.bookingId);
  if (!saved || saved.key !== command.key || (close && saved.state !== "unknown")) throw new DriverStageProblem("stale");
  const path = "/command-intents/" + encodeURIComponent(saved.key);
  const result = await apiCommand<Receipt>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal, body: JSON.stringify({ scope, target_id: saved.bookingId, payload_hash: saved.hash }),
  } : { method: "GET", signal });
  active(owner, signal);
  if (result.state === "unseen" && !close && result.scope === null && result.ref_id === null && result.current === null) {
    return { state: "unseen" };
  }
  if (result.scope !== scope) throw new DriverStageProblem("unknown");
  if (result.state === "applied" && result.ref_kind === "booking" && result.ref_id === saved.bookingId &&
      (result.current !== null || result.result_code === "idempotency_result_gone")) {
    const trip = await currentTrip(owner, saved.bookingId, signal);
    active(owner, signal);
    await settle(owner, saved, "applied");
    active(owner, signal);
    if (trip?.status === "done" || trip?.status === "cancelled") emitDriverAction(saved.bookingId, true);
    return { state: "applied", trip };
  }
  if ((result.state === "rejected" || result.state === "closed_no_effect") && result.ref_id === null &&
      result.ref_kind === null && result.current === null) {
    await settle(owner, saved, result.state);
    return { state: result.state };
  }
  throw new DriverStageProblem("unknown");
}

async function send(owner: BookingCommandOwner, command: DriverStageCommand, signal: AbortSignal): Promise<Result> {
  const request = await commandFingerprint(scope, command.bookingId, { status: command.stage });
  if (request.payloadHash !== command.hash) throw new DriverStageProblem("storage");
  active(owner, signal);
  try {
    const result = await apiCommand<{ ok?: boolean; id?: number }>(
      "/bookings/" + command.bookingId + "/commands/v1/driver-status", owner,
      { method: "POST", signal, key: command.key, body: request.wireBody },
    );
    if (result.ok !== true || result.id !== command.bookingId) throw new DriverStageProblem("unknown");
    active(owner, signal);
  } catch (error) {
    active(owner, signal);
    const refusal = error instanceof ApiError ? error : undefined;
    if (refusal && [400, 403, 409, 410, 422].includes(refusal.status)) {
      const known = await checkDriverStage(owner, command, false, signal).catch(() => undefined);
      active(owner, signal);
      if (known?.state === "applied") return known;
      if (known && known.state !== "unseen") return { ...known, refusal };
    }
    throw new DriverStageProblem("unknown", refusal);
  }
  const trip = await currentTrip(owner, command.bookingId, signal);
  active(owner, signal);
  await settle(owner, command, "applied");
  active(owner, signal);
  if (command.stage === "boarded" && trip?.status === "onboard") emitDriverAction(command.bookingId, false);
  return { state: "applied", trip };
}

export async function repeatDriverStage(owner: BookingCommandOwner, command: DriverStageCommand, signal: AbortSignal): Promise<Result> {
  active(owner, signal);
  const saved = await savedCommand(owner, command.bookingId);
  if (!saved || saved.key !== command.key || saved.state !== "unknown") throw new DriverStageProblem("stale");
  const age = Date.now() - saved.createdAt;
  if (age < 0 || age >= BOOKING_RETRY_WINDOW) throw new DriverStageProblem("expired");
  return sendStage(owner, saved, signal);
}

export async function advanceDriverStage(
  owner: BookingCommandOwner, bookingId: number, stage: DriverStage, signal: AbortSignal,
): Promise<Result> {
  active(owner, signal);
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0) throw new DriverStageProblem("storage");
  const prior = await savedCommand(owner, bookingId);
  if (prior?.state === "unknown") return checkDriverStage(owner, prior, false, signal);

  if (prior?.state === "applied" && prior.stage === stage) {
    const trip = await currentTrip(owner, bookingId, signal);
    if (!trip || trip.status === "done" || trip.status === "cancelled" ||
        (stage === "boarded" && trip.status === "onboard")) {
      return { state: "applied", trip };
    }
    if (stage === "done" || trip.status !== "confirmed") throw new DriverStageProblem("stale");
  }
  const request = await commandFingerprint(scope, bookingId, { status: stage });
  active(owner, signal);
  const command = await reserveDriverStageCommand(owner, bookingId, stage, request.payloadHash, prior?.key)
    .catch(() => { throw new DriverStageProblem("storage"); });
  if (!command) throw new DriverStageProblem("unknown");
  return sendStage(owner, command, signal);
}

async function sendStage(owner: BookingCommandOwner, command: DriverStageCommand, signal: AbortSignal): Promise<Result> {
  if (command.stage === "boarded") return send(owner, command, signal);
  let result: Result | undefined;
  try {
    result = await driverStep(command.bookingId, true, async () => {
      const outcome = await send(owner, command, signal);
      active(owner, signal);
      if (outcome.state !== "applied" || (outcome.trip?.status !== "done" && outcome.trip?.status !== "cancelled")) {
        result = outcome;
        throw new ApiError(409, "Driver stage rejected");
      }
      return outcome;
    });
  } catch (error) {
    if (!result) throw error;
  }
  return result;
}
