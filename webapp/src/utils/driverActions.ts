// ================================================================
//  Шаг водителя на ЭТОМ устройстве (Д2, 24.10.2026): «Выехал», «Подъезжаю», «Сел», «Завершить», «Не пришёл».
//  Слушает отправка точки на весь рейс (rideLocationShare.ts): так она узнаёт, что водитель ведёт рейс,
//  и сразу переводит точку на другую живую бронь, когда эта закрылась.
//  Три вести: шаг ушёл на сервер («start», ещё до ответа — сервер закрывает канал брони раньше, чем отвечает),
//  сервер принял («done»), сервер отверг («fail»; обрыв сети — не отказ).
//  Без зависимостей — его зовут модули api/*, и он не должен тянуть их по кругу.
//  Сам этот сигнал отправку точки никогда не начинает.
// ================================================================

export type DriverStep = "start" | "done" | "fail";
export type DriverActionListener = (bookingId: number, closed: boolean, step: DriverStep) => void;

const listeners = new Set<DriverActionListener>();

function emit(bookingId: number, closed: boolean, step: DriverStep): void {
  for (const fn of [...listeners]) {
    try {
      fn(bookingId, closed, step);
    } catch {
      /* слушатель упал — шаг водителя сервер уже решил, остальным это не мешает */
    }
  }
}

/** Сервер принял шаг водителя по брони. `closed` — бронь закрыта (завершена или неявка). */
export function emitDriverAction(bookingId: number, closed: boolean): void {
  emit(bookingId, closed, "done");
}

/**
 * Отправить шаг и сообщить слушателям: «ушёл» → «принят» / «отвергнут». `closing` — шаг закрывает бронь.
 * Отказ сервера — ошибка со статусом не 0; обрыв сети (статус 0) отказом не считаем.
 */
export async function driverStep<T>(bookingId: number, closing: boolean, request: () => Promise<T>): Promise<T> {
  emit(bookingId, closing, "start");
  try {
    const result = await request();
    emit(bookingId, closing, "done");
    return result;
  } catch (e) {
    const status = (e as { status?: number } | null)?.status;
    if (typeof status === "number" && status !== 0) emit(bookingId, closing, "fail");
    throw e;
  }
}

/** Подписка; возвращает отписку. */
export function onDriverAction(fn: DriverActionListener): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
