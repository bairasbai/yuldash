import type { BoardingUndoTerms } from "../api/bookings";
import { isConfirmedBoardingUndo, isStaleBoardingAfterUndo, sameBoardingTimestamp } from "./boardingUndo";

export interface TripPassOwner {
  userId: number;
  generation: string;
}

/** Данные для встречи с машиной и пути. Хранятся только у текущего владельца брони. */
export interface TripPass {
  ownerId: number;
  sessionGeneration: string;
  bookingId: number;
  rideId: number;
  fromCity: string;
  toCity: string;
  departAt: string;
  driverName: string;
  driverCar: string;
  driverPlate: string;
  driverPhone: string;
  boardingCode: string;
  pickup: string;
  pickupStop?: string | null;
  dropoffStop?: string | null;
  rideHasStops?: boolean;
  price: number;
  seats: number;
  payMethod?: "cash" | "sbp" | "negotiate";
  savedAt: number;
}

export type TripPassSnapshot = Omit<TripPass, "ownerId" | "sessionGeneration" | "boardingCode" | "savedAt">;
export type TripPassState = BoardingUndoTerms & {
  status: "pending" | "confirmed" | "onboard" | "done" | "cancelled";
  cancelled_in_transit?: boolean;
};
export type TripPassRecord =
  | { status: "terminal"; cancelledInTransitAt?: number }
  | {
      status: "confirmed" | "onboard";
      rideId?: number;
      onboardAt?: string | null;
      undoAt?: string | null;
      undoId?: string | null;
      revision: string | null;
      cancellationKey?: string;
      pass?: TripPass;
    };

export const newTripPassRecord = (): TripPassRecord & { status: "confirmed" } => ({
  status: "confirmed",
  revision: null,
});

export const positiveTripId = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value > 0;

export function validTripPass(pass: TripPass, owner: TripPassOwner, bookingId: number): boolean {
  return pass?.bookingId === bookingId && pass.ownerId === owner.userId && pass.sessionGeneration === owner.generation &&
    Number.isFinite(pass.savedAt) && pass.savedAt > 0 && pass.savedAt <= Date.now() &&
    Number.isFinite(pass.price) && pass.price >= 0 && positiveTripId(pass.seats) && positiveTripId(pass.rideId) &&
    [pass.fromCity, pass.toCity, pass.departAt, pass.driverName, pass.driverCar, pass.driverPlate,
      pass.driverPhone, pass.boardingCode, pass.pickup].every(value => typeof value === "string") &&
    (pass.payMethod === undefined || ["cash", "sbp", "negotiate"].includes(pass.payMethod)) &&
    [pass.pickupStop, pass.dropoffStop].every(value => value == null || typeof value === "string") &&
    (pass.rideHasStops === undefined || typeof pass.rideHasStops === "boolean");
}

/** Terminal history is permanent and has no snapshot. */
export function closeTripPass(previous: TripPassRecord | undefined, cancelledInTransit = false): TripPassRecord {
  if (previous?.status === "terminal" && (previous.cancelledInTransitAt !== undefined || !cancelledInTransit)) return previous;
  return { status: "terminal", ...(cancelledInTransit && { cancelledInTransitAt: Date.now() }) };
}

/** Change the stage and strip a used code in the same write. */
export function acceptTripPassState(
  previous: TripPassRecord | undefined,
  incoming: TripPassState,
  confirmedRevision: string | null | undefined,
): TripPassRecord | undefined {
  if (incoming.status === "done" || incoming.status === "cancelled") {
    return closeTripPass(previous, incoming.status === "cancelled" && incoming.cancelled_in_transit === true);
  }
  if (previous?.status === "terminal" || incoming.status === "pending") return previous;
  const current = previous ?? newTripPassRecord();
  const observed = { status: current.status, onboard_at: current.onboardAt,
    boarding_undo_onboard_at: current.undoAt, boarding_undo_confirmed_id: current.undoId };
  const unstampedBoarding = incoming.status === "onboard" && incoming.onboard_at == null &&
    current.status === "confirmed" && confirmedRevision !== undefined && confirmedRevision === current.revision;
  if (!unstampedBoarding && isStaleBoardingAfterUndo(observed, incoming)) return previous;
  if (incoming.status === "onboard") {
    const onboardAt = sameBoardingTimestamp(incoming.onboard_at, incoming.onboard_at) ? incoming.onboard_at : null;
    if (current.status === "onboard" && (current.onboardAt === onboardAt ||
        sameBoardingTimestamp(current.onboardAt, onboardAt))) return current;
    return { status: "onboard", onboardAt, rideId: current.rideId, revision: crypto.randomUUID(),
      cancellationKey: current.cancellationKey,
      pass: current.pass ? { ...current.pass, boardingCode: "" } : undefined };
  }
  if (confirmedRevision === undefined || confirmedRevision !== current.revision) return previous;
  if (current.status === "onboard") {
    if (!isConfirmedBoardingUndo(observed, incoming)) return current;
    return { status: "confirmed", rideId: current.rideId, undoAt: incoming.boarding_undo_onboard_at,
      undoId: incoming.boarding_undo_confirmed_id, revision: crypto.randomUUID(),
      cancellationKey: current.cancellationKey,
      pass: current.pass ? { ...current.pass, boardingCode: "" } : undefined };
  }
  if (!current.undoId && incoming.boarding_undo_confirmed_id &&
      sameBoardingTimestamp(incoming.boarding_undo_onboard_at, incoming.boarding_undo_onboard_at)) {
    return { ...current, undoAt: incoming.boarding_undo_onboard_at, undoId: incoming.boarding_undo_confirmed_id,
      revision: crypto.randomUUID(), pass: current.pass ? { ...current.pass, boardingCode: "" } : undefined };
  }
  return current;
}
