"use server";

import { revalidatePath } from "next/cache";
import { readSession } from "@/lib/session";
import { notifyHim, notifyOther } from "@/lib/telegram";
import { clockIn, todayInHerCity } from "@/lib/time";
import { CONFIG } from "@/lib/config";
import { moodByValue } from "@/data/moods";
import {
  addWish,
  answerDay,
  answersOn,
  removeWish,
  saveReply,
  sealCapsule,
  setMood,
  setWishDone,
} from "@/lib/store";

/**
 * Всё, что вы делаете на сайте: ответы, нажатия, списки, настроения.
 * Уведомление всегда уходит второму — сделала она, узнаёт он.
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

  // Ответ и сохраняется рядом с письмом, и уходит в телеграм.
  // Если базы нет, телеграм всё равно сработает.
  await saveReply(n, who, text);
  const result = await notifyOther(
    who,
    `💌 <b>${from}</b> — на письмо №${n}:\n\n${escapeHtml(text)}`,
  );

  revalidatePath(`/letters/${n}`);
  revalidatePath("/");

  if (!result.ok) {
    return { status: "error", message: "Не отправилось. Попробуй ещё раз." };
  }
  return { status: "sent" };
}

/* ── Что сделаем в Уфе ─────────────────────────────────────────── */

export type WishState = { error?: string };

export async function addWishAction(
  _prev: WishState,
  formData: FormData,
): Promise<WishState> {
  const who = await readSession();
  if (!who) return { error: "Сначала войди." };

  const text = String(formData.get("text") ?? "").trim().slice(0, 200);
  if (text.length < 2) return { error: "Пусто." };
  // Пауза короткая: в список нормально вписать три пункта подряд,
  // это не спам
  if (tooOften(`wish:${who}`, 700)) return { error: "Не так быстро." };

  const ok = await addWish(text, who);
  if (!ok) return { error: "Хранилище не подключено." };

  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  await notifyOther(who, `📝 ${from} добавила в список Уфы:\n\n${escapeHtml(text)}`);

  revalidatePath("/together");
  return {};
}

export async function toggleWishAction(id: number, done: boolean) {
  const who = await readSession();
  if (!who) return;
  await setWishDone(id, done);
  revalidatePath("/together");
}

export async function removeWishAction(id: number) {
  const who = await readSession();
  if (!who) return;
  await removeWish(id);
  revalidatePath("/together");
}

/* ── Настроение дня ────────────────────────────────────────────── */

export async function setMoodAction(
  value: string,
  note: string,
): Promise<{ ok: boolean }> {
  const who = await readSession();
  if (!who) return { ok: false };

  const option = moodByValue(value);
  if (!option) return { ok: false };

  const day = todayInHerCity();
  const ok = await setMood(day, who, value, note.trim().slice(0, 300));
  if (!ok) return { ok: false };

  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  // Про тяжёлый день сообщаем сразу, про остальные — тихо, чтобы
  // отметка настроения не превратилась в поток уведомлений
  if (option.alert) {
    await notifyOther(
      who,
      `🕯 У ${from} сегодня тяжёлый день${note ? `:\n\n${escapeHtml(note)}` : "."}\n\nПозвони.`,
    );
  }

  revalidatePath("/together");
  return { ok: true };
}

/* ── Вопрос дня ────────────────────────────────────────────────── */

export async function answerDayAction(
  _prev: WishState,
  formData: FormData,
): Promise<WishState> {
  const who = await readSession();
  if (!who) return { error: "Сначала войди." };

  const text = String(formData.get("text") ?? "").trim().slice(0, 1200);
  if (text.length < 2) return { error: "Тут пусто." };

  const day = todayInHerCity();
  const ok = await answerDay(day, who, text);
  if (!ok) return { error: "Хранилище не подключено." };

  // Второму сообщаем только сам факт: текст он увидит на сайте,
  // и только после того, как ответит сам
  const answers = await answersOn(day);
  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  await notifyOther(
    who,
    answers.length >= 2
      ? `❓ ${from} ответила на вопрос дня. Теперь видно оба ответа.`
      : `❓ ${from} ответила на вопрос дня. Ответь — и увидишь её ответ.`,
  );

  revalidatePath("/together");
  return {};
}

/* ── Капсула времени ───────────────────────────────────────────── */

export async function sealCapsuleAction(
  _prev: WishState,
  formData: FormData,
): Promise<WishState> {
  const who = await readSession();
  if (!who) return { error: "Сначала войди." };

  const text = String(formData.get("text") ?? "").trim().slice(0, 4000);
  if (text.length < 10) return { error: "Слишком коротко для года ожидания." };

  // Открыть можно ровно через год от сегодняшнего дня
  const today = todayInHerCity();
  const openAt = `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`;

  const ok = await sealCapsule(who, text, openAt);
  if (!ok) return { error: "Хранилище не подключено." };

  const from = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  await notifyOther(who, `⏳ ${from} запечатала письмо в капсулу. Откроется ${openAt}.`);

  revalidatePath("/together");
  return {};
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
