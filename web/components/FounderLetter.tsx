"use client";

import Image from "next/image";
import { useRef, useState } from "react";
import {
  AnimatePresence,
  m,
  useMotionValue,
  useSpring,
  useTransform,
  useReducedMotion,
  useInView,
} from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { Typewriter } from "./Typewriter";
import { DownloadButton } from "./DownloadButton";
import { FOUNDER } from "./founder-content";
import { BorderBeam } from "./BorderBeam";
import { SOCIAL } from "./config";
import { track } from "./analytics";

export function FounderLetter() {
  const { lang, tr } = useLang();
  const [contactOpen, setContactOpen] = useState(false);
  const reduce = useReducedMotion();

  // Depth-параллакс фото по движению мыши (слои едут с разной скоростью)
  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const sx = useSpring(mx, { stiffness: 120, damping: 18 });
  const sy = useSpring(my, { stiffness: 120, damping: 18 });
  const imgX = useTransform(sx, (v) => v * 10);
  const imgY = useTransform(sy, (v) => v * 10);
  const glowX = useTransform(sx, (v) => v * -26);
  const glowY = useTransform(sy, (v) => v * -20);
  const plateX = useTransform(sx, (v) => v * 18);
  const plateY = useTransform(sy, (v) => v * 14);
  const onMove = (e: React.MouseEvent) => {
    const r = e.currentTarget.getBoundingClientRect();
    mx.set((e.clientX - r.left) / r.width - 0.5);
    my.set((e.clientY - r.top) / r.height - 0.5);
  };
  const onLeave = () => {
    mx.set(0);
    my.set(0);
  };

  // Цитата печатается, когда доскроллили до неё
  const pullRef = useRef<HTMLQuoteElement>(null);
  const pullInView = useInView(pullRef, { once: true, margin: "-20% 0px" });

  return (
    <section id="founder" className="relative overflow-hidden px-6 py-24">
      {/* Кинематографичный фон: размытые огни ночной дороги (Higgsfield, ~60K) */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 opacity-[0.16] [mask-image:radial-gradient(120%_80%_at_50%_40%,#000_0%,transparent_75%)]"
      >
        <Image src="/nightroad.webp" alt="" fill sizes="100vw" className="object-cover object-center" />
      </div>

      <div className="mx-auto grid max-w-6xl gap-12 lg:grid-cols-[0.85fr_1.15fr] lg:gap-16">
        {/* Фото создателя */}
        <Reveal className="lg:sticky lg:top-28 lg:self-start">
          <div
            className="relative mx-auto max-w-sm [perspective:1000px]"
            onMouseMove={reduce ? undefined : onMove}
            onMouseLeave={reduce ? undefined : onLeave}
          >
            <m.div
              style={reduce ? undefined : { x: glowX, y: glowY }}
              className="pointer-events-none absolute -inset-4 -z-10 rounded-[34px] bg-green-bright/15 blur-3xl"
            />
            <m.div
              style={reduce ? undefined : { x: imgX, y: imgY }}
              className="relative overflow-hidden rounded-[28px] border border-white/10 will-change-transform"
            >
              {/* Обычный <img> eager: внутри параллакс-m.div next/image
                  lazy глючит на iOS Safari (фото не грузилось). */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="/founder.jpg"
                alt={FOUNDER.alt[lang]}
                width={800}
                height={1000}
                loading="lazy"
                decoding="async"
                className="h-auto w-full"
              />
              <BorderBeam />
            </m.div>
            {/* плашка с именем */}
            <m.div
              style={reduce ? undefined : { x: plateX, y: plateY }}
              className="glass absolute bottom-3 left-3 right-3 flex items-center gap-3 rounded-2xl px-4 py-3">
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-green-bright/20 text-green-glow">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
                  <path d="m9 12 2 2 4-4" />
                </svg>
              </span>
              <div>
                <div className="font-display text-sm font-extrabold leading-tight">{FOUNDER.name}</div>
                <div className="text-xs text-white/55">{FOUNDER.role[lang]}</div>
              </div>
            </m.div>
          </div>
        </Reveal>

        {/* Письмо */}
        <div>
          <Reveal>
            <span className="inline-flex items-center gap-2 rounded-full bg-green-bright/12 px-4 py-1.5 text-sm font-semibold text-green-glow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 21s-7.5-4.6-10-9.3C.3 8.4 1.7 4.7 5.2 4.1 7.3 3.7 9.2 4.8 12 7.4c2.8-2.6 4.7-3.7 6.8-3.3 3.5.6 4.9 4.3 3.2 7.6C19.5 16.4 12 21 12 21Z"/></svg>
              {FOUNDER.badge[lang]}
            </span>
          </Reveal>

          {/* Вводный абзац */}
          <Reveal>
            <p className="mt-6 font-display text-2xl font-bold leading-snug text-white sm:text-3xl">
              {FOUNDER.lead[lang]}
            </p>
          </Reveal>
          <Reveal delay={0.05}>
            <p className="mt-4 leading-relaxed text-white/70">{FOUNDER.intro[lang]}</p>
          </Reveal>

          {/* Список проблем */}
          <Reveal delay={0.1}>
            <div className="mt-6 rounded-canon border border-white/10 bg-white/[0.03] p-5 sm:p-6">
              <h3 className="font-display text-sm font-bold uppercase tracking-wide text-white/45">
                {FOUNDER.problemsTitle[lang]}
              </h3>
              <ul className="mt-4 space-y-3.5">
                {FOUNDER.problems.map((p, i) => (
                  <m.li
                    key={i}
                    initial={{ x: -10 }}
                    whileInView={{ opacity: 1, x: 0 }}
                    viewport={{ once: true }}
                    transition={{ delay: Math.min(i * 0.07, 0.35), duration: 0.45, ease: [0.21, 0.47, 0.32, 0.98] }}
                    className="flex items-start gap-3"
                  >
                    <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-gold/15 text-gold-light">
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
                        <path d="M18 6 6 18M6 6l12 12" />
                      </svg>
                    </span>
                    <span className="text-sm leading-relaxed text-white/70">{p[lang]}</span>
                  </m.li>
                ))}
              </ul>
            </div>
          </Reveal>

          {/* Решение → выгоды → честность */}
          <div className="mt-6 space-y-4">
            {FOUNDER.body.map((p, i) => (
              <Reveal key={i} delay={Math.min(i * 0.05, 0.2)}>
                <p className="leading-relaxed text-white/70">{p[lang]}</p>
              </Reveal>
            ))}
          </div>

          {/* Цитата-акцент */}
          <blockquote ref={pullRef} className="my-8 border-l-2 border-green-bright pl-5">
            <p className="font-display text-xl font-extrabold text-gradient sm:text-2xl">
              {pullInView ? (
                <Typewriter key={lang} once phrases={[FOUNDER.pull[lang]]} startDelayMs={200} typeMs={38} />
              ) : (
                <span className="opacity-0">{FOUNDER.pull[lang]}</span>
              )}
            </p>
          </blockquote>

          <Reveal>
            <p className="leading-relaxed text-white/70">{FOUNDER.closing[lang]}</p>
            <p className="mt-4 font-display text-lg font-bold text-white">
              {FOUNDER.go[lang]}
            </p>
          </Reveal>

          {/* Подпись */}
          <Reveal>
            <div className="mt-6 text-white/60">
              <p>{FOUNDER.signoff[lang]}</p>
              <p className="mt-1 font-display text-lg font-extrabold text-white">{FOUNDER.name}</p>
              <p className="text-sm text-white/50">{FOUNDER.role[lang]}</p>
            </div>
          </Reveal>

          {/* Действия */}
          <Reveal>
            <div className="mt-8">
              <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center">
                <DownloadButton label={tr("hero_cta")} sub={tr("hero_cta_sub")} size="md" />
                <button
                  type="button"
                  onClick={() => {
                    setContactOpen((v) => !v);
                    track("founder_contact_intent");
                  }}
                  aria-expanded={contactOpen}
                  className="inline-flex items-center gap-2 rounded-canon border border-white/15 px-6 py-3 text-sm font-semibold text-white/80 transition-all hover:-translate-y-0.5 hover:border-green-glow hover:text-white"
                >
                  {FOUNDER.write[lang]}
                  <m.svg
                    animate={{ rotate: contactOpen ? 180 : 0 }}
                    transition={{ duration: 0.3, ease: [0.21, 0.47, 0.32, 0.98] }}
                    width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"
                  >
                    <path d="m6 9 6 6 6-6" />
                  </m.svg>
                </button>
              </div>

              {/* выбор канала связи — плавно по высоте */}
              <AnimatePresence initial={false}>
                {contactOpen && (
                  <m.div
                    key="founder-channels"
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.35, ease: [0.21, 0.47, 0.32, 0.98] }}
                    className="overflow-hidden"
                  >
                    <div className="flex flex-wrap gap-2 pt-3">
                      <m.a
                        href={SOCIAL.telegram}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={() => track("founder_telegram")}
                        initial={{ opacity: 0, y: 8 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: 8 }}
                        transition={{ duration: 0.3, delay: 0.08, ease: [0.21, 0.47, 0.32, 0.98] }}
                        className="inline-flex items-center gap-2 rounded-canon border border-white/15 px-5 py-3 text-sm font-semibold text-white transition-colors hover:border-green-glow hover:bg-green-bright/10"
                      >
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" className="text-green-glow" aria-hidden="true"><path d="M21.9 4.3 18.6 20c-.2 1.1-.9 1.4-1.8.9l-4.9-3.6-2.4 2.3c-.3.3-.5.5-1 .5l.3-5 9.1-8.2c.4-.4-.1-.6-.6-.2L6.3 13.9l-4.8-1.5c-1-.3-1-1 .2-1.5l18.8-7.2c.9-.3 1.6.2 1.4 1.6Z"/></svg>
                        Telegram
                      </m.a>
                      <m.a
                        href={SOCIAL.vk}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={() => track("founder_vk")}
                        initial={{ opacity: 0, y: 8 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: 8 }}
                        transition={{ duration: 0.3, delay: 0.16, ease: [0.21, 0.47, 0.32, 0.98] }}
                        className="inline-flex items-center gap-2 rounded-canon border border-white/15 px-5 py-3 text-sm font-semibold text-white transition-colors hover:border-blue-300 hover:bg-blue-400/10"
                      >
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" className="text-blue-300" aria-hidden="true"><path d="M13.2 17c-5.3 0-8.5-3.7-8.6-9.8h2.7c.1 4.5 2.1 6.4 3.7 6.8V7.2h2.5v3.8c1.6-.2 3.2-2 3.8-3.8h2.5c-.4 2.2-2 4-3.2 4.7 1.2.6 3 2.2 3.7 4.9h-2.8c-.5-1.7-2-3-3.7-3.2V17h-.3Z"/></svg>
                        ВКонтакте
                      </m.a>
                    </div>
                  </m.div>
                )}
              </AnimatePresence>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
