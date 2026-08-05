import { redirect } from "next/navigation";
import Hero from "@/components/Hero";
import TabBar from "@/components/TabBar";
import TodayLetter from "@/components/TodayLetter";
import { readSession } from "@/lib/session";
import { todaysLetter } from "@/lib/letters";
import { repliesFor } from "@/lib/store";
import {
  dawnProgress,
  daysUntilMeeting,
  greetingIn,
  humanDate,
  letterNumberToday,
  plural,
  todayInHerCity,
  totalLetters,
} from "@/lib/time";
import { CONFIG } from "@/lib/config";

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

  // Обращение — к тому, кто смотрит, и по его времени суток
  const you = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  const greeting = greetingIn(
    who === "her" ? CONFIG.her.timeZone : CONFIG.him.timeZone,
  );

  return (
    <>
      <Hero progress={dawnProgress(today)}>
        <p className="rise font-sans text-[0.68rem] uppercase tracking-[0.22em] text-white/70">
          {humanDate(CONFIG.meetDate)} · {CONFIG.her.city}
        </p>
        <h1
          className="rise mt-3 font-serif text-[clamp(2rem,8vw,2.9rem)] leading-[1.15] text-white"
          style={{ textShadow: "0 2px 24px rgb(0 0 0 / 0.35)" }}
        >
          {greeting},
          <br />
          {you}
        </h1>

        <p
          className="rise mt-5 flex items-baseline gap-2 text-white"
          style={{ textShadow: "0 1px 14px rgb(0 0 0 / 0.45)" }}
        >
          {days === 0 ? (
            <span className="font-serif text-[1.2rem]">
              сегодня мы наконец увидимся
            </span>
          ) : (
            <>
              <span className="font-sans text-[0.68rem] uppercase tracking-[0.2em] text-white/75">
                до встречи
              </span>
              <span className="font-serif text-[2rem] leading-none tabular-nums">
                {days}
              </span>
              <span className="font-serif text-[1.05rem] text-white/85">
                {plural(days, "день", "дня", "дней")}
              </span>
            </>
          )}
        </p>
      </Hero>

      <main className="relative mx-auto w-full max-w-[38rem] px-5 pb-32">
        {n < 1 && (
          <p className="card px-6 py-8 text-center font-serif text-lg leading-relaxed text-sky-ink-soft">
            Первое письмо придёт {humanDate(CONFIG.startDate)}, утром.
          </p>
        )}

        {n >= 1 && n <= total && letter && (
          <TodayLetter letter={letter} replies={replies} />
        )}

        {n >= 1 && n <= total && !letter && (
          <p className="card px-6 py-8 text-center font-serif text-lg leading-relaxed text-sky-ink-soft">
            Сегодняшнее письмо ещё в пути. Загляни чуть позже.
          </p>
        )}

        {n > total && (
          <div className="card px-6 py-9 text-center">
            <p className="font-serif text-2xl leading-relaxed text-sky-ink">
              Письма кончились, потому что кончилось ожидание.
            </p>
            <p className="mt-3 font-sans text-sm text-sky-ink-soft">
              Все {total} остались в архиве — они теперь наши.
            </p>
          </div>
        )}
      </main>

      <TabBar />
    </>
  );
}
