import { METRIKA_ID } from "./config";

// Отправка цели в Яндекс.Метрику. Срабатывает только если Метрика подключена
// (METRIKA_ID задан и скрипт загружен). Иначе — тихо ничего не делает.
export function track(goal: string) {
  if (!METRIKA_ID) return;
  try {
    const ym = (window as unknown as { ym?: unknown }).ym;
    // typeof-проверка (не только optional-chaining): на localhost/без сети ym может
    // существовать, но ещё не быть функцией → иначе TypeError.
    if (typeof ym === "function") {
      (ym as (...a: unknown[]) => void)(Number(METRIKA_ID), "reachGoal", goal);
    }
  } catch {
    /* no-op */
  }
}
