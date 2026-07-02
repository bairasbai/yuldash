"use client";

import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { DownloadButton } from "./DownloadButton";
import { BorderBeam } from "./BorderBeam";
import { STORE_LINKS, QR_SRC } from "./config";
import { track } from "./analytics";

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

            {/* Каналы-магазины (появляются, когда ссылка задана в config) */}
            {(STORE_LINKS.rustore || STORE_LINKS.googlePlay) && (
              <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
                {STORE_LINKS.rustore && (
                  <a
                    href={STORE_LINKS.rustore}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={() => track("store_rustore")}
                    className="inline-flex items-center gap-2 rounded-canon border border-white/15 bg-white/5 px-5 py-3 text-sm font-semibold text-white/85 transition-all hover:-translate-y-0.5 hover:border-green-glow hover:bg-green-bright/10"
                  >
                    {tr("dl_rustore")}
                  </a>
                )}
                {STORE_LINKS.googlePlay && (
                  <a
                    href={STORE_LINKS.googlePlay}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={() => track("store_gplay")}
                    className="inline-flex items-center gap-2 rounded-canon border border-white/15 bg-white/5 px-5 py-3 text-sm font-semibold text-white/85 transition-all hover:-translate-y-0.5 hover:border-green-glow hover:bg-green-bright/10"
                  >
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 20.5 13.5 12 3 3.5C2.7 3.7 2.5 4.1 2.5 4.6v14.8c0 .5.2.9.5 1.1Zm12.3-7 2.7 2.7-9.6 5.5 6.9-8.2Zm0-3-6.9-8.2 9.6 5.5-2.7 2.7ZM20.5 12c.6.4.9 1 .9 1.6 0 .6-.3 1.2-.9 1.6l-2 1.1-3-2.7 3-2.7 2 1.1Z"/></svg>
                    {tr("dl_gplay")}
                  </a>
                )}
              </div>
            )}

            {/* QR для десктопа — навёл телефон, открыл страницу загрузки */}
            <div className="mt-10 hidden items-center justify-center gap-4 sm:flex">
              <div className="rounded-2xl bg-white p-2.5 shadow-card">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={QR_SRC} alt={tr("dl_qr_t")} width={104} height={104} className="h-26 w-26" style={{ width: 104, height: 104 }} />
              </div>
              <div className="text-left">
                <div className="font-display text-base font-bold text-white/90">{tr("dl_qr_t")}</div>
                <div className="mt-1 max-w-[190px] text-sm text-white/50">{tr("dl_qr_d")}</div>
              </div>
            </div>

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
