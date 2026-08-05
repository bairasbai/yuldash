import Link from "next/link";
import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import TestDelivery from "@/components/TestDelivery";
import PartingForm from "@/components/PartingForm";
import LetterEditor from "@/components/LetterEditor";
import { readSession } from "@/lib/session";
import { writingStatus } from "@/lib/letters";
import { latestReplies, moodHistory } from "@/lib/store";
import { hasDatabase } from "@/lib/db";
import { moodByValue } from "@/data/moods";
import { CONFIG } from "@/lib/config";
import {
  activeParting,
  daysUntilMeetIn,
  letterNumberIn,
  listPartings,
  partingLabel,
  upcomingParting,
  type Parting,
} from "@/lib/partings";
import { humanDate, todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Кухня. Здесь Байрас заводит разлуки и пишет письма.
 * Илиза сюда не попадёт: её код открывает сессию «her».
 */
export default async function HimPage({
  searchParams,
}: {
  searchParams: Promise<{ p?: string }>;
}) {
  const who = await readSession();
  if (!who) redirect("/gate");
  if (who !== "him") redirect("/");

  const today = todayInHerCity();
  const { p } = await searchParams;

  const [all, active, next, replies, herMoods] = await Promise.all([
    listPartings(),
    activeParting(today),
    upcomingParting(today),
    latestReplies(8),
    moodHistory("her", 14),
  ]);

  // Правим ту разлуку, что выбрана, иначе идущую, иначе ближайшую
  const chosen: Parting | null =
    all.find((x) => String(x.id) === p) ?? active ?? next ?? all[0] ?? null;

  const letters = chosen ? await writingStatus(chosen) : [];
  const n = active ? letterNumberIn(active, today) : 0;
  const notReady = chosen
    ? letters.filter((l) => !l.ready && (!active || l.n >= n))
    : [];
  const nextGap = notReady[0];

  return (
    <PageShell
      title="Кухня"
      subtitle={
        active
          ? `сегодня письмо ${n} · до встречи ${daysUntilMeetIn(active, today)}`
          : next
            ? `врозь с ${humanDate(next.start_date)}`
            : "сейчас вы вместе"
      }
    >
      <div className="space-y-8">
        {/* Главное: где кончаются написанные письма */}
        {chosen && (
          <section
            className="rounded-[var(--radius-card)] border px-5 py-6"
            style={{
              borderColor: nextGap
                ? "rgb(194 105 92 / 0.3)"
                : "var(--panel-border)",
              background: nextGap ? "rgb(194 105 92 / 0.06)" : "var(--panel-bg)",
              boxShadow: nextGap ? "none" : "var(--panel-shadow)",
            }}
          >
            {nextGap ? (
              <>
                <p className="font-serif text-xl leading-relaxed text-sky-ink">
                  Письмо {nextGap.n} на {nextGap.dateLabel} ещё не написано.
                </p>
                <p className="mt-2 font-sans text-sm text-sky-ink-soft">
                  В это утро {CONFIG.her.name}е не уйдёт ничего, а тебе
                  прилетит напоминание. Всего не готово: {notReady.length}.
                </p>
              </>
            ) : (
              <p className="font-serif text-xl text-sky-ink">
                Все письма впереди написаны.
              </p>
            )}
          </section>
        )}

        <PartingForm />

        {/* Переключатель разлук */}
        {all.length > 1 && (
          <div className="flex gap-2 overflow-x-auto pb-1">
            {all.map((x) => {
              const isChosen = x.id === chosen?.id;
              return (
                <Link
                  key={x.id}
                  href={`/him?p=${x.id}`}
                  className="shrink-0 rounded-full border px-4 py-2 font-sans text-[0.66rem] transition-colors"
                  style={{
                    borderColor: isChosen
                      ? "var(--color-coral)"
                      : "var(--panel-border)",
                    background: isChosen ? "rgb(212 121 106 / 0.1)" : "#fff",
                    color: isChosen
                      ? "var(--color-coral)"
                      : "var(--color-sky-ink-soft)",
                  }}
                >
                  {partingLabel(x)}
                </Link>
              );
            })}
          </div>
        )}

        {chosen && (
          <section>
            <h2 className="eyebrow mb-4 block">
              письма · {partingLabel(chosen)}
            </h2>
            <LetterEditor partingId={chosen.id} letters={letters} />
          </section>
        )}

        <section className="card px-5 py-6">
          <h2 className="eyebrow mb-4 block">связь</h2>
          <TestDelivery />
          {!hasDatabase() && (
            <p className="mt-4 font-sans text-sm leading-relaxed text-[#c2695c]">
              Хранилище не подключено — разлуки, письма и ответы негде
              хранить. Шаги подключения в README.
            </p>
          )}
        </section>

        {herMoods.length > 0 && (
          <section className="card px-5 py-6">
            <h2 className="eyebrow mb-4 block">
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
            <h2 className="eyebrow mb-4 block">последние ответы</h2>
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
      </div>
    </PageShell>
  );
}
