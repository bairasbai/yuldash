"use client";

import { useActionState, useState } from "react";
import { useFormStatus } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { saveLetterAction, type WishState } from "@/app/actions";

type LetterRow = {
  n: number;
  dateLabel: string;
  topic: string;
  body: string;
  baText: string;
  baRu: string;
  ready: boolean;
};

function Save() {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="rounded-full px-5 py-2 font-sans text-[0.62rem] uppercase tracking-[0.16em] text-white transition-opacity disabled:opacity-40"
      style={{
        background: "linear-gradient(160deg, #e08c76 0%, var(--color-coral) 100%)",
      }}
    >
      {pending ? "сохраняю…" : "сохранить"}
    </button>
  );
}

/**
 * Письма разлуки. Каждое раскрывается прямо здесь — писать можно
 * с телефона, без кода и без меня.
 *
 * Пустой абзац разделяет абзацы: так же, как когда пишешь в заметках.
 */
export default function LetterEditor({
  partingId,
  letters,
}: {
  partingId: number;
  letters: LetterRow[];
}) {
  const [openN, setOpenN] = useState<number | null>(null);

  return (
    <ul className="card divide-y divide-panel-border overflow-hidden">
      {letters.map((l) => {
        const isOpen = openN === l.n;
        return (
          <li key={l.n}>
            <button
              type="button"
              onClick={() => setOpenN(isOpen ? null : l.n)}
              className="flex w-full items-center gap-3 px-4 py-3.5 text-left"
            >
              <span className="w-7 shrink-0 font-serif text-lg text-sky-ink-soft">
                {String(l.n).padStart(2, "0")}
              </span>
              <span className="w-24 shrink-0 font-sans text-[0.62rem] uppercase tracking-[0.14em] text-sky-ink-soft/70">
                {l.dateLabel}
              </span>
              <span className="flex-1 truncate font-sans text-sm text-sky-ink/90">
                {l.topic || (l.ready ? "написано" : "пусто")}
              </span>
              <span
                className="shrink-0 font-sans text-[0.6rem] uppercase tracking-[0.16em]"
                style={{
                  color: l.ready ? "var(--color-sky-ink-soft)" : "#c2695c",
                }}
              >
                {l.ready ? "готово" : "пусто"}
              </span>
            </button>

            <AnimatePresence initial={false}>
              {isOpen && (
                <motion.div
                  initial={{ height: 0, opacity: 0 }}
                  animate={{ height: "auto", opacity: 1 }}
                  exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
                  className="overflow-hidden"
                >
                  <LetterForm partingId={partingId} letter={l} />
                </motion.div>
              )}
            </AnimatePresence>
          </li>
        );
      })}
    </ul>
  );
}

function LetterForm({
  partingId,
  letter,
}: {
  partingId: number;
  letter: LetterRow;
}) {
  const [state, formAction] = useActionState<WishState, FormData>(
    saveLetterAction,
    {},
  );

  return (
    <form action={formAction} className="space-y-3 bg-cream/60 px-4 pb-5 pt-1">
      <input type="hidden" name="partingId" value={partingId} />
      <input type="hidden" name="n" value={letter.n} />

      <input
        name="topic"
        defaultValue={letter.topic}
        maxLength={120}
        placeholder="о чём это письмо — заметка для себя"
        className="w-full border-b border-panel-border bg-transparent pb-1.5 font-sans text-sm text-sky-ink outline-none focus:border-sky-ink/40"
      />

      <textarea
        name="body"
        defaultValue={letter.body}
        rows={9}
        maxLength={6000}
        placeholder={"Текст письма.\n\nПустая строка — новый абзац."}
        className="w-full resize-y rounded-[var(--radius-soft)] border border-panel-border bg-white p-3.5 font-serif text-[1.02rem] leading-relaxed text-sky-ink outline-none focus:border-sky-ink/30"
      />

      <div className="flex gap-3">
        <input
          name="baText"
          defaultValue={letter.baText}
          maxLength={300}
          placeholder="строчка на башкирском"
          className="flex-1 border-b border-panel-border bg-transparent pb-1.5 font-serif text-[0.95rem] italic text-sky-ink outline-none focus:border-sky-ink/40"
        />
        <input
          name="baRu"
          defaultValue={letter.baRu}
          maxLength={300}
          placeholder="её перевод"
          className="flex-1 border-b border-panel-border bg-transparent pb-1.5 font-sans text-[0.9rem] text-sky-ink-soft outline-none focus:border-sky-ink/40"
        />
      </div>

      <div className="flex items-center justify-between gap-3 pt-1">
        <span className="font-sans text-[0.62rem] leading-snug text-sky-ink-soft">
          {state.error ??
            (state.ok
              ? "сохранено"
              : "пустое письмо бот не отправит — придёт напоминание тебе")}
        </span>
        <Save />
      </div>
    </form>
  );
}
