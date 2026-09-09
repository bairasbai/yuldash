const RELEASED_DRIVER_STATUSES = new Set(["cancelled", "expired"]);

/** Заказ уже не принадлежит водителю и не должен держать экран поездки. */
export function isReleasedDriverOrderStatus(status) {
  return RELEASED_DRIVER_STATUSES.has(status);
}

/** Ответ поллинга можно применить только к тому заказу, который всё ещё открыт. */
export function isCurrentDriverOrderPoll(currentOrderId, polledOrderId) {
  return currentOrderId != null && currentOrderId === polledOrderId;
}

/** Восстановление из localStorage допустимо, пока пользователь не открыл другой заказ. */
export function canRestoreDriverOrder(currentOrderId, savedOrderId) {
  return currentOrderId == null || currentOrderId === savedOrderId;
}

/** Не даём позднему ответу старого заказа стереть id уже принятого нового заказа. */
export function clearStoredDriverOrder(storage, key, orderId) {
  if (Number(storage.getItem(key) || 0) !== orderId) return false;
  storage.removeItem(key);
  return true;
}
