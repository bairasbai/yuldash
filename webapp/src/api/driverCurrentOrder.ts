import { ApiError, apiCommand, isAccountSession } from "./client";
import type { InstantOrder } from "./instant";

type Owner = Parameters<typeof isAccountSession>[0];
export type DriverCurrentOrder =
  | { state: "active"; order: InstantOrder }
  | { state: "none" | "conflict" | "unavailable" | "session-changed" };

/** Read-only. A null assignment is not proof that an in-flight Accept failed. */
export async function readDriverCurrentOrder(owner: Owner, signal: AbortSignal): Promise<DriverCurrentOrder> {
  const valid = () => !signal.aborted && isAccountSession(owner);
  const stopped = { state: "session-changed" } as const;
  if (!valid()) return stopped;
  const options = { method: "GET", oneShot: true, signal } as const;
  let payload: { order_id?: unknown };
  try {
    payload = await apiCommand("/instant/driver/current-order", owner, options);
  } catch (e) {
    if (!valid()) return stopped;
    if (e instanceof ApiError && e.status === 409 && e.code === "taxi_multiple_active_orders") {
      return { state: "conflict" };
    }
    return { state: "unavailable" };
  }
  if (!valid()) return stopped;
  const id = payload?.order_id;
  if (id === null) return { state: "none" };
  if (typeof id !== "number" || !Number.isInteger(id) || id <= 0 || id > 2_147_483_647) {
    return { state: "unavailable" };
  }
  try {
    const order = await apiCommand<InstantOrder>(`/instant/orders/${id}`, owner, options);
    if (!valid()) return stopped;
    if (order?.id === id && order.role === "driver" && order.driver_id === owner.userId &&
        ["accepted", "arriving", "onboard"].includes(order.status)) {
      return { state: "active", order };
    }
  } catch {
    if (!valid()) return stopped;
  }
  return { state: "unavailable" };
}
