import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import ArchiveGrid from "@/components/ArchiveGrid";
import { readSession } from "@/lib/session";
import { archive, openedCount } from "@/lib/letters";
import { plural, todayInHerCity, totalLetters } from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function LettersPage() {
  if (!(await readSession())) redirect("/gate");

  const today = todayInHerCity();
  const items = archive(today).map((l) => ({
    n: l.n,
    dateLabel: l.dateLabel,
    locked: l.locked,
  }));

  const opened = openedCount(today);
  const total = totalLetters();

  return (
    <PageShell
      title="Архив"
      subtitle={`${opened} ${plural(opened, "письмо", "письма", "писем")} из ${total}`}
    >
      <ArchiveGrid items={items} />

      <p className="mt-12 text-center font-serif text-lg italic leading-relaxed text-sky-ink-soft/80">
        Запертые откроются в свой день. Раньше — никак, я проверял.
      </p>
    </PageShell>
  );
}
