import Link from "next/link";
import { redirect } from "next/navigation";
import Hero from "@/components/Hero";
import TabBar from "@/components/TabBar";
import TodayLetter from "@/components/TodayLetter";
import { readSession } from "@/lib/session";
import { todaysLetter } from "@/lib/letters";
import { repliesFor } from "@/lib/store";
import { hasDatabase } from "@/lib/db";
import {
  activeParting,
  daysUntilMeetIn,
  letterNumberIn,
  listPartings,
  progressOf,
  totalOf,
  upcomingParting,
} from "@/lib/partings";
import { greetingIn, humanDate, plural, todayInHerCity } from "@/lib/time";
import { CONFIG } from "@/lib/config";

// Страница зависит от сегодняшней даты и от куки — кешировать её нельзя
export const dynamic = "force-dynamic";

export default async function Home() {
  const who = await readSession();
  if (!who) redirect("/gate");

  const today = todayInHerCity();
  const you = who === "her" ? CONFIG.her.name : CONFIG.him.name;
  const greeting = greetingIn(
    who === "her" ? CONFIG.her.timeZone : CONFIG.him.timeZone,
  );

  /*
    Без хранилища писем негде взять. Показывать в этом случае
    «мы вместе» нельзя — это была бы неправда: сайт не знает,
    вместе вы или нет, он просто ничего не помнит.
  */
  if (!hasDatabase()) {
    return (
      <>
        <Hero progress={1}>
          <h1
            className="rise font-serif text-[clamp(2rem,8vw,2.9rem)] leading-[1.15] text-white"
            style={{ textShadow: "0 2px 24px rgb(0 0 0 / 0.35)" }}
          >
            {greeting},
            <br />
            {you}
          </h1>
        </Hero>

        <main className="relative mx-auto w-full max-w-[38rem] px-5 pb-32 pt-4">
          <div className="card px-6 py-9 text-center">
            <p className="font-serif text-[1.4rem] leading-relaxed text-sky-ink">
              Сайт ещё просыпается.
            </p>
            <p className="mt-3 font-sans text-sm leading-relaxed text-sky-ink-soft">
              Письма скоро будут здесь. Загляни чуть позже.
            </p>
          </div>

          {who === "him" && (
            <p className="card mt-5 px-5 py-5 font-sans text-sm leading-relaxed text-[#c2695c]">
              Хранилище не подключено — письма, разлуки и ответы негде
              хранить, и утренняя доставка тоже не поедет. Шаги
              подключения в README, это две кнопки в Vercel.
            </p>
          )}
        </main>

        <TabBar />
      </>
    );
  }

  const parting = await activeParting(today);

  /* ── Вы вместе: разлука не идёт ──────────────────────────────── */
  if (!parting) {
    const [next, all] = await Promise.all([
      upcomingParting(today),
      listPartings(),
    ]);
    const written = all.length;

    return (
      <>
        <Hero progress={1}>
          <p className="rise font-sans text-[0.68rem] uppercase tracking-[0.22em] text-white/70">
            {CONFIG.her.city}
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
            className="rise mt-5 font-serif text-[1.15rem] text-white/90"
            style={{ textShadow: "0 1px 14px rgb(0 0 0 / 0.45)" }}
          >
            {next
              ? `врозь снова с ${humanDate(next.start_date)}`
              : "сейчас мы вместе"}
          </p>
        </Hero>

        <main className="relative mx-auto w-full max-w-[38rem] px-5 pb-32 pt-4">
          <div className="card px-6 py-9 text-center">
            <p className="font-serif text-[1.5rem] leading-relaxed text-sky-ink">
              Писем сегодня нет — и это лучшая из причин.
            </p>
            <p className="mt-3 font-sans text-sm leading-relaxed text-sky-ink-soft">
              {next
                ? `Следующие начнутся ${humanDate(next.start_date)}, когда мы снова разъедемся.`
                : "Пока мы в одном городе, сайт становится архивом. Всё, что было написано, никуда не делось."}
            </p>

            <Link
              href="/letters"
              className="mt-6 inline-block rounded-full px-6 py-3 font-sans text-[0.66rem] uppercase tracking-[0.18em] text-white"
              style={{
                background:
                  "linear-gradient(160deg, #e08c76 0%, var(--color-coral) 100%)",
              }}
            >
              перечитать письма
            </Link>
          </div>

          {who === "him" && (
            <div className="card mt-5 px-5 py-6">
              <p className="eyebrow">когда снова разъедетесь</p>
              <p className="mt-2 font-serif text-[1.15rem] leading-relaxed text-sky-ink">
                Заведи новую разлуку — сайт снова начнёт отсчёт и утренние
                письма.
              </p>
              <Link
                href="/him"
                className="mt-4 inline-block font-sans text-[0.66rem] uppercase tracking-[0.18em] text-[var(--color-coral)]"
              >
                на кухню →
              </Link>
              {written > 0 && (
                <p className="mt-3 font-sans text-xs text-sky-ink-soft">
                  Уже прожито разлук: {written}.
                </p>
              )}
            </div>
          )}

          {who === "him" && !hasDatabase() && (
            <p className="card mt-5 px-5 py-4 font-sans text-sm leading-relaxed text-[#c2695c]">
              Хранилище не подключено — разлуки и письма негде хранить.
              Шаги в README.
            </p>
          )}
        </main>

        <TabBar />
      </>
    );
  }

  /* ── Разлука идёт: отсчёт и письмо дня ───────────────────────── */
  const days = daysUntilMeetIn(parting, today);
  const n = letterNumberIn(parting, today);
  const total = totalOf(parting);
  const letter = await todaysLetter(parting, today);
  const replies = letter ? await repliesFor(parting.id, letter.n) : [];

  return (
    <>
      <Hero progress={progressOf(parting, today)}>
        <p className="rise font-sans text-[0.68rem] uppercase tracking-[0.22em] text-white/70">
          {humanDate(parting.meet_date)} · {CONFIG.her.city}
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

      <main className="relative mx-auto w-full max-w-[38rem] px-5 pb-32 pt-4">
        {n >= 1 && n <= total && letter && (
          <TodayLetter
            letter={letter}
            partingId={parting.id}
            replies={replies}
          />
        )}

        {n >= 1 && n <= total && !letter && (
          <p className="card px-6 py-8 text-center font-serif text-lg leading-relaxed text-sky-ink-soft">
            Сегодняшнее письмо ещё в пути. Загляни чуть позже.
          </p>
        )}
      </main>

      <TabBar />
    </>
  );
}
