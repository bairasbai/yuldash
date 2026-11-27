import type { TripPassOwner } from "../utils/tripPass";
import type { BookingDetails } from "./bookings";
import { isCurrentRatingDraft, type RatingDraft } from "../push/rateDraft";
import { ApiError, apiCommand } from "./client";

export async function rateBookingFromNotification(
  draft: RatingDraft, signal: AbortSignal,
): Promise<{ kept: boolean; stars: number } | undefined> {
  const result = await apiCommand<{ kept?: unknown; my_stars?: unknown } | null>(
    `/bookings/${draft.bookingId}/rate`, draft, {
      method: "POST", body: JSON.stringify({ stars: 5, from_notification: true }), signal,
      allowAttempt: () => isCurrentRatingDraft(draft),
    },
  ).catch(error => {
    if (error instanceof SyntaxError ||
        (error instanceof ApiError && (error.status === 408 || error.status === 429))) return undefined;
    throw error;
  });
  if (typeof result?.kept !== "boolean" || typeof result.my_stars !== "number" ||
      !Number.isInteger(result.my_stars) || result.my_stars < 1 || result.my_stars > 5 ||
      (!result.kept && result.my_stars !== 5)) return;
  return { kept: result.kept, stars: result.my_stars };
}

export async function fetchNotificationRatingState(
  id: number, owner: TripPassOwner, signal: AbortSignal,
): Promise<{ allowed: boolean; stars: number }> {
  const details = await apiCommand<BookingDetails | null>(`/bookings/${id}/details`, owner, { method: "GET", signal });
  const stars = details?.my_stars;
  if (details?.booking_id !== id || (details.role !== "driver" && details.role !== "passenger") ||
      !["pending", "confirmed", "onboard", "done", "cancelled"].includes(details.status) ||
      typeof details.can_rate !== "boolean" ||
      typeof stars !== "number" || !Number.isInteger(stars) || stars < 0 || stars > 5) {
    throw new SyntaxError("Invalid booking rating state");
  }
  return { allowed: details.status === "done" && details.can_rate, stars };
}
