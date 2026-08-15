// ================================================================
//  Кошелёк водителя (зеркало backend/app/routers/wallet.py).
//  GET /wallet/balance → { balance_kop, balance_rub }
//  GET /wallet/ledger  → [{ kind, amount_kop, note, created_at, ... }]
//  Деньги — в копейках (rubLabel в utils/format.ts). Записи: earn (+),
//  fee/payout (−), adj (любой знак). Появятся на проде после мержа release
//  → мягкая деградация (404/405 → «скоро»).
// ================================================================
import { apiGet, apiPost } from "./client";

export interface WalletBalance {
  balance_kop: number;
  balance_rub: number;
}

/** Вид записи ledger: начисление / комиссия / выплата / корректировка. */
export type LedgerKind = "earn" | "fee" | "payout" | "adj" | string;

export interface LedgerEntry {
  id: number;
  kind: LedgerKind;
  amount_kop: number; // earn > 0; fee/payout < 0
  amount_rub: number;
  order_id: number | null;
  booking_id: number | null;
  note: string | null;
  created_at: string;
}

export function fetchWalletBalance(signal?: AbortSignal): Promise<WalletBalance> {
  return apiGet<WalletBalance>("/wallet/balance", { signal });
}

export function fetchWalletLedger(
  limit = 100,
  signal?: AbortSignal
): Promise<LedgerEntry[]> {
  return apiGet<LedgerEntry[]>(`/wallet/ledger?limit=${limit}`, { signal });
}

// ------------------------------- Выплаты на карту (Модель Б) -------------------------------
/** GET /wallet/payout/status — доступны ли выплаты + баланс и реквизиты.
 *  enabled=false → рисуем «Выплаты на карту скоро». Границы — с сервера. */
export interface PayoutStatus {
  enabled: boolean;
  balance_kop: number;
  has_requisite: boolean;
  card_last4: string;
  min_kop: number;
  max_kop: number;
}

export function fetchPayoutStatus(signal?: AbortSignal): Promise<PayoutStatus> {
  return apiGet<PayoutStatus>("/wallet/payout/status", { signal });
}

/** Сохранить карту для выплат. ПРИВАТНОСТЬ: шлём ТОЛЬКО последние 4 цифры —
 *  полный номер карты не покидает устройство (сервер и так хранит только last4). */
export function savePayoutRequisite(cardLast4: string): Promise<{ ok: boolean; card_last4: string }> {
  return apiPost<{ ok: boolean; card_last4: string }>("/wallet/payout/requisite", {
    card_last4: cardLast4,
  });
}

/** Вывести на карту. idempotency_key — uuid на попытку: ретрай не спишет дважды.
 *  503 = выплаты ещё выключены («скоро»), 400 = границы/баланс/нет карты. */
export function requestPayout(
  amountKop: number,
  idempotencyKey: string
): Promise<{ ok?: boolean; [k: string]: unknown }> {
  return apiPost(`/wallet/payout`, {
    amount_kop: amountKop,
    idempotency_key: idempotencyKey,
  });
}

// ------------------------------- Оплата завершённой поездки -------------------------------
/**
 * Как пассажир платит за уже состоявшуюся поездку
 * (POST /bookings/{id}/pay, POST /instant/orders/{id}/pay).
 *
 * Наличные — просто отметка «отдал из рук в руки»: деньги идут напрямую водителю,
 * платформа их не держит. Карта и СБП — через банк.
 *
 * Оплата идемпотентна: повторное нажатие не спишет второй раз. И наоборот —
 * отметка «наличными» гасит висящую банковскую ссылку, иначе человек, который
 * передумал и заплатил налом, мог позже открыть старую ссылку и заплатить дважды.
 *
 * 503 = онлайн-оплата в этом городе ещё не включена: карту прячем, остаются
 * наличные и перевод «на доверии».
 */
export type PayMethodKey = "cash" | "card" | "sbp";

export interface PayTripResult {
  status: "paid" | "already_paid" | "pending" | string;
  method?: string;
  payment_id?: number | null;
  /** Банк вернул ссылку — уводим человека туда, дальше платит он сам. */
  confirmation_url?: string | null;
}

export function payBooking(bookingId: number, method: PayMethodKey): Promise<PayTripResult> {
  return apiPost<PayTripResult>(`/bookings/${bookingId}/pay`, { method });
}

export function payInstantOrder(orderId: number, method: PayMethodKey): Promise<PayTripResult> {
  return apiPost<PayTripResult>(`/instant/orders/${orderId}/pay`, { method });
}
