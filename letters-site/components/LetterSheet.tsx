"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";

/**
 * Сам лист письма. Разворачивается после вскрытия конверта,
 * текст проявляется абзац за абзацем — как будто его пишут при тебе.
 */

export type LetterSheetData = {
  n: number;
  dateLabel: string;
  body: string[];
  ba?: { text: string; ru: string };
};

const PAPER_TEXTURE =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='200'%3E%3Cfilter id='p'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4'/%3E%3C/filter%3E%3Crect width='200' height='200' filter='url(%23p)'/%3E%3C/svg%3E\")";

export default function LetterSheet({
  letter,
  footer,
}: {
  letter: LetterSheetData;
  footer?: React.ReactNode;
}) {
  const [showTranslation, setShowTranslation] = useState(false);

  return (
    <motion.article
      initial={{ opacity: 0, y: 46, scale: 0.965, filter: "blur(10px)" }}
      animate={{ opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }}
      transition={{ duration: 1.15, ease: [0.22, 1, 0.36, 1] }}
      className="relative mx-auto w-full max-w-[34rem] overflow-hidden rounded-[var(--radius-card)]"
      style={{
        background:
          "linear-gradient(178deg, #fdf9f1 0%, var(--color-paper) 42%, var(--color-paper-deep) 100%)",
        boxShadow:
          "0 40px 80px -30px rgb(0 0 0 / 0.6), 0 2px 0 rgb(255 255 255 / 0.6) inset",
      }}
    >
      {/* Волокна бумаги — чтобы лист не выглядел пластиковым */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-[0.055] mix-blend-multiply"
        style={{ backgroundImage: PAPER_TEXTURE }}
      />

      <div className="relative px-7 py-10 sm:px-11 sm:py-12">
        {/* Шапка листа */}
        <motion.header
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.25, duration: 0.7 }}
          className="mb-8 flex items-baseline justify-between"
        >
          <span className="font-serif text-[2.6rem] leading-none text-ink/25">
            {String(letter.n).padStart(2, "0")}
          </span>
          <span className="font-sans text-[0.66rem] uppercase tracking-[0.24em] text-ink-faint">
            {letter.dateLabel}
          </span>
        </motion.header>

        {/* Текст письма */}
        <div className="space-y-5">
          {letter.body.map((paragraph, i) => (
            <motion.p
              key={i}
              initial={{ opacity: 0, y: 12, filter: "blur(4px)" }}
              animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
              transition={{
                delay: 0.45 + i * 0.28,
                duration: 0.9,
                ease: [0.22, 1, 0.36, 1],
              }}
              className={
                i === 0
                  ? "font-serif text-[1.42rem] leading-[1.72] text-ink"
                  : "font-serif text-[1.2rem] leading-[1.85] text-ink/90"
              }
            >
              {paragraph}
            </motion.p>
          ))}
        </div>

        {/* Строчка на башкирском — перевод открывается по нажатию */}
        {letter.ba && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.5 + letter.body.length * 0.28, duration: 0.9 }}
            className="mt-9"
          >
            <div
              className="mb-5 h-px w-full"
              style={{
                background:
                  "linear-gradient(to right, transparent, color-mix(in srgb, var(--color-gold) 55%, transparent), transparent)",
              }}
            />
            <button
              type="button"
              onClick={() => setShowTranslation((v) => !v)}
              className="group w-full text-left"
            >
              <p className="font-serif text-[1.24rem] italic leading-relaxed text-ink/85">
                {letter.ba.text}
              </p>
              <AnimatePresence initial={false}>
                {showTranslation ? (
                  <motion.p
                    key="ru"
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.45, ease: [0.22, 1, 0.36, 1] }}
                    className="overflow-hidden pt-2 font-sans text-sm text-ink-soft"
                  >
                    {letter.ba.ru}
                  </motion.p>
                ) : (
                  <motion.span
                    key="hint"
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                    className="mt-2 inline-block font-sans text-[0.62rem] uppercase tracking-[0.2em] text-ink-faint transition-colors group-hover:text-ink-soft"
                  >
                    перевод
                  </motion.span>
                )}
              </AnimatePresence>
            </button>
          </motion.div>
        )}

        {/* Подпись */}
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.8 + letter.body.length * 0.28, duration: 1 }}
          className="mt-10 text-right"
        >
          <span className="font-hand text-3xl text-ink/75">Байрас</span>
        </motion.div>

        {footer}
      </div>
    </motion.article>
  );
}
