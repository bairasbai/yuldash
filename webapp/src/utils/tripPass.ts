// ================================================================
//  Офлайн-паспорт поездки. Зеркало Android `data/TripPass.kt` (F11).
//
//  Зачем. Машину сверяют ровно там, где связи может не быть: ночная
//  трасса, перевал, обочина у выезда из села. Без сети веб-версия
//  не могла показать НИЧЕГО — ни госномера, ни кода посадки, ни
//  телефона водителя. Человек стоял у машины и не мог проверить,
//  та ли это машина (сверка клиентов, слой офлайна, 2026-08-31).
//
//  Что храним: снимок подтверждённой брони — маршрут, водитель,
//  госномер, код посадки, точка встречи, цена. Пишется, когда бронь
//  подтверждена и данные уже открыты; стирается, когда поездка
//  закончилась или отменена.
//
//  ПРИВАТНОСТЬ. Телефон водителя — персональные данные. В браузере
//  «секретного хранилища» нет: localStorage читает любой скрипт на
//  этом домене, а на общем телефоне — и следующий человек. Поэтому:
//   • телефон храним, потому что без него офлайн-паспорт бесполезен
//     (позвонить водителю с обочины — это и есть его смысл);
//   • но живёт он ровно столько, сколько живёт поездка: при завершении
//     брони его стирает этот файл, при выходе из аккаунта — общая чистка
//     (`utils/privacy.ts`, префикс `yuldash.tripPass.`). Двух своих путей
//     чистки нарочно не заводим: они расходятся при первой же правке;
//   • в аналитику и логи ничего из паспорта не уходит.
// ================================================================

import { getSessionGeneration } from "../api/client";
import { ownedStorage } from "./ownedStorage";

/** Ключ на бронь: паспортов может быть несколько (две поездки в один день). */
const KEY = (bookingId: number) => `yuldash.tripPass.${bookingId}`;

/** Снимок брони для показа без сети. Только то, что нужно у машины. */
export interface TripPass {
  bookingId: number;
  fromCity: string;
  toCity: string;
  departAt: string;
  driverName: string;
  driverCar: string;
  /** Госномер — по нему и сверяют машину у обочины. */
  driverPlate: string;
  driverPhone: string;
  boardingCode: string;
  pickup: string;
  price: number;
  seats: number;
  savedAt: number;
}

/**
 * Сохранить паспорт. Зовётся, когда бронь подтверждена и контакты открыты:
 * до этого сохранять нечего, телефона и точки ещё нет.
 *
 * Пустой код посадки не мешает: у части поездок его нет, а госномер и телефон
 * нужны всё равно.
 */
export function saveTripPass(p: Omit<TripPass, "savedAt">, owner?: string): void {
  try {
    owner ??= getSessionGeneration();
    ownedStorage(owner).setItem(KEY(p.bookingId), JSON.stringify({ ...p, savedAt: Date.now() }));
  } catch {
    /* приватный режим или переполнение — офлайн-паспорта просто не будет */
  }
}

/** Достать паспорт. Нет или битый — null, экран просто не покажет карточку. */
export function loadTripPass(bookingId: number, owner?: string): TripPass | null {
  try {
    owner ??= getSessionGeneration();
    const raw = ownedStorage(owner).getItem(KEY(bookingId));
    if (!raw) return null;
    const v = JSON.parse(raw) as TripPass;
    return v && typeof v === "object" && v.bookingId === bookingId ? v : null;
  } catch {
    return null;
  }
}

/**
 * Убрать паспорт: поездка завершена или отменена.
 *
 * Держать его дольше нельзя — это телефон живого человека и код посадки,
 * которые после поездки не нужны никому.
 */
export function dropTripPass(bookingId: number, owner?: string): void {
  try {
    owner ??= getSessionGeneration();
    ownedStorage(owner).removeItem(KEY(bookingId));
  } catch {
    /* не критично */
  }
}
