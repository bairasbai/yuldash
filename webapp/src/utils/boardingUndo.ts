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
  const before = normalized(left);
  return before !== null && before === normalized(right);
}

export function isConfirmedBoardingUndo(current: BoardingUndoTerms | null | undefined, incoming: BoardingUndoTerms): boolean {
  return !!incoming.boarding_undo_confirmed_id && sameBoardingTimestamp(current?.onboard_at, incoming.boarding_undo_onboard_at);
}

/** Старый ответ о посадке не открывает уже исправленную посадку повторно. */
export function isStaleBoardingAfterUndo(
  current: (BoardingUndoTerms & { status?: string }) | null | undefined,
  incoming: BoardingUndoTerms & { status?: string },
): boolean {
  if (current?.status !== "confirmed" || !current.boarding_undo_confirmed_id || incoming.status !== "onboard") return false;
  const consumed = normalized(current.boarding_undo_onboard_at), source = normalized(incoming.onboard_at);
  return consumed === null || source === null || source <= consumed;
}
