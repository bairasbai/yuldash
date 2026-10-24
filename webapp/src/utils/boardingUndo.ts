import type { BoardingUndoTerms } from "../api/bookings";

// Сохраняем микросекунды: округление до миллисекунд могло бы спутать две посадки.
function normalized(value: string | null | undefined): string | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)?$/.test(value)) return null;
  const utc = value.replace(/(?:Z|\+00:00)$/, ""), millis = Date.parse(utc + "Z");
  if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== utc.slice(0, 19)) return null;
  return utc.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
}

/** Квитанция ответа пассажира разрешает откат только исходной посадки. */
export function sameBoardingTimestamp(left: string | null | undefined, right: string | null | undefined): boolean {
  return compareBoardingTimestamps(left, right) === 0;
}

export function compareBoardingTimestamps(left: string | null | undefined, right: string | null | undefined): -1 | 0 | 1 | null {
  const before = normalized(left), after = normalized(right);
  return before === null || after === null ? null : before === after ? 0 : before < after ? -1 : 1;
}

export function isConfirmedBoardingUndo(current: BoardingUndoTerms | null | undefined, incoming: BoardingUndoTerms): boolean {
  return !!incoming.boarding_undo_confirmed_id && sameBoardingTimestamp(current?.onboard_at, incoming.boarding_undo_onboard_at);
}

/** Известную посадку нельзя заменить более старой или открыть повторно после исправления. */
export function isStaleBoardingAfterUndo(
  current: (BoardingUndoTerms & { status?: string }) | null | undefined,
  incoming: BoardingUndoTerms & { status?: string },
): boolean {
  if (current?.status === "confirmed" && current.boarding_undo_confirmed_id && incoming.status === "confirmed") {
    const order = compareBoardingTimestamps(incoming.boarding_undo_onboard_at, current.boarding_undo_onboard_at);
    return !incoming.boarding_undo_confirmed_id || order === null || order < 0;
  }
  if (incoming.status !== "onboard") return false;
  if (current?.status === "onboard" && normalized(current.onboard_at) !== null) {
    const order = compareBoardingTimestamps(incoming.onboard_at, current.onboard_at);
    return order === null || order < 0;
  }
  if (current?.status !== "confirmed" || !current.boarding_undo_confirmed_id) return false;
  const consumed = normalized(current.boarding_undo_onboard_at), source = normalized(incoming.onboard_at);
  return consumed === null || source === null || source <= consumed;
}
