"use client";

import { useActionState } from "react";
import { useFormStatus } from "react-dom";
import { motion } from "motion/react";
import { answerDayAction, type WishState } from "@/app/actions";
import { CONFIG } from "@/lib/config";
import type { DayAnswer } from "@/lib/store";

function Send() {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="shrink-0 rounded-full border border-panel-border px-4 py-1.5 font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft transition-colors hover:text-sky-ink disabled:opacity-40"
    >
      {pending ? "…" : "ответить"}
    </button>
  );
}

/**
 * Вопрос дня. Чужой ответ закрыт, пока не ответишь сам —
 * иначе второй невольно подстроится под первого.
 */
export default function DayQuestion({
  question,
  answers,
  me,
}: {
  question: string;
  answers: DayAnswer[];
  me: "her" | "him";
}) {
  const [state, formAction] = useActionState<WishState, FormData>(
    answerDayAction,
    {},
  );

  const mine = answers.find((a) => a.author === me);
  const theirs = answers.find((a) => a.author !== me);
  const theirName = me === "her" ? CONFIG.him.name : CONFIG.her.name;

  return (
    <section className="rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-6 backdrop-blur-[2px]">
      <h2 className="mb-4 font-sans text-[0.66rem] uppercase tracking-[0.2em] text-sky-ink-soft/70">
        вопрос дня
      </h2>

      <p className="font-serif text-[1.35rem] leading-snug text-sky-ink">
        {question}
      </p>

      {!mine ? (
        <form action={formAction} className="mt-6">
          <div className="flex items-end gap-2">
            <textarea
              name="text"
              rows={2}
              maxLength={1200}
              placeholder="твой ответ"
              className="flex-1 resize-none border-b border-panel-border bg-transparent pb-2 font-serif text-[1.05rem] leading-relaxed text-sky-ink outline-none placeholder:text-sky-ink-soft/40 focus:border-sky-ink/40"
            />
            <Send />
          </div>
          {state.error && (
            <p className="mt-2 font-sans text-[0.68rem] text-[#e8a0a0]">
              {state.error}
            </p>
          )}
        </form>
      ) : (
        <div className="mt-6 space-y-5">
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
          >
            <p className="mb-1 font-sans text-[0.58rem] uppercase tracking-[0.18em] text-sky-ink-soft/50">
              ты
            </p>
            <p className="font-serif text-[1.05rem] leading-relaxed text-sky-ink/90">
              {mine.text}
            </p>
          </motion.div>

          <div className="hairline" />

          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.15 }}
          >
            <p className="mb-1 font-sans text-[0.58rem] uppercase tracking-[0.18em] text-sky-ink-soft/50">
              {theirName}
            </p>
            {theirs ? (
              <p className="font-serif text-[1.05rem] leading-relaxed text-sky-ink/90">
                {theirs.text}
              </p>
            ) : (
              <p className="font-serif text-[1.05rem] italic text-sky-ink-soft/60">
                ещё не ответил. Появится здесь само.
              </p>
            )}
          </motion.div>
        </div>
      )}
    </section>
  );
}
