import { apiCommand, isAccountSession } from "./client";
import type { InstantOrder } from "./instant";
import { classifyDriverAcceptRead } from "../utils/driverAcceptRecovery";

type Owner = Parameters<typeof isAccountSession>[0];
export type DriverCurrentOrder =
  | { state: "active"; order: InstantOrder }
  | { state: "none" | "unavailable" | "session-changed" };

/** Server snapshot, not evidence that an in-flight Accept failed. Never replays a mutation. */
export async function readDriverCurrentOrder(owner: Owner, signal: AbortSignal): Promise<DriverCurrentOrder> {
  const valid = () => !signal.aborted && isAccountSession(owner);
  const stopped = { state: "session-changed" } as const;
  if (!valid()) return stopped;
  try {
    const options = { method: "GET", oneShot: true, signal } as const;
    const payload = await apiCommand<{ order_id?: unknown }>("/instant/driver/current-order", owner, options);
    if (!valid()) return stopped;
    const id = payload?.order_id;
    if (id === null) return { state: "none" };
    // InstantOrder IDs are Int on Android; hold the same contract on web.
    if (typeof id !== "number" || !Number.isInteger(id) || id <= 0 || id > 2_147_483_647) {
      return { state: "unavailable" };
    }
    const order = await apiCommand<InstantOrder>(`/instant/orders/${id}`, owner, options);
    if (!valid()) return stopped;
    const checked = classifyDriverAcceptRead(id, owner.userId, order);
    if (checked === "active") return { state: "active", order };
    return { state: "unavailable" };
  } catch {
    return valid() ? { state: "unavailable" } : stopped;
  }
}
