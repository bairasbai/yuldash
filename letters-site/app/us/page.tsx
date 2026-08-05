import { redirect } from "next/navigation";
import PageShell from "@/components/PageShell";
import TwoClocks from "@/components/TwoClocks";
import DistanceMap from "@/components/DistanceMap";
import Timeline from "@/components/Timeline";
import Playlist from "@/components/Playlist";
import Capsule from "@/components/Capsule";
import { readSession } from "@/lib/session";
import { weatherIn } from "@/lib/weather";
import { getCapsule } from "@/lib/store";
import { TIMELINE } from "@/data/timeline";
import { PLAYLIST } from "@/data/playlist";
import { CONFIG } from "@/lib/config";
import {
  clockIn,
  dawnProgress,
  daysTogether,
  plural,
  todayInHerCity,
} from "@/lib/time";

export const dynamic = "force-dynamic";

export default async function UsPage() {
  const me = await readSession();
  if (!me) redirect("/gate");

  const today = todayInHerCity();
  const progress = dawnProgress(today);
  const together = daysTogether(today);

  // Всё внешнее тянем разом, чтобы страница не ждала по очереди
  const [herWeather, hisWeather, capsule] = await Promise.all([
    weatherIn("ufa"),
    weatherIn("moscow"),
    getCapsule(me),
  ]);

  return (
    <PageShell
      title="Мы"
      subtitle={`${together} ${plural(together, "день", "дня", "дней")} с того рассвета`}
    >
      <div className="space-y-8">
        <TwoClocks
          her={{
            who: "у тебя",
            city: CONFIG.her.city,
            timeZone: CONFIG.her.timeZone,
            weather: herWeather,
          }}
          him={{
            who: "у меня",
            city: CONFIG.him.city,
            timeZone: CONFIG.him.timeZone,
            weather: hisWeather,
          }}
          initialHer={clockIn(CONFIG.her.timeZone)}
          initialHim={clockIn(CONFIG.him.timeZone)}
        />

        <DistanceMap
          progress={progress}
          km={CONFIG.distanceKm}
          fromCity={CONFIG.him.city}
          toCity={CONFIG.her.city}
        />

        <Playlist songs={PLAYLIST} />

        <Capsule
          mine={capsule}
          canOpen={Boolean(capsule && today >= capsule.open_at)}
          today={today}
        />

        <section className="pt-4">
          <h2 className="mb-8 text-center font-sans text-[0.66rem] uppercase tracking-[0.26em] text-sky-ink-soft">
            как это было
          </h2>
          <Timeline moments={TIMELINE} />
        </section>
      </div>
    </PageShell>
  );
}
