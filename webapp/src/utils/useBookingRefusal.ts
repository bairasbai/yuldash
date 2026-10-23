import { useEffect, useRef } from "react";
import { getOutboxRefusals, subscribeOutboxRefusals, type OutboxRefusal } from "./outbox";

/**
 * Сервер отказал отметке по ЭТОЙ брони, пока она лежала в очереди («Выехал» без сети). Экран, который
 * написал «Нет сети — отправим позже», должен перестать это говорить: сообщаем ему отказ (и тот, что пришёл,
 * пока человек был на другом экране). Каждый отказ отдаём один раз за время жизни экрана.
 */
export function useBookingRefusal(bookingId: number | undefined, onRefusal: (refusal: OutboxRefusal) => void): void {
  const latest = useRef(onRefusal);
  latest.current = onRefusal;
  useEffect(() => {
    if (!bookingId) return;
    const told = new Set<string>();
    const check = () => {
      for (const refusal of getOutboxRefusals()) {
        if (refusal.bookingId !== bookingId || told.has(refusal.key)) continue;
        told.add(refusal.key);
        latest.current(refusal);
      }
    };
    check();
    return subscribeOutboxRefusals(check);
  }, [bookingId]);
}
