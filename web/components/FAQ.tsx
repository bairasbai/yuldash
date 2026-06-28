"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

const qa: { q: DictKey; a: DictKey }[] = [
  { q: "faq_1_q", a: "faq_1_a" },
  { q: "faq_2_q", a: "faq_2_a" },
  { q: "faq_3_q", a: "faq_3_a" },
  { q: "faq_4_q", a: "faq_4_a" },
  { q: "faq_5_q", a: "faq_5_a" },
];

export function FAQ() {
  const { tr } = useLang();
  const [open, setOpen] = useState<number | null>(0);

  return (
    <section id="faq" className="relative px-6 py-24">
      <div className="mx-auto max-w-3xl">
        <Reveal className="mb-12 text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("faq_title")}
          </h2>
          <p className="mt-4 text-lg text-white/60">{tr("faq_sub")}</p>
        </Reveal>

        <div className="space-y-3">
          {qa.map((item, i) => {
            const isOpen = open === i;
            return (
              <Reveal key={item.q} delay={i * 0.06}>
                <div className="glass overflow-hidden rounded-canon">
                  <button
                    onClick={() => setOpen(isOpen ? null : i)}
                    aria-expanded={isOpen}
                    className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left"
                  >
                    <span className="font-display text-base font-bold sm:text-lg">{tr(item.q)}</span>
                    <motion.span
                      animate={{ rotate: isOpen ? 45 : 0 }}
                      transition={{ duration: 0.25, ease: [0.21, 0.47, 0.32, 0.98] }}
                      className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-green-bright/15 text-green-glow"
                      aria-hidden="true"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                        <path d="M12 5v14M5 12h14" />
                      </svg>
                    </motion.span>
                  </button>
                  <AnimatePresence initial={false}>
                    {isOpen && (
                      <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: "auto", opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={{ duration: 0.3, ease: [0.21, 0.47, 0.32, 0.98] }}
                      >
                        <p className="px-5 pb-5 text-sm leading-relaxed text-white/65">
                          {tr(item.a)}
                        </p>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              </Reveal>
            );
          })}
        </div>

        <Reveal className="mt-8 text-center">
          <a href="/help" className="inline-flex items-center gap-1.5 text-sm font-semibold text-green-glow transition-colors hover:text-white">
            {tr("foot_help")}
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
          </a>
        </Reveal>
      </div>
    </section>
  );
}
