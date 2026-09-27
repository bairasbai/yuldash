"use client";

import Link from "next/link";
import Image from "next/image";
import { m } from "framer-motion";
import { LangProvider, useLang } from "./lang";
import type { LegalDoc } from "./legal-content";

const CHROME = {
  back: { ru: "На главную", ba: "Баш битькә" },
  updated: { ru: "Обновлено", ba: "Яңыртылды" },
  notice: {
    ru: "Документ приводится на русском языке как юридически значимый. Перевод на башкирский готовится.",
    ba: "Документ юридик әһәмиәтле тел булараҡ рус телендә бирелә. Башҡортса тәржемә әҙерләнә.",
  },
};

function LangToggle() {
  const { lang, setLang } = useLang();
  return (
    <div className="flex items-center rounded-full border border-white/10 bg-white/5 p-0.5 text-sm font-semibold">
      {(["ru", "ba"] as const).map((l) => (
        <button
          key={l}
          onClick={() => setLang(l)}
          className="relative z-10 rounded-full px-3 py-1 transition-colors"
          aria-pressed={lang === l}
        >
          {lang === l && (
            <m.span
              layoutId="legal-lang-pill"
              className="absolute inset-0 -z-10 rounded-full bg-green-bright/90"
              transition={{ type: "spring", stiffness: 380, damping: 30 }}
            />
          )}
          <span className={lang === l ? "text-night" : "text-white/60"}>
            {l === "ru" ? "RU" : "БА"}
          </span>
        </button>
      ))}
    </div>
  );
}

function Inner({ doc }: { doc: LegalDoc }) {
  const { lang } = useLang();
  return (
    <main id="top" tabIndex={-1} className="relative min-h-screen px-6 pb-24 pt-6">
      {/* фон */}
      <div className="pointer-events-none fixed inset-0 -z-10 bg-[radial-gradient(120%_100%_at_50%_-10%,#10241a_0%,#0a1410_60%,#070f0b_100%)]" />

      <div className="mx-auto max-w-3xl">
        {/* шапка */}
        <div className="glass mb-10 flex items-center justify-between gap-4 rounded-full px-4 py-2.5">
          <Link href="/" className="flex items-center gap-2.5">
            <Image src="/logo.png" alt="Юлдаш" width={32} height={32} className="rounded-lg" />
            <span className="font-display text-lg font-extrabold">Юлдаш</span>
          </Link>
          <div className="flex items-center gap-3">
            <LangToggle />
            <Link
              href="/"
              className="hidden rounded-full bg-green-bright px-4 py-2 text-sm font-bold text-night transition-transform hover:scale-105 sm:block"
            >
              {CHROME.back[lang]}
            </Link>
          </div>
        </div>

        {/* заголовок */}
        <h1 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">
          {doc.title[lang]}
        </h1>
        <p className="mt-3 text-sm text-white/45">
          {CHROME.updated[lang]}: {doc.updated}
        </p>

        {/* двуязычная пометка */}
        <p className="mt-6 rounded-canon border border-white/10 bg-white/5 px-5 py-3 text-sm text-white/55">
          {CHROME.notice[lang]}
        </p>

        {/* вводные абзацы */}
        <div className="mt-8 space-y-4">
          {doc.intro.map((t, i) => (
            <p key={i} className="leading-relaxed text-white/75">
              {t}
            </p>
          ))}
        </div>

        {/* разделы */}
        <div className="mt-10 space-y-9">
          {doc.sections.map((s) => (
            <section key={s.h}>
              <h2 className="font-display text-xl font-bold text-white">{s.h}</h2>
              <div className="mt-3 space-y-2.5">
                {s.p.map((t, i) => (
                  <p key={i} className="leading-relaxed text-white/70">
                    {t}
                  </p>
                ))}
              </div>
            </section>
          ))}
        </div>

        {/* низ */}
        <div className="mt-14 border-t border-white/10 pt-6">
          <Link href="/" className="text-green-glow transition-colors hover:text-white">
            ← {CHROME.back[lang]}
          </Link>
        </div>
      </div>
    </main>
  );
}

export function LegalView({ doc }: { doc: LegalDoc }) {
  return (
    <LangProvider>
      <Inner doc={doc} />
    </LangProvider>
  );
}
