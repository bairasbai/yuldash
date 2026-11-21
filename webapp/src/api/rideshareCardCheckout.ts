import { apiCommand } from "./client";
import type { BookingCommandOwner, SavedCardConfirmation } from "../utils/bookingCommandStore";

export const CARD_CONFIRM_SCOPE = "booking.card_confirm";
export const CARD_CONFIRM_RETRY_MS = 24 * 60 * 60 * 1000;
const operationId = /^[a-f0-9]{32}$/;

export interface CardCheckoutQuote {
  amount_kop: number;
  paid_revision: number;
  card_revision: number;
  recipient_version: string;
  driver_consent_at: string;
  retry_after_payment_id: string | null;
}

export interface CardCheckoutOffer {
  action: "start" | "retry" | "resume";
  confirmation: CardCheckoutQuote;
}

export async function readCardCheckoutOffer(
  bookingId: number, owner: BookingCommandOwner, signal: AbortSignal,
): Promise<CardCheckoutOffer | null> {
  const result = await apiCommand<Partial<CardCheckoutOffer>>(cardPath(bookingId) + "/terms", owner, {
    method: "GET", signal,
  });
  if (result?.action == null) return null;
  const action = result.action;
  const quote = result.confirmation;
  if (!quote || (action !== "start" && action !== "retry" && action !== "resume") ||
      !Number.isSafeInteger(quote.amount_kop) || quote.amount_kop <= 0 || quote.amount_kop > 2_147_483_647 ||
      ![quote.paid_revision, quote.card_revision].every(value => Number.isSafeInteger(value) && value >= 0) ||
      typeof quote.recipient_version !== "string" || !operationId.test(quote.recipient_version) ||
      typeof quote.driver_consent_at !== "string" || !/(?:Z|[+-]\d{2}:\d{2})$/.test(quote.driver_consent_at) ||
      !Number.isFinite(Date.parse(quote.driver_consent_at)) ||
      (action === "retry"
        ? typeof quote.retry_after_payment_id !== "string" || !operationId.test(quote.retry_after_payment_id)
        : quote.retry_after_payment_id !== null)) {
    throw new Error("Invalid bank confirmation terms");
  }
  const { amount_kop, paid_revision, card_revision, recipient_version, driver_consent_at, retry_after_payment_id } = quote;
  return { action, confirmation: {
    amount_kop, paid_revision, card_revision, recipient_version, driver_consent_at, retry_after_payment_id,
  } };
}

export async function sendCardConfirmation(
  owner: BookingCommandOwner, command: SavedCardConfirmation, signal: AbortSignal,
) {
  const result = await apiCommand<{ accepted: boolean }>(
    "/bookings/" + command.bookingId + "/commands/v1/card-confirm", owner, {
      method: "POST", key: command.key, body: command.wireBody, signal, oneShot: true,
    },
  );
  if (result?.accepted !== true) throw new Error("Missing bank command receipt");
}

type ConfirmationResult = "unseen" | "applied" | "closed_no_effect";
interface IntentResponse {
  state: ConfirmationResult;
  scope: string | null;
  ref_id: number | null;
  current: { accepted?: boolean } | null;
}

export async function readCardConfirmationResult(
  owner: BookingCommandOwner, command: SavedCardConfirmation, signal: AbortSignal, close = false,
): Promise<ConfirmationResult> {
  const path = "/command-intents/" + encodeURIComponent(command.key);
  const response = await apiCommand<IntentResponse>(close ? path + "/close" : path, owner, close ? {
    method: "POST", signal, oneShot: true,
    body: JSON.stringify({ scope: CARD_CONFIRM_SCOPE, target_id: command.bookingId, payload_hash: command.hash }),
  } : { method: "GET", signal });
  if (!close && response?.state === "unseen" && response.scope === null) return response.state;
  if (response?.scope === CARD_CONFIRM_SCOPE) {
    if (response.state === "closed_no_effect" && response.ref_id === null) return response.state;
    if (response.state === "applied" && response.ref_id === command.bookingId && response.current?.accepted === true) {
      return response.state;
    }
  }
  throw new Error("Unexpected bank command receipt");
}

export function trustedCardCheckoutUrl(value: unknown): value is string {
  return typeof value === "string" && !/[\s\\\x00-\x1f\x7f]/u.test(value) &&
    /^https:\/\/(?:yookassa\.ru|yoomoney\.ru)(?::443)?(?:[/?#]|$)/i.test(value);
}

export async function readCardCheckoutLink(
  bookingId: number, paymentId: string, owner: BookingCommandOwner, signal: AbortSignal,
): Promise<string | null> {
  if (!operationId.test(paymentId)) return null;
  const result = await apiCommand<{ checkout_url: unknown }>(
    cardPath(bookingId) + "/checkout-link?expected_payment_id=" + paymentId, owner, { method: "GET", signal },
  );
  return trustedCardCheckoutUrl(result?.checkout_url) ? result.checkout_url : null;
}

function cardPath(bookingId: number) {
  return "/bookings/" + bookingId + "/card-payment";
}
