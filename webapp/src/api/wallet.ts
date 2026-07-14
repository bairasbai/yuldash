// ================================================================
//  Кошелёк водителя (зеркало backend/app/routers/wallet.py).
//  GET /wallet/balance → { balance_kop, balance_rub }
//  GET /wallet/ledger  → [{ kind, amount_kop, note, created_at, ... }]
//  Деньги — в копейках (rubLabel в utils/format.ts). Записи: earn (+),
//  fee/payout (−), adj (любой знак). Появятся на проде после мержа release
//  → мягкая деградация (404/405 → «скоро»).
// ================================================================
import { apiGet } from "./client";

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
