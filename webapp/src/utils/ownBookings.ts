import type { MyBooking } from "../api/bookings";

export type OwnSeatBooking = Pick<MyBooking, "id" | "ride_id" | "status" | "front_seat">;

export function isLiveOwnBooking(booking: OwnSeatBooking | null | undefined): booking is OwnSeatBooking {
  return !!booking && Number.isSafeInteger(booking.id) && booking.id > 0 &&
    Number.isSafeInteger(booking.ride_id) && booking.ride_id > 0 &&
    (booking.status === "pending" || booking.status === "confirmed" || booking.status === "onboard");
}

/** Ownership is supplied by the authenticated /bookings/mine response, never by front_seat_taken. */
export function hasOwnFrontSeat(rideId: number, booking?: OwnSeatBooking | null): boolean {
  return isLiveOwnBooking(booking) && booking.ride_id === rideId && booking.front_seat === true;
}
