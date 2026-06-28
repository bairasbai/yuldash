"use client";

import { useLang } from "./lang";
import { SAFETY } from "./safety-content";

export function SafetyView() {
  const { lang } = useLang();
  return (
    <article>
      <h1 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{SAFETY.title[lang]}</h1>
      <div className="mt-6 space-y-4">
        {SAFETY.intro.map((t, i) => (
          <p key={i} className="text-lg leading-relaxed text-white/70">{t[lang]}</p>
        ))}
      </div>

      <div className="mt-10 space-y-5">
        {SAFETY.sections.map((s, i) => (
          <section key={i} className="glass rounded-canon p-6">
            <div className="flex items-start gap-4">
              <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-green-bright/15 font-display text-sm font-extrabold text-green-glow">
                {i + 1}
              </span>
              <div>
                <h2 className="font-display text-xl font-bold">{s.h[lang]}</h2>
                <div className="mt-2 space-y-2">
                  {s.p.map((t, j) => (
                    <p key={j} className="leading-relaxed text-white/70">{t[lang]}</p>
                  ))}
                </div>
              </div>
            </div>
          </section>
        ))}
      </div>
    </article>
  );
}
