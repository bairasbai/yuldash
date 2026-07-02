"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import { useReducedMotion } from "framer-motion";

/**
 * Кинематографичный фон героя: тёплый постер дороги (LCP, ~160K) + поверх него
 * лениво подгружаемое кинофон-видео (дрон, Higgsfield) + CSS-«световые следы».
 * Видео грузится в простое (постер уже отрисован → быстрый LCP), плавно
 * проявляется. При prefers-reduced-motion видео и движение выключены — только постер.
 */
export function HeroBackdrop() {
  const reduce = useReducedMotion();
  const [showVideo, setShowVideo] = useState(false);
  const [videoReady, setVideoReady] = useState(false);

  // Кинофон-видео (5 МБ) — тяжёлое. Грузим ТОЛЬКО на десктопе и быстром
  // соединении. На телефоне/медленном/save-data — остаётся лёгкий постер (~160K),
  // чтобы сайт быстро открывался даже на троттлящихся (ТСПУ/DPI) мобильных сетях.
  useEffect(() => {
    if (reduce) return;
    const isDesktop = window.matchMedia("(min-width: 1024px)").matches;
    const conn = (navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
    const slow = !!conn && (conn.saveData === true || /(^|-)([23])g$/.test(conn.effectiveType || ""));
    if (!isDesktop || slow) return; // только постер

    const start = () => setShowVideo(true);
    const w = window as Window & {
      requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number;
      cancelIdleCallback?: (id: number) => void;
    };
    let idleId = 0;
    let timeoutId = 0;
    if (w.requestIdleCallback) idleId = w.requestIdleCallback(start, { timeout: 2500 });
    else timeoutId = window.setTimeout(start, 1200);
    return () => {
      if (idleId && w.cancelIdleCallback) w.cancelIdleCallback(idleId);
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [reduce]);

  return (
    <div aria-hidden="true" className="absolute inset-0 z-0 overflow-hidden">
      {/* Постер + кинофон-видео с маской-затуханием в тёмный фон */}
      <div className="absolute inset-0 [mask-image:linear-gradient(to_bottom,#000_0%,#000_44%,transparent_94%)] [-webkit-mask-image:linear-gradient(to_bottom,#000_0%,#000_44%,transparent_94%)]">
        <Image
          src="/hero-poster.webp"
          alt=""
          fill
          sizes="100vw"
          style={{ opacity: videoReady ? 0 : 0.5 }}
          className="absolute inset-0 h-full w-full scale-105 object-cover object-center transition-opacity duration-[1200ms] ease-out motion-safe:[animation:hero-kenburns_26s_ease-in-out_infinite_alternate]"
        />
        {showVideo && (
          <video
            className="absolute inset-0 h-full w-full object-cover object-center transition-opacity duration-[1200ms] ease-out"
            style={{ opacity: videoReady ? 0.5 : 0 }}
            autoPlay
            muted
            loop
            playsInline
            preload="auto"
            poster="/hero-poster.jpg"
            onLoadedData={() => setVideoReady(true)}
          >
            <source src="/hero.mp4" type="video/mp4" />
          </video>
        )}
      </div>

      {/* Световые следы — эффект скорости (чистый CSS) */}
      {!reduce && (
        <div className="pointer-events-none absolute inset-0 [mask-image:linear-gradient(to_bottom,transparent,#000_30%,#000_70%,transparent)]">
          <div className="hero-streaks hero-streaks-1" />
          <div className="hero-streaks hero-streaks-2" />
          <span className="hero-flare hero-flare-a" />
          <span className="hero-flare hero-flare-b" />
        </div>
      )}

      {/* Бренд-тонировка + читаемость текста */}
      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(7,15,11,0.55)_0%,rgba(7,15,11,0.42)_40%,rgba(7,15,11,0.90)_100%)]" />
      <div className="absolute inset-0 bg-[radial-gradient(125%_100%_at_50%_38%,rgba(7,15,11,0)_0%,rgba(7,15,11,0.50)_78%)] lg:bg-[radial-gradient(120%_95%_at_16%_32%,rgba(7,15,11,0)_0%,rgba(7,15,11,0.62)_72%)]" />
      <div className="absolute inset-0 mix-blend-soft-light bg-[radial-gradient(80%_60%_at_70%_18%,rgba(127,227,171,0.18)_0%,transparent_60%)]" />

      <div className="grain absolute inset-0" />
    </div>
  );
}
