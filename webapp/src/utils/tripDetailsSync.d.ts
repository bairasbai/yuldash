import type { BookingStatus } from "../api/bookings";
import type { PayMethod } from "../api/bookings";

export interface PaymentDraft {
  bookingId: number;
  payMethod: PayMethod;
  payAmount: string;
  dirty: boolean;
}
type PaymentAgreement = { pay_method: PayMethod; pay_amount?: number | null };
export function reconcilePaymentDraft(current: PaymentDraft, bookingId: number, agreement: PaymentAgreement): PaymentDraft;
export function acceptPaymentDraft(current: PaymentDraft, submitted: PaymentDraft, agreement: PaymentAgreement): PaymentDraft;

export const DETAILS_REFRESH_MS: number;
export function isTerminalBookingStatus(status: BookingStatus | null | undefined): boolean;
export function shouldApplyBookingDetails(
  liveStatus: BookingStatus | null | undefined,
  detailsStatus: BookingStatus
): boolean;
export function shouldRefreshBookingDetails(
  previousStatus: BookingStatus | null | undefined,
  nextStatus: BookingStatus,
  elapsedMs: number
): boolean;
