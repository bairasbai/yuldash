const DISPUTABLE_STATUSES = new Set([
  "accepted",
  "in_transit",
  "returning",
  "delivered",
  "returned",
  "canceled",
]);

/** Спор возможен только между реальными сторонами уже назначенной доставки. */
export function canOpenParcelDispute(parcel, actorId) {
  if (!parcel || !Number.isInteger(actorId) || !parcel.courier_id) return false;
  const participant = actorId === parcel.sender_id || actorId === parcel.courier_id;
  return participant && DISPUTABLE_STATUSES.has(String(parcel.status));
}
