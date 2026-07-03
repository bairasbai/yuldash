"use client";

import { useEffect, useState } from "react";
import { m, useReducedMotion } from "framer-motion";
import { useLang, type DictKey } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";

// На телефоне бесконечное «парение» телефонов (framer) двигает blur-слой каждый
// кадр → GPU греется. Отдаём статичные карточки на мобиле, парение — десктоп.
function useDesktopMotion() {
  const [ok, setOk] = useState(false);
  useEffect(() => {
    setOk(window.matchMedia("(min-width: 768px) and (pointer: fine)").matches);
  }, []);
  return ok;
}

// Витрина экранов приложения — РЕАЛЬНЫЕ скриншоты в готовой рамке телефона
// (mockup, прозрачный фон). Показываем целиком (object-contain, без обрезки).
// Премиум-анимация: reveal, мягкое парение. Уважает reduced-m.

const SCREENS: { src: string; label: DictKey }[] = [
  { src: "/screens/app-map.jpg", label: "sc_s1" },        // Карта поездок (JPEG — webp этой сложной карты не грузился на iOS)
  { src: "/screens/app-form.webp", label: "sc_s2" },      // Заявка и условия
  { src: "/screens/app-request.webp", label: "sc_s3" },   // Заявки и отклики
];

function PhoneShot({ src, label, delay, float }: { src: string; label: string; delay: number; float: number }) {
  const reduce = useReducedMotion();
  const desktop = useDesktopMotion();
  const floats = desktop && !reduce;
  return (
    <Reveal delay={delay} className="flex flex-col items-center">
      <m.div
        animate={floats ? { y: [0, -10, 0] } : undefined}
        transition={floats ? { duration: 6, repeat: Infinity, ease: "easeInOut", delay: float } : undefined}
        whileHover={reduce ? undefined : { y: -14, scale: 1.02 }}
        className="relative"
      >
        {/* мягкое свечение под телефоном */}
        <div className="pointer-events-none absolute inset-4 -z-10 rounded-[48px] bg-green-bright/20 blur-3xl" />
        {/* Обычный <img> eager: внутри motion-контейнера (парение) next/image
            lazy глючит на iOS Safari → битая картинка. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={src}
          alt={`Юлдаш — экран «${label}»`}
          width={232}
          height={500}
          loading="lazy"
          decoding="async"
          className="h-[500px] w-auto rounded-[34px] object-contain drop-shadow-[0_30px_60px_rgba(0,0,0,0.5)]"
        />
      </m.div>
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
          {/* float=0 у всех → парят синхронно, одинаково (не волной) */}
          <PhoneShot src={SCREENS[0].src} label={tr(SCREENS[0].label)} delay={0} float={0} />
          <PhoneShot src={SCREENS[1].src} label={tr(SCREENS[1].label)} delay={0.12} float={0} />
          <PhoneShot src={SCREENS[2].src} label={tr(SCREENS[2].label)} delay={0.24} float={0} />
        </div>
      </div>
    </section>
  );
}
