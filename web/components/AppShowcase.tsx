"use client";

import Image from "next/image";
import { motion, useReducedMotion } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// Витрина экранов приложения — РЕАЛЬНЫЕ скриншоты в готовой рамке телефона
// (mockup, прозрачный фон). Показываем целиком (object-contain, без обрезки).
// Премиум-анимация: reveal, мягкое парение. Уважает reduced-motion.

const SCREENS: { src: string; label: DictKey }[] = [
  { src: "/screens/app-map.png", label: "sc_s1" },       // Карта поездок
  { src: "/screens/app-form.png", label: "sc_s2" },      // Заявка и условия
  { src: "/screens/app-request.png", label: "sc_s3" },   // Заявки и отклики
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
        {/* мягкое свечение под телефоном */}
        <div className="pointer-events-none absolute inset-4 -z-10 rounded-[48px] bg-green-bright/20 blur-3xl" />
        <Image
          src={src}
          alt={label}
          width={232}
          height={500}
          sizes="232px"
          className="h-[500px] w-auto rounded-[34px] object-contain drop-shadow-[0_30px_60px_rgba(0,0,0,0.5)]"
        />
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

        <div className="flex flex-wrap items-start justify-center gap-8 lg:gap-6">
          <PhoneShot src={SCREENS[0].src} label={tr(SCREENS[0].label)} delay={0} float={0} />
          <PhoneShot src={SCREENS[1].src} label={tr(SCREENS[1].label)} delay={0.12} float={1.2} />
          <PhoneShot src={SCREENS[2].src} label={tr(SCREENS[2].label)} delay={0.24} float={2.4} />
        </div>
      </div>
    </section>
  );
}
