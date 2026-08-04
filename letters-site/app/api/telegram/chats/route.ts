import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

/**
 * Помощник для настройки: показывает, кто написал боту и какой у него
 * chat_id. Нужен один раз — чтобы вписать эти номера в переменные
 * TELEGRAM_CHAT_ID_HER и TELEGRAM_CHAT_ID_HIM.
 *
 * Закрыт тем же ключом, что и доставка: адрес не должен выдавать
 * посторонним, кто пишет боту.
 */
export async function GET(req: Request) {
  const secret = process.env.CRON_SECRET;
  const url = new URL(req.url);
  const given =
    url.searchParams.get("key") ??
    req.headers.get("authorization")?.replace("Bearer ", "");

  if (!secret || given !== secret) {
    return NextResponse.json({ error: "не тот ключ" }, { status: 401 });
  }

  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token) {
    return NextResponse.json({ error: "нет TELEGRAM_BOT_TOKEN" }, { status: 400 });
  }

  const res = await fetch(`https://api.telegram.org/bot${token}/getUpdates`);
  const data = (await res.json()) as {
    ok: boolean;
    result?: { message?: { chat?: { id: number; first_name?: string; username?: string } } }[];
  };

  if (!data.ok) {
    return NextResponse.json({ error: "телеграм не ответил" }, { status: 502 });
  }

  const seen = new Map<number, { id: number; name: string; username?: string }>();
  for (const update of data.result ?? []) {
    const chat = update.message?.chat;
    if (chat) {
      seen.set(chat.id, {
        id: chat.id,
        name: chat.first_name ?? "без имени",
        username: chat.username,
      });
    }
  }

  return NextResponse.json({
    подсказка:
      "Напиши боту «привет» с обоих телефонов и обнови страницу — увидишь оба номера.",
    чаты: [...seen.values()],
  });
}
