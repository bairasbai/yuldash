import type { InstantOrder } from "../api/instant";

/** Only the system timeout from a retired route, never a passenger cancellation. */
export function isTaxiRouteResumeTimeout(order: Pick<InstantOrder, "status" | "cancel_by" | "cancel_reason">) {
  return order.status === "cancelled" && order.cancel_by === "system" &&
    order.cancel_reason === "route_resume_timeout";
}
