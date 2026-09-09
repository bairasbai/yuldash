export function isReleasedDriverOrderStatus(status: string): boolean;
export function isCurrentDriverOrderPoll(
  currentOrderId: number | null,
  polledOrderId: number
): boolean;
export function canRestoreDriverOrder(
  currentOrderId: number | null,
  savedOrderId: number
): boolean;
export function clearStoredDriverOrder(
  storage: Pick<Storage, "getItem" | "removeItem">,
  key: string,
  orderId: number
): boolean;
