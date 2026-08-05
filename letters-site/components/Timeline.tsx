"use client";

import { motion } from "motion/react";
import type { Moment } from "@/data/timeline";

/**
 * Хроника «мы». Смысл в том, чтобы ожидание не выглядело началом:
 * у вас уже есть история, встреча — просто её продолжение.
 */
export default function Timeline({ moments }: { moments: Moment[] }) {
  return (
    <ol
      className="relative ml-1 pl-7"
      style={{ borderLeft: "1px solid rgb(47 38 32 / 0.12)" }}
    >
      {moments.map((m, i) => (
        <motion.li
          key={m.title}
          initial={{ opacity: 0, x: -10 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{
            delay: i * 0.08,
            duration: 0.85,
            ease: [0.22, 1, 0.36, 1],
          }}
          className="relative pb-10 last:pb-0"
        >
          <span
            className="absolute -left-[35px] top-1.5 block h-2.5 w-2.5 rounded-full"
            style={
              m.accent
                ? {
                    background: "var(--color-coral)",
                    boxShadow: "0 0 0 4px rgb(212 121 106 / 0.16)",
                  }
                : { background: "var(--color-sky-ink-soft)", opacity: 0.35 }
            }
          />
          <p className="font-sans text-[0.6rem] uppercase tracking-[0.22em] text-sky-ink-soft/70">
            {m.when}
          </p>
          <h3 className="mt-1.5 font-serif text-2xl font-light">{m.title}</h3>
          <p className="mt-2 font-serif text-[1.05rem] leading-relaxed text-sky-ink-soft">
            {m.text}
          </p>
        </motion.li>
      ))}
    </ol>
  );
}
