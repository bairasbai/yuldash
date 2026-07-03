"use client";

import { useState } from "react";
import { AnimatePresence, m } from "framer-motion";
import { useLang } from "./lang";
import { HELP } from "./help-content";

export function HelpView() {
  const { lang } = useLang();
  const [open, setOpen] = useState<string | null>("0-0");

  return (
    <article>
      <h1 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{HELP.title[lang]}</h1>
      <p className="mt-4 text-lg text-white/60">{HELP.sub[lang]}</p>

      <div className="mt-10 space-y-10">
        {HELP.categories.map((cat, ci) => (
          <section key={ci}>
            <h2 className="mb-4 font-display text-xl font-extrabold text-green-glow">{cat.name[lang]}</h2>
            <div className="space-y-3">
              {cat.items.map((item, ii) => {
                const id = `${ci}-${ii}`;
                const isOpen = open === id;
                return (
                  <div key={id} className="glass overflow-hidden rounded-canon">
                    <button
                      onClick={() => setOpen(isOpen ? null : id)}
                      aria-expanded={isOpen}
                      className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left"
                    >
                      <span className="font-display font-bold">{item.q[lang]}</span>
                      <m.span
                        animate={{ rotate: isOpen ? 45 : 0 }}
                        transition={{ duration: 0.25 }}
                        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-green-bright/15 text-green-glow"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
                      </m.span>
                    </button>
                    <AnimatePresence initial={false}>
                      {isOpen && (
                        <m.div
                          initial={{ height: 0, opacity: 0 }}
                          animate={{ height: "auto", opacity: 1 }}
                          exit={{ height: 0, opacity: 0 }}
                          transition={{ duration: 0.3, ease: [0.21, 0.47, 0.32, 0.98] }}
                        >
                          <p className="px-5 pb-5 leading-relaxed text-white/65">{item.a[lang]}</p>
                        </m.div>
                      )}
                    </AnimatePresence>
                  </div>
                );
              })}
            </div>
          </section>
        ))}
      </div>
    </article>
  );
}
