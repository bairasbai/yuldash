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

/**
 * Статистика по своему промокоду (GET /promo/{code}/stats).
 *
 * Видит только владелец кода или админ; чужой код отвечает «не найден» — по статистике
 * нельзя проверять, существует ли чей-то код.
 *
 * `issued` — сколько раз код выдан и списан с бюджета кампании; `applied` — сколько
 * применивших ещё существует; `active` — у скольких из них живая поездка, именно по
 * этому числу платят блогеру за результат. Большой `vanished` = чью-то ферму видно
 * невооружённым глазом.
 */
export interface PromoStats {
  code: string;
  title: string;
  campaign: string;
  issued: number;
  applied: number;
  active: number;
  vanished: number;
}

export function fetchPromoStats(code: string, signal?: AbortSignal): Promise<PromoStats> {
  return apiGet<PromoStats>(`/promo/${encodeURIComponent(code.trim().toUpperCase())}/stats`, {
    signal,
  });
}
