"use client";

import { motion } from "framer-motion";
import { KuraiBloom } from "./Ornament";

// Живой «аврора»-фон: мягко дышащие зелёно-золотые пятна света.
// Дорогой матовый эффект усиливается зернистостью (.grain) поверх.
export function Aurora() {
  return (
    <div className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      {/* базовый тёмный градиент */}
      <div className="absolute inset-0 bg-[radial-gradient(120%_120%_at_50%_-10%,#10241a_0%,#0a1410_55%,#070f0b_100%)]" />

      <motion.div
        className="absolute -top-40 left-[12%] h-[44rem] w-[44rem] rounded-full bg-green-bright/25 blur-[140px]"
        animate={{ x: [0, 60, -20, 0], y: [0, 40, 10, 0], scale: [1, 1.12, 0.98, 1] }}
        transition={{ duration: 22, repeat: Infinity, ease: "easeInOut" }}
      />
      <motion.div
        className="absolute top-[18%] right-[8%] h-[38rem] w-[38rem] rounded-full bg-gold/20 blur-[150px]"
        animate={{ x: [0, -50, 30, 0], y: [0, 30, -20, 0], scale: [1, 1.08, 1.04, 1] }}
        transition={{ duration: 26, repeat: Infinity, ease: "easeInOut" }}
      />
      <motion.div
        className="absolute bottom-[-10%] left-[35%] h-[40rem] w-[40rem] rounded-full bg-green-deep/30 blur-[150px]"
        animate={{ x: [0, 40, -30, 0], y: [0, -30, 20, 0], scale: [1, 1.1, 0.96, 1] }}
        transition={{ duration: 28, repeat: Infinity, ease: "easeInOut" }}
      />

      {/* курай-водяной знак (символ дружбы) — еле заметный, медленно дышит */}
      <motion.div
        className="absolute -right-20 top-24 text-gold-light/[0.05]"
        animate={{ rotate: 360 }}
        transition={{ duration: 140, repeat: Infinity, ease: "linear" }}
      >
        <KuraiBloom size={460} variant="outline" strokeWidth={1} />
      </motion.div>
      <motion.div
        className="absolute -left-24 bottom-32 text-green-glow/[0.04]"
        animate={{ rotate: -360 }}
        transition={{ duration: 170, repeat: Infinity, ease: "linear" }}
      >
        <KuraiBloom size={360} variant="outline" strokeWidth={1} />
      </motion.div>

      {/* тонкая сетка-виньетка для глубины */}
      <div className="absolute inset-0 bg-[radial-gradient(100%_60%_at_50%_0%,transparent_60%,rgba(0,0,0,0.55)_100%)]" />
      <div className="grain absolute inset-0" />
    </div>
  );
}
