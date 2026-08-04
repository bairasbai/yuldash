"use client";

import { motion } from "motion/react";
import { plural } from "@/lib/time";

/**
 * Отсчёт. Главная цифра сайта — она должна читаться с порога,
 * ещё до того, как глаз зацепится за что-то ещё.
 */
export default function Countdown({
  days,
  meetLabel,
}: {
  days: number;
  meetLabel: string;
}) {
  const isToday = days === 0;

  return (
    <div className="text-center">
      <motion.p
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 1, delay: 0.15 }}
        className="font-sans text-[0.66rem] uppercase tracking-[0.34em] text-sky-ink-soft"
      >
        {isToday ? "сегодня" : "до встречи"}
      </motion.p>

      {isToday ? (
        <motion.h1
          initial={{ opacity: 0, filter: "blur(16px)", scale: 1.04 }}
          animate={{ opacity: 1, filter: "blur(0px)", scale: 1 }}
          transition={{ duration: 1.6, delay: 0.3, ease: [0.22, 1, 0.36, 1] }}
          className="mt-3 font-serif text-[clamp(3rem,13vw,5.5rem)] font-light leading-none tracking-tight"
          style={{ textShadow: "0 2px 30px rgb(0 0 0 / 0.35)" }}
        >
          я в Уфе
        </motion.h1>
      ) : (
        <div className="mt-2 flex items-baseline justify-center gap-4">
          <motion.span
            key={days}
            initial={{ opacity: 0, filter: "blur(18px)", y: 12 }}
            animate={{ opacity: 1, filter: "blur(0px)", y: 0 }}
            transition={{ duration: 1.5, delay: 0.28, ease: [0.22, 1, 0.36, 1] }}
            className="font-serif text-[clamp(5rem,22vw,9.5rem)] font-light leading-[0.85] tracking-tight"
            style={{ textShadow: "0 4px 40px rgb(0 0 0 / 0.4)" }}
          >
            {days}
          </motion.span>
          <motion.span
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 1.2, delay: 0.7 }}
            className="font-serif text-[clamp(1.1rem,4vw,1.6rem)] font-light text-sky-ink-soft"
          >
            {plural(days, "день", "дня", "дней")}
          </motion.span>
        </div>
      )}

      <motion.div
        initial={{ opacity: 0, scaleX: 0.3 }}
        animate={{ opacity: 1, scaleX: 1 }}
        transition={{ duration: 1.4, delay: 0.85, ease: [0.22, 1, 0.36, 1] }}
        className="hairline mx-auto mt-7 w-40"
      />

      <motion.p
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 1.2, delay: 1 }}
        className="mt-4 font-sans text-[0.7rem] uppercase tracking-[0.28em] text-sky-ink-soft/80"
      >
        {meetLabel}
      </motion.p>
    </div>
  );
}
