"use client";

import Image from "next/image";
import { motion, useMotionValue, useSpring, useTransform, useReducedMotion } from "framer-motion";

// Телефон в hero — РЕАЛЬНЫЙ скриншот главного экрана приложения (карта «Куда поедем?»)
// в готовой рамке. Премиум-микроинтеракция: лёгкий 3D-тилт за курсором + свечение.
export function HeroPhone() {
  const reduce = useReducedMotion();
  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const spring = { stiffness: 150, damping: 18 };
  const rotateX = useSpring(useTransform(my, [-0.5, 0.5], [6, -6]), spring);
  const rotateY = useSpring(useTransform(mx, [-0.5, 0.5], [-8, 8]), spring);
  const onMove = (e: React.MouseEvent) => {
    const r = e.currentTarget.getBoundingClientRect();
    mx.set((e.clientX - r.left) / r.width - 0.5);
    my.set((e.clientY - r.top) / r.height - 0.5);
  };
  const onLeave = () => {
    mx.set(0);
    my.set(0);
  };

  return (
    <div
      onMouseMove={reduce ? undefined : onMove}
      onMouseLeave={reduce ? undefined : onLeave}
      style={{ perspective: 1100 }}
      className="relative"
    >
      {/* свечение под телефоном */}
      <div className="pointer-events-none absolute inset-6 -z-10 rounded-[60px] bg-green-bright/20 blur-3xl" />
      <motion.div style={reduce ? undefined : { rotateX, rotateY, transformStyle: "preserve-3d" }}>
        <Image
          src="/screens/app-map.webp"
          alt="Экран приложения Юлдаш: карта поездок по Башкортостану с ценами"
          width={272}
          height={614}
          priority
          sizes="(max-width: 1024px) 240px, 300px"
          className="h-[500px] w-auto rounded-[38px] drop-shadow-[0_40px_80px_rgba(0,0,0,0.55)] sm:h-[560px]"
        />
      </motion.div>
    </div>
  );
}
