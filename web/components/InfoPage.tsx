"use client";

import Link from "next/link";
import Image from "next/image";
import { motion } from "framer-motion";
import { ReactNode } from "react";
import { LangProvider, useLang } from "./lang";

const BACK = { ru: "На главную", ba: "Баш битькә" };

function LangToggle() {
  const { lang, setLang } = useLang();
  return (
    <div className="flex items-center rounded-full border border-white/10 bg-white/5 p-0.5 text-sm font-semibold">
      {(["ru", "ba"] as const).map((l) => (
        <button key={l} onClick={() => setLang(l)} className="relative z-10 rounded-full px-3 py-1" aria-pressed={lang === l}>
          {lang === l && (
            <motion.span layoutId="info-lang" className="absolute inset-0 -z-10 rounded-full bg-green-bright/90" transition={{ type: "spring", stiffness: 380, damping: 30 }} />
          )}
          <span className={lang === l ? "text-night" : "text-white/60"}>{l === "ru" ? "RU" : "БА"}</span>
        </button>
      ))}
    </div>
  );
}

function Chrome({ children }: { children: ReactNode }) {
  const { lang } = useLang();
  return (
    <main className="relative min-h-screen px-6 pb-24 pt-6">
      <div className="pointer-events-none fixed inset-0 -z-10 bg-[radial-gradient(120%_100%_at_50%_-10%,#10241a_0%,#0a1410_60%,#070f0b_100%)]" />
      <div className="mx-auto max-w-3xl">
        <div className="glass mb-10 flex items-center justify-between gap-4 rounded-full px-4 py-2.5">
          <Link href="/" className="flex items-center gap-2.5">
            <Image src="/logo.png" alt="Юлдаш" width={32} height={32} className="rounded-lg" />
            <span className="font-display text-lg font-extrabold">Юлдаш</span>
          </Link>
          <div className="flex items-center gap-3">
            <LangToggle />
            <Link href="/" className="hidden rounded-full bg-green-bright px-4 py-2 text-sm font-bold text-night transition-transform hover:scale-105 sm:block">
              {BACK[lang]}
            </Link>
          </div>
        </div>

        {children}

        <div className="mt-14 border-t border-white/10 pt-6">
          <Link href="/" className="text-green-glow transition-colors hover:text-white">
            ← {BACK[lang]}
          </Link>
        </div>
      </div>
    </main>
  );
}

export function InfoPage({ children }: { children: ReactNode }) {
  return (
    <LangProvider>
      <Chrome>{children}</Chrome>
    </LangProvider>
  );
}
