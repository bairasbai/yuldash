import { METRIKA_ID } from "./config";

// Отправка цели в Яндекс.Метрику. Срабатывает только если Метрика подключена
// (METRIKA_ID задан и скрипт загружен). Иначе — тихо ничего не делает.
export function track(goal: string) {
  if (!METRIKA_ID) return;
  try {
    (window as unknown as { ym?: (...a: unknown[]) => void }).ym?.(
      Number(METRIKA_ID),
      "reachGoal",
      goal
    );
  } catch {
    /* no-op */
  }
}
