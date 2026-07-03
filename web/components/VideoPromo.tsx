"use client";

import { useRef, useState } from "react";
import { m } from "framer-motion";
import { useLang } from "./lang";
import { Reveal } from "./Reveal";
import { OrnamentKicker } from "./Ornament";
import { BorderBeam } from "./BorderBeam";
import { track } from "./analytics";

// Промо-ролик (Remotion, 16:9). Click-to-play: показываем постер + кнопку, видео
// подгружается ТОЛЬКО по клику — ноль веса до взаимодействия (перф на мобиле).
export function VideoPromo() {
  const { tr } = useLang();
  const [playing, setPlaying] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  const play = () => {
    setPlaying(true);
    track("promo_play");
    // видео появится в DOM на следующий кадр → запускаем после
    requestAnimationFrame(() => videoRef.current?.play().catch(() => {}));
  };

  return (
    <section id="promo" className="relative px-6 py-[var(--sp-section)]">
      <div className="mx-auto max-w-5xl">
        <Reveal className="mx-auto mb-12 max-w-2xl text-center">
          <OrnamentKicker />
          <h2 className="fluid-h2 font-display font-extrabold">{tr("promo_title")}</h2>
          <p className="mt-4 text-lg text-white/60">{tr("promo_sub")}</p>
        </Reveal>

        <Reveal>
          <m.div
            whileHover={{ y: -6 }}
            transition={{ type: "spring", stiffness: 260, damping: 22 }}
            className="glass relative mx-auto overflow-hidden rounded-[28px] p-2 shadow-card"
          >
            <BorderBeam />
            <div className="relative aspect-video w-full overflow-hidden rounded-[22px] bg-forest">
              {!playing ? (
                <button
                  type="button"
                  onClick={play}
                  aria-label={tr("promo_play")}
                  className="group absolute inset-0 h-full w-full"
                >
                  {/* постер (лёгкий JPG) */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src="/promo-wide-poster.jpg"
                    alt=""
                    className="absolute inset-0 h-full w-full object-cover transition-transform duration-700 group-hover:scale-[1.03]"
                  />
                  <span className="absolute inset-0 bg-gradient-to-t from-night/70 via-transparent to-night/20" />
                  {/* кнопка play */}
                  <span className="absolute left-1/2 top-1/2 flex h-20 w-20 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-green-bright text-night shadow-glow transition-transform duration-300 group-hover:scale-110">
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-bright/50" />
                    <svg width="30" height="30" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className="relative ml-1">
                      <path d="M8 5v14l11-7z" />
                    </svg>
                  </span>
                  <span className="absolute bottom-5 left-1/2 -translate-x-1/2 rounded-full bg-black/40 px-4 py-1.5 text-sm font-semibold text-white/90 backdrop-blur-sm">
                    {tr("promo_play")} · 0:15
                  </span>
                </button>
              ) : (
                <video
                  ref={videoRef}
                  className="absolute inset-0 h-full w-full object-cover"
                  controls
                  playsInline
                  poster="/promo-wide-poster.jpg"
                >
                  <source src="/promo-wide.mp4" type="video/mp4" />
                </video>
              )}
            </div>
          </m.div>
        </Reveal>
      </div>
    </section>
  );
}
