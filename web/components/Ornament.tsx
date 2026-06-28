"use client";

import { motion } from "framer-motion";

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
      <motion.span
        initial={{ y: 8, opacity: 0, scale: 0.8 }}
        whileInView={{ y: 0, opacity: 1, scale: 1 }}
        viewport={{ once: true }}
        transition={{ duration: 0.7, ease: [0.21, 0.47, 0.32, 0.98] }}
      >
        <KuraiBloom size={34} />
      </motion.span>
      <span className="h-px w-12 bg-gradient-to-l from-transparent to-gold-light/40 sm:w-20" />
    </div>
  );
}

// ===== Орнаментальный бордюр (кускар: ромбы + S-завитки) =====
export function OrnamentBand({ className = "" }: { className?: string }) {
  return (
    <div className={`relative mx-auto my-2 max-w-6xl px-6 ${className}`} aria-hidden="true">
      <motion.svg
        viewBox="0 0 800 40"
        preserveAspectRatio="xMidYMid meet"
        className="mx-auto h-7 w-full max-w-2xl text-gold-light/30"
        initial={{ opacity: 0 }}
        whileInView={{ opacity: 1 }}
        viewport={{ once: true }}
        transition={{ duration: 1 }}
      >
        <defs>
          <pattern id="kuskar" x="0" y="0" width="80" height="40" patternUnits="userSpaceOnUse">
            <g fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M40 8 L52 20 L40 32 L28 20 Z" />
              <path d="M28 20 C 16 20 14 10 4 12 C 0 13 1 18 6 17" />
              <path d="M52 20 C 64 20 66 30 76 28 C 80 27 79 22 74 23" />
              <circle cx="40" cy="20" r="2.4" fill="currentColor" stroke="none" />
            </g>
          </pattern>
        </defs>
        <rect width="800" height="40" fill="url(#kuskar)" />
      </motion.svg>
    </div>
  );
}
