import { NextResponse } from "next/server";
import { inviteHer, notifyHim } from "@/lib/telegram";
import { readLetter } from "@/lib/letters";
import {
  daysUntilMeeting,
  letterNumberToday,
  todayInHerCity,
  totalLetters,
} from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Утренняя доставка. Этот адрес дёргает расписание каждый день
 * в 03:00 UTC — это 08:00 в Уфе.
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
  const n = letterNumberToday(today);
  const total = totalLetters();

  if (n < 1) {
    return NextResponse.json({ skipped: "отсчёт ещё не начался", today });
  }
  if (n > total) {
    return NextResponse.json({ skipped: "письма кончились", today });
  }

  const letter = readLetter(n, today);
  if (!letter) {
    await notifyHim(
      `⚠️ Письмо №${n} на сегодня не готово — Илизе ничего не ушло.\n\nДопиши текст и поставь ready: true.`,
    );
    return NextResponse.json({ sent: false, reason: "текст не готов", n });
  }

  const result = await inviteHer(n, daysUntilMeeting(today));
  if (!result.ok) {
    await notifyHim(`⚠️ Письмо №${n} не доставлено: ${result.error}`);
    return NextResponse.json({ sent: false, error: result.error, n }, { status: 502 });
  }

  return NextResponse.json({ sent: true, n, today });
}
