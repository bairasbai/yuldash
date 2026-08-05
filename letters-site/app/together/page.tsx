import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import SadDayCard from "@/components/SadDayCard";
import WishList from "@/components/WishList";
import MoodPicker from "@/components/MoodPicker";
import DayQuestion from "@/components/DayQuestion";
import { readSession } from "@/lib/session";
import { SAD_DAY_LETTER } from "@/data/special";
import { questionFor } from "@/data/questions";
import { answersOn, listWishes, moodHistory, moodOn } from "@/lib/store";
import { hasDatabase } from "@/lib/db";
import { todayInHerCity } from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function TogetherPage() {
  const me = await readSession();
  if (!me) redirect("/gate");

  const today = todayInHerCity();

  // Один заход в базу вместо четырёх по очереди
  const [wishes, mood, history, answers] = await Promise.all([
    listWishes(),
    moodOn(today, me),
    moodHistory(me),
    answersOn(today),
  ]);

  return (
    <PageShell title="Вместе" subtitle="пока мы в разных городах">
      <div className="space-y-8">

        <MoodPicker today={mood} history={history} />

        <DayQuestion question={questionFor(today)} answers={answers} me={me} />

        <WishList wishes={wishes} me={me} />

        <SadDayCard
          title={SAD_DAY_LETTER.title}
          body={SAD_DAY_LETTER.body}
          ba={SAD_DAY_LETTER.ba}
        />

        {/* Видно только Байрасу и только пока база не подключена */}
        {me === "him" && !hasDatabase() && (
          <p className="rounded-[var(--radius-card)] border border-[#e8a0a0]/30 bg-[#e8a0a0]/[0.07] px-5 py-4 font-sans text-sm leading-relaxed text-sky-ink-soft">
            Хранилище не подключено: настроение, вопрос дня и список Уфы
            пока некуда сохранять. Как подключишь базу — заработает само,
            шаги в README.
          </p>
        )}
      </div>
    </PageShell>
  );
}
