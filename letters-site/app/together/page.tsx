import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import ThinkingButton from "@/components/ThinkingButton";
import SadDayCard from "@/components/SadDayCard";
import { readSession } from "@/lib/session";
import { SAD_DAY_LETTER } from "@/data/special";
import { todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function TogetherPage() {
  if (!(await readSession())) redirect("/gate");

  return (
    <PageShell
      title="Вместе"
      subtitle="пока мы в разных городах"
    >
      <div className="space-y-8">
        <ThinkingButton />

        <SadDayCard
          title={SAD_DAY_LETTER.title}
          body={SAD_DAY_LETTER.body}
          ba={SAD_DAY_LETTER.ba}
        />
      </div>
    </PageShell>
  );
}
