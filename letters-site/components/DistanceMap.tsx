"use client";

import { motion } from "motion/react";

const FROM = { x: 62, y: 118 };
const TO = { x: 338, y: 86 };
const CTRL = { x: 200, y: 22 };

/** Точка на дуге: чем ближе встреча, тем ближе она к Уфе. */
function pointAt(t: number) {
  const u = 1 - t;
  return {
    x: u * u * FROM.x + 2 * u * t * CTRL.x + t * t * TO.x,
    y: u * u * FROM.y + 2 * u * t * CTRL.y + t * t * TO.y,
  };
}

/**
 * Москва и Уфа на одной дуге. Пройденная часть пути светится,
 * остальное — пунктир. Каждое утро точка сдвигается вправо.
 */
export default function DistanceMap({
  progress,
  km,
  fromCity,
  toCity,
}: {
  progress: number;
  km: number;
  fromCity: string;
  toCity: string;
}) {
  const p = Math.min(1, Math.max(0, progress));
  const plane = pointAt(p);
  const path = `M${FROM.x},${FROM.y} Q${CTRL.x},${CTRL.y} ${TO.x},${TO.y}`;

  return (
    <motion.section
      initial={{ opacity: 0, y: 16 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: "-60px" }}
      transition={{ duration: 1, ease: [0.22, 1, 0.36, 1] }}
      className="rounded-[var(--radius-card)] border border-panel-border bg-panel px-5 py-7 backdrop-blur-[2px]"
    >
      <svg viewBox="0 0 400 160" className="w-full" role="img"
        aria-label={`Путь из ${fromCity} в ${toCity}, ${km} километров`}>
        {/* Весь путь — пунктиром */}
        <path
          d={path}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.2"
          strokeDasharray="4 6"
          className="text-sky-ink-soft/35"
        />

        {/* Пройденная часть. pathLength=1 позволяет считать долю пути напрямую. */}
        <motion.path
          d={path}
          fill="none"
          stroke="url(#trail)"
          strokeWidth="2"
          strokeLinecap="round"
          pathLength={1}
          initial={{ strokeDasharray: "1 1", strokeDashoffset: 1 }}
          whileInView={{ strokeDashoffset: 1 - p }}
          viewport={{ once: true }}
          transition={{ duration: 2, delay: 0.3, ease: [0.22, 1, 0.36, 1] }}
        />

        <defs>
          <linearGradient id="trail" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#9db4e8" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#ffd9a8" stopOpacity="0.95" />
          </linearGradient>
        </defs>

        {/* Города */}
        {[
          { pt: FROM, label: fromCity, anchor: "start" as const },
          { pt: TO, label: toCity, anchor: "end" as const },
        ].map(({ pt, label, anchor }) => (
          <g key={label}>
            <circle cx={pt.x} cy={pt.y} r="3.5" className="fill-sky-ink" />
            <circle
              cx={pt.x}
              cy={pt.y}
              r="9"
              className="fill-sky-ink/12"
            />
            <text
              x={pt.x}
              y={pt.y + 26}
              textAnchor={anchor === "start" ? "start" : "end"}
              className="fill-current font-sans text-[11px] uppercase tracking-[0.16em] text-sky-ink-soft"
              style={{ letterSpacing: "0.16em" }}
            >
              {label}
            </text>
          </g>
        ))}

        {/* Где мы сейчас */}
        <motion.g
          initial={{ opacity: 0 }}
          whileInView={{ opacity: 1 }}
          viewport={{ once: true }}
          transition={{ delay: 1.6, duration: 0.8 }}
        >
          <circle cx={plane.x} cy={plane.y} r="12" fill="#ffd9a8" opacity="0.14" />
          <circle cx={plane.x} cy={plane.y} r="4" fill="#ffd9a8" />
        </motion.g>
      </svg>

      <p className="mt-2 text-center font-serif text-lg text-sky-ink-soft">
        {km} км — и они уже не бесконечные
      </p>
    </motion.section>
  );
}
