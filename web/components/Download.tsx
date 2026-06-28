"use client";

import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { DownloadButton } from "./DownloadButton";
import { BorderBeam } from "./BorderBeam";

const steps: DictKey[] = ["dl_step_1", "dl_step_2", "dl_step_3"];

export function Download() {
  const { tr } = useLang();
  return (
    <section id="download" className="relative px-6 py-24">
      <div className="mx-auto max-w-4xl">
        <Reveal>
          <div className="glass relative overflow-hidden rounded-[34px] p-10 text-center sm:p-14">
            <BorderBeam />
            {/* свечение внутри карточки */}
            <div className="pointer-events-none absolute -top-24 left-1/2 h-64 w-64 -translate-x-1/2 rounded-full bg-green-bright/25 blur-[100px]" />

            <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
              {tr("dl_title")}
            </h2>
            <p className="mx-auto mt-4 max-w-md text-lg text-white/60">{tr("dl_sub")}</p>

            <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <DownloadButton label={tr("dl_cta")} sub={tr("hero_cta_sub")} />
              {/* iPhone — скоро (неактивно) */}
              <div
                className="inline-flex cursor-not-allowed flex-col items-center rounded-canon border border-white/10 bg-white/5 px-7 py-4 text-white/50"
                aria-disabled="true"
                title={tr("plat_ios_note")}
              >
                <span className="flex items-center gap-2 font-display text-base font-bold">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M16.7 12.7c0-2.3 1.9-3.4 2-3.5-1.1-1.6-2.8-1.8-3.4-1.8-1.4-.1-2.8.9-3.5.9-.7 0-1.8-.8-3-.8-1.5 0-2.9.9-3.7 2.3-1.6 2.7-.4 6.8 1.1 9 .7 1.1 1.6 2.3 2.7 2.2 1.1 0 1.5-.7 2.8-.7s1.6.7 2.8.7c1.2 0 1.9-1.1 2.6-2.1.8-1.2 1.2-2.4 1.2-2.4s-2.2-.9-2.2-3.5Zm-2.3-6.4c.6-.7 1-1.7.9-2.8-.9 0-2 .6-2.6 1.3-.6.6-1.1 1.6-.9 2.6 1 .1 2-.5 2.6-1.1Z"/></svg>
                  iPhone
                </span>
                <span className="mt-0.5 text-xs font-semibold text-gold-light/80">{tr("plat_ios")}</span>
              </div>
            </div>
            <p className="mx-auto mt-4 max-w-sm text-sm text-white/40">{tr("plat_ios_note")}</p>

            {/* шаги установки */}
            <div className="mt-12 text-left">
              <h3 className="mb-5 text-center font-display text-lg font-bold text-white/80">
                {tr("dl_steps_t")}
              </h3>
              <ol className="mx-auto grid max-w-2xl gap-4 sm:grid-cols-3">
                {steps.map((s, i) => (
                  <li key={s} className="rounded-canon bg-white/5 p-4">
                    <span className="font-display text-2xl font-extrabold text-green-glow">
                      {i + 1}
                    </span>
                    <p className="mt-1 text-sm leading-relaxed text-white/65">{tr(s)}</p>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
