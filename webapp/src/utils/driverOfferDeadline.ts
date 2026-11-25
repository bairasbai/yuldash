import { serverMs } from "./serverTime";

// Ограничиваем формат оффера тем же ISO UTC/offset, который читает Android.
// Date сам исправляет, например, 30 февраля и 24:00; для дедлайна так нельзя.
const OFFER_ISO = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?(?:Z|[+-](?:(?:0\d|1[0-7]):[0-5]\d|18:00))?$/;
const MONTH_DAYS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

export function driverOfferDeadline(value: string | null | undefined): number | null {
  if (typeof value !== "string") return null;
  const raw = value.trim().toUpperCase();
  const match = OFFER_ISO.exec(raw);
  if (!match) return null;
  const [, y, m, d] = match;
  const year = Number(y);
  const month = Number(m);
  const leap = month === 2 && year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  if (Number(d) > MONTH_DAYS[month - 1] + Number(leap)) return null;
  const parsed = serverMs(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function driverOfferRemaining(expiresAt: number | null, now: number): number {
  return expiresAt === null ? 0 : Math.max(0, expiresAt - now);
}
