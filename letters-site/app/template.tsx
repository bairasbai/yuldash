"use client";

import { motion } from "motion/react";

/**
 * Переход между разделами. Небо остаётся на месте (оно в layout),
 * а содержимое мягко всплывает — так переходы читаются как движение
 * внутри одного пространства.
 */
export default function Template({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10, filter: "blur(5px)" }}
      animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
      transition={{ duration: 0.75, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </motion.div>
  );
}
