"use client";

import { useActionState, useOptimistic, useState, useTransition } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  addWishAction,
  removeWishAction,
  toggleWishAction,
  type WishState,
} from "@/app/actions";
import { haptic } from "@/lib/haptics";
import type { Wish } from "@/lib/store";

/**
 * Список «что сделаем в Уфе». Общий: оба добавляют, оба вычёркивают.
 * Ожидание из пустого превращается в подготовку.
 */
export default function WishList({
  wishes,
  me,
}: {
  wishes: Wish[];
  me: "her" | "him";
}) {
  const [state, formAction] = useActionState<WishState, FormData>(
    addWishAction,
    {},
  );
  const [, startTransition] = useTransition();

  // Галочка должна срабатывать под пальцем, а не после ответа сервера
  const [items, toggle] = useOptimistic(
    wishes,
    (list: Wish[], id: number) =>
      list.map((w) => (w.id === id ? { ...w, done: !w.done } : w)),
  );

  const open = items.filter((w) => !w.done);
  const done = items.filter((w) => w.done);

  return (
    <section className="rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-6 backdrop-blur-[2px]">
      <h2 className="mb-1 font-serif text-2xl">Что сделаем в Уфе</h2>
      <p className="mb-5 font-sans text-[0.66rem] uppercase tracking-[0.2em] text-sky-ink-soft/70">
        {done.length} из {items.length} уже сделано
      </p>

      <ul className="space-y-1">
        <AnimatePresence initial={false}>
          {[...open, ...done].map((w) => (
            <motion.li
              key={w.id}
              layout
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
              className="group flex items-start gap-3 py-2"
            >
              <button
                type="button"
                aria-label={w.done ? "Вернуть в список" : "Отметить сделанным"}
                onClick={() => {
                  haptic(10);
                  startTransition(async () => {
                    toggle(w.id);
                    await toggleWishAction(w.id, !w.done);
                  });
                }}
                className="mt-[3px] flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full border transition-colors"
                style={{
                  borderColor: w.done
                    ? "rgb(255 217 168 / 0.6)"
                    : "var(--panel-border)",
                  background: w.done ? "rgb(255 217 168 / 0.22)" : "transparent",
                }}
              >
                {w.done && (
                  <motion.svg
                    width="10"
                    height="8"
                    viewBox="0 0 10 8"
                    fill="none"
                    initial={{ scale: 0.4, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                  >
                    <path
                      d="M1 4.2 3.6 6.8 9 1.4"
                      stroke="#ffd9a8"
                      strokeWidth="1.5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </motion.svg>
                )}
              </button>

              <span
                className={`flex-1 font-serif text-[1.05rem] leading-snug transition-colors ${
                  w.done ? "text-sky-ink-soft/45 line-through" : "text-sky-ink/95"
                }`}
              >
                {w.text}
              </span>

              <span className="mt-1 shrink-0 font-sans text-[0.58rem] uppercase tracking-[0.14em] text-sky-ink-soft/40">
                {w.author === "her" ? "И" : "Б"}
              </span>

              {w.author === me && (
                <button
                  type="button"
                  aria-label="Убрать из списка"
                  onClick={() =>
                    startTransition(async () => {
                      await removeWishAction(w.id);
                    })
                  }
                  className="mt-1 shrink-0 px-1 font-sans text-sm text-sky-ink-soft/0 transition-colors group-hover:text-sky-ink-soft/60"
                >
                  ×
                </button>
              )}
            </motion.li>
          ))}
        </AnimatePresence>
      </ul>

      {items.length === 0 && (
        <p className="py-3 font-serif text-[1.05rem] italic text-sky-ink-soft/70">
          Пока пусто. Напиши первое — хоть «выпить кофе там, где ты обычно».
        </p>
      )}

      <form action={formAction} className="mt-5 flex items-center gap-2">
        <input
          name="text"
          maxLength={200}
          autoComplete="off"
          placeholder="добавить…"
          className="flex-1 border-b border-panel-border bg-transparent pb-2 font-serif text-[1.02rem] text-sky-ink outline-none transition-colors placeholder:text-sky-ink-soft/40 focus:border-sky-ink/40"
        />
        <button
          type="submit"
          className="shrink-0 rounded-full border border-panel-border px-4 py-1.5 font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft transition-colors hover:text-sky-ink"
        >
          в список
        </button>
      </form>

      {state.error && (
        <p className="mt-2 font-sans text-[0.68rem] text-[#e8a0a0]">
          {state.error}
        </p>
      )}
    </section>
  );
}
