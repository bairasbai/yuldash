"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { sealCapsuleAction, type WishState } from "@/app/actions";
import { humanDate } from "@/lib/time";
import type { Capsule as CapsuleData } from "@/lib/store";

function Seal() {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="rounded-full border border-panel-border px-5 py-2 font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft transition-colors hover:text-sky-ink disabled:opacity-40"
    >
      {pending ? "запечатываю…" : "запечатать на год"}
    </button>
  );
}

/**
 * Капсула времени. Письмо себе будущим, которое нельзя переписать
 * и нельзя открыть раньше срока — в этом вся его ценность.
 */
export default function Capsule({
  mine,
  canOpen,
  today,
}: {
  mine: CapsuleData | null;
  canOpen: boolean;
  today: string;
}) {
  const [writing, setWriting] = useState(false);
  const [state, formAction] = useActionState<WishState, FormData>(
    sealCapsuleAction,
    {},
  );

  return (
    <section className="rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-6 backdrop-blur-[2px]">
      <h2 className="mb-1 font-serif text-2xl">Капсула</h2>
      <p className="mb-5 font-sans text-[0.66rem] uppercase tracking-[0.2em] text-sky-ink-soft/70">
        письмо себе через год
      </p>

      <AnimatePresence mode="wait" initial={false}>
        {mine && canOpen ? (
          <motion.div
            key="opened"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            className="space-y-3"
          >
            <p className="font-sans text-[0.6rem] uppercase tracking-[0.18em] text-sky-ink-soft/55">
              запечатано {humanDate(mine.open_at)} год назад
            </p>
            <p className="whitespace-pre-line font-serif text-[1.08rem] leading-relaxed text-sky-ink/95">
              {mine.text}
            </p>
          </motion.div>
        ) : mine ? (
          <motion.div
            key="sealed"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="flex items-center gap-4 py-2"
          >
            <span className="text-2xl" aria-hidden>
              ⏳
            </span>
            <div>
              <p className="font-serif text-[1.08rem] text-sky-ink/90">
                Запечатано. Откроется {humanDate(mine.open_at)}.
              </p>
              <p className="mt-1 font-sans text-[0.68rem] text-sky-ink-soft/60">
                Даже тебе — раньше никак.
              </p>
            </div>
          </motion.div>
        ) : !writing ? (
          <motion.button
            key="start"
            type="button"
            onClick={() => setWriting(true)}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="w-full text-left"
          >
            <p className="font-serif text-[1.08rem] leading-relaxed text-sky-ink-soft">
              Напиши себе будущей. Что ты сейчас чувствуешь, чего ждёшь,
              о чём боишься забыть. Через год прочтёшь и сравнишь.
            </p>
            <span className="mt-3 inline-block font-sans text-[0.62rem] uppercase tracking-[0.18em] text-sky-ink-soft/70">
              написать →
            </span>
          </motion.button>
        ) : (
          <motion.form
            key="form"
            action={formAction}
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            className="overflow-hidden"
          >
            <textarea
              name="text"
              rows={6}
              autoFocus
              maxLength={4000}
              placeholder="Дорогая я через год…"
              className="w-full resize-none rounded-[var(--radius-soft)] border border-panel-border bg-white/[0.04] p-4 font-serif text-[1.05rem] leading-relaxed text-sky-ink outline-none placeholder:text-sky-ink-soft/40 focus:border-sky-ink/30"
            />
            <div className="mt-3 flex items-center justify-between">
              <span className="font-sans text-[0.64rem] text-sky-ink-soft/60">
                {state.error ?? "переписать потом будет нельзя"}
              </span>
              <Seal />
            </div>
          </motion.form>
        )}
      </AnimatePresence>
    </section>
  );
}
