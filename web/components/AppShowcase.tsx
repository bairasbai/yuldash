"use client";

import { ReactNode } from "react";
import { motion, useReducedMotion } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// Витрина экранов приложения — стилизованные (не реальные скрины) превью в фирменном стиле.
// Три экрана: лента поездок, карта/маршрут, чат+безопасность. Премиум-анимация: reveal,
// мягкое парение, наклон при наведении. Всё уважает prefers-reduced-motion.

function PhoneFrame({ children, label, delay, float }: { children: ReactNode; label: string; delay: number; float: number }) {
  const reduce = useReducedMotion();
  return (
    <Reveal delay={delay} className="flex flex-col items-center">
      <motion.div
        animate={reduce ? undefined : { y: [0, -10, 0] }}
        transition={{ duration: 6, repeat: Infinity, ease: "easeInOut", delay: float }}
        whileHover={reduce ? undefined : { y: -14, scale: 1.02 }}
        className="relative"
      >
        <div className="pointer-events-none absolute -inset-5 -z-10 rounded-[48px] bg-green-bright/15 blur-3xl" />
        <div className="relative h-[460px] w-[226px] rounded-[36px] border-[8px] border-[#1c2722] bg-forest shadow-card">
          {/* островок */}
          <div className="absolute left-1/2 top-2.5 z-20 h-4 w-16 -translate-x-1/2 rounded-full bg-black/80" />
          <div className="relative h-full w-full overflow-hidden rounded-[28px] bg-[#0e1714]">{children}</div>
        </div>
      </motion.div>
      <div className="mt-5 text-sm font-semibold text-white/70">{label}</div>
    </Reveal>
  );
}

// ===== Экран 1: лента поездок =====
function ScreenRides() {
  const { tr, lang } = useLang();
  const rides = [
    { n: lang === "ba" ? "Айгөл" : "Айгуль", r: "4.9", from: lang === "ba" ? "Өфө" : "Уфа", to: lang === "ba" ? "Стәрлетамаҡ" : "Стерлитамак", p: "350 ₽", s: 3, v: true },
    { n: lang === "ba" ? "Илдар" : "Ильдар", r: "4.8", from: lang === "ba" ? "Сибай" : "Сибай", to: lang === "ba" ? "Баймаҡ" : "Баймак", p: "300 ₽", s: 2, v: true },
    { n: lang === "ba" ? "Рәмил" : "Рамиль", r: "5.0", from: lang === "ba" ? "Өфө" : "Уфа", to: lang === "ba" ? "Бөрө" : "Бирск", p: "400 ₽", s: 4, v: false },
  ];
  return (
    <div className="flex h-full flex-col p-3 pt-10">
      {/* поиск откуда→куда */}
      <div className="glass mb-3 flex items-center gap-2 rounded-2xl px-3 py-2.5 text-[11px] font-semibold text-white/85">
        <span className="h-2 w-2 rounded-full border-2 border-gold" />
        {lang === "ba" ? "Өфө" : "Уфа"}
        <svg className="mx-0.5 text-green-glow" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
        <span className="h-2 w-2 rounded-full bg-green-bright" />
        {lang === "ba" ? "Сибай" : "Сибай"}
      </div>
      <div className="flex flex-1 flex-col gap-2.5">
        {rides.map((r, i) => (
          <div key={i} className="rounded-2xl bg-[#141d19] p-3">
            <div className="flex items-center gap-2.5">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-green-bright/30 to-green-deep/40 text-xs font-extrabold text-green-glow ring-1 ring-green-bright/20">
                {r.n.charAt(0)}
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-1.5 text-[12px] font-bold text-white">
                  {r.n}
                  {r.v && (
                    <span className="inline-flex h-3.5 w-3.5 items-center justify-center rounded-full bg-green-bright/20 text-green-glow">
                      <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round"><path d="m5 13 4 4L19 7" /></svg>
                    </span>
                  )}
                  <span className="ml-auto text-[10px] font-semibold text-gold-light">★ {r.r}</span>
                </div>
                <div className="mt-0.5 text-[10px] text-white/45">{r.from} → {r.to}</div>
              </div>
            </div>
            <div className="mt-2 flex items-center justify-between">
              <span className="text-[10px] text-white/45">{r.s} {tr("sc_seats")}</span>
              <span className="font-display text-sm font-extrabold text-gold-light">{r.p}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ===== Экран 2: карта и маршрут =====
function ScreenMap() {
  const { tr, lang } = useLang();
  return (
    <div className="relative h-full w-full">
      <svg viewBox="0 0 210 460" preserveAspectRatio="xMidYMid slice" className="absolute inset-0 h-full w-full" aria-hidden="true">
        <defs>
          <radialGradient id="scMapBg" cx="30%" cy="10%" r="120%">
            <stop offset="0%" stopColor="#17291f" /><stop offset="60%" stopColor="#0e1714" /><stop offset="100%" stopColor="#0a1310" />
          </radialGradient>
          <linearGradient id="scRoute" x1="0" y1="1" x2="1" y2="0">
            <stop offset="0%" stopColor="#0E8247" /><stop offset="60%" stopColor="#2FB36E" /><stop offset="100%" stopColor="#7FE3AB" />
          </linearGradient>
          <filter id="scGlow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="4" /></filter>
        </defs>
        <rect width="210" height="460" fill="url(#scMapBg)" />
        <g fill="#13201a" opacity="0.7">
          <rect x="14" y="40" width="46" height="40" rx="6" /><rect x="74" y="30" width="42" height="34" rx="6" /><rect x="130" y="48" width="48" height="42" rx="6" />
          <rect x="20" y="110" width="44" height="48" rx="6" /><rect x="120" y="130" width="52" height="44" rx="6" /><rect x="30" y="210" width="46" height="46" rx="6" />
          <rect x="118" y="240" width="50" height="48" rx="6" /><rect x="24" y="320" width="48" height="50" rx="6" /><rect x="120" y="340" width="52" height="52" rx="6" />
        </g>
        <g fill="none" strokeLinecap="round">
          <g stroke="#0b1410" strokeWidth="10"><path d="M-10 96 L100 120 L220 90" /><path d="M40 -10 L60 240 L36 470" /><path d="M-10 300 L120 320 L220 290" /></g>
          <g stroke="#22332b" strokeWidth="5"><path d="M-10 96 L100 120 L220 90" /><path d="M40 -10 L60 240 L36 470" /><path d="M-10 300 L120 320 L220 290" /></g>
        </g>
        <path d="M55 400 C 100 340 80 240 130 200 S 170 110 160 80" fill="none" stroke="#2FB36E" strokeWidth="6" strokeLinecap="round" opacity="0.4" filter="url(#scGlow)" />
        <path className="mockup-route" d="M55 400 C 100 340 80 240 130 200 S 170 110 160 80" fill="none" stroke="url(#scRoute)" strokeWidth="5" strokeLinecap="round" />
        <circle className="mockup-ping" cx="55" cy="400" r="6" fill="#D89B12" />
        <circle cx="55" cy="400" r="6" fill="#D89B12" stroke="#0a1310" strokeWidth="3" />
        <g className="mockup-pin">
          <circle cx="160" cy="80" r="11" fill="#D89B12" />
          <path d="M160 73 c-2.4 0-4.3 1.9-4.3 4.3 0 3.2 4.3 7.6 4.3 7.6s4.3-4.4 4.3-7.6c0-2.4-1.9-4.3-4.3-4.3z" fill="#0a1310" />
        </g>
        <g className="mockup-car"><circle r="8" fill="#2FB36E" stroke="#0a1310" strokeWidth="3" /><path d="M-2.2 -2.8 L3.5 0 L-2.2 2.8 Z" fill="#0a1310" /></g>
      </svg>
      <div className="pointer-events-none absolute inset-x-0 top-0 z-[5] h-16 bg-gradient-to-b from-black/45 to-transparent" />
      <div className="glass absolute left-3 top-10 z-10 flex items-center gap-2 rounded-full py-1.5 pl-2 pr-3">
        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-green-bright/20 text-green-glow">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
        </span>
        <span className="text-[11px] font-semibold leading-none text-white">12 {lang === "ba" ? "мин" : "мин"}<span className="ml-1 font-medium text-white/45">· 8 {lang === "ba" ? "км" : "км"}</span></span>
      </div>
      {/* нижняя мини-плашка пункта */}
      <div className="glass absolute inset-x-3 bottom-3 z-10 rounded-2xl px-3 py-2.5">
        <div className="text-[11px] font-bold text-white">{tr("sc_s2")}</div>
        <div className="mt-0.5 text-[10px] text-white/50">{lang === "ba" ? "Өфө → Стәрлетамаҡ" : "Уфа → Стерлитамак"}</div>
      </div>
    </div>
  );
}

// ===== Экран 3: чат и безопасность =====
function ScreenChat() {
  const { tr, lang } = useLang();
  const driver = lang === "ba" ? "Айгөл" : "Айгуль";
  return (
    <div className="flex h-full flex-col p-3 pt-10">
      {/* шапка чата */}
      <div className="mb-3 flex items-center gap-2.5 border-b border-white/8 pb-3">
        <div className="relative">
          <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-green-bright/30 to-green-deep/40 text-sm font-extrabold text-green-glow ring-1 ring-green-bright/20">{driver.charAt(0)}</div>
          <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full bg-green-bright ring-2 ring-[#0e1714]" />
        </div>
        <div>
          <div className="text-[12px] font-bold text-white">{driver}</div>
          <div className="inline-flex items-center gap-1 text-[9px] font-semibold text-green-glow">
            <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round"><path d="m5 13 4 4L19 7" /></svg>
            {lang === "ba" ? "Тикшерелгән" : "Проверена"}
          </div>
        </div>
      </div>
      {/* сообщения */}
      <div className="flex flex-1 flex-col gap-2">
        <div className="max-w-[80%] self-start rounded-2xl rounded-bl-md bg-[#1a2520] px-3 py-2 text-[11px] text-white/85">{tr("sc_msg_in")}</div>
        <div className="max-w-[80%] self-end rounded-2xl rounded-br-md bg-green-bright px-3 py-2 text-[11px] font-medium text-night">{tr("sc_msg_out")}</div>
      </div>
      {/* безопасность */}
      <div className="mt-3 flex gap-2">
        <div className="flex flex-1 items-center justify-center gap-1.5 rounded-full bg-red-500/15 py-2 text-[11px] font-bold text-red-300">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" /></svg>
          {tr("sc_sos")}
        </div>
        <button className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-white/8 text-green-glow" aria-label={tr("sc_share")}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.6 13.5 6.8 4M15.4 6.5 8.6 10.5" /></svg>
        </button>
      </div>
    </div>
  );
}

export function AppShowcase() {
  const { tr } = useLang();
  return (
    <section id="showcase" className="relative px-6 py-24">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto mb-14 max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="font-display text-4xl font-extrabold tracking-tight sm:text-5xl">{tr("showcase_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("showcase_sub")}</p>
        </Reveal>

        <div className="flex flex-wrap items-center justify-center gap-10 lg:gap-8">
          <PhoneFrame label={tr("sc_s1")} delay={0} float={0}><ScreenRides /></PhoneFrame>
          <div className="lg:-mt-8"><PhoneFrame label={tr("sc_s2")} delay={0.12} float={1.2}><ScreenMap /></PhoneFrame></div>
          <PhoneFrame label={tr("sc_s3")} delay={0.24} float={2.4}><ScreenChat /></PhoneFrame>
        </div>
      </div>
    </section>
  );
}
