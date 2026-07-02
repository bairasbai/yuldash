"use client";

import Image from "next/image";
import { motion, useReducedMotion } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// Витрина экранов приложения — РЕАЛЬНЫЕ скриншоты (тёмная тема, снято с приложения),
// в фирменных рамках телефона. Премиум-анимация: reveal, мягкое парение. Уважает reduced-motion.

const SCREENS: { src: string; label: DictKey }[] = [
  { src: "/screens/app-map.jpg", label: "sc_s1" },       // Карта поездок
  { src: "/screens/app-form.jpg", label: "sc_s2" },      // Заявка и условия
  { src: "/screens/app-request.jpg", label: "sc_s3" },   // Заявки и отклики
];

function PhoneShot({ src, label, delay, float }: { src: string; label: string; delay: number; float: number }) {
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
          <div className="relative h-full w-full overflow-hidden rounded-[28px] bg-[#0e1714]">
            <Image
              src={src}
              alt={label}
              fill
              sizes="226px"
              className="object-cover object-top"
            />
          </div>
        </div>
      </motion.div>
      <div className="mt-5 text-sm font-semibold text-white/70">{label}</div>
    </Reveal>
  );
}

export function AppShowcase() {
  const { tr } = useLang();
  return (
    <section id="showcase" className="relative px-6 py-[var(--sp-section)]">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto mb-14 max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="fluid-h2 font-display font-extrabold">{tr("showcase_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("showcase_sub")}</p>
        </Reveal>

        <div className="flex flex-wrap items-center justify-center gap-10 lg:gap-8">
          <PhoneShot src={SCREENS[0].src} label={tr(SCREENS[0].label)} delay={0} float={0} />
          <div className="lg:-mt-8">
            <PhoneShot src={SCREENS[1].src} label={tr(SCREENS[1].label)} delay={0.12} float={1.2} />
          </div>
          <PhoneShot src={SCREENS[2].src} label={tr(SCREENS[2].label)} delay={0.24} float={2.4} />
        </div>
      </div>
    </section>
  );
}
