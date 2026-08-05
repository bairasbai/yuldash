"use client";

import Link from "next/link";
import { motion } from "motion/react";
import { Lock } from "./Icons";

type Item = { n: number; dateLabel: string; locked: boolean };

/**
 * Архив. Открытые письма — светлые конверты, их можно взять в руки.
 * Ещё не наступившие — запертые: видно только дату, текста нет
 * даже в исходном коде страницы.
 */
export default function ArchiveGrid({ items }: { items: Item[] }) {
  return (
    <ul className="grid grid-cols-2 gap-3.5 sm:grid-cols-3">
      {items.map((item, i) => (
        <motion.li
          key={item.n}
          initial={{ opacity: 0, y: 18, filter: "blur(6px)" }}
          animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
          transition={{
            delay: 0.05 + i * 0.045,
            duration: 0.8,
            ease: [0.22, 1, 0.36, 1],
          }}
        >
          {item.locked ? (
            <div
              className="flex aspect-[7/5] flex-col items-center justify-center rounded-[14px] border border-panel-border bg-cream-deep"
              aria-label={`Письмо ${item.n} откроется ${item.dateLabel}`}
            >
              <Lock size={19} className="opacity-30" />
              <span className="mt-2.5 font-sans text-[0.6rem] uppercase tracking-[0.16em] text-sky-ink-soft/55">
                {item.dateLabel}
              </span>
            </div>
          ) : (
            <Link
              href={`/letters/${item.n}`}
              className="group relative flex aspect-[7/5] flex-col items-center justify-center overflow-hidden rounded-[14px] transition-transform duration-500 ease-[var(--ease-soft)] hover:-translate-y-1"
              style={{
                background:
                  "linear-gradient(160deg, var(--color-paper) 0%, var(--color-paper-deep) 100%)",
                boxShadow: "0 14px 26px -14px rgb(0 0 0 / 0.6)",
              }}
            >
              {/* Тот же диагональный сгиб, что и на большом конверте */}
              <span
                aria-hidden
                className="absolute inset-x-0 top-0 h-1/2 opacity-45"
                style={{
                  background:
                    "linear-gradient(185deg, #fffdf7 0%, var(--color-paper-deep) 100%)",
                  clipPath: "polygon(0 0, 100% 0, 50% 100%)",
                }}
              />
              <span className="relative font-serif text-[2rem] leading-none text-ink/75">
                {String(item.n).padStart(2, "0")}
              </span>
              <span className="relative mt-2 font-sans text-[0.58rem] uppercase tracking-[0.16em] text-ink-faint">
                {item.dateLabel}
              </span>
            </Link>
          )}
        </motion.li>
      ))}
    </ul>
  );
}
