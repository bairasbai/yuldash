"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { thinkOfYou } from "@/app/actions";

/**
 * «Думаю о тебе». Одно нажатие — и у него в телеграме появляется
 * строчка с её временем. Ничего больше эта кнопка не делает,
 * и в этом вся суть.
 */
export default function ThinkingButton() {
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  const press = async () => {
    if (busy || sent) return;
    setBusy(true);
    await thinkOfYou();
    setBusy(false);
    setSent(true);
    window.setTimeout(() => setSent(false), 6000);
  };

  return (
    <section className="flex flex-col items-center rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-10 backdrop-blur-[2px]">
      <button
        type="button"
        onClick={press}
        aria-label="Сказать, что думаешь о нём"
        className="relative flex h-28 w-28 items-center justify-center rounded-full transition-transform duration-300 active:scale-95"
        style={{
          // Заливку держим слабой: плотный тёплый круг на тёмном небе
          // выглядит грязно-бурым. Работает свечение, а не цвет.
          background:
            "radial-gradient(circle, rgb(255 217 168 / 0.14) 0%, transparent 66%)",
          boxShadow:
            "inset 0 0 0 1px rgb(255 255 255 / 0.16), 0 0 44px -12px rgb(255 205 150 / 0.55)",
        }}
      >
        {/* Кольца расходятся при нажатии */}
        <AnimatePresence>
          {sent &&
            [0, 1, 2].map((i) => (
              <motion.span
                key={i}
                className="absolute inset-0 rounded-full border border-[#ffd9a8]/60"
                initial={{ scale: 1, opacity: 0.75 }}
                animate={{ scale: 2.1, opacity: 0 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 1.8, delay: i * 0.22, ease: "easeOut" }}
              />
            ))}
        </AnimatePresence>

        <motion.span
          className="text-4xl"
          style={{ textShadow: "0 0 18px rgb(255 214 160 / 0.75)" }}
          animate={
            sent
              ? { scale: [1, 1.28, 1] }
              : { scale: [1, 1.06, 1] }
          }
          transition={
            sent
              ? { duration: 0.7 }
              : { duration: 3.5, repeat: Infinity, ease: "easeInOut" }
          }
        >
          ✧
        </motion.span>
      </button>

      <div className="mt-6 h-6">
        <AnimatePresence mode="wait">
          <motion.p
            key={sent ? "sent" : "idle"}
            initial={{ opacity: 0, y: 5 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -5 }}
            transition={{ duration: 0.4 }}
            className="text-center font-sans text-[0.68rem] uppercase tracking-[0.22em] text-sky-ink-soft"
          >
            {busy ? "лечу…" : sent ? "он уже знает" : "думаю о тебе"}
          </motion.p>
        </AnimatePresence>
      </div>
    </section>
  );
}
