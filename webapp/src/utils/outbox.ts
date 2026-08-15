// ================================================================
//  Очередь исходящих: что человек сделал без сети — не пропадает.
//
//  Попутки едут по трассе, где связь пропадает на десятки километров.
//  Водитель отмечает «выехал», пишет «жду у поворота» — и до сервера это
//  не доходит. Приложение такие действия копит и досылает само, как только
//  сеть вернётся (`Outbox` в `TripPass.kt`, F11). Сайт до этого просто
//  показывал «Не получилось, проверь сеть» — и действие терялось.
//
//  Разница между «сети нет» и «сервер отказал» принципиальная:
//   • сети нет (`ApiError.status === 0`) → в очередь, повторим позже;
//   • сервер ответил отказом → повтор не поможет, действие снимаем,
//     иначе очередь «отравится» вечным повтором и застрянет навсегда.
// ================================================================
import { ApiError } from "../api/client";
import { sendMessageRest } from "../api/chat";
import { setDriverStatus, type DriverPhase } from "../api/driver";
import { setTripStatus, type TripStatus } from "../api/family";

const KEY = "yuldash.outbox";

export type OutboxKind = "message" | "trip_status" | "driver_status";

export interface OutboxAction {
  id: number;
  bookingId: number;
  kind: OutboxKind;
  /** Текст сообщения либо код статуса. */
  payload: string;
  createdAt: number;
}

// --------------------------- хранилище ---------------------------

function readAll(): OutboxAction[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr) ? (arr as OutboxAction[]) : [];
  } catch {
    return []; // повреждённая очередь не должна ломать экран
  }
}

/** Кто хочет знать, что очередь изменилась (плашка «N ждут отправки»). */
const listeners = new Set<() => void>();

function writeAll(list: OutboxAction[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* переполнено / приватный режим — очередь просто не переживёт перезагрузку */
  }
  listeners.forEach((fn) => fn());
}

export function subscribeOutbox(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

let seq = Date.now();
function nextId(): number {
  seq += 1;
  return seq;
}

// --------------------------- операции ---------------------------

export function enqueue(bookingId: number, kind: OutboxKind, payload: string): void {
  const list = readAll();
  list.push({ id: nextId(), bookingId, kind, payload, createdAt: Date.now() });
  writeAll(list);
}

/** Сколько действий ждёт отправки по этой поездке — для плашки на экране. */
export function outboxCount(bookingId: number): number {
  return readAll().filter((a) => a.bookingId === bookingId).length;
}

/** Есть ли вообще что слать (чтобы не дёргать разгрузку на пустой очереди). */
export function hasPending(): boolean {
  return readAll().length > 0;
}

/**
 * Выбросить всё — при выходе из аккаунта. Иначе неотправленные сообщения
 * прошлого человека ушли бы ОТ НОВОГО аккаунта при первом появлении сети.
 */
export function clearOutbox(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* нечего чистить */
  }
  listeners.forEach((fn) => fn());
}

/** Ошибка «до сервера не дозвонились» — в отличие от отказа сервера. */
function isOffline(e: unknown): boolean {
  return e instanceof ApiError && e.status === 0;
}

async function run(a: OutboxAction): Promise<void> {
  switch (a.kind) {
    case "message":
      await sendMessageRest(a.bookingId, a.payload);
      return;
    case "trip_status":
      await setTripStatus(a.bookingId, a.payload as TripStatus);
      return;
    case "driver_status":
      await setDriverStatus(a.bookingId, a.payload as DriverPhase);
      return;
    default:
      return; // неизвестный вид (старая версия сайта) — просто снимаем
  }
}

let flushing = false;

/**
 * Отправить накопленное по порядку. Возвращает true, если очередь изменилась —
 * значит экрану пора перечитать историю и состояние с сервера.
 */
export async function flushOutbox(): Promise<boolean> {
  if (flushing) return false; // две разгрузки разом дублировали бы сообщения
  flushing = true;
  let changed = false;
  try {
    // Порядок важен: «выехал» не должен уйти после «завершил».
    for (;;) {
      const list = readAll();
      if (list.length === 0) break;
      const a = list[0];
      try {
        await run(a);
      } catch (e) {
        if (isOffline(e)) break; // сеть всё ещё лежит — попробуем в следующий раз
        // Сервер увидел запрос и отказал: повтор не поможет, снимаем действие.
      }
      writeAll(list.slice(1));
      changed = true;
    }
  } finally {
    flushing = false;
  }
  return changed;
}

/**
 * Разгружать очередь, когда сеть вернулась. Возвращает отписку.
 * Дёргаем и сразу — очередь могла накопиться в прошлый заход.
 */
export function watchOutbox(onFlushed: () => void): () => void {
  const tryFlush = () => {
    if (!hasPending()) return;
    void flushOutbox().then((changed) => {
      if (changed) onFlushed();
    });
  };
  tryFlush();
  window.addEventListener("online", tryFlush);
  return () => window.removeEventListener("online", tryFlush);
}
