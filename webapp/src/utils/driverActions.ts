// ================================================================
//  Шаг водителя на ЭТОМ устройстве дошёл до сервера (Д2, 24.10.2026): «Выехал», «Подъезжаю», «Сел»,
//  «Завершить», «Не пришёл». Слушает отправка точки на весь рейс (rideLocationShare.ts): так она узнаёт,
//  что водитель ведёт рейс, и сразу переводит точку на другую живую бронь, когда эта закрылась.
//  Без зависимостей — его зовут модули api/*, и он не должен тянуть их по кругу.
//  Сам этот сигнал отправку точки никогда не начинает.
// ================================================================

export type DriverActionListener = (bookingId: number, closed: boolean) => void;

const listeners = new Set<DriverActionListener>();

/** Сервер принял шаг водителя по брони. `closed` — бронь закрыта (завершена или неявка). */
export function emitDriverAction(bookingId: number, closed: boolean): void {
  for (const fn of [...listeners]) {
    try {
      fn(bookingId, closed);
    } catch {
      /* слушатель упал — шаг водителя уже принят сервером, остальным это не мешает */
    }
  }
}

/** Подписка; возвращает отписку. */
export function onDriverAction(fn: DriverActionListener): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
