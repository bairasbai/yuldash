import { ApiError, apiCommand } from "./client";
import type { BookingRow, CreateBookingInput } from "./bookings";
import { commandFingerprint } from "../utils/commandFingerprint";
import { readBookingCommand, reserveBookingCommand, resolveBookingCommand, type BookingCommand, type BookingCommandOwner, type PublicBookingSelection } from "../utils/bookingCommandStore";

export { bookingCommandOwner, readBookingCommand } from "../utils/bookingCommandStore";
export type { BookingCommand } from "../utils/bookingCommandStore";
export const BOOKING_COMMAND_SCOPE = "booking.create";
export const BOOKING_RETRY_WINDOW = 24 * 60 * 60 * 1000;

const publicBookingFields = new Set([
  "ride_id", "pickup_stop", "dropoff_stop", "seats", "expected_price", "expected_terms_version",
  "pay_method", "pay_amount", "front_seat",
]);

function optionalUnsignedInteger(value: number | undefined): boolean {
  return value === undefined || (Number.isSafeInteger(value) && value >= 0);
}

function publicBookingSelection(body: CreateBookingInput): PublicBookingSelection | undefined {
  if (Object.entries(body).some(([field, value]) => value !== undefined && !publicBookingFields.has(field))) return;
  const { pickup_stop: pickup, dropoff_stop: dropoff, seats, expected_price: price,
    pay_method: payMethod, pay_amount: payAmount, front_seat: frontSeat } = body;
  const place = (value: unknown) => typeof value === "string" && value.length <= 120 && /\p{L}/u.test(value) &&
    /^[\p{L} .()\-]+$/u.test(value);
  if (!pickup || !dropoff || pickup === dropoff || !place(pickup) || !place(dropoff) ||
      seats === undefined || !Number.isSafeInteger(seats) || seats < 1 || seats > 8 ||
      !optionalUnsignedInteger(price) || !optionalUnsignedInteger(payAmount) ||
      !optionalUnsignedInteger(body.expected_terms_version) ||
      (payMethod !== undefined && !["cash", "sbp", "negotiate"].includes(payMethod)) ||
      (frontSeat !== undefined && typeof frontSeat !== "boolean")) return;
  return { version: 1, pickup, dropoff, seats, price, payMethod, payAmount, frontSeat };
}

const publicSelectionFields = new Set(["version", "pickup", "dropoff", "seats", "price", "payMethod", "payAmount", "frontSeat"]);

export async function restorePublicBookingInput(command: BookingCommand): Promise<CreateBookingInput | undefined> {
  const selection = command.publicSelection;
  const age = Date.now() - command.createdAt;
  if (selection?.version !== 1 || command.forAnother || command.state !== "unknown" ||
      !Number.isSafeInteger(command.rideId) || command.rideId < 1 ||
      Object.keys(selection).some(field => !publicSelectionFields.has(field)) ||
      !Number.isSafeInteger(command.createdAt) || age < 0 || age >= BOOKING_RETRY_WINDOW) return;
  const body: CreateBookingInput = {
    ride_id: command.rideId, seats: selection.seats, pay_method: selection.payMethod, pay_amount: selection.payAmount,
    front_seat: selection.frontSeat, pickup_stop: selection.pickup, dropoff_stop: selection.dropoff,
    expected_price: selection.price, expected_terms_version: command.expectedTermsVersion ?? undefined,
  };
  if (!publicBookingSelection(body)) return;
  const { payloadHash } = await commandFingerprint(BOOKING_COMMAND_SCOPE, command.rideId, body);
  return payloadHash === command.hash ? body : undefined;
}

type Outcome =
  | { state: "unseen" | "closed_no_effect"; booking: null }
  | { state: "rejected"; booking: null; code: string | null; conflictBookingId?: number }
  | { state: "applied"; booking: BookingRow | null };

export class BookingCommandProblem extends Error {
  constructor(
    readonly kind: "storage" | "full" | "unknown" | "changed" | "expired" | "gone" | "closed" | "rejected" | "unsupported",
    readonly command?: BookingCommand,
    readonly original?: unknown,
    readonly code?: string | null,
    readonly conflictBookingId?: number,
  ) { super(kind); }
}

function checkedBooking(value: BookingRow | null, rideId: number, userId: number): BookingRow | null {
  if (value === null) return null;
  if (value.ride_id !== rideId || value.passenger_id !== userId || !Number.isSafeInteger(value.id) || value.id <= 0 ||
      !["pending", "confirmed", "onboard", "done", "cancelled"].includes(value.status)) {
    throw new Error("Unexpected booking result");
  }
  return value;
}

async function sendBookingCommand(
  owner: BookingCommandOwner, command: BookingCommand, body: string, signal: AbortSignal,
): Promise<BookingRow> {
  const path = command.forAnother ? "/bookings/for-another/commands/v1" : "/bookings/commands/v1";
  const response = await apiCommand<BookingRow>(path, owner, {
    method: "POST", key: command.key, body, signal,
  });
  const booking = checkedBooking(response, command.rideId, owner.userId);
  if (!booking) throw new Error("Missing booking result");
  if (!await resolveBookingCommand(owner, command, "applied")) throw new Error("Booking command superseded");
  return booking;
}

export async function createBooking(
  body: CreateBookingInput, signal: AbortSignal,
  owner: BookingCommandOwner | null, prior?: BookingCommand, retry = false,
  onPrepared?: (key: string, input: CreateBookingInput) => void,
  publishedRoute?: readonly string[],
): Promise<BookingRow> {
  if (!owner) throw new BookingCommandProblem("storage");
  const forAnother = body.traveler_name !== undefined;
  if (forAnother && !retry) {
    try {
      const capability = await apiCommand<{ traveler_name?: boolean }>("/bookings/capabilities", owner, {
        method: "GET", signal,
      });
      if (capability.traveler_name !== true) throw new BookingCommandProblem("unsupported");
    } catch (error) {
      if (error instanceof ApiError && [404, 405].includes(error.status)) {
        throw new BookingCommandProblem("unsupported");
      }
      throw error;
    }
  }
  let command: BookingCommand;
  let wireBody: string;
  let preparedInput: CreateBookingInput;
  try {
    const current = retry ? await readBookingCommand(owner, body.ride_id) : undefined;
    if (retry && (!current || current.key !== prior?.key || current.state !== "unknown")) {
      throw new BookingCommandProblem("unknown", current);
    }
    // The ride's public version can move when this very request reserves a seat.
    preparedInput = current?.expectedTermsVersion === undefined ? body
      : { ...body, expected_terms_version: current.expectedTermsVersion ?? undefined };
    const snapshot = await commandFingerprint(BOOKING_COMMAND_SCOPE, body.ride_id, preparedInput);
    if (snapshot.wireBody === undefined) throw new Error("Missing booking body");
    wireBody = snapshot.wireBody;
    if (current) {
      if (Boolean(current.forAnother) !== forAnother || current.hash !== snapshot.payloadHash) {
        throw new BookingCommandProblem("changed", current);
      }
      const age = Date.now() - current.createdAt;
      if (age < 0 || age >= BOOKING_RETRY_WINDOW) throw new BookingCommandProblem("expired", current);
      command = current;
    } else {
      const selection = publishedRoute && publicBookingSelection(preparedInput);
      const start = selection ? publishedRoute?.indexOf(selection.pickup) ?? -1 : -1;
      const publicSelection = selection && start >= 0 && (publishedRoute?.indexOf(selection.dropoff) ?? -1) > start
        ? selection : undefined;
      const reservation = await reserveBookingCommand(owner, body.ride_id, snapshot.payloadHash,
        prior && prior.state !== "unknown" ? prior.key : undefined, body.expected_terms_version ?? null, forAnother, publicSelection);
      if (reservation.status === "full") throw new BookingCommandProblem("full");
      if (reservation.status === "existing") throw new BookingCommandProblem("unknown", reservation.command);
      command = reservation.command;
    }
  } catch (error) {
    if (error instanceof BookingCommandProblem) throw error;
    throw new BookingCommandProblem("storage");
  }
  try {
    if (signal.aborted) throw new Error("Booking request interrupted");
    onPrepared?.(command.key, preparedInput);
    return await sendBookingCommand(owner, command, wireBody, signal);
  } catch (error) {
    if (command.forAnother && error instanceof ApiError && [404, 405].includes(error.status)) {
      throw new BookingCommandProblem("unsupported", command);
    }
    let result: Outcome | undefined;
    if (!signal.aborted && error instanceof ApiError && error.status >= 400 && error.status < 500 &&
        ![401, 404, 405, 408, 429].includes(error.status)) {
      try { result = await reconcileBookingCommand(owner, command, false, signal); } catch { /* Keep the key. */ }
    }
    if (result?.state === "applied") {
      if (result.booking) return result.booking;
      throw new BookingCommandProblem("gone", { ...command, state: "applied" });
    }
    if (result?.state === "rejected") {
      const original = error instanceof ApiError && error.code === result.code ? error : undefined;
      throw new BookingCommandProblem("rejected", { ...command, state: "rejected" }, original, result.code, result.conflictBookingId);
    }
    if (result?.state === "closed_no_effect") throw new BookingCommandProblem("closed", { ...command, state: "closed_no_effect" });
    throw new BookingCommandProblem("unknown", command);
  }
}

export async function reconcileBookingCommand(
  owner: BookingCommandOwner, command: BookingCommand, close: boolean, signal: AbortSignal,
): Promise<Outcome> {
  const path = `/command-intents/${encodeURIComponent(command.key)}`;
  const view = await apiCommand<{
    state: string; scope: string | null; ref_id: number | null; current: BookingRow | null; result_code: string | null;
    booking_id?: number;
  }>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal,
    body: JSON.stringify({ scope: BOOKING_COMMAND_SCOPE, target_id: command.rideId, payload_hash: command.hash }),
  } : { method: "GET", signal });
  if (view.state === "unseen" && !close && view.scope === null) {
    const current = await readBookingCommand(owner, command.rideId);
    if (current?.key !== command.key || current.state !== "unknown") throw new Error("Booking command superseded");
    return { state: "unseen", booking: null };
  }
  if (view.scope !== BOOKING_COMMAND_SCOPE) throw new Error("Unexpected booking command scope");
  if (view.state === "applied") {
    const booking = checkedBooking(view.current, command.rideId, owner.userId);
    if (booking && view.ref_id !== booking.id) throw new Error("Booking receipt does not match result");
    if (!await resolveBookingCommand(owner, command, "applied")) throw new Error("Booking command superseded");
    return { state: "applied", booking };
  }
  if (view.state === "rejected" || view.state === "closed_no_effect") {
    if (!await resolveBookingCommand(owner, command, view.state)) throw new Error("Booking command superseded");
    return view.state === "rejected"
      ? { state: "rejected", booking: null, code: view.result_code,
          conflictBookingId: view.result_code === "booking_traveler_conflict" &&
            typeof view.booking_id === "number" && Number.isSafeInteger(view.booking_id) && view.booking_id > 0
            ? view.booking_id : undefined }
      : { state: "closed_no_effect", booking: null };
  }
  throw new Error("Unexpected booking command state");
}
