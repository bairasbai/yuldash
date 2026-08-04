"use server";

import { redirect } from "next/navigation";
import { createSession, whoseCode } from "@/lib/session";

export type GateState = { error: string | null };

/**
 * Проверка кодового слова.
 *
 * Пауза перед ответом — намеренная: она делает перебор кодов
 * бессмысленно медленным, а живому человеку незаметна.
 */
export async function enter(
  _prev: GateState,
  formData: FormData,
): Promise<GateState> {
  const code = String(formData.get("code") ?? "");
  const who = whoseCode(code);

  await new Promise((r) => setTimeout(r, 600));

  if (!who) return { error: "Не то. Попробуй ещё раз." };

  await createSession(who);
  redirect("/");
}
