import Link from "next/link";
import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import TestDelivery from "@/components/TestDelivery";
import { readSession } from "@/lib/session";
import { writingStatus } from "@/lib/letters";
import { latestReplies, moodHistory } from "@/lib/store";
import { hasDatabase } from "@/lib/db";
import { moodByValue } from "@/data/moods";
import { CONFIG } from "@/lib/config";
import {
  daysUntilMeeting,
  letterNumberToday,
  todayInHerCity,
} from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Служебная страница Байраса. Илиза сюда не попадёт: её код
 * открывает сессию «her», а тут нужна «him».
 */
export default async function HimPage() {
  const who = await readSession();
  if (!who) redirect("/gate");
  if (who !== "him") redirect("/");

  const today = todayInHerCity();
  const n = letterNumberToday(today);
  const letters = writingStatus();
  const notReady = letters.filter((l) => !l.ready && l.n >= n);
  const nextGap = notReady.find((l) => l.n >= n);

  const [replies, herMoods] = await Promise.all([
    latestReplies(8),
    moodHistory("her", 14),
  ]);

  return (
    <PageShell
      title="Кухня"
      subtitle={`сегодня письмо ${n} · до встречи ${daysUntilMeeting(today)}`}
    >
      <div className="space-y-8">
        {/* Главное, что тут нужно знать: где кончаются написанные письма */}
        <section
          className="rounded-[var(--radius-card)] border px-5 py-6"
          style={{
            borderColor: nextGap
              ? "rgb(232 160 160 / 0.35)"
              : "var(--panel-border)",
            background: nextGap ? "rgb(232 160 160 / 0.07)" : "var(--panel-bg)",
          }}
        >
          {nextGap ? (
            <>
              <p className="font-serif text-xl leading-relaxed">
                Письмо {nextGap.n} на {nextGap.dateLabel} ещё не написано.
              </p>
              <p className="mt-2 font-sans text-sm text-sky-ink-soft">
                В это утро Илизе не уйдёт ничего, а тебе прилетит напоминание.
                Всего не готово: {notReady.length}.
              </p>
            </>
          ) : (
            <p className="font-serif text-xl">Все письма впереди написаны.</p>
          )}
        </section>

        <section className="card px-5 py-6">
          <h2 className="mb-4 font-sans text-[0.62rem] uppercase tracking-[0.22em] text-sky-ink-soft">
            связь
          </h2>
          <TestDelivery />
          {!hasDatabase() && (
            <p className="mt-4 font-sans text-sm leading-relaxed text-[#e8a0a0]/85">
              Хранилище не подключено — ответы, настроения и список Уфы
              сохранять некуда. Письма и утренняя доставка от него не зависят
              и работают. Шаги подключения в README.
            </p>
          )}
        </section>

        {/* Как у неё дела — не спрашивая */}
        {herMoods.length > 0 && (
          <section className="card px-5 py-6">
            <h2 className="mb-4 font-sans text-[0.62rem] uppercase tracking-[0.22em] text-sky-ink-soft">
              настроение {CONFIG.her.nameOf.toLowerCase()}
            </h2>
            <ul className="space-y-2.5">
              {[...herMoods].reverse().map((m) => {
                const option = moodByValue(m.value);
                return (
                  <li key={m.day} className="flex items-baseline gap-3">
                    <span
                      className="mt-1 block h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{ background: option?.color ?? "#888" }}
                    />
                    <span className="w-20 shrink-0 font-sans text-[0.62rem] uppercase tracking-[0.14em] text-sky-ink-soft/70">
                      {m.day.slice(8)}.{m.day.slice(5, 7)}
                    </span>
                    <span className="font-sans text-sm text-sky-ink/90">
                      {option?.label ?? m.value}
                      {m.note && (
                        <span className="text-sky-ink-soft"> — {m.note}</span>
                      )}
                    </span>
                  </li>
                );
              })}
            </ul>
          </section>
        )}

        {replies.length > 0 && (
          <section className="card px-5 py-6">
            <h2 className="mb-4 font-sans text-[0.62rem] uppercase tracking-[0.22em] text-sky-ink-soft">
              последние ответы
            </h2>
            <ul className="space-y-4">
              {replies.map((r) => (
                <li key={r.id}>
                  <p className="mb-1 font-sans text-[0.6rem] uppercase tracking-[0.14em] text-sky-ink-soft/60">
                    письмо {r.letter_n} · {r.created_at} ·{" "}
                    {r.author === "her" ? CONFIG.her.name : CONFIG.him.name}
                  </p>
                  <p className="whitespace-pre-line font-serif text-[1.02rem] leading-relaxed text-sky-ink/90">
                    {r.text}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        )}

        <section>
          <h2 className="mb-4 font-sans text-[0.62rem] uppercase tracking-[0.22em] text-sky-ink-soft">
            все письма
          </h2>
          <ul className="divide-y divide-panel-border overflow-hidden card">
            {letters.map((l) => (
              <li key={l.n} className="flex items-center gap-3 px-4 py-3.5">
                <span className="w-7 shrink-0 font-serif text-lg text-sky-ink-soft">
                  {String(l.n).padStart(2, "0")}
                </span>
                <span className="w-24 shrink-0 font-sans text-[0.62rem] uppercase tracking-[0.14em] text-sky-ink-soft/70">
                  {l.dateLabel}
                </span>
                <span className="flex-1 truncate font-sans text-sm text-sky-ink/90">
                  {l.topic}
                </span>
                {l.ready ? (
                  <Link
                    href={`/him/preview/${l.n}`}
                    className="shrink-0 font-sans text-[0.6rem] uppercase tracking-[0.16em] text-sky-ink-soft transition-colors hover:text-sky-ink"
                  >
                    смотреть
                  </Link>
                ) : (
                  <span className="shrink-0 font-sans text-[0.6rem] uppercase tracking-[0.16em] text-[#e8a0a0]/75">
                    пусто
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      </div>
    </PageShell>
  );
}
