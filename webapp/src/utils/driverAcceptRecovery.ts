import type { InstantOrder } from "../api/instant";

type AcceptOrderView = Pick<InstantOrder, "id" | "role" | "driver_id" | "status">;

export function classifyDriverAcceptRead(orderId: number, driverId: number, data?: AcceptOrderView, failure?: { status: number; message: string }) {
  // A transport/gateway 403 is not proof that the offer was lost.
  if (failure?.status === 403 && ["Нет доступа к заказу", "Заказға рөхсәт юҡ"].includes(failure.message)) return "closed";
  if (failure?.status === 404 && ["Заказ не найден", "Заказ табылманы"].includes(failure.message)) return "closed";
  if (!data || data.id !== orderId) return "unknown";
  if (data.role === "driver" && data.driver_id === driverId &&
    ["accepted", "arriving", "onboard"].includes(data.status)) return "active";
  if (["expired", "cancelled", "done"].includes(data.status)) return "closed";
  return "unknown";
}
