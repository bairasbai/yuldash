// Read-only fixture oracles: user actions still go through the actual clients.
import { q, q1, sleep } from "./common.mjs";

export const fareMarker = price => new RegExp(String.raw`(?:^|\D)${price}\s*₽`);
export function ownRideForArchive({ rideId, driverId, price, route }) {
  if (![rideId, driverId, price].every(Number.isSafeInteger) || price <= 0) throw new Error("Exact archive ride/owner/positive unique price required");
  const ride = q1("SELECT id,driver_id,price,status,from_city,to_city FROM ride WHERE id=?", rideId);
  if (!ride || ride.driver_id !== driverId || ride.price !== price || `${ride.from_city} → ${ride.to_city}` !== route) throw new Error("Archive marker does not belong to this ride/owner");
  const same = q("SELECT id FROM ride WHERE driver_id=? AND price=?", driverId, price);
  if (same.length !== 1 || same[0].id !== rideId) throw new Error("Archive fare is ambiguous in this driver's history");
  if (ride.status === "active") {
    const active = q("SELECT id FROM ride WHERE driver_id=? AND status='active'", driverId);
    if (active.length !== 1 || active[0].id !== rideId) throw new Error("Refusing archive action: another active ride exists");
  } else if (ride.status !== "done") throw new Error("Current archive ride is neither active nor completed");
  return ride;
}
export async function waitRideDone(rideId) {
  const until = Date.now() + 20000;
  while (Date.now() < until) {
    if (q1("SELECT status FROM ride WHERE id=?", rideId)?.status === "done") return;
    await sleep(350);
  }
  throw new Error("UI did not complete the exact current ride");
}
export function ownBookingForHistory({ bookingId, passengerId, price, route, expectedStatus = "done" }) {
  if (![bookingId, passengerId, price].every(Number.isSafeInteger) || price <= 0) throw new Error("Exact history booking/owner/positive unique fare required");
  const b = q1("SELECT b.id,b.passenger_id,b.price,b.status,r.from_city,r.to_city FROM booking b JOIN ride r ON r.id=b.ride_id WHERE b.id=?", bookingId);
  if (!b || b.passenger_id !== passengerId || b.price !== price || b.status !== expectedStatus || `${b.from_city} → ${b.to_city}` !== route) throw new Error("History marker is not the expected own booking");
  const same = q("SELECT id FROM booking WHERE passenger_id=? AND price=?", passengerId, price);
  if (same.length !== 1 || same[0].id !== bookingId) throw new Error("History fare is ambiguous for this passenger");
  return b;
}
