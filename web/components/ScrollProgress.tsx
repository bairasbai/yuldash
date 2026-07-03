"use client";

import { m, useScroll, useSpring } from "framer-motion";

// Тонкая полоса прогресса прокрутки сверху — премиум-микродеталь.
export function ScrollProgress() {
  const { scrollYProgress } = useScroll();
  const scaleX = useSpring(scrollYProgress, { stiffness: 120, damping: 24, restDelta: 0.001 });

  return (
    <m.div
      style={{ scaleX }}
      className="fixed inset-x-0 top-0 z-[60] h-0.5 origin-left bg-gradient-to-r from-green-bright via-green-glow to-gold-light"
      aria-hidden="true"
    />
  );
}
