import Link from "next/link";
import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import ArchiveGrid from "@/components/ArchiveGrid";
import { readSession } from "@/lib/session";
import { archive, openedCount } from "@/lib/letters";
import {
  activeParting,
  listPartings,
  partingLabel,
  totalOf,
  type Parting,
} from "@/lib/partings";
import { plural, todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

/**
 * Архив. Пока разлука идёт — показывает её. Когда вы вместе —
 * последнюю прожитую, а остальные лежат рядом переключателем.
 */
export default async function LettersPage({
  searchParams,
}: {
  searchParams: Promise<{ p?: string }>;
}) {
  if (!(await readSession())) redirect("/gate");

  const today = todayInHerCity();
  const { p } = await searchParams;

  const all = await listPartings();
  if (all.length === 0) {
    return (
      <PageShell title="Архив" subtitle="пока пусто">
        <p className="card px-6 py-8 text-center font-serif text-lg leading-relaxed text-sky-ink-soft">
          Здесь появятся письма, как только начнётся первая разлука.
        </p>
      </PageShell>
    );
  }

  /*
    Что показываем: выбранную, идущую сейчас, иначе последнюю начатую.
    Будущую разлуку по умолчанию открывать нельзя — в ней ещё нечего
    читать, а прожитые письма лежат в предыдущей.
  */
  const started = all.filter((x) => x.start_date <= today);
  const chosen: Parting =
    all.find((x) => String(x.id) === p) ??
    (await activeParting(today)) ??
    started[0] ??
    all[0];

  const items = (await archive(chosen, today)).map((l) => ({
    n: l.n,
    dateLabel: l.dateLabel,
    locked: l.locked,
  }));

  const opened = await openedCount(chosen, today);
  const total = totalOf(chosen);

  return (
    <PageShell
      title="Архив"
      subtitle={`${opened} ${plural(opened, "письмо", "письма", "писем")} из ${total}`}
    >
      {all.length > 1 && (
        <div className="mb-6 flex gap-2 overflow-x-auto pb-1">
          {all.map((x) => {
            const active = x.id === chosen.id;
            return (
              <Link
                key={x.id}
                href={`/letters?p=${x.id}`}
                className="shrink-0 rounded-full border px-4 py-2 font-sans text-[0.66rem] transition-colors"
                style={{
                  borderColor: active
                    ? "var(--color-coral)"
                    : "var(--panel-border)",
                  background: active ? "rgb(212 121 106 / 0.1)" : "#fff",
                  color: active
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

      <ArchiveGrid items={items} partingId={chosen.id} />

      <p className="mt-12 text-center font-serif text-lg italic leading-relaxed text-sky-ink-soft">
        Запертые откроются в свой день. Раньше — никак, я проверял.
      </p>
    </PageShell>
  );
}
