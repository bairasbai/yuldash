import crypto from "node:crypto";
import { cookies } from "next/headers";

/**
 * Вход по кодовому слову. Никаких паролей в коде: коды лежат
 * в переменных окружения и в репозиторий не попадают.
 */

const COOKIE = "dawn_session";
const MAX_AGE_DAYS = 120;

export type Who = "her" | "him";

function secret(): string {
  return process.env.SESSION_SECRET ?? "dev-only-secret";
}

function sign(payload: string): string {
  return crypto.createHmac("sha256", secret()).update(payload).digest("base64url");
}

/** «02.07.2026», «02 07 2026» и «02072026» — это один и тот же код. */
function normalize(input: string): string {
  return input.toLowerCase().replace(/[^a-zа-я0-9]/gi, "");
}

/** Сравнение без утечки времени: чтобы код нельзя было подобрать по задержке ответа. */
function sameSecret(a: string, b: string): boolean {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/** Кто ввёл этот код — она, он, или код неверный. */
export function whoseCode(input: string): Who | null {
  const given = normalize(input);
  if (!given) return null;

  const hers = normalize(process.env.ACCESS_CODE_HER ?? "");
  const his = normalize(process.env.ACCESS_CODE_HIM ?? "");

  if (hers && sameSecret(given, hers)) return "her";
  if (his && sameSecret(given, his)) return "him";
  return null;
}

export async function createSession(who: Who): Promise<void> {
  const expires = Date.now() + MAX_AGE_DAYS * 86_400_000;
  const payload = `${who}.${expires}`;
  const jar = await cookies();
  jar.set(COOKIE, `${payload}.${sign(payload)}`, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: MAX_AGE_DAYS * 86_400,
  });
}

export async function readSession(): Promise<Who | null> {
  const jar = await cookies();
  const raw = jar.get(COOKIE)?.value;
  if (!raw) return null;

  const [who, expires, signature] = raw.split(".");
  if (!who || !expires || !signature) return null;
  if (!sameSecret(signature, sign(`${who}.${expires}`))) return null;
  if (Number(expires) < Date.now()) return null;
  if (who !== "her" && who !== "him") return null;

  return who;
}

export async function endSession(): Promise<void> {
  const jar = await cookies();
  jar.delete(COOKIE);
}
