"use client";

import { useState } from "react";
import { motion } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { BorderBeam } from "./BorderBeam";
import { Megaphone, Handshake, Spark } from "./icons";
import { SOCIAL, PARTNER_PRICES, PARTNER_PERIODS, PARTNER_INTRO_OFFER } from "./config";
import { track } from "./analytics";
import { ComponentType } from "react";

type IconC = ComponentType<{ className?: string; size?: number }>;
const perks: { Icon: IconC; k: DictKey }[] = [
  { Icon: Megaphone, k: "partner_1" },
  { Icon: Handshake, k: "partner_2" },
  { Icon: Spark, k: "partner_3" },
];

type Plan = { name: DictKey; price: number; features: DictKey[]; tag?: DictKey; highlight?: boolean };
const fmt = (n: number) => n.toLocaleString("ru-RU");
const perMonth = (base: number, off: number) => Math.round((base * (1 - off)) / 10) * 10;
const PLANS: Plan[] = [
  { name: "plan1_name", price: PARTNER_PRICES.founder, tag: "plan1_badge", features: ["plan1_f1", "plan1_f2", "plan1_f3"] },
  { name: "plan2_name", price: PARTNER_PRICES.standard, features: ["plan2_f1", "plan2_f2", "plan2_f3"] },
  { name: "plan3_name", price: PARTNER_PRICES.premium, tag: "plan3_popular", highlight: true, features: ["plan3_f1", "plan3_f2", "plan3_f3"] },
];

export function Partners() {
  const { tr } = useLang();
  const [periodIdx, setPeriodIdx] = useState(0);
  const period = PARTNER_PERIODS[periodIdx];
  return (
    <section id="partners" className="relative px-6 py-24">
      <div className="mx-auto max-w-5xl">
        <Reveal>
          <div className="relative overflow-hidden rounded-[34px] border border-gold/20 bg-gradient-to-br from-gold/[0.10] via-transparent to-green-bright/[0.06] p-10 sm:p-14">
            <BorderBeam />
            {/* золотое свечение */}
            <div className="pointer-events-none absolute -right-20 -top-24 h-72 w-72 rounded-full bg-gold/15 blur-[100px]" />

            <div className="relative grid gap-10 lg:grid-cols-[1.1fr_0.9fr] lg:items-center">
              {/* Левая часть — текст */}
              <div>
                <span className="inline-flex items-center gap-2 rounded-full bg-gold/15 px-3.5 py-1.5 text-sm font-semibold text-gold-light">
                  <Megaphone size={16} />
                  {tr("partner_kicker")}
                </span>
                <h2 className="mt-5 font-display text-3xl font-extrabold tracking-tight sm:text-4xl">
                  {tr("partner_title")}
                </h2>
                <p className="mt-4 max-w-xl leading-relaxed text-white/65">{tr("partner_sub")}</p>
              </div>

              {/* Правая часть — перки */}
              <div className="space-y-3">
                {perks.map((p, i) => (
                  <Reveal key={p.k} delay={i * 0.08}>
                    <motion.div
                      whileHover={{ x: 4 }}
                      transition={{ type: "spring", stiffness: 300, damping: 20 }}
                      className="glass flex items-center gap-3 rounded-canon p-4"
                    >
                      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-gold/15 text-gold-light">
                        <p.Icon size={20} />
                      </span>
                      <span className="text-sm font-semibold text-white/85">{tr(p.k)}</span>
                    </motion.div>
                  </Reveal>
                ))}
              </div>
            </div>
          </div>
        </Reveal>

        {/* Тарифы размещения */}
        <Reveal className="mx-auto mt-12 max-w-2xl text-center">
          <h3 className="font-display text-2xl font-extrabold sm:text-3xl">{tr("partner_plans_title")}</h3>
          <p className="mt-3 text-white/60">{tr("partner_plans_sub")}</p>
        </Reveal>

        {PARTNER_INTRO_OFFER && (
          <Reveal className="mx-auto mt-6 max-w-2xl">
            <div className="flex items-center justify-center gap-2.5 rounded-canon border border-gold/40 bg-gradient-to-r from-gold/15 to-transparent px-5 py-3 text-center">
              <svg className="shrink-0 text-gold-light" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 12v9H4v-9M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" /></svg>
              <span className="text-sm font-semibold text-gold-light">{tr("partner_offer")}</span>
            </div>
          </Reveal>
        )}

        {/* Срок размещения — предоплата со скидкой */}
        <Reveal className="mt-7 flex flex-col items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-white/40">{tr("partner_period")}</span>
          <div className="inline-flex flex-wrap justify-center gap-1 rounded-full border border-white/10 bg-white/5 p-1">
            {PARTNER_PERIODS.map((pp, i) => (
              <button
                key={pp.months}
                type="button"
                onClick={() => setPeriodIdx(i)}
                className={`relative rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
                  i === periodIdx ? "bg-gold text-night" : "text-white/65 hover:text-white"
                }`}
              >
                {pp.months} {tr("partner_mo_short")}
                {pp.off > 0 && (
                  <span className={`ml-1 text-xs font-bold ${i === periodIdx ? "text-night/90" : "text-gold-light"}`}>
                    −{Math.round(pp.off * 100)}%
                  </span>
                )}
              </button>
            ))}
          </div>
        </Reveal>

        <div className="mt-8 grid gap-4 sm:grid-cols-3">
          {PLANS.map((p, i) => (
            <Reveal key={p.name} delay={i * 0.08}>
              <div
                className={`relative flex h-full flex-col rounded-canon p-6 ${
                  p.highlight
                    ? "border border-gold/40 bg-gradient-to-b from-gold/[0.12] to-transparent shadow-gold"
                    : "glass"
                }`}
              >
                {p.tag && (
                  <span className="absolute -top-3 left-6 rounded-full bg-gold px-3 py-1 text-xs font-bold text-night shadow-gold">
                    {tr(p.tag)}
                  </span>
                )}
                <div className="font-display text-lg font-extrabold">{tr(p.name)}</div>
                <div className="mt-3 flex items-baseline gap-1">
                  <span className="font-display text-3xl font-extrabold text-gold-light">{fmt(perMonth(p.price, period.off))} ₽</span>
                  <span className="text-sm text-white/45">{tr("partner_mo")}</span>
                </div>
                {period.months > 1 ? (
                  <div className="mt-1 flex items-center gap-2 text-xs">
                    <span className="text-white/35 line-through">{fmt(p.price)} ₽</span>
                    <span className="text-white/50">
                      {period.months} {tr("partner_mo_short")} · {fmt(perMonth(p.price, period.off) * period.months)} ₽
                    </span>
                  </div>
                ) : (
                  <div className="mt-1 text-xs text-white/35">{period.months} {tr("partner_mo_short")}</div>
                )}
                <ul className="mt-5 flex-1 space-y-2.5">
                  {p.features.map((f) => (
                    <li key={f} className="flex items-start gap-2 text-sm text-white/75">
                      <svg className="mt-0.5 shrink-0 text-green-glow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="m5 13 4 4L19 7" /></svg>
                      {tr(f)}
                    </li>
                  ))}
                </ul>
              </div>
            </Reveal>
          ))}
        </div>

        {/* Один блок связи — Telegram и ВКонтакте видимы сразу */}
        <Reveal className="mx-auto mt-10 max-w-xl text-center">
          <p className="font-display text-lg font-bold">{tr("partner_contact")}</p>
          <div className="mt-4 flex flex-wrap justify-center gap-3">
            <a
              href={SOCIAL.telegram}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => track("partner_telegram")}
              className="inline-flex items-center gap-2 rounded-canon bg-gold px-6 py-3 font-bold text-night shadow-gold transition-transform hover:scale-[1.04] active:scale-95"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21.9 4.3 18.6 20c-.2 1.1-.9 1.4-1.8.9l-4.9-3.6-2.4 2.3c-.3.3-.5.5-1 .5l.3-5 9.1-8.2c.4-.4-.1-.6-.6-.2L6.3 13.9l-4.8-1.5c-1-.3-1-1 .2-1.5l18.8-7.2c.9-.3 1.6.2 1.4 1.6Z"/></svg>
              Telegram
            </a>
            <a
              href={SOCIAL.vk}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => track("partner_vk")}
              className="inline-flex items-center gap-2 rounded-canon border border-white/15 px-6 py-3 font-bold text-white transition-colors hover:border-blue-300 hover:bg-blue-400/10"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" className="text-blue-300" aria-hidden="true"><path d="M13.2 17c-5.3 0-8.5-3.7-8.6-9.8h2.7c.1 4.5 2.1 6.4 3.7 6.8V7.2h2.5v3.8c1.6-.2 3.2-2 3.8-3.8h2.5c-.4 2.2-2 4-3.2 4.7 1.2.6 3 2.2 3.7 4.9h-2.8c-.5-1.7-2-3-3.7-3.2V17h-.3Z"/></svg>
              ВКонтакте
            </a>
          </div>
          <p className="mt-5 text-sm leading-relaxed text-white/50">{tr("partner_note")}</p>
        </Reveal>
      </div>
    </section>
  );
}
