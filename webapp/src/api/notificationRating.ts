import type { TripPassOwner } from "../utils/tripPass";
import type { BookingDetails } from "./bookings";
import { isCurrentRatingDraft, type RatingDraft } from "../push/rateDraft";
import { apiCommand } from "./client";

export async function rateBookingFromNotification(
  draft: RatingDraft, signal: AbortSignal,
): Promise<{ kept: boolean; stars: number } | undefined> {
  const result = await apiCommand<{ kept?: unknown; my_stars?: unknown } | null>(
    `/bookings/${draft.bookingId}/rate`, draft, {
      method: "POST", body: JSON.stringify({ stars: 5, from_notification: true }), signal,
      allowAttempt: () => isCurrentRatingDraft(draft),
    },
  ).catch(error => {
    if (!(error instanceof SyntaxError)) throw error;
    return undefined;
  });
  if (typeof result?.kept !== "boolean" || typeof result.my_stars !== "number" ||
      !Number.isInteger(result.my_stars) || result.my_stars < 1 || result.my_stars > 5 ||
      (!result.kept && result.my_stars !== 5)) return;
  return { kept: result.kept, stars: result.my_stars };
}

export async function fetchNotificationRatingState(
  id: number, owner: TripPassOwner, signal: AbortSignal,
): Promise<{ allowed: boolean; stars: number }> {
  const details = await apiCommand<BookingDetails>(`/bookings/${id}/details`, owner, { method: "GET", signal });
  const participant = details.booking_id === id && (details.role === "driver" || details.role === "passenger");
  const stars = details.my_stars;
  return {
    allowed: participant && details.status === "done" && details.can_rate === true,
    stars: participant && typeof stars === "number" && Number.isInteger(stars) && stars >= 1 && stars <= 5 ? stars : 0,
  };
}
