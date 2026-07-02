"use client";

import { motion, useReducedMotion } from "framer-motion";

// Телефон в hero — РЕАЛЬНЫЙ скриншот главного экрана приложения (карта «Куда поедем?»).
// БЕЗ 3D (preserve-3d): на iOS Safari картинка внутри 3D-контекста не декодировалась
// → app-map «терялся» (и в hero, и в витрине — тот же ресурс). Только 2D-подъём + свечение.
export function HeroPhone() {
  const reduce = useReducedMotion();
  return (
    <div className="relative">
      {/* свечение под телефоном */}
      <div className="pointer-events-none absolute inset-6 -z-10 rounded-[60px] bg-green-bright/20 blur-3xl" />
      <motion.div
        whileHover={reduce ? undefined : { y: -10, scale: 1.015 }}
        transition={{ type: "spring", stiffness: 260, damping: 22 }}
      >
        {/* Обычный <img> (не next/image): next/image lazy глючил на iOS. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src="/screens/app-map.webp"
          alt="Экран приложения Юлдаш: карта поездок по Башкортостану с ценами"
          width={272}
          height={614}
          className="h-[500px] w-auto rounded-[38px] drop-shadow-[0_40px_80px_rgba(0,0,0,0.55)] sm:h-[560px]"
        />
      </motion.div>
    </div>
  );
}
