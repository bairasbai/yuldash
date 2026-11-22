import type { InstantOrder } from "../api/instant";
import type { TripPassOwner } from "./tripPass";

/** A navigation hint, never an order snapshot or permission to restore history. */
export function expiredScheduledContext(order: InstantOrder, requestedId: number, owner: TripPassOwner) {
  if (!owner.generation || !Number.isSafeInteger(owner.userId) || owner.userId <= 0
      || !Number.isSafeInteger(requestedId) || requestedId <= 0 || order.id !== requestedId
      || order.role !== "passenger" || order.status !== "expired") return null;
  return { scheduledTaxi: { id: requestedId, userId: owner.userId, generation: owner.generation } };
}

export function requestedScheduledTaxiId(state: unknown, owner: TripPassOwner): number | null {
  if (!state || typeof state !== "object" || !("scheduledTaxi" in state)) return null;
  const hint = state.scheduledTaxi;
  if (!hint || typeof hint !== "object" || !("id" in hint) || !("userId" in hint) || !("generation" in hint)) return null;
  return typeof hint.id === "number" && Number.isSafeInteger(hint.id) && hint.id > 0
    && owner.userId > 0 && !!owner.generation && hint.userId === owner.userId
    && hint.generation === owner.generation ? hint.id : null;
}

export function requestedTaxiOrderMatches(order: InstantOrder, id: number): boolean {
  return order?.id === id && order.role === "passenger"
    && ["created", "searching", "offered", "accepted", "arriving", "onboard", "expired", "cancelled", "done"].includes(order.status);
}
