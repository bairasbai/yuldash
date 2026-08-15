// ================================================================
//  Форматирование дат/цены/оплаты — общие помощники для экранов
//  волны 2 (карта, заявка, бронь, поездка, квитанция).
// ================================================================
import type { PayMethod } from "../api/bookings";
import { serverDate } from "./serverTime";

/**
 * Названия месяцев. Браузер знает только русские (`toLocaleDateString("ru-RU")`),
 * поэтому в башкирском интерфейсе дата выходила по-русски: «5 июл» посреди
 * башкирского текста. Приложение свои названия держит (`ParcelsScreen.kt`) —
 * держим и мы. Черновик башкирского — на проверку Александру.
 */
const MONTH_SHORT_RU = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"];
const MONTH_SHORT_BA = ["ғин", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"];

/** Полные названия — для строк вида «нужно не позже 5 августа». */
const MONTH_LONG_RU = [
  "января", "февраля", "марта", "апреля", "мая", "июня",
  "июля", "августа", "сентября", "октября", "ноября", "декабря",
];
const MONTH_LONG_BA = [
  "ғинуар", "февраль", "март", "апрель", "май", "июнь",
  "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
];

/** «5 авг» — короткая дата на языке интерфейса. */
export function dayMonthShort(d: Date, ru = true): string {
  const m = (ru ? MONTH_SHORT_RU : MONTH_SHORT_BA)[d.getMonth()];
  return `${d.getDate()} ${m}`;
}

/** «5 августа» — полная дата на языке интерфейса. */
export function dayMonthLong(d: Date, ru = true): string {
  const m = (ru ? MONTH_LONG_RU : MONTH_LONG_BA)[d.getMonth()];
  return `${d.getDate()} ${m}`;
}

/**
 * Русское окончание по числу: «1 оценка», «2 оценки», «5 оценок».
 * Башкирскому это не нужно — там счётное слово не меняется («1 баһа», «5 баһа»),
 * поэтому функция только для русской половины пары.
 */
export function pluralRu(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return few;
  return many;
}

/** «14:30» — время суток. Цифры одинаковы в обоих языках. */
export function hhmm(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** «Сегодня 14:30» / «Завтра 09:00» / «5 июл 18:00». */
export function formatWhen(iso?: string | null, ru = true): string {
  if (!iso) return ru ? "Время уточняется" : "Ваҡыт асыҡлана";
  const d = serverDate(iso);
  if (!d) return "";
  const now = new Date();
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const time = hhmm(d);
  if (sameDay(d, now)) return `${ru ? "Сегодня" : "Бөгөн"} ${time}`;
  if (sameDay(d, tomorrow)) return `${ru ? "Завтра" : "Иртәгә"} ${time}`;
  return `${dayMonthShort(d, ru)} ${time}`;
}

/** Относительное время: «только что» / «5 мин» / «2 ч» / «3 дн» / дата. */
export function formatRelative(iso?: string | null, ru = true): string {
  if (!iso) return "";
  const d = serverDate(iso);
  if (!d) return "";
  const diff = Date.now() - d.getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return ru ? "только что" : "хәҙер генә";
  if (min < 60) return ru ? `${min} мин` : `${min} мин`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return ru ? `${hr} ч` : `${hr} сәғ`;
  const day = Math.floor(hr / 24);
  if (day < 7) return ru ? `${day} дн` : `${day} көн`;
  // Старше недели — показываем дату (формат как formatWhen для дальних дат).
  return dayMonthShort(d, ru);
}

/** «1 200 ₽» либо «Бесплатно/Договорная» при 0. */
export function priceLabel(price: number, ru = true): string {
  if (price > 0) return `${price.toLocaleString("ru-RU")} ₽`;
  return ru ? "Договорная" : "Килешеү буйынса";
}

/** Копейки → «1 200 ₽» (деньги на бэке хранятся в копейках). */
export function rubLabel(kop: number): string {
  return `${Math.round((kop || 0) / 100).toLocaleString("ru-RU")} ₽`;
}

/**
 * Копейки → «188,50 ₽» / «1 200 ₽» — копейки показываем, только если они есть.
 *
 * Округлять нельзя там, где человек сверяет сумму с чеком: цена такси считается по тарифу
 * и коэффициенту спроса, круглой почти не бывает. «188 ₽» в списке против «188,50 ₽» в чеке —
 * первый же повод усомниться в приложении (урок Android, разбор 2026-08-03).
 */
export function kopExactLabel(kop: number): string {
  const value = (kop || 0) / 100;
  const hasCents = Math.round(kop || 0) % 100 !== 0;
  return `${value.toLocaleString("ru-RU", {
    minimumFractionDigits: hasCents ? 2 : 0,
    maximumFractionDigits: 2,
  })} ₽`;
}

/** Название способа оплаты (пара ru/ba). */
export function payMethodLabel(m: PayMethod | string, ru = true): string {
  switch (m) {
    case "cash":
      return ru ? "Наличными" : "Аҡса менән";
    case "sbp":
      return ru ? "Перевод (СБП)" : "Күсереү (СБП)";
    default:
      return ru ? "По договорённости" : "Килешеү буйынса";
  }
}
