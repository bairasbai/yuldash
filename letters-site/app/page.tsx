import { redirect } from "next/navigation";
import Link from "next/link";
import Countdown from "@/components/Countdown";
import TodayLetter from "@/components/TodayLetter";
import { readSession } from "@/lib/session";
import { todaysLetter } from "@/lib/letters";
import {
  daysUntilMeeting,
  humanDate,
  letterNumberToday,
  todayInHerCity,
  totalLetters,
} from "@/lib/time";
import { CONFIG } from "@/lib/config";
import { repliesFor } from "@/lib/store";

// Страница зависит от сегодняшней даты и от куки — кешировать её нельзя
export const dynamic = "force-dynamic";

export default async function Home() {
  const who = await readSession();
  if (!who) redirect("/gate");

  const today = todayInHerCity();
  const days = daysUntilMeeting(today);
  const n = letterNumberToday(today);
  const total = totalLetters();
  const letter = todaysLetter(today);
  const replies = letter ? await repliesFor(letter.n) : [];

  return (
    <>
      <main className="relative flex min-h-dvh flex-col items-center px-5 pb-[max(2rem,env(safe-area-inset-bottom))] pt-[max(2.5rem,env(safe-area-inset-top))]">
        <div className="flex w-full max-w-[34rem] flex-1 flex-col items-center justify-center gap-14 py-10">
          <Countdown
            days={days}
            meetLabel={`${humanDate(CONFIG.meetDate)} · ${CONFIG.her.city}`}
          />
          {n < 1 && (
            <p className="rise max-w-xs text-center font-serif text-xl leading-relaxed text-sky-ink-soft">
              Первое письмо придёт {humanDate(CONFIG.startDate)}, утром.
            </p>
          )}
          {n >= 1 && n <= total && letter && (
            <TodayLetter letter={letter} replies={replies} />
          )}
          {n >= 1 && n <= total && !letter && (
            <p className="rise max-w-xs text-center font-serif text-xl leading-relaxed text-sky-ink-soft">
              Сегодняшнее письмо ещё в пути. Загляни чуть позже.
            </p>
          )}
          {n > total && (
            <p className="rise max-w-sm text-center font-serif text-2xl leading-relaxed">
              Письма кончились, потому что кончилось ожидание.
              <span className="mt-3 block font-sans text-sm tracking-wide text-sky-ink-soft">
                Все {total} остались в архиве — они теперь наши.
              </span>
            </p>
          )}
        </div>
        <nav className="flex items-center gap-5 font-sans text-[0.68rem] uppercase tracking-[0.22em] text-sky-ink-soft">
          <Link href="/letters" className="transition-colors hover:text-sky-ink">
            архив
          </Link>
          <span className="text-sky-ink-soft/35">·</span>
          <Link href="/us" className="transition-colors hover:text-sky-ink">
            мы
          </Link>
          <span className="text-sky-ink-soft/35">·</span>
          <Link href="/together" className="transition-colors hover:text-sky-ink">
            вместе
          </Link>
        </nav>
      </main>
    </>
  );
}
