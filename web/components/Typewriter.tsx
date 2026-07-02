"use client";

import { useEffect, useRef, useState } from "react";
import { useReducedMotion } from "framer-motion";

/**
 * Печатающаяся строка: набирает фразу, держит паузу, стирает, берёт следующую.
 * При prefers-reduced-motion — просто показывает первую фразу без анимации.
 * Каретка мигает через CSS (.tw-caret).
 */
export function Typewriter({
  phrases,
  typeMs = 55,
  deleteMs = 28,
  holdMs = 1700,
  className = "",
  startDelayMs = 600,
  once = false,
}: {
  phrases: string[];
  typeMs?: number;
  deleteMs?: number;
  holdMs?: number;
  className?: string;
  startDelayMs?: number;
  once?: boolean;
}) {
  const reduce = useReducedMotion();
  const [text, setText] = useState(reduce ? phrases[0] ?? "" : "");
  const idx = useRef(0);
  const phase = useRef<"type" | "hold" | "delete">("type");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (reduce || phrases.length === 0) return;
    let pos = 0;
    let cancelled = false;

    const tick = () => {
      if (cancelled) return;
      const full = phrases[idx.current % phrases.length] ?? "";

      if (phase.current === "type") {
        pos++;
        setText(full.slice(0, pos));
        if (pos >= full.length) {
          if (once) return; // одноразовый набор — останавливаемся на полной фразе
          phase.current = "hold";
          timer.current = setTimeout(tick, holdMs);
        } else {
          timer.current = setTimeout(tick, typeMs);
        }
      } else if (phase.current === "hold") {
        phase.current = "delete";
        timer.current = setTimeout(tick, deleteMs);
      } else {
        pos--;
        setText(full.slice(0, Math.max(pos, 0)));
        if (pos <= 0) {
          phase.current = "type";
          idx.current++;
          timer.current = setTimeout(tick, 320);
        } else {
          timer.current = setTimeout(tick, deleteMs);
        }
      }
    };

    timer.current = setTimeout(tick, startDelayMs);
    return () => {
      cancelled = true;
      if (timer.current) clearTimeout(timer.current);
    };
    // фразы фиксированы по языку; смена языка пересоздаёт компонент через key
  }, [reduce, phrases, typeMs, deleteMs, holdMs, startDelayMs, once]);

  return (
    <span className={className} aria-live="polite">
      {text}
      {!reduce && <span className="tw-caret" aria-hidden="true" />}
    </span>
  );
}
