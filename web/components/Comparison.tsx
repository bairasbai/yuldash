"use client";

import { m } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// Граф-сравнение примерной цены поездки (Уфа → Сибай)
const PRICE: { key: DictKey; val: number; bar: string; tone: string }[] = [
  { key: "cmp_col_app", val: 1150, bar: "bg-gradient-to-r from-green-deep via-green to-green-bright", tone: "text-green-glow" },
  { key: "cmp_col_bus", val: 1560, bar: "bg-white/25", tone: "text-white/60" },
  { key: "cmp_col_taxi", val: 5500, bar: "bg-gradient-to-r from-gold/70 to-gold-light", tone: "text-gold-light" },
];
const PRICE_MAX = 5500;

type Mark = "yes" | "no" | "partial";
const rows: { label: DictKey; vals: [Mark, Mark, Mark] }[] = [
  { label: "cmp_r1", vals: ["yes", "no", "yes"] },
  { label: "cmp_r2", vals: ["yes", "no", "yes"] },
  { label: "cmp_r3", vals: ["yes", "no", "partial"] },
  { label: "cmp_r4", vals: ["yes", "no", "no"] },
  { label: "cmp_r5", vals: ["yes", "yes", "no"] },
  { label: "cmp_r6", vals: ["yes", "partial", "partial"] },
];

function Cell({ m }: { m: Mark }) {
  if (m === "yes")
    return (
      <span className="mx-auto flex h-7 w-7 items-center justify-center rounded-full bg-green-bright/20 text-green-glow">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><path d="m5 13 4 4L19 7" /></svg>
      </span>
    );
  if (m === "partial")
    return (
      <span className="mx-auto flex h-7 w-7 items-center justify-center rounded-full bg-gold/15 text-gold-light">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round"><path d="M5 12h14" /></svg>
      </span>
    );
  return (
    <span className="mx-auto flex h-7 w-7 items-center justify-center rounded-full bg-white/5 text-white/30">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round"><path d="M6 6l12 12M18 6 6 18" /></svg>
    </span>
  );
}

export function Comparison() {
  const { tr } = useLang();
  return (
    <section id="why" className="relative px-6 py-24">
      <div className="mx-auto max-w-4xl">
        <Reveal className="mx-auto mb-12 max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{tr("cmp_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("cmp_sub")}</p>
        </Reveal>

        {/* Граф: примерная цена поездки — столбцы растут при скролле */}
        <Reveal>
          <div className="glass mb-8 rounded-canon p-6 sm:p-8">
            <h3 className="font-display text-lg font-extrabold text-white sm:text-xl">{tr("cmp_price_title")}</h3>
            <p className="mt-1 text-sm text-white/50">{tr("cmp_price_sub")}</p>
            <div className="mt-6 space-y-4">
              {PRICE.map((b, i) => (
                <div key={b.key}>
                  <div className="mb-1.5 flex items-baseline justify-between text-sm">
                    <span className="font-semibold text-white/85">{tr(b.key)}</span>
                    <span className={`font-display font-extrabold ${b.tone}`}>
                      ≈ {b.val.toLocaleString("ru-RU")} ₽
                    </span>
                  </div>
                  <div className="h-3 overflow-hidden rounded-full bg-white/5">
                    <m.div
                      className={`h-full rounded-full ${b.bar}`}
                      initial={{ width: 0 }}
                      animate={{ width: `${(b.val / PRICE_MAX) * 100}%` }}
                      transition={{ duration: 1, delay: 0.15 + i * 0.12, ease: [0.21, 0.47, 0.32, 0.98] }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </Reveal>

        <Reveal>
          <div className="glass overflow-hidden rounded-canon">
            {/* шапка */}
            <div className="grid grid-cols-[1.6fr_1fr_1fr_1fr] items-center border-b border-white/10">
              <div className="px-3 py-4 sm:px-5" />
              <div className="relative px-1 py-4 text-center">
                <div className="absolute inset-x-1 inset-y-0 -z-0 rounded-t-2xl bg-green-bright/10" />
                <span className="relative font-display text-sm font-extrabold text-green-glow sm:text-base">
                  {tr("cmp_col_app")}
                </span>
              </div>
              <div className="px-1 py-4 text-center font-display text-sm font-bold text-white/55 sm:text-base">
                {tr("cmp_col_taxi")}
              </div>
              <div className="px-1 py-4 text-center font-display text-sm font-bold text-white/55 sm:text-base">
                {tr("cmp_col_bus")}
              </div>
            </div>

            {/* строки */}
            {rows.map((r, i) => (
              <div
                key={r.label}
                className={`grid grid-cols-[1.6fr_1fr_1fr_1fr] items-center ${
                  i < rows.length - 1 ? "border-b border-white/5" : ""
                }`}
              >
                <div className="px-3 py-3.5 text-sm text-white/80 sm:px-5 sm:text-[15px]">{tr(r.label)}</div>
                <div className="relative px-1 py-3.5">
                  <div className={`absolute inset-x-1 inset-y-0 -z-0 bg-green-bright/10 ${i === rows.length - 1 ? "rounded-b-2xl" : ""}`} />
                  <div className="relative"><Cell m={r.vals[0]} /></div>
                </div>
                <div className="px-1 py-3.5"><Cell m={r.vals[1]} /></div>
                <div className="px-1 py-3.5"><Cell m={r.vals[2]} /></div>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}
