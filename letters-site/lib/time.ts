import { CONFIG } from "./config";

/**
 * Все даты считаем по Уфе — она там, письма приходят по её утрам.
 * Сервер может стоять где угодно, поэтому часовой пояс задаём явно.
 */

/** Сегодняшняя дата в её городе, в виде "2026-08-14". */
export function todayInHerCity(now: Date = new Date()): string {
  // Отладка: посмотреть, как сайт выглядит в любой день отсчёта.
  // В рабочей версии не действует — только при локальной разработке.
  if (process.env.NODE_ENV !== "production" && process.env.DEBUG_TODAY) {
    return process.env.DEBUG_TODAY;
  }
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: CONFIG.her.timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}

/** Часы и минуты в произвольном поясе — для двух часов на странице «Мы». */
export function clockIn(timeZone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat("ru-RU", {
    timeZone,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(now);
}

/** Разница в днях между двумя датами вида "2026-08-05". */
export function daysBetween(from: string, to: string): number {
  const a = Date.parse(`${from}T00:00:00Z`);
  const b = Date.parse(`${to}T00:00:00Z`);
  return Math.round((b - a) / 86_400_000);
}

/** Сколько всего писем — по числу дней от старта до встречи включительно. */
export function totalLetters(): number {
  return daysBetween(CONFIG.startDate, CONFIG.meetDate) + 1;
}

/** Дата, когда открывается письмо №n (n начинается с 1). */
export function dateOfLetter(n: number): string {
  const start = Date.parse(`${CONFIG.startDate}T00:00:00Z`);
  return new Date(start + (n - 1) * 86_400_000).toISOString().slice(0, 10);
}

/**
 * Номер сегодняшнего письма.
 * 0 — игра ещё не началась, больше totalLetters() — уже встретились.
 */
export function letterNumberToday(today: string = todayInHerCity()): number {
  return daysBetween(CONFIG.startDate, today) + 1;
}

/** Сколько дней осталось до встречи. 0 — она сегодня. */
export function daysUntilMeeting(today: string = todayInHerCity()): number {
  return Math.max(0, daysBetween(today, CONFIG.meetDate));
}

/** Сколько дней вы вместе — от рассвета в Исянбетово. */
export function daysTogether(today: string = todayInHerCity()): number {
  return Math.max(0, daysBetween(CONFIG.firstDawn, today));
}

/**
 * Насколько рассвело: 0 — глубокая ночь в день старта, 1 — утро встречи.
 * От этого числа зависит весь цвет сайта.
 */
export function dawnProgress(today: string = todayInHerCity()): number {
  const total = daysBetween(CONFIG.startDate, CONFIG.meetDate);
  if (total <= 0) return 1;
  const passed = daysBetween(CONFIG.startDate, today);
  return Math.min(1, Math.max(0, passed / total));
}

/** «14 августа» — для подписи под письмом. */
export function humanDate(iso: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    timeZone: "UTC",
    day: "numeric",
    month: "long",
  }).format(new Date(`${iso}T00:00:00Z`));
}

/** Правильное окончание: 1 день, 2 дня, 5 дней. */
export function plural(n: number, one: string, few: string, many: string): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
  return many;
}
