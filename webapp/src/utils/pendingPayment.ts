// ================================================================
//  Платёж, ради которого человек ушёл с сайта.
//
//  Оплата картой уводит в ЮKassa целиком: сайт закрывается, состояние в памяти
//  пропадает. Банк возвращает человека на /pay/done — и до сих пор он попадал
//  на стартовую заставку, без единого слова о том, прошла оплата или нет.
//
//  Поэтому перед уходом запоминаем НОМЕР платежа и куда вернуть человека.
//  Приложению это не нужно: там браузер открывается поверх, и экран остаётся
//  жив (проверка идёт на возврате, ON_RESUME).
// ================================================================

const KEY = "yuldash.pendingPayment";
/** Дольше суток платёж не ждём: человек давно закрыл вопрос. */
const MAX_AGE_MS = 24 * 3600 * 1000;

export interface PendingPayment {
  paymentId: number;
  /** Куда вернуть человека после оплаты. */
  backTo: string;
  /** За что платил — чтобы сказать это словами на экране возврата. */
  what: "boost" | "commission" | "debt" | "trip" | "ad" | "donate";
  at: number;
}

export function rememberPayment(
  paymentId: number,
  what: PendingPayment["what"],
  backTo: string
): void {
  try {
    const data: PendingPayment = { paymentId, what, backTo, at: Date.now() };
    localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    /* хранилище недоступно — экран возврата просто попросит проверить вручную */
  }
}

export function readPendingPayment(): PendingPayment | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const p = JSON.parse(raw) as PendingPayment;
    if (!p || typeof p.paymentId !== "number" || typeof p.at !== "number") return null;
    if (Date.now() - p.at > MAX_AGE_MS) {
      localStorage.removeItem(KEY);
      return null;
    }
    return p;
  } catch {
    return null;
  }
}

export function forgetPayment(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* нечего чистить */
  }
}
