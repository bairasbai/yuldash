"use server";

import { readSession } from "@/lib/session";
import { notifyHim } from "@/lib/telegram";
import { clockIn } from "@/lib/time";
import { CONFIG } from "@/lib/config";

/**
 * То, что Илиза делает на сайте, а Байрас получает в телеграм.
 * Никакой базы: ответ и нажатие уходят ему сразу — этого хватает,
 * чтобы связь работала с первого дня.
 */

function escapeHtml(s: string): string {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

/** Грубая защита от случайного шквала нажатий. */
const lastAction = new Map<string, number>();
function tooOften(key: string, ms: number): boolean {
  const now = Date.now();
  const prev = lastAction.get(key) ?? 0;
  if (now - prev < ms) return true;
  lastAction.set(key, now);
  return false;
}

export type ReplyState = { status: "idle" | "sent" | "error"; message?: string };

export async function sendReply(
  _prev: ReplyState,
  formData: FormData,
): Promise<ReplyState> {
  const who = await readSession();
  if (!who) return { status: "error", message: "Сначала войди." };

  const n = Number(formData.get("n") ?? 0);
  const text = String(formData.get("text") ?? "").trim().slice(0, 3000);

  if (text.length < 2) {
    return { status: "error", message: "Тут пусто." };
  }
  if (tooOften(`reply:${who}`, 4000)) {
    return { status: "error", message: "Подожди пару секунд." };
  }

  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  const result = await notifyHim(
    `💌 <b>${from}</b> — на письмо №${n}:\n\n${escapeHtml(text)}`,
  );

  if (!result.ok) {
    return { status: "error", message: "Не отправилось. Попробуй ещё раз." };
  }
  return { status: "sent" };
}

/** Она вскрыла запасной конверт — значит, день не задался. Это надо знать. */
export async function openedSadLetter(): Promise<{ ok: boolean }> {
  const who = await readSession();
  if (who !== "her") return { ok: true };
  if (tooOften("sad", 60_000)) return { ok: true };

  const time = clockIn(CONFIG.her.timeZone);
  await notifyHim(
    `🕯 ${CONFIG.her.name} открыла конверт «когда грустно». У неё сейчас ${time}.\n\nПозвони.`,
  );
  return { ok: true };
}

/** Проверка связи: письмо уходит самому Байрасу, Илиза ничего не увидит. */
export async function testDelivery(): Promise<{ ok: boolean; message: string }> {
  const who = await readSession();
  if (who !== "him") return { ok: false, message: "Не твоя кнопка." };

  const result = await notifyHim(
    "✅ Проверка связи. Бот на месте, доставка работает.",
  );
  return result.ok
    ? { ok: true, message: "Ушло. Проверь телеграм." }
    : { ok: false, message: result.error ?? "Не отправилось." };
}

export async function thinkOfYou(): Promise<{ ok: boolean }> {
  const who = await readSession();
  if (!who) return { ok: false };
  if (tooOften(`think:${who}`, 20_000)) return { ok: true };

  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  const time = clockIn(who === "her" ? CONFIG.her.timeZone : CONFIG.him.timeZone);

  const result = await notifyHim(`💭 ${from} подумала о тебе. У неё сейчас ${time}.`);
  return { ok: result.ok };
}
