"use client";

import { useState } from "react";
import { m } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { useDownload } from "./DownloadProvider";

const CITIES: [string, string][] = [
  ["Уфа", "Өфө"],
  ["Стерлитамак", "Стәрлетамаҡ"],
  ["Салават", "Салауат"],
  ["Сибай", "Сибай"],
  ["Нефтекамск", "Нефтекама"],
  ["Октябрьский", "Октябрьский"],
  ["Белорецк", "Белорет"],
  ["Баймак", "Баймаҡ"],
  ["Туймазы", "Туймазы"],
  ["Ишимбай", "Ишембай"],
  ["Дюртюли", "Дүртөйлө"],
  ["Учалы", "Учалы"],
];

export function SearchTrip() {
  const { tr, lang } = useLang();
  const { request } = useDownload();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const selectCls =
    "w-full appearance-none rounded-canon bg-white/5 px-4 py-3.5 pr-9 text-sm font-medium text-white outline-none ring-1 ring-white/10 transition focus:ring-green-bright";

  return (
    <section className="relative z-20 px-6 pt-8 pb-16">
      <Reveal className="mx-auto max-w-4xl">
        <div className="glass rounded-[28px] p-5 shadow-card sm:p-6">
          <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
            <Field label={tr("search_from")}>
              <div className="relative">
                <select value={from} onChange={(e) => setFrom(e.target.value)} className={selectCls}>
                  <option value="">{tr("search_from")}</option>
                  {CITIES.map((c) => (
                    <option key={c[0]} value={c[0]} className="bg-forest">
                      {lang === "ba" ? c[1] : c[0]}
                    </option>
                  ))}
                </select>
                <Chevron />
              </div>
            </Field>

            <Field label={tr("search_to")}>
              <div className="relative">
                <select value={to} onChange={(e) => setTo(e.target.value)} className={selectCls}>
                  <option value="">{tr("search_to")}</option>
                  {CITIES.map((c) => (
                    <option key={c[0]} value={c[0]} className="bg-forest">
                      {lang === "ba" ? c[1] : c[0]}
                    </option>
                  ))}
                </select>
                <Chevron />
              </div>
            </Field>

            <m.button
              type="button"
              onClick={request}
              whileTap={{ scale: 0.97 }}
              className="flex items-center justify-center gap-2 rounded-canon bg-green-bright px-7 py-3.5 font-bold text-night shadow-glow transition-transform hover:scale-[1.03]"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
              {tr("search_btn")}
            </m.button>
          </div>
          <p className="mt-3 text-center text-xs text-white/40 sm:text-left">{tr("search_hint")}</p>
        </div>
      </Reveal>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block px-1 text-xs font-semibold text-white/50">{label}</span>
      {children}
    </label>
  );
}

function Chevron() {
  return (
    <svg className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-white/40" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
