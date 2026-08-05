"use client";

import { useMemo, useState } from "react";
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

export default function LetterSheet({
  letter,
  footer,
}: {
  letter: LetterSheetData;
  footer?: React.ReactNode;
}) {
  const [showTranslation, setShowTranslation] = useState(false);

  // Слова проявляются одной непрерывной волной через всё письмо,
  // а не заново в каждом абзаце — иначе ритм сбивается на каждой точке.
  const { wordOffsets, totalWords } = useMemo(() => {
    let acc = 0;
    const offsets = letter.body.map((p) => {
      const start = acc;
      acc += p.split(" ").length;
      return start;
    });
    return { wordOffsets: offsets, totalWords: acc };
  }, [letter.body]);

  const afterText = 0.42 + totalWords * 0.028;

  return (
    <motion.article
      initial={{ opacity: 0, y: 46, scale: 0.965, filter: "blur(10px)" }}
      animate={{ opacity: 1, y: 0, scale: 1, filter: "blur(0px)" }}
      transition={{ duration: 1.15, ease: [0.22, 1, 0.36, 1] }}
      className="paper-grain relative mx-auto w-full max-w-[34rem] overflow-hidden rounded-[var(--radius-card)]"
      style={{
        background:
          "linear-gradient(178deg, #fdf9f1 0%, var(--color-paper) 42%, var(--color-paper-deep) 100%)",
        boxShadow:
          "0 2px 4px rgb(0 0 0 / 0.22), 0 16px 32px -12px rgb(0 0 0 / 0.42), 0 46px 90px -34px rgb(0 0 0 / 0.62), inset 0 2px 0 rgb(255 255 255 / 0.6)",
      }}
    >
      {/*
        Следы сгибов. Письмо лежало в конверте сложенным втрое —
        значит на нём две складки. Деталь, которую не замечают,
        но без неё лист выглядит распечатанным на принтере.
      */}
      {[33.3, 66.6].map((top) => (
        <div
          key={top}
          aria-hidden
          className="pointer-events-none absolute inset-x-0 h-[2px]"
          style={{
            top: `${top}%`,
            background:
              "linear-gradient(to bottom, rgb(0 0 0 / 0.032) 0%, rgb(255 255 255 / 0.35) 100%)",
          }}
        />
      ))}

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

        {/* Текст письма — проявляется слово за словом */}
        <div className="space-y-5">
          {letter.body.map((paragraph, i) => {
            const startAt = wordOffsets[i];
            // У первого абзаца первая буква уходит в буквицу,
            // а всё остальное дальше идёт словами как обычно
            const text = i === 0 ? paragraph.slice(1) : paragraph;
            return (
              <p
                key={i}
                className={
                  i === 0
                    ? "font-serif text-[1.34rem] leading-[1.75] text-ink"
                    : "font-serif text-[1.2rem] leading-[1.85] text-ink/90"
                }
              >
                {i === 0 && (
                  <motion.span
                    className="dropcap-letter"
                    initial={{ opacity: 0, y: 7, filter: "blur(5px)" }}
                    animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
                    transition={{
                      delay: 0.42,
                      duration: 0.8,
                      ease: [0.22, 1, 0.36, 1],
                    }}
                  >
                    {paragraph.charAt(0)}
                  </motion.span>
                )}
                {text.split(" ").map((word, w) => (
                  <motion.span
                    key={w}
                    // inline-block, чтобы слово можно было двигать,
                    // и отступ справа вместо пробела — иначе строки
                    // перестают переноситься по словам
                    className="mr-[0.26em] inline-block"
                    initial={{ opacity: 0, y: 7, filter: "blur(5px)" }}
                    animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
                    transition={{
                      delay: 0.42 + (startAt + w) * 0.028,
                      duration: 0.62,
                      ease: [0.22, 1, 0.36, 1],
                    }}
                  >
                    {word}
                  </motion.span>
                ))}
              </p>
            );
          })}
        </div>

        {/* Строчка на башкирском — перевод открывается по нажатию */}
        {letter.ba && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: afterText + 0.25, duration: 0.9 }}
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
          transition={{ delay: afterText + 0.55, duration: 1 }}
          className="mt-10 text-right"
        >
          <span className="font-hand text-3xl text-ink/75">Байрас</span>
        </motion.div>

        {footer}
      </div>
    </motion.article>
  );
}
