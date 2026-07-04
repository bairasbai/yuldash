"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import { useReducedMotion } from "framer-motion";
import { useIsMobile } from "./useIsMobile";

/**
 * Кинематографичный фон героя: тёплый постер дороги (LCP, ~160K) + поверх него
 * лениво подгружаемое кинофон-видео (дрон, Higgsfield) + CSS-«световые следы».
 * Видео грузится в простое (постер уже отрисован → быстрый LCP), плавно
 * проявляется. При prefers-reduced-motion видео и движение выключены — только постер.
 */
export function HeroBackdrop() {
  const reduce = useReducedMotion();
  const isMobile = useIsMobile();
  const [showVideo, setShowVideo] = useState(false);
  const [videoReady, setVideoReady] = useState(false);
  // На узком/портретном экране (телефон) landscape-видео пришлось бы сильно
  // кропить по бокам — теряется дорога. Отдаём вертикальный дубль (Higgsfield,
  // 720×1280, ~0.3 МБ). Определяем один раз на маунте.
  const [portrait, setPortrait] = useState(false);
  useEffect(() => {
    const t = window.setTimeout(() => {
      setPortrait(window.matchMedia("(max-aspect-ratio: 3/4)").matches);
    }, 0);
    return () => window.clearTimeout(t);
  }, []);
  const videoSrc = portrait ? "/hero-vertical.mp4" : "/hero.mp4";

  // Кинофон-видео (~0.6 МБ) крутим ТОЛЬКО на десктопе. На телефоне зациклённый
  // декод видео жарит GPU (корпус греется) и жрёт трафик на LTE, а сам ролик —
  // фон за текстом. Мобиле отдаём статичный постер (он же LCP, ~160К) → холодно
  // и быстро. Также пропускаем при Save-Data / медленной сети (2g/3g).
  useEffect(() => {
    if (reduce) return;
    const isMobile = window.matchMedia("(max-width: 767px), (pointer: coarse)").matches;
    if (isMobile) return; // телефон — только постер, без вечного декода видео
    const conn = (navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
    const slow = !!conn && (conn.saveData === true || /(^|-)([23])g$/.test(conn.effectiveType || ""));
    if (slow) return; // только постер на экономном/медленном соединении

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
          className="hero-poster-ken absolute inset-0 h-full w-full scale-105 object-cover object-center transition-opacity duration-[1200ms] ease-out motion-safe:[animation:hero-kenburns_26s_ease-in-out_infinite_alternate]"
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
            <source src={videoSrc} type="video/mp4" />
          </video>
        )}
      </div>

      {/* Световые следы — эффект скорости (чистый CSS). Только десктоп:
          на телефоне это лишняя постоянная анимация поверх постера. */}
      {!reduce && !isMobile && (
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
