"use client";

import { useEffect, useState } from "react";
import { motion } from "motion/react";
import type { Weather } from "@/lib/weather";
import { WeatherIcon } from "./Icons";

type Side = {
  city: string;
  timeZone: string;
  weather: Weather;
  who: string;
};

function useClock(timeZone: string, initial: string) {
  const [time, setTime] = useState(initial);

  useEffect(() => {
    const tick = () =>
      setTime(
        new Intl.DateTimeFormat("ru-RU", {
          timeZone,
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
        }).format(new Date()),
      );
    tick();
    const id = setInterval(tick, 15_000);
    return () => clearInterval(id);
  }, [timeZone]);

  return time;
}

function ClockCard({ side, initial }: { side: Side; initial: string }) {
  const time = useClock(side.timeZone, initial);

  return (
    <div className="flex-1 text-center">
      <p className="font-sans text-[0.6rem] uppercase tracking-[0.22em] text-sky-ink-soft/70">
        {side.who}
      </p>
      <p
        suppressHydrationWarning
        className="mt-2 font-serif text-[clamp(2.4rem,10vw,3.4rem)] font-light leading-none tabular-nums"
      >
        {time}
      </p>
      <p className="mt-2 font-sans text-[0.68rem] uppercase tracking-[0.18em] text-sky-ink-soft">
        {side.city}
      </p>
      {side.weather && (
        <p className="mt-3.5 flex items-center justify-center gap-2 font-sans text-[0.82rem] text-sky-ink-soft/85">
          <WeatherIcon kind={side.weather.kind} size={17} className="opacity-80" />
          <span className="tabular-nums">
            {side.weather.temp > 0 ? "+" : ""}
            {side.weather.temp}°
          </span>
          <span className="text-sky-ink-soft/45">·</span>
          <span>{side.weather.label}</span>
        </p>
      )}
    </div>
  );
}

/**
 * Двое часов рядом. Смысл не в том, чтобы узнать время,
 * а в том, чтобы видеть: у неё уже ночь, а у него ещё вечер.
 */
export default function TwoClocks({
  her,
  him,
  initialHer,
  initialHim,
}: {
  her: Side;
  him: Side;
  initialHer: string;
  initialHim: string;
}) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 1, ease: [0.22, 1, 0.36, 1] }}
      className="flex items-start gap-4 rounded-[var(--radius-card)] border border-panel-border bg-panel px-4 py-8 backdrop-blur-[2px]"
    >
      <ClockCard side={her} initial={initialHer} />
      <div className="w-px self-stretch bg-panel-border" />
      <ClockCard side={him} initial={initialHim} />
    </motion.section>
  );
}
