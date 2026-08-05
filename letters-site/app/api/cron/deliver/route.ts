import { NextResponse } from "next/server";
import { inviteHer, notifyHim } from "@/lib/telegram";
import { todaysLetter } from "@/lib/letters";
import { activeParting, daysUntilMeetIn, letterNumberIn } from "@/lib/partings";
import { todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Утренняя доставка. Этот адрес дёргает расписание каждый день
 * в 03:00 UTC — это 08:00 в Уфе.
 *
 * Письмо берётся из разлуки, которая идёт сейчас. Пока вы вместе,
 * бот молчит: писать не о чем.
 *
 * Если текста на сегодня нет, Илизе не уходит ничего, а Байрасу
 * прилетает предупреждение: лучше пустое утро, чем пустое письмо.
 */
export async function GET(req: Request) {
  const secret = process.env.CRON_SECRET;
  const auth = req.headers.get("authorization");
  if (!secret || auth !== `Bearer ${secret}`) {
    return NextResponse.json({ error: "не тот ключ" }, { status: 401 });
  }

  const today = todayInHerCity();
  const parting = await activeParting(today);

  if (!parting) {
    return NextResponse.json({ skipped: "сейчас вы вместе", today });
  }

  const n = letterNumberIn(parting, today);
  const letter = await todaysLetter(parting, today);

  if (!letter) {
    await notifyHim(
      `⚠️ Письмо №${n} на сегодня не готово — Илизе ничего не ушло.\n\nЗайди на «Кухню» и допиши.`,
    );
    return NextResponse.json({ sent: false, reason: "текст не готов", n });
  }

  const result = await inviteHer(n, daysUntilMeetIn(parting, today));
  if (!result.ok) {
    await notifyHim(`⚠️ Письмо №${n} не доставлено: ${result.error}`);
    return NextResponse.json(
      { sent: false, error: result.error, n },
      { status: 502 },
    );
  }

  return NextResponse.json({ sent: true, n, parting: parting.id, today });
}
