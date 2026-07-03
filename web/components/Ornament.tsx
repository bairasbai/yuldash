"use client";

import { useId } from "react";
import { m } from "framer-motion";

// ===== Цветок курая — символ Башкортостана (с герба/флага) =====
// Настоящая форма: зонтик-соцветие — центр-втулка + лучи-стебельки с шариками
// на концах + стебель вниз. variant "filled" — эмблема; "outline" — контур (фон).
// ring — добавить обрамляющее кольцо (полная эмблема).
const RAYS = [-100, -75, -50, -25, 0, 25, 50, 75, 100]; // 9 лучей-стебельков

export function KuraiBloom({
  className = "",
  size = 40,
  variant = "filled",
  strokeWidth = 2,
  ring = false,
}: {
  className?: string;
  size?: number;
  variant?: "filled" | "outline";
  strokeWidth?: number;
  ring?: boolean;
}) {
  const outline = variant === "outline";
  const cx = 50;
  const cy = 52;
  const L = 30; // длина луча до шарика
  const hubR = 8;
  const dotR = 6.2;
  const sw = outline ? strokeWidth : 3.4; // толщина стебельков

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      className={className}
      aria-hidden="true"
    >
      <g
        fill={outline ? "none" : "currentColor"}
        stroke="currentColor"
        strokeLinecap="round"
      >
        {ring && <circle cx={cx} cy={cy} r="46" fill="none" strokeWidth={outline ? strokeWidth : 6} />}

        {/* стебель вниз */}
        <line x1={cx} y1={cy} x2={cx} y2={cy + 32} strokeWidth={sw} />

        {/* лучи-стебельки + шарики */}
        {RAYS.map((deg, i) => {
          const r = (deg * Math.PI) / 180;
          const sin = Math.sin(r);
          const cos = Math.cos(r);
          const ex = cx + sin * L;
          const ey = cy - cos * L;
          return (
            <g key={i}>
              <line
                x1={cx + sin * (hubR - 1)}
                y1={cy - cos * (hubR - 1)}
                x2={ex}
                y2={ey}
                strokeWidth={sw}
              />
              <circle cx={ex} cy={ey} r={dotR} strokeWidth={outline ? strokeWidth : 0} />
            </g>
          );
        })}

        {/* центральная втулка */}
        <circle cx={cx} cy={cy} r={hubR} strokeWidth={outline ? strokeWidth : 0} />
      </g>
    </svg>
  );
}

// ===== Орнаментальный киккер над заголовком =====
export function OrnamentKicker() {
  return (
    <div className="mb-5 flex items-center justify-center gap-4 text-gold-light/80" aria-hidden="true">
      <span className="h-px w-12 bg-gradient-to-r from-transparent to-gold-light/40 sm:w-20" />
      <m.span
        initial={{ y: 8, scale: 0.8 }}
        whileInView={{ y: 0, scale: 1 }}
        viewport={{ once: true }}
        transition={{ duration: 0.7, ease: [0.21, 0.47, 0.32, 0.98] }}
      >
        <KuraiBloom size={34} />
      </m.span>
      <span className="h-px w-12 bg-gradient-to-l from-transparent to-gold-light/40 sm:w-20" />
    </div>
  );
}

// ===== Орнаментальный разделитель секций (кускар, расходящийся от курая) =====
// Узор «вытекает» из центрального цветка курая в обе стороны и гаснет к краям;
// под ним — тонкий золотой шов-градиент и мягкое свечение. Читается как
// дорогой декоративный переход между секциями, а не «одинокая полоска».
export function OrnamentBand({ className = "" }: { className?: string }) {
  const pid = useId().replace(/[:]/g, "");
  return (
    <div className={`relative mx-auto my-12 max-w-4xl px-6 ${className}`} aria-hidden="true">
      {/* тонкий шов, гаснущий к краям — сшивает соседние секции */}
      <div className="absolute inset-x-6 top-1/2 h-px -translate-y-1/2 bg-gradient-to-r from-transparent via-gold-light/20 to-transparent" />
      {/* мягкое золотое свечение по центру */}
      <div className="pointer-events-none absolute left-1/2 top-1/2 h-20 w-80 -translate-x-1/2 -translate-y-1/2 rounded-full bg-gold/10 blur-3xl" />

      <div className="relative flex items-center justify-center">
        <m.svg
          viewBox="0 0 800 40"
          preserveAspectRatio="xMidYMid meet"
          className="h-8 w-full max-w-2xl text-gold-light/35 [mask-image:linear-gradient(to_right,transparent_0%,#000_15%,#000_43%,transparent_47%,transparent_53%,#000_57%,#000_85%,transparent_100%)] [-webkit-mask-image:linear-gradient(to_right,transparent_0%,#000_15%,#000_43%,transparent_47%,transparent_53%,#000_57%,#000_85%,transparent_100%)]"
          initial={{ scaleX: 0.9 }}
          animate={{ opacity: 1, scaleX: 1 }}
          transition={{ duration: 1, ease: [0.21, 0.47, 0.32, 0.98] }}
        >
          <defs>
            <pattern id={pid} x="0" y="0" width="80" height="40" patternUnits="userSpaceOnUse">
              <g fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M40 8 L52 20 L40 32 L28 20 Z" />
                <path d="M28 20 C 16 20 14 10 4 12 C 0 13 1 18 6 17" />
                <path d="M52 20 C 64 20 66 30 76 28 C 80 27 79 22 74 23" />
                <circle cx="40" cy="20" r="2.4" fill="currentColor" stroke="none" />
              </g>
            </pattern>
          </defs>
          <rect width="800" height="40" fill={`url(#${pid})`} />
        </m.svg>

        {/* центральный цветок курая со свечением.
            Центрирование (-translate-1/2) держим на статичном span — иначе
            framer перезаписывает transform своими scale/rotate и центр уезжает. */}
        <span className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
          <m.span
            className="relative flex items-center justify-center text-gold-light/90"
            initial={{ scale: 0.85 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.7, delay: 0.15, ease: [0.34, 1.56, 0.64, 1] }}
          >
            <KuraiBloom size={30} className="relative" />
          </m.span>
        </span>
      </div>
    </div>
  );
}
