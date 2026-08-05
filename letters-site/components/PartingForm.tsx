"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { createPartingAction, type WishState } from "@/app/actions";
import CardIcon from "./CardIcon";
import { HourglassIcon } from "./Icons";

function Submit() {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="rounded-full px-6 py-2.5 font-sans text-[0.66rem] uppercase tracking-[0.18em] text-white transition-opacity disabled:opacity-40"
      style={{
        background: "linear-gradient(160deg, #e08c76 0%, var(--color-coral) 100%)",
      }}
    >
      {pending ? "завожу…" : "начать разлуку"}
    </button>
  );
}

/**
 * Новая разлука. Задаёшь две даты — сайт сам считает, сколько будет
 * писем, и создаёт пустые болванки под каждый день. Остаётся заполнить.
 */
export default function PartingForm() {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<WishState, FormData>(
    createPartingAction,
    {},
  );

  return (
    <section className="card px-5 py-6">
      <div className="flex items-start gap-4">
        <CardIcon tone="coral">
          <HourglassIcon size={21} />
        </CardIcon>
        <div className="flex-1">
          <h2 className="eyebrow">новая разлука</h2>
          <p className="mt-1.5 font-serif text-[1.3rem] leading-snug text-sky-ink">
            Улетаешь снова?
          </p>
        </div>
      </div>

      <AnimatePresence initial={false}>
        {!open ? (
          <motion.button
            key="ask"
            type="button"
            onClick={() => setOpen(true)}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="mt-4 font-sans text-[0.66rem] uppercase tracking-[0.18em] text-[var(--color-coral)]"
          >
            задать даты →
          </motion.button>
        ) : (
          <motion.form
            key="form"
            action={formAction}
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className="overflow-hidden"
          >
            <div className="mt-5 space-y-4">
              <label className="block">
                <span className="font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft">
                  как назовём
                </span>
                <input
                  name="title"
                  maxLength={80}
                  placeholder="например: до сентября"
                  className="mt-1.5 w-full border-b border-panel-border bg-transparent pb-2 font-serif text-[1.05rem] text-sky-ink outline-none focus:border-sky-ink/40"
                />
              </label>

              <div className="flex gap-4">
                <label className="flex-1">
                  <span className="font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft">
                    улетаю
                  </span>
                  <input
                    name="start"
                    type="date"
                    required
                    className="mt-1.5 w-full border-b border-panel-border bg-transparent pb-2 font-sans text-[0.95rem] text-sky-ink outline-none focus:border-sky-ink/40"
                  />
                </label>
                <label className="flex-1">
                  <span className="font-sans text-[0.62rem] uppercase tracking-[0.16em] text-sky-ink-soft">
                    снова вместе
                  </span>
                  <input
                    name="meet"
                    type="date"
                    required
                    className="mt-1.5 w-full border-b border-panel-border bg-transparent pb-2 font-sans text-[0.95rem] text-sky-ink outline-none focus:border-sky-ink/40"
                  />
                </label>
              </div>
            </div>

            <div className="mt-5 flex items-center justify-between gap-3">
              <span className="font-sans text-[0.64rem] leading-snug text-sky-ink-soft">
                {state.error ?? "письма создадутся сами — по одному на день"}
              </span>
              <Submit />
            </div>
          </motion.form>
        )}
      </AnimatePresence>
    </section>
  );
}
