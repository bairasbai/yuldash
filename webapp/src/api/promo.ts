// ================================================================
//  Промокоды (зеркало backend/app/routers/promo.py).
//  POST /promo/apply {code} — применить код друга/акции (один на жизнь аккаунта).
//  GET  /promo/mine          — мой применённый код (или {promo:null}).
//  Отказ ввести код ничего не ломает — попутка остаётся бесплатной.
//  Бонус kind="boost" → бесплатные поднятия поездки; "welcome" — атрибуция.
//  Появится на проде после мержа release → мягкая деградация.
// ================================================================
import { apiGet, apiPost } from "./client";

export interface PromoApplyResult {
  ok: boolean;
  kind: "welcome" | "boost" | string;
  perk_value: number;
  message_ru: string;
  message_ba: string;
}

export interface PromoMine {
  promo: {
    code: string;
    title: string;
    kind: string;
    perk_value: number;
  } | null;
  redeemed_at?: string | null;
  /**
   * Скидка на поездку в такси, копейки. Вводить ничего не надо: сервер
   * применит её сам при следующем заказе, и человек увидит её в цене
   * ещё до кнопки «Вызвать».
   *
   * available=false при discount_used_order_id=null — кампанию выключили
   * или срок вышел. Это разные вещи, и молчать о них нельзя: иначе
   * человек будет ждать скидку, которой больше нет.
   */
  discount_kop?: number;
  discount_available?: boolean;
  discount_used_order_id?: number | null;
}

export function applyPromo(code: string): Promise<PromoApplyResult> {
  return apiPost<PromoApplyResult>("/promo/apply", { code });
}

export function fetchPromoMine(signal?: AbortSignal): Promise<PromoMine> {
  return apiGet<PromoMine>("/promo/mine", { signal });
}
