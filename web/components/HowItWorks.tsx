"use client";

import { useState } from "react";
import { AnimatePresence, m } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";
import { useDownload } from "./DownloadProvider";
import { ReactNode } from "react";

const IconSearch = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" />
  </svg>
);
const IconChat = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5Z" />
  </svg>
);
const IconRoute = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="6" cy="19" r="3" /><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15" /><circle cx="18" cy="5" r="3" />
  </svg>
);
const IconPin = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 22s8-7 8-12a8 8 0 1 0-16 0c0 5 8 12 8 12Z" /><circle cx="12" cy="10" r="2.5" />
  </svg>
);
const IconUsers = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
  </svg>
);
const IconWallet = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" /><path d="M21 12a2 2 0 0 0-2-2h-4a2 2 0 0 0 0 4h4a2 2 0 0 0 2-2Z" />
  </svg>
);

type Step = { icon: ReactNode; t: DictKey; d: DictKey };
const passengerSteps: Step[] = [
  { icon: IconSearch, t: "how_1_t", d: "how_1_d" },
  { icon: IconChat, t: "how_2_t", d: "how_2_d" },
  { icon: IconRoute, t: "how_3_t", d: "how_3_d" },
];
const driverSteps: Step[] = [
  { icon: IconPin, t: "hd_1_t", d: "hd_1_d" },
  { icon: IconUsers, t: "hd_2_t", d: "hd_2_d" },
  { icon: IconWallet, t: "hd_3_t", d: "hd_3_d" },
];

export function HowItWorks() {
  const { tr } = useLang();
  const { request } = useDownload();
  const [tab, setTab] = useState<"pass" | "driver">("pass");
  const steps = tab === "pass" ? passengerSteps : driverSteps;

  return (
    <section id="how" className="relative px-6 py-24">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("how_title")}
          </h2>
          <p className="mt-4 text-lg text-white/60">{tr("how_sub")}</p>
        </Reveal>

        {/* переключатель пассажир / водитель */}
        <Reveal className="mt-10 flex justify-center">
          <div className="relative flex rounded-full border border-white/10 bg-white/5 p-1 text-sm font-semibold">
            {([["pass", "how_tab_pass"], ["driver", "how_tab_driver"]] as const).map(([key, label]) => (
              <button
                key={key}
                onClick={() => setTab(key)}
                className="relative z-10 rounded-full px-6 py-2.5 transition-colors"
              >
                {tab === key && (
                  <m.span
                    layoutId="how-tab"
                    className="absolute inset-0 -z-10 rounded-full bg-green-bright"
                    transition={{ type: "spring", stiffness: 380, damping: 30 }}
                  />
                )}
                <span className={tab === key ? "text-night" : "text-white/65"}>{tr(label)}</span>
              </button>
            ))}
          </div>
        </Reveal>

        <div className="relative mt-14 min-h-[220px]">
          {/* соединительная линия (десктоп) */}
          <div className="pointer-events-none absolute left-0 right-0 top-7 hidden h-px bg-gradient-to-r from-transparent via-green-bright/30 to-transparent md:block" />

          <AnimatePresence mode="wait">
            <m.div
              key={tab}
              initial={{ y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.35, ease: [0.21, 0.47, 0.32, 0.98] }}
              className="grid gap-8 md:grid-cols-3"
            >
              {steps.map((s, i) => (
                <div key={s.t} className="relative text-center">
                  <m.div
                    whileHover={{ y: -6 }}
                    transition={{ type: "spring", stiffness: 300, damping: 20 }}
                    className="flex flex-col items-center"
                  >
                    <div className="relative mb-5 flex h-14 w-14 items-center justify-center rounded-full border border-green-bright/30 bg-night text-green-glow">
                      {s.icon}
                      <span className="absolute -right-1 -top-1 flex h-6 w-6 items-center justify-center rounded-full bg-green-bright text-xs font-extrabold text-night">
                        {i + 1}
                      </span>
                    </div>
                    <h3 className="font-display text-xl font-bold">{tr(s.t)}</h3>
                    <p className="mt-2 max-w-xs text-sm leading-relaxed text-white/60">{tr(s.d)}</p>
                  </m.div>
                </div>
              ))}
            </m.div>
          </AnimatePresence>
        </div>

        {/* CTA под шагами — меняется по вкладке */}
        <Reveal className="mt-12 flex justify-center">
          <m.button
            type="button"
            onClick={request}
            whileTap={{ scale: 0.97 }}
            className="inline-flex items-center gap-2.5 rounded-canon bg-green-bright px-8 py-4 font-bold text-night shadow-glow transition-transform hover:scale-[1.03]"
          >
            {tr(tab === "pass" ? "how_cta_pass" : "how_cta_driver")}
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
          </m.button>
        </Reveal>
      </div>
    </section>
  );
}
