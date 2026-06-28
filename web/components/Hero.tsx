"use client";

import { useRef } from "react";
import {
  motion,
  useScroll,
  useTransform,
  useMotionValue,
  useSpring,
  useReducedMotion,
} from "framer-motion";
import { useLang } from "./lang";
import { DownloadButton } from "./DownloadButton";
import { PhoneMockup } from "./PhoneMockup";
import { Counter } from "./Counter";
import { APK_SIZE } from "./config";

const container = {
  hidden: {},
  show: { transition: { staggerChildren: 0.12, delayChildren: 0.1 } },
};
const item = {
  hidden: { opacity: 0, y: 22 },
  show: { opacity: 1, y: 0, transition: { duration: 0.7, ease: [0.21, 0.47, 0.32, 0.98] } },
};
const wordWrap = {
  hidden: {},
  show: { transition: { staggerChildren: 0.07, delayChildren: 0.15 } },
};
const word = {
  hidden: { opacity: 0, y: "0.5em", filter: "blur(6px)" },
  show: { opacity: 1, y: 0, filter: "blur(0px)", transition: { duration: 0.6, ease: [0.21, 0.47, 0.32, 0.98] } },
};

export function Hero() {
  const { tr } = useLang();
  const reduce = useReducedMotion();
  const ref = useRef<HTMLElement>(null);

  // Scroll-parallax
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end start"] });
  const yText = useTransform(scrollYProgress, [0, 1], [0, reduce ? 0 : -20]);
  const yPhone = useTransform(scrollYProgress, [0, 1], [0, reduce ? 0 : -40]);
  const fade = useTransform(scrollYProgress, [0, 0.95], [1, 0.15]);

  // Cursor-spotlight
  const sx = useSpring(useMotionValue(0), { stiffness: 120, damping: 20 });
  const sy = useSpring(useMotionValue(0), { stiffness: 120, damping: 20 });
  const onMove = (e: React.MouseEvent) => {
    const r = e.currentTarget.getBoundingClientRect();
    sx.set(e.clientX - r.left);
    sy.set(e.clientY - r.top);
  };

  const titleWords = tr("hero_title_1").split(" ");

  return (
    <section
      id="top"
      ref={ref}
      onMouseMove={reduce ? undefined : onMove}
      className="relative overflow-hidden px-6 pt-36 pb-10 sm:pt-44 lg:pb-12"
    >
      {/* спотлайт за курсором */}
      {!reduce && (
        <motion.div
          aria-hidden="true"
          className="pointer-events-none absolute -z-0 h-[420px] w-[420px] rounded-full bg-green-bright/10 blur-[100px]"
          style={{ left: sx, top: sy, x: "-50%", y: "-50%" }}
        />
      )}

      <motion.div
        style={{ opacity: fade }}
        className="relative z-10 mx-auto grid max-w-6xl items-center gap-14 lg:grid-cols-[1.05fr_0.95fr]"
      >
        {/* Левая колонка — текст */}
        <motion.div
          variants={container}
          initial="hidden"
          animate="show"
          style={{ y: yText }}
          className="text-center lg:text-left"
        >
          <motion.span
            variants={item}
            className="glass inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-sm font-semibold text-green-glow"
          >
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-bright opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-green-bright" />
            </span>
            {tr("hero_badge")}
          </motion.span>

          {/* Заголовок — пословное появление */}
          <motion.h1
            variants={wordWrap}
            className="mt-6 flex flex-wrap justify-center gap-x-[0.28em] font-display text-5xl font-extrabold leading-[1.05] tracking-tight sm:text-6xl lg:justify-start lg:text-7xl"
          >
            {titleWords.map((w, i) => (
              <motion.span key={i} variants={word} className="inline-block">
                {w}
              </motion.span>
            ))}
            <motion.span variants={word} className="text-gradient animate-shimmer inline-block">
              {tr("hero_title_2")}
            </motion.span>
          </motion.h1>

          <motion.p
            variants={item}
            className="mx-auto mt-6 max-w-xl text-lg leading-relaxed text-white/70 lg:mx-0"
          >
            {tr("hero_sub")}
          </motion.p>

          <motion.div
            variants={item}
            className="mt-9 flex flex-col items-center gap-4 sm:flex-row lg:justify-start"
          >
            <DownloadButton label={tr("hero_cta")} sub={`${tr("hero_cta_sub")}`.replace("~12 МБ", APK_SIZE)} />
            <a
              href="#how"
              className="rounded-canon border border-white/15 px-7 py-4 text-base font-semibold text-white/80 transition-all hover:-translate-y-0.5 hover:border-green-glow hover:bg-green-bright/10 hover:text-white"
            >
              {tr("hero_cta2")}
            </a>
          </motion.div>

          {/* Платформы */}
          <motion.div
            variants={item}
            className="mt-6 flex flex-wrap items-center justify-center gap-2.5 lg:justify-start"
          >
            <span className="inline-flex items-center gap-2 rounded-full border border-green-bright/30 bg-green-bright/10 px-3.5 py-1.5 text-sm font-semibold text-green-glow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 20.5 13.5 12 3 3.5C2.7 3.7 2.5 4.1 2.5 4.6v14.8c0 .5.2.9.5 1.1Zm12.3-7 2.7 2.7-9.6 5.5 6.9-8.2Zm0-3-6.9-8.2 9.6 5.5-2.7 2.7ZM20.5 12c.6.4.9 1 .9 1.6 0 .6-.3 1.2-.9 1.6l-2 1.1-3-2.7 3-2.7 2 1.1Z"/></svg>
              {tr("plat_android")}
            </span>
            <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3.5 py-1.5 text-sm font-medium text-white/55">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M16.7 12.7c0-2.3 1.9-3.4 2-3.5-1.1-1.6-2.8-1.8-3.4-1.8-1.4-.1-2.8.9-3.5.9-.7 0-1.8-.8-3-.8-1.5 0-2.9.9-3.7 2.3-1.6 2.7-.4 6.8 1.1 9 .7 1.1 1.6 2.3 2.7 2.2 1.1 0 1.5-.7 2.8-.7s1.6.7 2.8.7c1.2 0 1.9-1.1 2.6-2.1.8-1.2 1.2-2.4 1.2-2.4s-2.2-.9-2.2-3.5Zm-2.3-6.4c.6-.7 1-1.7.9-2.8-.9 0-2 .6-2.6 1.3-.6.6-1.1 1.6-.9 2.6 1 .1 2-.5 2.6-1.1Z"/></svg>
              {tr("plat_ios")}
            </span>
          </motion.div>

          <motion.p variants={item} className="mt-5 max-w-md text-sm text-white/45 lg:mx-0 mx-auto">
            {tr("hero_note")}
          </motion.p>

          {/* Мини-метрики */}
          <motion.div
            variants={item}
            className="mt-9 flex flex-wrap justify-center gap-x-8 gap-y-4 lg:justify-start"
          >
            <div>
              <div className="font-display text-2xl font-extrabold text-gold-light">
                <Counter value={100} suffix="%" />
              </div>
              <div className="mt-0.5 text-sm text-white/45">{tr("hero_s1_l")}</div>
            </div>
            <div>
              <div className="font-display text-2xl font-extrabold text-gold-light">{tr("hero_s2_n")}</div>
              <div className="mt-0.5 text-sm text-white/45">{tr("hero_s2_l")}</div>
            </div>
            <div>
              <div className="font-display text-2xl font-extrabold text-gold-light">{tr("hero_s3_n")}</div>
              <div className="mt-0.5 text-sm text-white/45">{tr("hero_s3_l")}</div>
            </div>
          </motion.div>
        </motion.div>

        {/* Правая колонка — телефон (параллакс) */}
        <motion.div style={{ y: yPhone }} className="flex justify-center">
          <PhoneMockup />
        </motion.div>
      </motion.div>
    </section>
  );
}
