"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { sendReply, type ReplyState } from "@/app/actions";

function SendButton() {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="rounded-full bg-ink px-6 py-2.5 font-sans text-[0.7rem] uppercase tracking-[0.18em] text-paper transition-opacity disabled:opacity-40"
    >
      {pending ? "отправляю…" : "отправить"}
    </button>
  );
}

/**
 * Поле ответа под письмом. Свёрнуто до одной строчки, чтобы
 * не мешать чтению: сначала письмо, потом уже желание ответить.
 */
export default function ReplyBox({
  n,
  partingId,
}: {
  n: number;
  partingId: number;
}) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<ReplyState, FormData>(sendReply, {
    status: "idle",
  });

  const sent = state.status === "sent";

  return (
    <div className="mt-10">
      <div
        className="mb-6 h-px w-full"
        style={{
          background:
            "linear-gradient(to right, transparent, color-mix(in srgb, var(--color-ink) 18%, transparent), transparent)",
        }}
      />

      <AnimatePresence mode="wait" initial={false}>
        {sent ? (
          <motion.p
            key="done"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            className="text-center font-serif text-lg italic text-ink-soft"
          >
            Ушло. Он прочтёт.
          </motion.p>
        ) : !open ? (
          <motion.button
            key="collapsed"
            type="button"
            onClick={() => setOpen(true)}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="mx-auto block font-sans text-[0.66rem] uppercase tracking-[0.22em] text-ink-faint transition-colors hover:text-ink-soft"
          >
            ответить
          </motion.button>
        ) : (
          <motion.form
            key="form"
            action={formAction}
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
            className="overflow-hidden"
          >
            <input type="hidden" name="n" value={n} />
            <input type="hidden" name="partingId" value={partingId} />
            <textarea
              name="text"
              rows={4}
              autoFocus
              maxLength={3000}
              placeholder="Что скажешь?"
              className="w-full resize-none rounded-[var(--radius-soft)] border border-ink/12 bg-white/45 p-4 font-serif text-[1.05rem] leading-relaxed text-ink outline-none transition-colors placeholder:text-ink-faint/70 focus:border-ink/30"
            />
            <div className="mt-3 flex items-center justify-between">
              <span className="font-sans text-[0.66rem] text-ink-faint">
                {state.status === "error" ? state.message : "придёт ему в телеграм"}
              </span>
              <SendButton />
            </div>
          </motion.form>
        )}
      </AnimatePresence>
    </div>
  );
}
