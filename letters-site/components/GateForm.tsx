"use client";

import { useActionState } from "react";
import { useFormStatus } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { enter, type GateState } from "@/app/gate/actions";

function SubmitHint() {
  const { pending } = useFormStatus();
  return (
    <motion.span
      animate={{ opacity: pending ? 0.4 : 1 }}
      className="font-sans text-[0.62rem] uppercase tracking-[0.24em] text-sky-ink-soft/70"
    >
      {pending ? "открываю…" : "нажми ввод"}
    </motion.span>
  );
}

export default function GateForm({ hint }: { hint: string }) {
  const [state, formAction] = useActionState<GateState, FormData>(enter, {
    error: null,
  });

  return (
    <motion.form
      action={formAction}
      // При ошибке форма коротко «мотает головой»
      animate={state.error ? { x: [0, -9, 8, -5, 0] } : { x: 0 }}
      transition={{ duration: 0.45 }}
      className="w-full max-w-sm text-center"
    >
      <motion.p
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 1.1, delay: 0.5 }}
        className="font-serif text-lg italic text-sky-ink-soft"
      >
        {hint}
      </motion.p>

      <motion.div
        initial={{ opacity: 0, scaleX: 0.4 }}
        animate={{ opacity: 1, scaleX: 1 }}
        transition={{ duration: 1.2, delay: 0.75, ease: [0.22, 1, 0.36, 1] }}
        className="mt-7"
      >
        <input
          name="code"
          autoComplete="off"
          autoFocus
          aria-label={hint}
          className="w-full border-b border-sky-ink/25 bg-transparent pb-3 text-center font-serif text-2xl tracking-[0.14em] text-sky-ink outline-none transition-colors placeholder:text-sky-ink-soft/30 focus:border-sky-ink/60"
          placeholder="· · · ·"
        />
      </motion.div>

      <div className="mt-6 flex h-5 items-center justify-center">
        <AnimatePresence mode="wait">
          {state.error ? (
            <motion.span
              key="err"
              initial={{ opacity: 0, y: -4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="font-sans text-[0.7rem] tracking-wide text-[#e8a0a0]"
            >
              {state.error}
            </motion.span>
          ) : (
            <motion.span
              key="hint"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ delay: 1 }}
            >
              <SubmitHint />
            </motion.span>
          )}
        </AnimatePresence>
      </div>
    </motion.form>
  );
}
