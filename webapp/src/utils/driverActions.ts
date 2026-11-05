// ================================================================
//  События HTTP: «Выехал», «Подъезжаю», «Сел», «Завершить», «Не пришёл».
//  Они обновляют уже разрешённый рейс, но не доказывают живое действие: событие может прийти из очереди.
//  Только отдельный UI-ticket ниже может активировать новое разрешение в памяти вкладки.
//  Три вести: шаг ушёл на сервер («start», ещё до ответа — сервер закрывает канал брони раньше, чем отвечает),
//  сервер принял («done»), сервер отверг («fail»; обрыв сети — не отказ).
//  Без зависимостей — его зовут модули api/*, и он не должен тянуть их по кругу.
//  Сам этот сигнал отправку точки никогда не начинает.
// ================================================================

export type DriverStep = "start" | "done" | "fail";
export type DriverActionListener = (bookingId: number, closed: boolean, step: DriverStep) => void;

const listeners = new Set<DriverActionListener>();
type DriverRideTerminalListener = (rideId: number, owner: string) => void;
const rideTerminalListeners = new Set<DriverRideTerminalListener>();

/** Только подтверждённый HTTP terminal всего рейса; owner зафиксирован до HTTP, не из replay-ответа. */
export function emitDriverRideTerminal(rideId: number, owner: string): void {
  for (const fn of [...rideTerminalListeners]) {
    try { fn(rideId, owner); } catch { /* решение сервера уже принято; остальные слушатели тоже получают сигнал */ }
  }
}

/** Сигнал ничего не создаёт: слушатель может лишь отозвать уже существующий grant своего рейса. */
export function onDriverRideTerminal(fn: DriverRideTerminalListener): () => void {
  rideTerminalListeners.add(fn);
  return () => { rideTerminalListeners.delete(fn); };
}

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

// Эта проводка вызывается UI в момент нажатия. События API выше её не вызывают.
import type { DriverClickTicket } from "./rideLocationShare";
interface DriverClickHandler {
  begin(id: number, rideId: number, closing: boolean): DriverClickTicket | null;
  finish(ticket: DriverClickTicket, accepted: boolean): void | Promise<void>;
}
let clickHandler: DriverClickHandler | null = null;
export function installDriverClickHandler(handler: DriverClickHandler): void { clickHandler = handler; }
export function beginDriverClick(id: number, rideId: number, closing: boolean): DriverClickTicket | null {
  try { return clickHandler?.begin(id, rideId, closing) ?? null; } catch { return null; }
}
export function finishDriverClick(ticket: DriverClickTicket | null | undefined, accepted: boolean): void {
  if (ticket) void clickHandler?.finish(ticket, accepted);
}
