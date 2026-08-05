"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { openedSadLetter } from "@/app/actions";
import { Candle } from "./Icons";

/**
 * Запасной конверт вне графика. Открывается когда угодно — но однажды.
 * Как только она его вскрыла, Байрасу уходит сигнал: день не задался.
 */
export default function SadDayCard({
  title,
  body,
  ba,
}: {
  title: string;
  body: string[];
  ba?: { text: string; ru: string };
}) {
  const [open, setOpen] = useState(false);
  const [ru, setRu] = useState(false);

  useEffect(() => {
    try {
      if (localStorage.getItem("sad-opened")) setOpen(true);
    } catch {
      // приватный режим — просто останется закрытым
    }
  }, []);

  const unseal = async () => {
    setOpen(true);
    try {
      localStorage.setItem("sad-opened", "1");
    } catch {
      // не критично
    }
    await openedSadLetter();
  };

  return (
    <section className="overflow-hidden card">
      <AnimatePresence mode="wait" initial={false}>
        {!open ? (
          <motion.button
            key="sealed"
            type="button"
            onClick={unseal}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="flex w-full flex-col items-center px-5 py-11"
          >
            <motion.span
              className="flex h-14 w-14 items-center justify-center rounded-[18px]"
              style={{ background: "#fbe9e5", color: "var(--color-coral)" }}
              animate={{ opacity: [0.75, 1, 0.75] }}
              transition={{ duration: 4.5, repeat: Infinity, ease: "easeInOut" }}
            >
              <Candle size={26} />
            </motion.span>
            <span className="mt-5 font-serif text-[1.6rem] text-sky-ink">
              {title}
            </span>
            <span className="mt-2.5 font-sans text-[0.66rem] uppercase tracking-[0.18em] text-sky-ink-soft/70">
              один раз · когда правда понадобится
            </span>
          </motion.button>
        ) : (
          <motion.div
            key="open"
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.9, ease: [0.22, 1, 0.36, 1] }}
            className="px-6 py-9 sm:px-9"
          >
            <div className="space-y-4">
              {body.map((p, i) => (
                <motion.p
                  key={i}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: 0.2 + i * 0.25, duration: 0.8 }}
                  className="font-serif text-[1.15rem] leading-[1.8] text-sky-ink/95"
                >
                  {p}
                </motion.p>
              ))}
            </div>

            {ba && (
              <button
                type="button"
                onClick={() => setRu((v) => !v)}
                className="mt-7 block w-full text-left"
              >
                <div className="hairline mb-4 w-full" />
                <p className="font-serif text-[1.1rem] italic text-sky-ink/85">
                  {ba.text}
                </p>
                <AnimatePresence initial={false}>
                  {ru && (
                    <motion.p
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: "auto" }}
                      exit={{ opacity: 0, height: 0 }}
                      className="overflow-hidden pt-2 font-sans text-sm text-sky-ink-soft"
                    >
                      {ba.ru}
                    </motion.p>
                  )}
                </AnimatePresence>
              </button>
            )}

            <p className="mt-8 text-right font-hand text-2xl text-sky-ink/70">
              Байрас
            </p>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  );
}
