// ================================================================
//  Форматирование дат/цены/оплаты — общие помощники для экранов
//  волны 2 (карта, заявка, бронь, поездка, квитанция).
// ================================================================
import type { PayMethod } from "../api/bookings";

/** «Сегодня 14:30» / «Завтра 09:00» / «5 июл 18:00». */
export function formatWhen(iso?: string | null, ru = true): string {
  if (!iso) return ru ? "Время уточняется" : "Ваҡыт асыҡлана";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  const now = new Date();
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const time = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  if (sameDay(d, now)) return `${ru ? "Сегодня" : "Бөгөн"} ${time}`;
  if (sameDay(d, tomorrow)) return `${ru ? "Завтра" : "Иртәгә"} ${time}`;
  const date = d.toLocaleDateString("ru-RU", { day: "numeric", month: "short" });
  return `${date} ${time}`;
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
