// ================================================================
//  Инвайты «позови своего» (зеркало backend/app/routers/referral.py).
//  Реальные эндпоинты: GET /referral/me, POST /referral/redeem {code}.
//  1 бонус = 1 бесплатное поднятие поездки. Оба (позвавший и пришедший) получают бонус.
// ================================================================
import { apiGet, apiPost } from "./client";

export interface ReferralMe {
  code: string;
  invited: number; // сколько людей пришло по коду
  credits: number; // накоплено бонусов (бесплатных поднятий)
  redeemed: boolean; // уже ввёл чужой код
}

export function fetchReferral(signal?: AbortSignal): Promise<ReferralMe> {
  return apiGet<ReferralMe>("/referral/me", { signal });
}

/** Ввести чужой код. Ошибки: 400 «Код уже введён» / «Код не найден». */
export function redeemReferral(code: string): Promise<{ ok: boolean; credits: number }> {
  return apiPost<{ ok: boolean; credits: number }>("/referral/redeem", { code });
}
