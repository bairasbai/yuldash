/** Договорённость второй стороны можно увидеть без частого тяжёлого GET. */
export const DETAILS_REFRESH_MS = 60_000;

/** Server updates remain visible without replacing an unfinished payment form. */
export function reconcilePaymentDraft(current, bookingId, agreement) {
  if (current.bookingId === bookingId && current.dirty) return current;
  return {
    bookingId,
    payMethod: agreement.pay_method,
    payAmount: agreement.pay_amount == null ? "" : String(agreement.pay_amount),
    dirty: false,
  };
}

/** A save response may only acknowledge the draft that was actually submitted. */
export function acceptPaymentDraft(current, submitted, agreement) {
  if (current.bookingId !== submitted.bookingId || current.payMethod !== submitted.payMethod || current.payAmount !== submitted.payAmount) return current;
  return reconcilePaymentDraft({ ...current, dirty: false }, current.bookingId, agreement);
}

export function isTerminalBookingStatus(status) {
  return status === "done" || status === "cancelled";
}

const STATUS_RANK = {
  pending: 0,
  confirmed: 1,
  onboard: 2,
  done: 3,
  cancelled: 3,
};

/** Запоздалые details не должны откатывать live-статус и возвращать удалённый паспорт. */
export function shouldApplyBookingDetails(liveStatus, detailsStatus) {
  if (!liveStatus || liveStatus === detailsStatus) return true;
  const liveRank = STATUS_RANK[liveStatus];
  const detailsRank = STATUS_RANK[detailsStatus];
  if (liveRank == null || detailsRank == null) return false;
  return detailsRank >= liveRank;
}

/**
 * Полные детали нужны сразу после перехода статуса и не чаще раза в минуту,
 * пока статус не меняется. Терминал обрабатывается отдельно без лишнего GET.
 */
export function shouldRefreshBookingDetails(previousStatus, nextStatus, elapsedMs) {
  if (isTerminalBookingStatus(nextStatus)) return false;
  if (previousStatus && previousStatus !== nextStatus) return true;
  return elapsedMs >= DETAILS_REFRESH_MS;
}
