"use client";

import { ComponentType } from "react";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { BadgeCheck, Star, LifeBuoy, Lock } from "./icons";

type IconC = ComponentType<{ className?: string; size?: number }>;
const rows: { t: DictKey; d: DictKey; Icon: IconC }[] = [
  { t: "trust_1_t", d: "trust_1_d", Icon: BadgeCheck },
  { t: "trust_2_t", d: "trust_2_d", Icon: Star },
  { t: "trust_3_t", d: "trust_3_d", Icon: LifeBuoy },
  { t: "trust_4_t", d: "trust_4_d", Icon: Lock },
];

export function Trust() {
  const { tr } = useLang();
  return (
    <section id="trust" className="relative px-6 py-24">
      <div className="mx-auto grid max-w-6xl items-center gap-14 lg:grid-cols-2">
        <Reveal>
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
            {tr("trust_title")}
          </h2>
          <p className="mt-5 max-w-md text-lg leading-relaxed text-white/60">{tr("trust_sub")}</p>

          {/* большая золотая «печать доверия» */}
          <div className="mt-10 inline-flex items-center gap-4 rounded-canon bg-gradient-to-br from-gold/20 to-transparent p-5 pr-8">
            <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-gold text-night shadow-gold">
              <BadgeCheck size={30} />
            </div>
            <div>
              <div className="font-display text-2xl font-extrabold text-gold-light">100%</div>
              <div className="text-sm text-white/60">{tr("foot_tagline")}</div>
            </div>
          </div>

          <div className="mt-6">
            <a href="/safety" className="inline-flex items-center gap-1.5 text-sm font-semibold text-green-glow transition-colors hover:text-white">
              {tr("more_safety")}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
            </a>
          </div>
        </Reveal>

        <div className="space-y-4">
          {rows.map((r, i) => (
            <Reveal key={r.t} delay={i * 0.08}>
              <div className="glass group flex items-start gap-4 rounded-canon p-5 transition-colors hover:border-green-bright/25">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-green-bright/12 text-green-glow transition-transform group-hover:scale-110">
                  <r.Icon size={22} />
                </div>
                <div>
                  <h3 className="font-display text-lg font-bold">{tr(r.t)}</h3>
                  <p className="mt-1 text-sm text-white/60">{tr(r.d)}</p>
                </div>
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
