"use client";

import { useState, useTransition } from "react";
import { AnimatePresence, motion } from "motion/react";
import { setMoodAction } from "@/app/actions";
import { MOODS, moodByValue } from "@/data/moods";
import { haptic } from "@/lib/haptics";
import type { Mood } from "@/lib/store";

/**
 * Настроение дня. Не шкала от одного до десяти, а слово:
 * человек не измеряет себя цифрой.
 *
 * История за месяц рядом — по ней видно не один день, а весь период
 * ожидания целиком.
 */
export default function MoodPicker({
  today,
  history,
}: {
  today: Mood | null;
  history: Mood[];
}) {
  const [chosen, setChosen] = useState(today?.value ?? null);
  const [note, setNote] = useState(today?.note ?? "");
  const [showNote, setShowNote] = useState(false);
  const [, startTransition] = useTransition();

  const pick = (value: string) => {
    haptic(10);
    setChosen(value);
    setShowNote(true);
    startTransition(async () => {
      await setMoodAction(value, note);
    });
  };

  const saveNote = () => {
    if (!chosen) return;
    startTransition(async () => {
      await setMoodAction(chosen, note);
    });
    setShowNote(false);
  };

  return (
    <section className="card px-5 py-6">
      <div className="mb-5 flex items-baseline justify-between">
        <h2 className="font-serif text-[1.45rem] text-sky-ink">Как ты сегодня</h2>
        <span className="eyebrow">он увидит</span>
      </div>

      <div className="flex flex-wrap gap-2">
        {MOODS.map((m) => {
          const active = chosen === m.value;
          return (
            <button
              key={m.value}
              type="button"
              onClick={() => pick(m.value)}
              className="flex items-center gap-2 rounded-full border px-3.5 py-2 font-sans text-[0.74rem] transition-all duration-300"
              style={{
                borderColor: active ? `${m.color}88` : "var(--panel-border)",
                background: active ? `${m.color}1f` : "transparent",
                color: active ? m.color : "var(--color-sky-ink-soft)",
              }}
            >
              <span
                className="block h-2 w-2 rounded-full transition-transform duration-300"
                style={{
                  background: m.color,
                  opacity: active ? 1 : 0.45,
                  transform: active ? "scale(1.25)" : "scale(1)",
                }}
              />
              {m.label}
            </button>
          );
        })}
      </div>

      <AnimatePresence initial={false}>
        {(showNote || (chosen && note)) && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
            className="overflow-hidden"
          >
            <div className="mt-4 flex items-center gap-2">
              <input
                value={note}
                onChange={(e) => setNote(e.target.value)}
                onBlur={saveNote}
                maxLength={300}
                placeholder="если хочешь — словами"
                className="flex-1 border-b border-panel-border bg-transparent pb-2 font-serif text-[1.02rem] text-sky-ink outline-none placeholder:text-sky-ink-soft/40 focus:border-sky-ink/40"
              />
              <button
                type="button"
                onClick={saveNote}
                className="shrink-0 font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft transition-colors hover:text-sky-ink"
              >
                ок
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {history.length > 1 && (
        <div className="mt-7">
          <p className="mb-3 font-sans text-[0.6rem] uppercase tracking-[0.2em] text-sky-ink-soft/60">
            этот месяц
          </p>
          <div className="flex flex-wrap gap-1.5">
            {history.map((h) => {
              const m = moodByValue(h.value);
              return (
                <span
                  key={h.day}
                  title={`${h.day} · ${m?.label ?? h.value}`}
                  className="block h-2.5 w-2.5 rounded-full"
                  style={{ background: m?.color ?? "#888", opacity: 0.8 }}
                />
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}
