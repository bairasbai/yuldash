import { apiGet } from "./client";

const statusCodes = ["unavailable", "not_started", "queued", "waiting_bank", "reconciling", "succeeded", "canceled"] as const;
export type BankStatus = (typeof statusCodes)[number];

export interface RideshareCardPayment {
  currency: "RUB";
  payment_status: BankStatus;
  amount_kop: number | null;
  paid_kop: number;
  refund_status: BankStatus;
  refunded_kop: number;
  refund_coverage: "none" | "partial" | "full";
  payout_status: BankStatus | null;
  paid_out_kop: number | null;
  payout_remaining_kop: number | null;
}

export const RIDESHARE_CARD_RECEIPT_METHOD = "rideshare_card";

export function hasBankPayment(payment: RideshareCardPayment | null): boolean {
  return payment !== null && payment.payment_status !== "unavailable" && payment.payment_status !== "not_started";
}

export function replacesDirectPayment(payment: RideshareCardPayment | null): boolean {
  return hasBankPayment(payment) && payment?.payment_status !== "canceled";
}

export async function fetchRideshareCardPayment(
  bookingId: number,
  generation: string,
  signal: AbortSignal,
): Promise<RideshareCardPayment> {
  const payment = await apiGet<RideshareCardPayment>(`/bookings/${bookingId}/card-payment`, {
    cache: "no-store",
    _generation: generation,
    signal,
  });
  if (payment.currency !== "RUB" || !statusCodes.includes(payment.payment_status) ||
      !statusCodes.includes(payment.refund_status) ||
      (payment.payout_status !== null && !statusCodes.includes(payment.payout_status)) ||
      !["none", "partial", "full"].includes(payment.refund_coverage) ||
      ![payment.paid_kop, payment.refunded_kop].every(isKop) ||
      ![payment.amount_kop, payment.paid_out_kop, payment.payout_remaining_kop].every((value) => value === null || isKop(value))) {
    throw new Error("Invalid rideshare card receipt");
  }
  return payment;
}

function isKop(value: number): boolean {
  return Number.isSafeInteger(value) && value >= 0 && value <= 2_147_483_647;
}
