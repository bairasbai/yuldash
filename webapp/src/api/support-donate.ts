// ================================================================
//  «Поддержать Юлдаш» — добровольная поддержка платформы
//  (зеркало backend/app/routers/payments.py).
//
//  POST /support/donate {amount_kop} — сумма в копейках, границы держит сервер
//  (10 ₽ … 5 000 ₽): клиенту не верим, пресеты — только кнопки.
//
//  Это доход платформы, а НЕ водителю: на его заработок не влияет никак.
//  Без ключей эквайринга — перевод по СБП, подтверждает админ.
// ================================================================
import { apiPost } from "./client";

export interface SupportResult {
  status: "succeeded" | "pending" | string;
  method: "yookassa" | "sbp_manual" | string;
  payment_id: number;
  amount?: number; // ₽, при sbp_manual
  confirmation_url?: string; // yookassa
  payee?: { phone: string; bank: string; name: string };
}

/** Кнопки-пресеты. Своя сумма вводится руками — границы проверит сервер. */
export const SUPPORT_PRESETS_RUB = [100, 300, 500] as const;
export const SUPPORT_MIN_RUB = 10;
export const SUPPORT_MAX_RUB = 5000;

export function supportDonate(amountRub: number): Promise<SupportResult> {
  return apiPost<SupportResult>("/support/donate", { amount_kop: Math.round(amountRub * 100) });
}
